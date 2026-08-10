package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Request
import kotlin.time.Duration

/**
 * No-op rate limit interceptor.
 *
 * Kept for compatibility with SpecificHostRateLimitInterceptor
 * and existing extensions. Rate limiting is disabled.
 */
internal class RateLimitInterceptor(
    private val host: String?,
    private val permits: Int,
    private val period: Duration,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return when {
            host == null || request.url.host == host -> chain.proceed(request)
            else -> chain.proceed(request)
        }
    }
}
