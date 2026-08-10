package eu.kanade.tachiyomi.network.interceptor

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Rate limiting is disabled.
 *
 * This function is kept for compatibility with existing extensions.
 *
 * @param permits Ignored.
 * @param period Ignored.
 * @param unit Ignored.
 */
@Deprecated("Rate limiting is disabled.")
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) = this

/**
 * Rate limiting is disabled.
 *
 * This function is kept for compatibility with existing extensions.
 *
 * @param permits Ignored.
 * @param period Ignored.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
) = this
