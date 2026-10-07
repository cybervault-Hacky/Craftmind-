package com.craftmind.app.domain.account

/**
 * Where the account service lives, and the rules for that address (Phase 17).
 *
 * The address arrives from the build configuration, never from a literal in the source: the repository contains no
 * service address, no credential, and no environment-specific value. Two rules are enforced here rather than trusted to
 * callers:
 *
 * * an address must be absolute HTTPS — plain HTTP is refused, so an account session can never travel in the clear, and
 *   the Android manifest keeps cleartext traffic disabled as a second line of defence;
 * * an address must not carry a path prefix, a query, or a fragment, because every endpoint is a known path.
 *
 * An empty address is a legitimate configuration, not an error: it means this build has no account service, and the app
 * says exactly that instead of offering a sign-in that cannot work.
 */
data class AccountServiceConfiguration private constructor(val baseUrl: String?) {
    val isConfigured: Boolean get() = baseUrl != null

    /** The absolute URL of an endpoint. Only ever called for a configured service. */
    fun endpointUrl(path: String): String {
        val base = requireNotNull(baseUrl) { "no account service is configured for this build" }
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return base + normalizedPath
    }

    override fun toString(): String = if (baseUrl == null) {
        "AccountServiceConfiguration(none)"
    } else {
        // The address itself is configuration, not a secret, but it is still not printed where a user could be misled
        // into thinking it identifies them. The host is enough for diagnostics.
        "AccountServiceConfiguration(configured)"
    }

    companion object {
        const val REQUIREMENT = "an account service address must be an absolute HTTPS URL with no path or query"

        /** An address that is usable, or an exception stating which rule it broke. */
        fun of(baseUrl: String): AccountServiceConfiguration {
            val trimmed = baseUrl.trim()
            require(trimmed.startsWith("$SECURE_SCHEME://")) { "an account service must use $SECURE_SCHEME: $REQUIREMENT" }
            val remainder = trimmed.removePrefix("$SECURE_SCHEME://")
            val host = remainder.substringBefore('/')
            require(host.isNotBlank() && host != ":" && !host.startsWith(":")) { "an account service address needs a host" }
            require(!remainder.contains('?') && !remainder.contains('#')) { REQUIREMENT }
            val normalized = trimmed.trimEnd('/')
            require(normalized.substringAfter("$SECURE_SCHEME://").none { it == '/' }) { REQUIREMENT }
            return AccountServiceConfiguration(normalized)
        }

        /** The build's configuration. A blank value is a deliberate "no account service" build. */
        fun fromBuildValue(baseUrl: String?): AccountServiceConfiguration =
            if (baseUrl.isNullOrBlank()) AccountServiceConfiguration(null) else of(baseUrl)

        const val SECURE_SCHEME = "https"
    }
}
