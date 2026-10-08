package com.remotetv.app

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The app's own client certificate. The TV remembers it after pairing, so the
 * code is only needed once. Generated on first launch and kept in app storage.
 */
class TlsIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {

    fun sslContext(): SSLContext {
        val pass = "atv".toCharArray()
        val ks = KeyStore.getInstance("BKS")
        ks.load(null, null)
        ks.setKeyEntry("atv", privateKey, pass, arrayOf(certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }
    }

    companion object {
        fun load(ctx: Context): TlsIdentity {
            val kf = File(ctx.filesDir, "atv_key.der")
            val cf = File(ctx.filesDir, "atv_cert.der")
            if (kf.exists() && cf.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                    val cert = cf.inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                    }
                    return TlsIdentity(key, cert)
                } catch (_: Exception) { /* regenerate below */ }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val kp = gen.generateKeyPair()
            val name = X500Name("CN=atvremote")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now),
                Date(now - 86_400_000L), Date(now + 20L * 365L * 86_400_000L),
                name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(cert.encoded)
            return TlsIdentity(kp.private, cert)
        }
    }
}
