package me.rerere.rikkahub.data.classrecord

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.classRecordOperationMatches
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ClassRecordProtocolTest {
    private val origin = classRecordOrigin("https://classroom.iamyx.co")

    @Test fun `credentials and insecure or pathful endpoints are refused`() {
        listOf("http://classroom.iamyx.co", "https://user:secret@classroom.iamyx.co", "https://classroom.iamyx.co/path",
            "https://classroom.iamyx.co?token=secret", "https://classroom.iamyx.co/#section").forEach {
            assertThrows(IllegalArgumentException::class.java) { classRecordOrigin(it) }
        }
    }

    @Test fun `same origin and exact path are retained while query values are encoded`() {
        val url = classRecordUrl(origin, "/api/v1/console/classes", buildJsonObject {
            put("q", "22班 & 数学?#"); put("schoolId", "a/b"); putJsonArray("classId") { add("22"); add("25") }
        })
        assertEquals("classroom.iamyx.co", url.host)
        assertEquals("22班 & 数学?#", url.queryParameter("q"))
        assertEquals("a/b", url.queryParameter("schoolId"))
        assertEquals(listOf("22", "25"), url.queryParameterValues("classId"))
    }

    @Test fun `paths cannot escape change origin or smuggle query`() {
        listOf("https://evil.test/api/v1/console", "//evil.test/api/v1/console", "/api/v1/../../logout",
            "/api/v1/%2e%2e/secret", "/api/v1/%252e%252e/secret", "/api/v1/%2fsecret",
            "/api/v1/console?redirect=https://evil.test", "/api/v1/console#fragment", "/api/v1/console\\..").forEach {
            assertThrows(IllegalArgumentException::class.java) { classRecordUrl(origin, it, buildJsonObject {}) }
        }
    }

    @Test fun `download names cannot become paths`() {
        assertEquals("lesson.pdf", classRecordSafeName("../../lesson.pdf"))
        assertEquals("lesson.pdf", classRecordSafeName("C:\\secret\\lesson.pdf"))
        assertFalse(classRecordSafeName("bad\nname:*?").contains('\n'))
        assertEquals("classrecord-file", classRecordSafeName("/"))
    }

    @Test fun `streamed file is registered only after length and hash match`() {
        val content = "课堂原始录音与课件\n".repeat(20000).toByteArray()
        val sha = hash(content)
        val output = ByteArrayOutputStream()
        copyVerifiedClassRecordFile(ByteArrayInputStream(content), output, content.size.toLong(), sha.uppercase())
        assertArrayEquals(content, output.toByteArray())
        assertThrows(IllegalStateException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(content.copyOf(content.size - 1)), ByteArrayOutputStream(), content.size.toLong(), sha)
        }
        assertThrows(IllegalStateException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(content), ByteArrayOutputStream(), content.size.toLong() - 1, sha)
        }
        assertThrows(IllegalStateException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(content), ByteArrayOutputStream(), content.size.toLong(), "0".repeat(64))
        }
    }

    @Test fun `unknown identity and oversized download are rejected before reading`() {
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), CLASS_RECORD_FILE_LIMIT + 1, "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0, "")
        }
    }

    @Test fun `download cancellation stops stream copy`() {
        var count = 0
        assertThrows(InterruptedException::class.java) {
            copyVerifiedClassRecordFile(ByteArrayInputStream(ByteArray(256000)), ByteArrayOutputStream(), 256000, "0".repeat(64)) {
                if (++count == 2) throw InterruptedException("cancelled")
            }
        }
        assertEquals(2, count)
    }

    @Test fun `AI management matches method and complete authorized template`() {
        val operation = buildJsonObject { put("method", "POST"); put("path", "/api/v1/console/jobs/{id:guid}/retry") }
        assertTrue(classRecordOperationMatches(operation, "POST", "/api/v1/console/jobs/abc/retry"))
        assertFalse(classRecordOperationMatches(operation, "DELETE", "/api/v1/console/jobs/abc/retry"))
        assertFalse(classRecordOperationMatches(operation, "POST", "/api/v1/console/jobs/abc/retry/extra"))
        assertFalse(classRecordOperationMatches(operation, "POST", "/api/v1/console/jobs/abc/else"))
    }

    private fun hash(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
