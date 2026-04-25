package com.thomas.notiguide.shared.http

import org.springframework.http.server.reactive.ServerHttpRequest

object ClientIpResolver {

    private const val UNKNOWN = "unknown"
    private const val X_REAL_IP = "X-Real-IP"
    private const val X_FORWARDED_FOR = "X-Forwarded-For"

    fun resolve(request: ServerHttpRequest): String {
        // ForwardedHeaderTransformer (enabled by server.forward-headers-strategy=framework)
        // rewrites remoteAddress via InetSocketAddress.createUnresolved(host, port), which
        // makes getAddress() null but keeps the IP in getHostString(). Check both.
        request.remoteAddress?.let { addr ->
            val ip = addr.address?.hostAddress?.takeIf { it.isNotBlank() }
                ?: addr.hostString?.takeIf { it.isNotBlank() }
            if (!ip.isNullOrBlank()) return normalize(ip)
        }

        request.headers.getFirst(X_REAL_IP)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        request.headers.getFirst(X_FORWARDED_FOR)
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalize(it) }

        return UNKNOWN
    }

    private fun normalize(ip: String): String =
        if (ip == "0:0:0:0:0:0:0:1" || ip == "::1") "127.0.0.1" else ip
}
