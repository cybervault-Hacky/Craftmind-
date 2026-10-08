package com.craftmind.app.domain.account

/**
 * The JSON bodies the app sends to the account service (Phase 17).
 *
 * Written here, in plain Kotlin, rather than in the HTTP client, for one reason: escaping is a correctness and safety
 * boundary. An email address, a display name, or a password containing a quote, a backslash, a newline, or a control
 * character must not be able to change the shape of the request, and that is only testable if the encoding is separate
 * from the socket.
 *
 * The encoding is deliberately minimal — object of string members — because that is all these endpoints accept. Values
 * are never interpolated into JSON without passing through [escapeJsonString].
 */
object AccountApiRequests {
    const val REGISTER_PATH = "/auth/register"
    const val LOGIN_PATH = "/auth/login"
    const val REFRESH_PATH = "/auth/refresh"
    const val LOGOUT_PATH = "/auth/logout"
    const val CURRENT_ACCOUNT_PATH = "/auth/me"
    const val GUEST_PATH = "/auth/guest"
    const val PASSWORD_RESET_PATH = "/auth/password-reset"

    fun register(
        emailAddress: String,
        password: CharArray,
        displayName: String,
        guestIdentityId: String?,
    ): String = buildJsonBody(
        "email" to emailAddress,
        "password" to String(password),
        "displayName" to displayName,
        "guestIdentityId" to guestIdentityId,
    )

    fun login(emailAddress: String, password: CharArray, guestIdentityId: String?): String = buildJsonBody(
        "email" to emailAddress,
        "password" to String(password),
        "guestIdentityId" to guestIdentityId,
    )

    fun refresh(refreshToken: CharArray): String = buildJsonBody("refreshToken" to String(refreshToken))

    fun logout(accessToken: CharArray?, refreshToken: CharArray?): String =
        buildJsonBody("accessToken" to accessToken?.let(::String), "refreshToken" to refreshToken?.let(::String))

    fun guestIdentity(guestIdentityId: String): String = buildJsonBody("guestIdentityId" to guestIdentityId)

    /** Password reset exists as a contract only; calling it is how a client learns it is not implemented. */
    fun passwordReset(emailAddress: String): String = buildJsonBody("email" to emailAddress)

    private fun buildJsonBody(vararg members: Pair<String, String?>): String {
        val present = members.filter { (_, value) -> value != null }
        return present.joinToString(prefix = "{", postfix = "}", separator = ",") { (name, value) ->
            "\"${escapeJsonString(name)}\":\"${escapeJsonString(value!!)}\""
        }
    }

    /**
     * Escapes a JSON string value completely: the two mandatory escapes, the five short escapes, and every control
     * character below 0x20 as a unicode escape. Nothing here depends on the platform or on a library.
     */
    fun escapeJsonString(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (character < ' ') {
                    builder.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
        return builder.toString()
    }
}
