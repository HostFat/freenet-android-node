package org.freenet.androidnode

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

/**
 * Installs the crash hook before any Activity or Service starts, so a Java or
 * Kotlin crash that happens while the node runs still leaves a usable stack
 * trace for the next report.
 */
class FreenetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportOffer.initialize(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReportOffer.noteThrowable(this, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
