package io.wrtpilot.core.network

/**
 * Everything that can go wrong talking to a router, as stable types the UI
 * can turn into localised messages.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Host unreachable, timeout, DNS failure… */
    class Network(cause: Throwable) : ApiError("network error: ${cause.message}", cause)

    /** HTTPS certificate not trusted (yet). [sha256] is the presented certificate. */
    class UntrustedCertificate(val sha256: String?, cause: Throwable? = null) :
        ApiError("untrusted certificate", cause)

    /** The pinned certificate fingerprint no longer matches. */
    class CertificateChanged(val expected: String, val actual: String?) :
        ApiError("certificate changed")

    /** HTTP error from uhttpd (e.g. 404 when ubus over HTTP is disabled). */
    class Http(val status: Int) : ApiError("HTTP $status")

    /** session.login refused the username/password. */
    object BadCredentials : ApiError("bad credentials") {
        private fun readResolve(): Any = BadCredentials
    }

    /** The session's ACL does not allow the call. */
    object AccessDenied : ApiError("access denied") {
        private fun readResolve(): Any = AccessDenied
    }

    /** The "wrtpilot" ubus object does not exist: agent not installed. */
    object AgentMissing : ApiError("wrtpilot agent not installed") {
        private fun readResolve(): Any = AgentMissing
    }

    /** The agent does not know the method: router agent older than the app. */
    class AgentOutdated(val method: String) : ApiError("method not supported: $method")

    /** Any other non-zero ubus status code. */
    class Ubus(val status: Int) : ApiError("ubus status $status")

    /** The agent answered { ok: false, error: code, message }. */
    class Agent(val code: String, val detail: String?) : ApiError("$code: ${detail ?: ""}")

    /** Malformed / unexpected response. */
    class Protocol(detail: String, cause: Throwable? = null) : ApiError("protocol error: $detail", cause)

    companion object {
        // ubus status codes (libubus)
        const val UBUS_INVALID_ARGUMENT = 2
        const val UBUS_METHOD_NOT_FOUND = 3
        const val UBUS_NOT_FOUND = 4
        const val UBUS_NO_DATA = 5
        const val UBUS_PERMISSION_DENIED = 6
        const val UBUS_TIMEOUT = 7

        // JSON-RPC error codes emitted by uhttpd-mod-ubus
        const val RPC_SESSION_NOT_FOUND = -32001
        const val RPC_ACCESS_DENIED = -32002
        const val RPC_TIMEOUT = -32003
        const val RPC_METHOD_NOT_FOUND = -32601
        const val RPC_NOT_FOUND = -32000
    }
}

/** Error codes returned by the agent in { ok: false, error: <code> }. */
object AgentErrors {
    const val SELF_BLOCK = "self_block"
    const val INVALID_MAC = "invalid_mac"
    const val INVALID_ARGUMENT = "invalid_argument"
    const val INVALID_DOMAIN = "invalid_domain"
    const val INVALID_SCHEDULE = "invalid_schedule"
    const val INVALID_GROUP_ID = "invalid_group_id"
    const val UNKNOWN_GROUP = "unknown_group"
    const val SQM_MISSING = "sqm_missing"
    const val NO_WAN = "no_wan"
    const val COLLECTOR_UNAVAILABLE = "collector_unavailable"
    const val NFT_FAILED = "nft_failed"
    const val APPLY_FAILED = "apply_failed"
    const val INTERNAL = "internal"
}
