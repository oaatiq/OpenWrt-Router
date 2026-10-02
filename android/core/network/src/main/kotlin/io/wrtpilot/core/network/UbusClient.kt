package io.wrtpilot.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** kotlinx.serialization settings used for every router response. */
val WrtJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * JSON-RPC client for OpenWrt's ubus over HTTP (uhttpd-mod-ubus + rpcd).
 *
 * Handles the rpcd session: logs in lazily, and when a call is rejected
 * with "Access denied" because the session expired, logs in again once and
 * retries. Safe to share between coroutines.
 */
class UbusClient(
    val endpoint: RouterEndpoint,
    private val credentials: Credentials,
    baseClient: OkHttpClient,
    private val json: Json = WrtJson,
) {
    private val http: OkHttpClient =
        if (endpoint.https && endpoint.certSha256 != null) Tls.pinnedClient(baseClient, endpoint.certSha256)
        else baseClient

    private val loginMutex = Mutex()
    private val ids = AtomicLong(1)

    @Volatile
    private var session: String? = null

    val hasSession: Boolean get() = session != null

    /** Logs in (or re-uses the current session) and returns the session id. */
    suspend fun login(force: Boolean = false): String = loginMutex.withLock {
        val current = session
        if (!force && current != null) return current

        val args = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.password)
        }
        val res = try {
            rawCall(ANONYMOUS_SESSION, "session", "login", args)
        } catch (e: ApiError.Ubus) {
            throw if (e.status == ApiError.UBUS_PERMISSION_DENIED) ApiError.BadCredentials else e
        } catch (e: ApiError.AccessDenied) {
            throw ApiError.BadCredentials
        }
        val sid = (res["ubus_rpc_session"] as? JsonPrimitive)?.contentOrNull
            ?: throw ApiError.BadCredentials
        session = sid
        sid
    }

    fun logout() {
        session = null
    }

    /**
     * Calls `object.method(args)` and returns the reply object. Throws [ApiError].
     */
    suspend fun call(obj: String, method: String, args: JsonObject = EMPTY): JsonObject {
        val sid = login()
        return try {
            rawCall(sid, obj, method, args)
        } catch (e: ApiError.AccessDenied) {
            // most likely the session timed out: re-login once and retry
            val fresh = login(force = true)
            rawCall(fresh, obj, method, args)
        }
    }

    private suspend fun rawCall(sid: String, obj: String, method: String, args: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ids.getAndIncrement())
                put("method", "call")
                put("params", buildJsonArray {
                    add(JsonPrimitive(sid))
                    add(JsonPrimitive(obj))
                    add(JsonPrimitive(method))
                    add(args)
                })
            }
            val request = Request.Builder()
                .url(endpoint.ubusUrl)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val text = try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ApiError.Http(response.code)
                    response.body?.string() ?: throw ApiError.Protocol("empty body")
                }
            } catch (e: ApiError) {
                throw e
            } catch (e: SSLException) {
                throw mapTlsError(e)
            } catch (e: IOException) {
                throw ApiError.Network(e)
            }

            parseResponse(text, method)
        }

    private fun parseResponse(text: String, method: String): JsonObject {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ApiError.Protocol("not JSON", e)
        }

        root["error"]?.let { err ->
            val code = (err as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull
            throw when (code) {
                ApiError.RPC_ACCESS_DENIED, ApiError.RPC_SESSION_NOT_FOUND -> ApiError.AccessDenied
                ApiError.RPC_NOT_FOUND -> ApiError.AgentMissing
                ApiError.RPC_TIMEOUT -> ApiError.Ubus(ApiError.UBUS_TIMEOUT)
                ApiError.RPC_METHOD_NOT_FOUND -> ApiError.AgentOutdated(method)
                else -> ApiError.Protocol("JSON-RPC error $code")
            }
        }

        val result = root["result"] as? JsonArray ?: throw ApiError.Protocol("missing result")
        val status = result.firstOrNull()?.jsonPrimitive?.intOrNull ?: throw ApiError.Protocol("missing status")
        if (status != 0) {
            throw when (status) {
                ApiError.UBUS_NOT_FOUND -> ApiError.AgentMissing
                ApiError.UBUS_METHOD_NOT_FOUND -> ApiError.AgentOutdated(method)
                else -> ApiError.Ubus(status)
            }
        }
        val data: JsonElement = result.getOrNull(1) ?: JsonNull
        return data as? JsonObject ?: EMPTY
    }

    private fun mapTlsError(e: SSLException): ApiError {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CertificateMismatch) return ApiError.CertificateChanged(cause.expected, cause.actual)
            cause = cause.cause
        }
        return if (e is SSLHandshakeException || e is SSLPeerUnverifiedException) {
            ApiError.UntrustedCertificate(
                runCatching { Tls.fetchCertificateSha256(endpoint.host, endpoint.port) }.getOrNull(), e
            )
        } else {
            ApiError.Network(e)
        }
    }

    companion object {
        const val ANONYMOUS_SESSION = "00000000000000000000000000000000"
        val EMPTY = JsonObject(emptyMap())
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
