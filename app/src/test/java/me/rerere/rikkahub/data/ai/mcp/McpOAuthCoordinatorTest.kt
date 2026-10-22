package me.rerere.rikkahub.data.ai.mcp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpOAuthCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appScope = AppScope()
    private val requests = AtomicInteger()
    private val requestArrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var blockResponse = false
    @Volatile private var responseCode = 200
    @Volatile private var responseBody =
        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
    @Volatile private var responseFailure: IOException? = null
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests.incrementAndGet()
        requestArrived.countDown()
        if (blockResponse && !release.await(10, TimeUnit.SECONDS)) throw IOException("Test response timed out")
        responseFailure?.let { throw it }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message("")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val original = McpServerConfig.StreamableHTTPServer(
        url = "https://mcp.example.test/mcp",
        commonOptions = McpCommonOptions(
            name = "Synthetic MCP",
            oauth = McpOAuthState(
                enabled = true,
                clientId = "test-client",
                tokenEndpoint = "https://auth.example.test/token",
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1),
            ),
        ),
    )

    @After
    fun tearDown() = runBlocking {
        release.countDown()
        appScope.coroutineContext.job.cancelAndJoin()
        storeScope.coroutineContext.job.cancelAndJoin()
    }

    private suspend fun store(config: McpServerConfig = original): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "settings.preferences_pb") },
        storeScope,
    ).also { store ->
        store.awaitLoaded()
        store.update { it.copy(mcpServers = listOf(config)) }
    }

    private fun coordinator(store: SettingsStore) = McpOAuthCoordinator(
        settingsStore = store,
        appScope = appScope,
        oauthClient = OAuthHttpClient(client),
        discoveryClient = McpOAuthDiscoveryClient(client),
        callbackServer = OAuthLoopbackCallbackServer(),
        authorizationLauncher = OAuthAuthorizationLauncher { _, _ -> error("Unexpected interactive login") },
        updateStatus = { _, _ -> },
    )

    private fun SettingsStore.current(): McpServerConfig = settingsFlow.value.mcpServers.single { it.id == original.id }

    private suspend fun awaitRequest() = assertTrue(withContext(Dispatchers.IO) {
        requestArrived.await(10, TimeUnit.SECONDS)
    })

    @Test
    fun `network errors do not probe oauth metadata or request a login`() = runBlocking {
        val oauth = coordinator(store())
        listOf(
            "Connection timed out for https://mcp.example.test:4010/path401",
            "HTTP 503 for https://mcp.example.test/mcp",
            "Missing or invalid tool arguments",
            "HTTP 4010 is not an authorization status",
        ).forEach { message ->
            assertFalse(message, oauth.needsAuthorization(original, IOException(message)))
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun `explicit unauthorized responses are recognized through exception causes`() = runBlocking {
        val oauth = coordinator(store())
        listOf("HTTP 401", "HTTP/1.1 401 Unauthorized", "status code: 401", "response code: 401", "invalid_token", "Invalid access token",
            "Client request(POST https://mcp.example.test/mcp) invalid: 401 Unauthorized. Text: \"denied\"",
            "Error POSTing to endpoint (HTTP 401 Unauthorized): denied")
            .forEach { message ->
                assertTrue(message, oauth.needsAuthorization(original, IOException("Transport failed", IOException(message))))
            }
        assertEquals(0, requests.get())
    }

    @Test
    fun `sdk transport status is used even when its message omits unauthorized`() = runBlocking {
        val oauth = coordinator(store())
        assertTrue(oauth.needsAuthorization(original, IOException("wrapped", StreamableHttpError(401, "Request denied"))))
        // Body text is not an HTTP authentication status when the typed transport status is 503.
        assertFalse(oauth.needsAuthorization(original, StreamableHttpError(503, "upstream unauthorized")))
        assertEquals(0, requests.get())
    }

    @Test
    fun `manual authorization headers do not invoke oauth recovery`() = runBlocking {
        val manual = original.copy(commonOptions = original.commonOptions.copy(
            headers = listOf("authorization" to "Synthetic manually configured credential"),
            oauth = original.commonOptions.oauth!!.copy(expiresAt = 1),
        ))
        val oauth = coordinator(store(manual))
        assertFalse(oauth.needsAuthorization(manual, IOException("HTTP 401")))
        assertEquals(manual, oauth.ensureFreshToken(manual))
        assertEquals(0, requests.get())
    }

    @Test
    fun `unexpired token is reused until rejected by its server`() = runBlocking {
        val store = store()
        val oauth = coordinator(store)
        assertEquals(original, oauth.ensureFreshToken(original))
        assertEquals(0, requests.get())
        val fresh = oauth.ensureFreshToken(original, rejectedAccessToken = "old-access")
        assertEquals("new-access", fresh.commonOptions.oauth?.accessToken)
        assertEquals("new-refresh", store.current().commonOptions.oauth?.refreshToken)
        assertEquals(1, requests.get())
    }

    @Test
    fun `concurrent rejections of one token issue only one refresh`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val first = async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        val followers = List(7) { async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") } }
        release.countDown()
        (followers + first).awaitAll().forEach { assertEquals("new-access", it.commonOptions.oauth?.accessToken) }
        assertEquals(1, requests.get())
    }

    @Test
    fun `issued refresh persists rotated tokens after caller cancellation`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val caller = launch(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        caller.cancelAndJoin()
        release.countDown()
        withTimeout(10_000) {
            store.settingsFlow.first { it.mcpServers.single().commonOptions.oauth?.refreshToken == "new-refresh" }
        }
        assertEquals("new-access", store.current().commonOptions.oauth?.accessToken)
        assertEquals(1, requests.get())
    }

    @Test
    fun `logout during refresh cannot be overwritten by its result`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val caller = async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        oauth.clearAuthorization(original)
        release.countDown()
        assertNull(caller.await().commonOptions.oauth)
        assertNull(store.current().commonOptions.oauth)
    }

    @Test
    fun `new authorization during refresh wins over its old result`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val caller = async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        val newLogin = original.commonOptions.oauth!!.copy(accessToken = "another-login", refreshToken = "another-refresh")
        store.update { it.copy(mcpServers = listOf(original.copy(commonOptions = original.commonOptions.copy(oauth = newLogin)))) }
        release.countDown()
        assertEquals(newLogin, caller.await().commonOptions.oauth)
        assertEquals(newLogin, store.current().commonOptions.oauth)
    }

    @Test
    fun `url edit during refresh cannot receive the previous resources credentials`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val caller = async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        val edited = original.copy(url = "https://other.example.test/mcp")
        store.update { it.copy(mcpServers = listOf(edited)) }
        release.countDown()
        assertEquals(edited, caller.await())
        assertEquals(edited, store.current())
    }

    @Test
    fun `queued request for previous url does not refresh new resources credentials`() = runBlocking {
        val edited = original.copy(url = "https://other.example.test/mcp")
        val store = store(edited)
        assertEquals(edited, coordinator(store).ensureFreshToken(original, "old-access"))
        assertEquals(0, requests.get())
    }

    @Test
    fun `refresh preserves tool and header edits made during the request`() = runBlocking {
        blockResponse = true
        val store = store()
        val oauth = coordinator(store)
        val caller = async(Dispatchers.Default) { oauth.ensureFreshToken(original, "old-access") }
        awaitRequest()
        val editedOptions = original.commonOptions.copy(
            name = "Renamed server", tools = listOf(McpTool(name = "kept-tool", enable = false)),
            headers = listOf("X-Setting" to "kept-value"),
        )
        store.update { it.copy(mcpServers = listOf(original.copy(commonOptions = editedOptions))) }
        release.countDown()
        val fresh = caller.await()
        assertEquals(editedOptions.copy(oauth = fresh.commonOptions.oauth), fresh.commonOptions)
        assertEquals("new-access", fresh.commonOptions.oauth?.accessToken)
    }

    @Test
    fun `network refresh failure retains tokens and is retryable without authorization`() = runBlocking {
        responseFailure = IOException("Socket read timed out")
        val store = store()
        val oauth = coordinator(store)
        val failure = runCatching { oauth.ensureFreshToken(original, "old-access") }.exceptionOrNull()
        assertTrue(failure is McpTokenRefreshException)
        assertFalse(oauth.needsAuthorization(original, failure!!))
        assertEquals(original, store.current())
    }

    @Test
    fun `invalid grant requires authorization but retains the saved credentials`() = runBlocking {
        responseCode = 400
        responseBody = """{"error":"invalid_grant"}"""
        val store = store()
        val oauth = coordinator(store)
        val failure = runCatching { oauth.ensureFreshToken(original, "old-access") }.exceptionOrNull()
        assertTrue(failure is McpTokenRefreshException)
        assertTrue(oauth.needsAuthorization(original, failure!!))
        assertEquals(original, store.current())
    }

    @Test
    fun `refresh response without refresh token keeps the reusable existing token`() = runBlocking {
        responseBody = """{"access_token":"new-access","expires_in":3600}"""
        val store = store()
        val fresh = coordinator(store).ensureFreshToken(original, "old-access")
        assertEquals("new-access", fresh.commonOptions.oauth?.accessToken)
        assertEquals("old-refresh", store.current().commonOptions.oauth?.refreshToken)
    }

    @Test
    fun `removed server never issues a refresh from an old snapshot`() = runBlocking {
        val store = store()
        store.update { it.copy(mcpServers = emptyList()) }
        coordinator(store).ensureFreshToken(original, "old-access")
        assertTrue(store.settingsFlow.value.mcpServers.isEmpty())
        assertEquals(0, requests.get())
    }
}
