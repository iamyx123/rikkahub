package me.rerere.rikkahub.data.power

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** A stream owns its network lease until EOF or close, rather than just until headers arrive. */
class ReadingPowerInterceptor(private val controller: ReadingPowerController) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val lease = runBlocking(Dispatchers.IO) { controller.acquireNetwork() }
        try {
            val response = chain.proceed(chain.request())
            if (response.code == 101) {
                // Voice sessions separately suspend reading mode for the lifetime of their socket.
                lease.close()
                return response
            }
            val original = response.body
            val source = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { if (it == -1L) lease.close() }
                } catch (failure: Exception) {
                    lease.close()
                    throw failure
                }

                override fun close() {
                    try { super.close() } finally { lease.close() }
                }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }
}
