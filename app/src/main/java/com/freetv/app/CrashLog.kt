package com.freetv.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tiny crash reporter. When the app crashes, the stack trace is saved to a file.
 * On the next launch MainActivity shows it in a dialog so it can be copied and sent to the developer.
 * Note: this only helps for crashes that happen AFTER the app is installed and opened once more;
 * a crash inside Application.onCreate itself would repeat before the dialog can show.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_SHOWN = 4000

    /** Call once, first thing in Application.onCreate. */
    fun install(app: Application) {
        val appContext = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (e: Throwable) {
                // never let the reporter itself hide the real crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** The saved crash text, or null when there is none. */
    fun pending(ctx: Context): String? {
        return try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Shows the saved crash (if any) in a dialog with Copy / Close buttons. */
    fun showIfAny(activity: AppCompatActivity) {
        val report = pending(activity) ?: return
        if (activity.isFinishing) return

        val shown = if (report.length > MAX_SHOWN) report.substring(0, MAX_SHOWN) + "\n..." else report
        val pad = (20 * activity.resources.displayMetrics.density).toInt()

        val text = TextView(activity)
        text.text = shown
        text.textSize = 12f
        text.setPadding(pad, pad / 2, pad, pad / 2)
        text.setTextIsSelectable(true)

        val scroll = ScrollView(activity)
        scroll.addView(text)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Wosux Stream crashed last time")
            .setView(scroll)
            .setPositiveButton("Copy", null)   // real click handler is set below so "Copy" does not close the dialog
            .setNegativeButton("Close") { _, _ -> clear(activity) }
            .create()
        dialog.show()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Wosux Stream crash", report))
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun write(ctx: Context, thread: Thread, t: Throwable) {
        val trace = StringWriter()
        t.printStackTrace(PrintWriter(trace))

        @Suppress("DEPRECATION")
        val versionName = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }

        val sb = StringBuilder()
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("App version: ").append(versionName).append('\n')
        sb.append("Thread: ").append(thread.name).append('\n')
        sb.append('\n')
        sb.append(trace.toString())

        File(ctx.filesDir, FILE_NAME).writeText(sb.toString())
    }
}
