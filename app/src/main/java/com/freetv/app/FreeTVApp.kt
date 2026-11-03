package com.freetv.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class FreeTVApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Phase 39: StrictMode only in debuggable builds (the test APK). BuildConfig.DEBUG is not available, so the flag is checked.
        // penaltyLog only: it writes to Logcat (tag "StrictMode") and never crashes or slows the release APK.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        CrashLog.install(this)
        Prefs.init(this)
        Prefs.preload() // Phase 39: favorites + recents JSON are parsed on a background thread
        HealthStore.init(this)
    }

    /**
     * Coil calls this once (on the first image request) and uses the result as the app-wide loader,
     * so every `imageView.load(url)` gets the shared OkHttp client, a memory cache,
     * a 100 MB disk cache and a short fade-in.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // Lambda form: Coil creates the client lazily on a background thread (first logo download),
            // so building Net.client never happens on the main thread.
            .okHttpClient { Net.client }
            .memoryCache {
                MemoryCache.Builder(this@FreeTVApp)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "img"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Logos rarely change: use the saved copy without asking the server again (also works offline).
            .respectCacheHeaders(false)
            .crossfade(120)
            .build()
}
