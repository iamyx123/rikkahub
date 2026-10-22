package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import io.ktor.client.plugins.ResponseException
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "McpOAuthCoordinator"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
internal const val MCP_OAUTH_CALLBACK_PORT = 52_134
internal const val MCP_OAUTH_CALLBACK_PATH = "/oauth/callback"
internal const val MCP_OAUTH_REDIRECT_URI =
    "http://localhost:$MCP_OAUTH_CALLBACK_PORT$MCP_OAUTH_CALLBACK_PATH"
// 托管在 GitHub Pages 上的 Client ID Metadata Document，其 redirect_uris 必须与 MCP_OAUTH_REDIRECT_URI 一致
internal const val MCP_OAUTH_CLIENT_METADATA_URL = "https://rikkahub.github.io/oauth/client.json"
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

internal class McpTokenRefreshException(
    val authorizationRequired: Boolean,
    cause: Exception,
) : IOException(if (authorizationRequired) "MCP 登录已失效，请重新授权。" else "MCP 登录刷新暂时失败，已保留会话，请稍后重试。", cause)

/**
 * 负责 MCP OAuth 的授权、令牌刷新与持久化。
 *
 * 连接生命周期由配置流的消费者管理；令牌持久化后，配置变化会自然触发连接替换。
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val oauthClient: OAuthHttpClient,
    private val discoveryClient: McpOAuthDiscoveryClient,
    private val callbackServer: OAuthLoopbackCallbackServer,
    private val authorizationLauncher: OAuthAuthorizationLauncher,
    private val updateStatus: (Uuid, McpStatus) -> Unit,
) {
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth authorization failed for ${config.commonOptions.name}", e)
                updateStatus(config.id, McpStatus.Error.from(e, fallbackMessage = "OAuth authorization failed"))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization)
    }

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        authorizationJobs.remove(config.id)?.cancel()
        persistOAuthState(config.id, null)
        return settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
            ?: config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * 按 serverId 串行刷新。获得锁后重新读取配置，避免并发工具调用重复使用同一个 refresh token。
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig, rejectedAccessToken: String? = null): McpServerConfig =
        // An issued refresh may rotate its token. Stopping a tool call must not cancel its persistence.
        appScope.async(Dispatchers.IO) { refreshTokenLocked(configInput, rejectedAccessToken) }.await()

    private suspend fun refreshTokenLocked(configInput: McpServerConfig, rejectedAccessToken: String?): McpServerConfig {
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
                ?: return@withLock configInput
            // A queued request belongs to its original resource, never refresh a newly edited URL.
            if (config.serverUrl != configInput.serverUrl ||
                config.commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }
            ) return@withLock config
            val oauth = config.commonOptions.oauth ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config

            val expired = oauth.expiresAt > 0 &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            val rejected = rejectedAccessToken != null && oauth.accessToken == rejectedAccessToken
            if (!oauth.accessToken.isNullOrBlank() && !expired && !rejected) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            try {
                val token = oauthClient.refreshToken(
                    OAuthHttpClient.RefreshTokenRequest(
                        tokenEndpoint = tokenEndpoint,
                        clientId = clientId,
                        clientSecret = oauth.clientSecret,
                        refreshToken = oauth.refreshToken,
                        resources = listOf(McpOAuthDiscoveryClient.canonicalResource(config.serverUrl)),
                        scope = oauth.scope,
                    )
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                // A logout, authorization, or URL edit during the request must win over this old refresh.
                settingsStore.update { latest ->
                    latest.copy(mcpServers = latest.mcpServers.map { server ->
                        if (server.id == config.id && server.serverUrl == config.serverUrl && server.commonOptions.oauth == oauth) {
                            server.clone(commonOptions = server.commonOptions.copy(oauth = updated))
                        } else server
                    })
                }
                settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id } ?: configInput
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw McpTokenRefreshException(
                    authorizationRequired = looksUnauthorized(failure) ||
                        Regex("(?i)\\binvalid_grant\\b").containsMatchIn(failure.message.orEmpty()),
                    cause = failure,
                )
            }
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (config.commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }) {
            return false
        }
        if (error is McpTokenRefreshException) return error.authorizationRequired
        // Metadata describes capability, not a failed credential: a timeout must never request login.
        return looksUnauthorized(error)
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

        // 部分服务器（如 Zomato）不提供 RFC 9728 元数据，按旧版规范退回到服务器 origin 作为授权服务器
        val protectedResource = runCatching { discoveryClient.discoverProtectedResource(serverUrl) }
            .onFailure { Log.i(TAG, "Protected resource discovery failed, fallback to server origin: ${it.message}") }
            .getOrNull()
        val issuer = protectedResource?.authorizationServers?.firstOrNull()
            ?: McpOAuthDiscoveryClient.serverOrigin(serverUrl)
            ?: error("无法确定授权服务器")
        val metadata = discoveryClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("授权服务器缺少 authorization_endpoint")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("授权服务器缺少 token_endpoint")
        val scope = config.commonOptions.oauth?.scope
            ?: protectedResource?.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val resource = McpOAuthDiscoveryClient.canonicalResource(serverUrl)
        val callbackSession = callbackServer.openSession(context, state)
        try {
            val redirectUri = callbackSession.redirectUri
            check(redirectUri == MCP_OAUTH_REDIRECT_URI) {
                "OAuth 回调服务器地址不一致: $redirectUri"
            }
            val existing = config.commonOptions.oauth
            val canReuseClient = existing?.redirectUri == redirectUri && !existing.clientId.isNullOrBlank()
            var clientId = existing?.clientId.takeIf { canReuseClient }
            var clientSecret = existing?.clientSecret.takeIf { canReuseClient }
            if (clientId.isNullOrBlank() && metadata.registrationEndpoint == null &&
                metadata.clientIdMetadataDocumentSupported
            ) {
                // 无动态注册端点时，使用 Client ID Metadata Document（URL 即 client_id）
                clientId = MCP_OAUTH_CLIENT_METADATA_URL
                clientSecret = null
            }
            if (clientId.isNullOrBlank()) {
                val registrationEndpoint = metadata.registrationEndpoint
                    ?: error("授权服务器不支持动态注册，且未预配置 client_id")
                val registration = oauthClient.registerClient(
                    registrationEndpoint = registrationEndpoint,
                    request = OAuthHttpClient.ClientRegistrationRequest(
                        clientName = config.commonOptions.name.ifBlank { "RikkaHub" },
                        redirectUris = listOf(redirectUri),
                        scope = scope,
                    ),
                )
                clientId = registration.clientId
                clientSecret = registration.clientSecret
            }

            persistOAuthState(
                config.id,
                (existing ?: McpOAuthState()).copy(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = scope,
                )
            )

            val authorizationUrl = oauthClient.buildAuthorizationUrl(
                OAuthHttpClient.AuthorizationRequest(
                    authorizationEndpoint = authorizationEndpoint,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    pkce = pkce,
                    state = state,
                    scope = scope,
                    resources = listOf(resource),
                )
            )
            withContext(Dispatchers.Main) {
                authorizationLauncher.launch(context, authorizationUrl)
            }

            val callback = callbackSession.awaitCallback(OAUTH_CALLBACK_TIMEOUT)
                ?: error("OAuth 授权超时")
            callback.error?.let { error(buildAuthorizationError(it, callback.errorDescription)) }
            val code = callback.code ?: error("授权失败: 未返回授权码")

            val token = oauthClient.exchangeAuthorizationCode(
                OAuthHttpClient.AuthorizationCodeTokenRequest(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = redirectUri,
                    resources = listOf(resource),
                )
            )
            persistOAuthState(
                config.id,
                McpOAuthState(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authorizationEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = metadata.registrationEndpoint,
                    redirectUri = redirectUri,
                    scope = token.scope ?: scope,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                )
            )
        } finally {
            withContext(NonCancellable) {
                callbackSession.close()
            }
        }
    }

    private fun buildAuthorizationError(error: String, description: String?): String =
        if (description.isNullOrBlank()) "授权失败: $error" else "授权失败: $error ($description)"

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    private fun looksUnauthorized(error: Throwable): Boolean {
        // The SDK's StreamableHttpError may contain only the server body, without "401" in its message.
        val status = generateSequence(error) { it.cause }.mapNotNull {
            when (it) {
                is ResponseException -> it.response.status.value
                is StreamableHttpError -> it.code?.takeIf { code -> code in 100..599 }
                else -> null
            }
        }.firstOrNull()
        if (status != null) return status == 401
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return Regex("\\bunauthorized\\b|\\binvalid_token\\b|\\binvalid access token\\b|" +
            "\\bhttp(?:/\\d(?:\\.\\d)?)?\\s*(?:status(?:\\s+code)?\\s*)?[:=]?\\s*401\\b|" +
            "\\b(?:status|response)(?:\\s+code)?\\s*[:=]?\\s*401\\b|\\b401\\s+unauthorized\\b")
            .containsMatchIn(message)
    }
}
