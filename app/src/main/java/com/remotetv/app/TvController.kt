package com.remotetv.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data class NeedsCode(val host: String) : ConnState
    data class Connected(val name: String, val host: String) : ConnState
    data class Failed(val reason: String) : ConnState
}

/**
 * Controls an Android TV with the same protocol as Google's own "Android TV Remote" app
 * (pairing code once, then no developer options / ADB needed).
 */
class TvController(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val identity by lazy { TlsIdentity.load(ctx) }

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var session: RemoteSession? = null
    private var pairing: Pairing? = null
    private var currentHost = ""
    private var earlyFailures = 0
    @Volatile private var wantConnected = false
    private val outbox = Channel<() -> Unit>(64)

    var savedHost: String
        get() = prefs.getString("host", "") ?: ""
        set(v) { prefs.edit().putString("host", v).apply() }
    var savedName: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", v).apply() }

    init {
        scope.launch {
            for (job in outbox) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    sessionClosed(null, e)
                }
            }
        }
        scope.launch {
            while (true) {
                delay(4_000)
                try { watchdog() } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            }
        }
    }

    // ---------------------------------------------------------------- public API

    fun connect(host: String, name: String = "") {
        val h = host.trim()
        if (h.isEmpty()) return
        if (_state.value == ConnState.Connecting) return
        savedHost = h
        savedName = name.trim()
        wantConnected = true
        scope.launch { connectFlow(h, notify = true) }
    }

    fun autoConnect() {
        val s = _state.value
        if (savedHost.isNotEmpty() && (s is ConnState.Disconnected || s is ConnState.Failed)) connect(savedHost, savedName)
    }

    fun disconnect() {
        wantConnected = false
        scope.launch { lock.withLock { closeAll(); _state.value = ConnState.Disconnected } }
    }

    fun submitCode(code: String) {
        val p = pairing ?: return
        val host = (_state.value as? ConnState.NeedsCode)?.host ?: return
        scope.launch {
            try {
                p.finish(code.trim())
                p.close()
                pairing = null
                setPaired(host, true)
                lock.withLock { startSession(host) }
            } catch (e: WrongCodeException) {
                _messages.tryEmit("Wrong code - check the TV screen and try again")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lock.withLock { fail("Pairing failed: ${e.message ?: "unknown error"}", true) }
            }
        }
    }

    fun cancelPairing() {
        wantConnected = false
        scope.launch { lock.withLock { closeAll(); _state.value = ConnState.Disconnected } }
    }

    fun key(code: Int) = send { it.sendKey(code, 3) }

    fun openAllApps() = key(Key.ALL_APPS)
    fun sleep() = key(Key.SLEEP)

    fun launch(app: AppTarget) {
        val link = linkFor(app)
        send { it.sendAppLink(link) }
    }

    /** Long-press POWER: Android shows its power menu on the TV (pick "Power off" with the D-pad). */
    fun powerMenu() = send {
        it.sendKey(26, 1)
        Thread.sleep(1_100)
        it.sendKey(26, 2)
    }

    /** Optional hard shutdown through ADB when it happens to be enabled on the TV. */
    fun shutdownViaAdb() {
        val host = currentHost.ifEmpty { savedHost }
        if (host.isEmpty()) { _messages.tryEmit("Not connected"); return }
        scope.launch {
            val job = async { AdbShutdown.run(ctx, host) }
            val err = try {
                withTimeout(20_000) { job.await() }
            } catch (e: TimeoutCancellationException) {
                "ADB did not answer"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The link usually drops while the machine powers off - that is success.
                null
            }
            if (err != null) _messages.tryEmit(err) else { wantConnected = false; _messages.tryEmit("Shutting down…") }
        }
    }

    fun linkFor(app: AppTarget): String {
        val raw = (prefs.getString("link_${app.label}", null) ?: app.link).trim()
        return if (raw.contains("://") || raw.startsWith("intent:")) raw else "market://launch?id=$raw"
    }

    fun rawLink(app: AppTarget): String = prefs.getString("link_${app.label}", null) ?: app.link

    fun setLink(app: AppTarget, value: String) {
        val v = value.trim()
        prefs.edit().apply { if (v.isEmpty()) remove("link_${app.label}") else putString("link_${app.label}", v) }.apply()
    }

    // ---------------------------------------------------------------- internals

    private fun send(block: (RemoteSession) -> Unit) {
        val s = session
        if (s == null || _state.value !is ConnState.Connected) {
            _messages.tryEmit("Not connected — tap the status to connect")
            return
        }
        outbox.trySend { block(s) }
    }

    private fun isPaired(host: String) = prefs.getBoolean("paired_$host", false)
    private fun setPaired(host: String, v: Boolean) = prefs.edit().putBoolean("paired_$host", v).apply()

    private fun closeAll() {
        runCatching { session?.close() }
        session = null
        runCatching { pairing?.close() }
        pairing = null
    }

    private fun fail(msg: String, notify: Boolean) {
        closeAll()
        _state.value = ConnState.Failed(msg)
        if (notify) _messages.tryEmit(msg)
    }

    private suspend fun connectFlow(host: String, notify: Boolean) = lock.withLock {
        val s = _state.value
        if (s is ConnState.Connected || s is ConnState.NeedsCode) return@withLock
        currentHost = host
        _state.value = ConnState.Connecting
        closeAll()
        if (!NetScan.portOpen(host, Atv.REMOTE_PORT, 2_500)) {
            fail("Cannot reach the TV at $host. Is it on, and on the same Wi-Fi?", notify)
            return@withLock
        }
        if (!isPaired(host)) {
            val p = Pairing(identity, host)
            try {
                p.begin()
                pairing = p
                _state.value = ConnState.NeedsCode(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                runCatching { p.close() }
                fail("Could not start pairing: ${e.message ?: "unknown error"}", notify)
            }
        } else {
            startSession(host)
        }
    }

    private fun startSession(host: String) {
        currentHost = host
        _state.value = ConnState.Connecting
        val sess = RemoteSession(identity, host)
        session = sess
        scope.launch {
            try {
                sess.run(onReady = {
                    earlyFailures = 0
                    val name = savedName.ifBlank { sess.deviceName.ifBlank { "Android TV" } }
                    _state.value = ConnState.Connected(name, host)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                sessionClosed(sess, e)
            }
        }
    }

    private fun sessionClosed(sess: RemoteSession?, e: Throwable) {
        val cur = session
        if (sess != null && sess !== cur) return
        val ready = cur?.ready == true
        runCatching { cur?.close() }
        session = null
        if (_state.value is ConnState.NeedsCode) return
        if (!ready) {
            // Never got past the handshake: the TV probably forgot us -> pair again.
            earlyFailures++
            if (earlyFailures >= 2) { setPaired(currentHost, false); earlyFailures = 0 }
        }
        _state.value = ConnState.Failed(if (ready) "Connection lost" else (e.message ?: "Connection failed"))
    }

    private suspend fun watchdog() {
        val s = _state.value
        if ((s is ConnState.Failed || s is ConnState.Disconnected) && wantConnected && savedHost.isNotBlank()) {
            connectFlow(savedHost, notify = false)
        }
    }
}
