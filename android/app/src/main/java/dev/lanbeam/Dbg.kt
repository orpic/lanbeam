package dev.lanbeam

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Logging that may contain details about people and networks (addresses, file names, Wi-Fi names,
 * pairing links). It only writes in debug builds; release builds keep the system log clean.
 */
object Dbg {
    @Volatile var on = false
        private set

    fun init(ctx: Context) { on = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    fun log(msg: String) { if (on) Log.d("LanBeam", msg) }
}
