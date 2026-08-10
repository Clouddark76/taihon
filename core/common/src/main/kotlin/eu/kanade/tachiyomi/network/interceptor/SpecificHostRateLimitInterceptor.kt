package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limiting is disabled.
 *
 * Kept for compatibility with existing extensions.
 */
@Deprecated("Rate limiting is disabled.")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) = this

/**
 * Rate limiting is disabled.
 *
 * Kept for compatibility with existing extensions.
 */
@Suppress("UNUSED")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
) = this

/**
 * Rate limiting is disabled.
 *
 * Kept for compatibility with existing extensions.
 */
@Suppress("UNUSED")
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
) = this
