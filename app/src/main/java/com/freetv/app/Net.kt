package com.freetv.app

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for the whole app.
 * No OkHttp Cache on purpose: live segments must not fill the disk.
 */
object Net {

    const val UA = "WosuxStream/1.0 (Android)"

    /** Main client: playlists, images, player data source. */
    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        val userAgentInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") == null) {
                chain.proceed(request.newBuilder().header("User-Agent", UA).build())
            } else {
                chain.proceed(request)
            }
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(dispatcher)
            .addInterceptor(userAgentInterceptor)
            .build()
    }

    /** Short-timeout client for quick checks. Shares the pool and dispatcher with [client]. */
    val probe: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
