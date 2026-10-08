package com.remotetv.app

/** Android KeyEvent codes sent to the TV. */
object Key {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val HOME = 3
    const val BACK = 4
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val MUTE = 164
    const val CH_UP = 166
    const val CH_DOWN = 167
    const val MIC = 231          // KEYCODE_VOICE_ASSIST
    const val INPUT = 178        // KEYCODE_TV_INPUT
    const val SETTINGS = 176
    const val RED = 183
    const val GREEN = 184
    const val YELLOW = 185
    const val BLUE = 186
    const val NUM_ENTRY = 234    // KEYCODE_TV_NUMBER_ENTRY  (-/--)
    const val SLEEP = 223
    const val ALL_APPS = 284
    fun digit(n: Int) = 7 + n    // KEYCODE_0 = 7
}

/**
 * An app the quick-launch tiles can open. [link] is either a full link/intent URI
 * (https://..., intent:...) or just an Android package name (opened through market://launch).
 * The user can edit these from More > App shortcuts.
 */
data class AppTarget(val label: String, val link: String) {
    companion object {
        val YouTube = AppTarget("YouTube", "https://www.youtube.com")
        val Prime = AppTarget("Prime Video", "com.amazon.amazonvideo.livingroom")
        val Spotify = AppTarget("Spotify", "com.spotify.tv.android")
        val Shahid = AppTarget("Shahid", "net.mbc.shahidtv")
        val WatchIt = AppTarget("Watch It", "com.watchit.tv")
        val all = listOf(YouTube, Prime, Spotify, Shahid, WatchIt)
    }
}
