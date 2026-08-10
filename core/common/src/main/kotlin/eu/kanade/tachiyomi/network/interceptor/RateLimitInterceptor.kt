package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

/**
 * Rate limiting is disabled.
 *
 * This class and the extension functions are kept for compatibility
 * with existing extensions.
 *
 * All rate limit parameters are intentionally ignored.
 */
internal class RateLimitInterceptor(
    host: String?,
    permits: Int,
    period: Duration,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}

/**
 * Rate limiting is disabled.
 *
 * Kept for compatibility with existing extensions.
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
 * Kept for compatibility with existing extensions.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
) = this
