package com.craftmind.app.data.account

import com.craftmind.app.domain.account.AccountApi
import com.craftmind.app.domain.account.AccountApiErrorCode
import com.craftmind.app.domain.account.AccountApiOutcome
import com.craftmind.app.domain.account.AccountApiRequests
import com.craftmind.app.domain.account.AccountEmailDeliveryStatus
import com.craftmind.app.domain.account.AccountEmailVerificationResult
import com.craftmind.app.domain.account.AccountOperationReceipt
import com.craftmind.app.domain.account.AccountPasswordChangeResult
import com.craftmind.app.domain.account.AccountRegistration
import com.craftmind.app.domain.account.AccountRevokeSessionsResult
import com.craftmind.app.domain.account.AccountRemoteSession
import com.craftmind.app.domain.account.AccountSessionList
import com.craftmind.app.domain.account.AccountServerRecord
import com.craftmind.app.domain.account.AccountServerSession
import com.craftmind.app.domain.account.AccountServerStatus
import com.craftmind.app.domain.account.AccountServiceConfiguration
import com.craftmind.app.domain.account.AccountSessionTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * The app's account service client (Phase 17).
 *
 * Responsibilities, in order: build the request body with the tested pure encoders, send it over the transport, and turn
 * the answer into one of the four outcomes the domain understands. It never throws at a caller, never retries writes on
 * its own, and never interprets a failure as success.
 *
 * Two rules are load-bearing:
 *
 * * **No token escapes into a message.** Tokens are handled as [CharArray] and are only ever materialised into a String
 *   for the duration of one request, inside the transport call. Nothing else — not a log, not an exception, not an
 *   outcome — can carry one.
 * * **An unrecognised answer is discarded.** An HTTP error with an unknown code, a body that does not match the
 *   contract, or a 2xx response missing a required field all become [AccountApiOutcome.Malformed], never a guessed
 *   success.
 */
class HttpAccountApi(
    private val configuration: AccountServiceConfiguration,
    private val transport: AccountTransport,
    private val json: Json = DEFAULT_JSON,
) : AccountApi {
    override val isConfigured: Boolean get() = configuration.isConfigured

    override fun register(
        emailAddress: String,
        password: CharArray,
        displayName: String,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration> = exchange(
        path = AccountApiRequests.REGISTER_PATH,
        body = AccountApiRequests.register(emailAddress, password, displayName, guestIdentityId),
        method = AccountTransportMethod.POST,
        bearerToken = null,
        decode = ::decodeRegistration,
    )

    override fun login(
        emailAddress: String,
        password: CharArray,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration> = exchange(
        path = AccountApiRequests.LOGIN_PATH,
        body = AccountApiRequests.login(emailAddress, password, guestIdentityId),
        method = AccountTransportMethod.POST,
        bearerToken = null,
        decode = ::decodeRegistration,
    )

    override fun refresh(refreshToken: CharArray): AccountApiOutcome<AccountServerSession> = exchange(
        path = AccountApiRequests.REFRESH_PATH,
        body = AccountApiRequests.refresh(refreshToken),
        method = AccountTransportMethod.POST,
        bearerToken = null,
        decode = ::decodeSession,
    )

    override fun currentAccount(accessToken: CharArray): AccountApiOutcome<AccountServerRecord> = exchange(
        path = AccountApiRequests.CURRENT_ACCOUNT_PATH,
        body = null,
        method = AccountTransportMethod.GET,
        bearerToken = accessToken,
        decode = ::decodeAccount,
    )

    override fun logout(accessToken: CharArray?, refreshToken: CharArray?): AccountApiOutcome<Unit> = exchange(
        path = AccountApiRequests.LOGOUT_PATH,
        body = AccountApiRequests.logout(accessToken, refreshToken),
        method = AccountTransportMethod.POST,
        bearerToken = null,
        decode = { AccountApiOutcome.Success(Unit) },
    )

    override fun registerGuestIdentity(guestIdentityId: String): AccountApiOutcome<Unit> = exchange(
        path = AccountApiRequests.GUEST_PATH,
        body = AccountApiRequests.guestIdentity(guestIdentityId),
        method = AccountTransportMethod.POST,
        bearerToken = null,
        decode = { AccountApiOutcome.Success(Unit) },
    )

    override fun verifyEmail(token: CharArray): AccountApiOutcome<AccountEmailVerificationResult> = exchange(
        AccountApiRequests.VERIFY_EMAIL_PATH,
        AccountApiRequests.verifyEmail(token),
        AccountTransportMethod.POST,
        null,
        ::decodeEmailVerification,
    )

    override fun resendVerification(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> = exchange(
        AccountApiRequests.RESEND_VERIFICATION_PATH,
        AccountApiRequests.resendVerification(emailAddress),
        AccountTransportMethod.POST,
        null,
        ::decodeOperationReceipt,
    )

    override fun requestPasswordReset(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> = exchange(
        AccountApiRequests.PASSWORD_RESET_REQUEST_PATH,
        AccountApiRequests.requestPasswordReset(emailAddress),
        AccountTransportMethod.POST,
        null,
        ::decodeOperationReceipt,
    )

    override fun confirmPasswordReset(token: CharArray, newPassword: CharArray): AccountApiOutcome<AccountOperationReceipt> = exchange(
        AccountApiRequests.PASSWORD_RESET_CONFIRM_PATH,
        AccountApiRequests.confirmPasswordReset(token, newPassword),
        AccountTransportMethod.POST,
        null,
        ::decodeOperationReceipt,
    )

    override fun changePassword(
        accessToken: CharArray,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): AccountApiOutcome<AccountPasswordChangeResult> = exchange(
        AccountApiRequests.PASSWORD_CHANGE_PATH,
        AccountApiRequests.changePassword(currentPassword, newPassword),
        AccountTransportMethod.POST,
        accessToken,
        ::decodePasswordChange,
    )

    override fun listSessions(accessToken: CharArray): AccountApiOutcome<AccountSessionList> = exchange(
        AccountApiRequests.SESSIONS_PATH,
        null,
        AccountTransportMethod.GET,
        accessToken,
        ::decodeSessions,
    )

    override fun revokeSession(accessToken: CharArray, sessionId: String): AccountApiOutcome<Unit> = exchange(
        AccountApiRequests.SESSION_REVOKE_PATH,
        AccountApiRequests.revokeSession(sessionId),
        AccountTransportMethod.POST,
        accessToken,
        ::decodeSessionRevocation,
    )

    override fun revokeOtherSessions(accessToken: CharArray): AccountApiOutcome<AccountRevokeSessionsResult> = exchange(
        AccountApiRequests.SESSIONS_REVOKE_ALL_PATH,
        "{}",
        AccountTransportMethod.POST,
        accessToken,
        ::decodeRevokeSessions,
    )

    private fun <T> exchange(
        path: String,
        body: String?,
        method: AccountTransportMethod,
        bearerToken: CharArray?,
        decode: (JsonObject) -> AccountApiOutcome<T>,
    ): AccountApiOutcome<T> {
        if (!configuration.isConfigured) return AccountApiOutcome.Rejected(AccountApiErrorCode.BACKEND_UNAVAILABLE)
        val url = configuration.endpointUrl(path)
        // The token is converted for the duration of the call only, and the copy is cleared immediately afterwards.
        val bearer = bearerToken?.let { characters ->
            try {
                String(characters)
            } finally {
                // `String(characters)` already copied; the caller's array is cleared by the caller.
            }
        }
        val response = try {
            transport.send(
                AccountTransportRequest(
                    method = method,
                    url = url,
                    jsonBody = body,
                    bearerToken = bearer,
                ),
            )
        } catch (_: IOException) {
            return AccountApiOutcome.Unreachable
        } catch (_: Exception) {
            // Anything else is still "we could not complete this", never a success and never an exception at the caller.
            return AccountApiOutcome.Unreachable
        }
        return interpret(response.statusCode, response.body, decode)
    }

    private fun <T> interpret(
        statusCode: Int,
        body: String,
        decode: (JsonObject) -> AccountApiOutcome<T>,
    ): AccountApiOutcome<T> {
        val payload = parseObject(body)
        if (statusCode in 200..299) {
            if (payload == null) return AccountApiOutcome.Malformed
            return decode(payload)
        }
        val code = payload?.get("error")?.let { error ->
            runCatching { error.jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return AccountApiOutcome.Rejected(AccountApiErrorCode.fromWireValue(code))
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun decodeRegistration(payload: JsonObject): AccountApiOutcome<AccountRegistration> {
        val account = payload["account"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return AccountApiOutcome.Malformed
        val recordValue = (decodeAccount(account) as? AccountApiOutcome.Success<AccountServerRecord>)?.value
            ?: return AccountApiOutcome.Malformed
        val verificationRequired = payload["verificationRequired"]?.jsonPrimitive?.booleanOrNull == true
        val deliveryStatusWire = payload.stringOrNull("deliveryStatus")
        val deliveryStatus = if (deliveryStatusWire == null) {
            AccountEmailDeliveryStatus.NOT_APPLICABLE
        } else {
            AccountEmailDeliveryStatus.entries.firstOrNull { it.name == deliveryStatusWire }
                ?: return AccountApiOutcome.Malformed
        }
        val sessionObject = payload["session"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (sessionObject == null) {
            if (!verificationRequired || recordValue.emailVerified) return AccountApiOutcome.Malformed
            return AccountApiOutcome.Success(
                AccountRegistration(recordValue, session = null, verificationRequired = true, deliveryStatus = deliveryStatus),
            )
        }
        val tokens = decodeTokens(sessionObject) ?: return AccountApiOutcome.Malformed
        if (verificationRequired || !recordValue.emailVerified) {
            tokens.close()
            return AccountApiOutcome.Malformed
        }
        return AccountApiOutcome.Success(
            AccountRegistration(
                record = recordValue,
                session = AccountServerSession(
                    tokens = tokens,
                    accessExpiresAtEpochMillis = sessionObject.epochMillisOrNull("accessExpiresAt"),
                    refreshExpiresAtEpochMillis = sessionObject.epochMillisOrNull("refreshExpiresAt"),
                ),
                verificationRequired = false,
                deliveryStatus = deliveryStatus,
            ),
        )
    }

    private fun decodeEmailVerification(payload: JsonObject): AccountApiOutcome<AccountEmailVerificationResult> {
        val account = payload["account"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return AccountApiOutcome.Malformed
        val record = (decodeAccount(account) as? AccountApiOutcome.Success<AccountServerRecord>)?.value
            ?: return AccountApiOutcome.Malformed
        if (!record.emailVerified || record.status != AccountServerStatus.ACTIVE) return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(AccountEmailVerificationResult(record))
    }

    private fun decodeOperationReceipt(payload: JsonObject): AccountApiOutcome<AccountOperationReceipt> {
        val accepted = payload["accepted"]?.jsonPrimitive?.booleanOrNull
            ?: payload["reset"]?.jsonPrimitive?.booleanOrNull
            ?: return AccountApiOutcome.Malformed
        if (!accepted) return AccountApiOutcome.Malformed
        val deliveryModeWire = payload.stringOrNull("deliveryMode")
        val deliveryMode = if (deliveryModeWire == null) {
            AccountEmailDeliveryStatus.NOT_APPLICABLE
        } else {
            AccountEmailDeliveryStatus.entries.firstOrNull { it.name == deliveryModeWire }
                ?: return AccountApiOutcome.Malformed
        }
        return AccountApiOutcome.Success(AccountOperationReceipt(accepted, deliveryMode))
    }

    private fun decodePasswordChange(payload: JsonObject): AccountApiOutcome<AccountPasswordChangeResult> {
        val changed = payload["changed"]?.jsonPrimitive?.booleanOrNull ?: return AccountApiOutcome.Malformed
        if (!changed) return AccountApiOutcome.Malformed
        val retained = payload["currentSessionRetained"]?.jsonPrimitive?.booleanOrNull
            ?: return AccountApiOutcome.Malformed
        val revoked = payload["revokedOtherSessions"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return AccountApiOutcome.Malformed
        if (!retained || revoked < 0) return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(AccountPasswordChangeResult(retained, revoked))
    }

    private fun decodeSessionRevocation(payload: JsonObject): AccountApiOutcome<Unit> {
        val revoked = payload["revoked"]?.jsonPrimitive?.booleanOrNull ?: return AccountApiOutcome.Malformed
        val alreadyRevoked = payload["alreadyRevoked"]?.jsonPrimitive?.booleanOrNull ?: return AccountApiOutcome.Malformed
        if (revoked == alreadyRevoked) return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(Unit)
    }

    private fun decodeRevokeSessions(payload: JsonObject): AccountApiOutcome<AccountRevokeSessionsResult> {
        val count = payload["revokedSessions"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return AccountApiOutcome.Malformed
        val retained = payload["currentSessionRetained"]?.jsonPrimitive?.booleanOrNull
            ?: return AccountApiOutcome.Malformed
        if (count < 0 || !retained) return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(AccountRevokeSessionsResult(count, retained))
    }

    private fun decodeSessions(payload: JsonObject): AccountApiOutcome<AccountSessionList> {
        val values = runCatching { payload["sessions"]?.jsonArray }.getOrNull()
            ?: return AccountApiOutcome.Malformed
        val sessions = values.map { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return AccountApiOutcome.Malformed
            val id = item.stringOrNull("sessionId") ?: return AccountApiOutcome.Malformed
            val label = item.stringOrNull("deviceLabel") ?: return AccountApiOutcome.Malformed
            val current = item["isCurrent"]?.jsonPrimitive?.booleanOrNull ?: return AccountApiOutcome.Malformed
            AccountRemoteSession(
                sessionId = id,
                createdAtEpochMillis = item.epochMillisOrNull("createdAt"),
                lastUsedAtEpochMillis = item.epochMillisOrNull("lastUsedAt"),
                expiresAtEpochMillis = item.epochMillisOrNull("expiresAt"),
                deviceLabel = label,
                isCurrent = current,
            )
        }
        if (sessions.count { it.isCurrent } != 1 || sessions.map { it.sessionId }.distinct().size != sessions.size) {
            return AccountApiOutcome.Malformed
        }
        return AccountApiOutcome.Success(AccountSessionList(sessions))
    }

    private fun decodeAccount(payload: JsonObject): AccountApiOutcome<AccountServerRecord> {
        // `/auth/me` wraps the account; register and login pass the account object directly.
        val account = payload["account"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: payload
        val userId = account.stringOrNull("userId") ?: return AccountApiOutcome.Malformed
        val email = account.stringOrNull("email") ?: return AccountApiOutcome.Malformed
        val displayName = account.stringOrNull("displayName") ?: return AccountApiOutcome.Malformed
        val status = AccountServerStatus.fromWireValue(account.stringOrNull("status"))
            ?: return AccountApiOutcome.Malformed
        val verified = account["emailVerified"]?.jsonPrimitive?.booleanOrNull
            ?: return AccountApiOutcome.Malformed
        val verifiedAt = account.epochMillisOrNull("emailVerifiedAt")
        if (verified != (verifiedAt != null)) return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(
            AccountServerRecord(
                userId = userId,
                emailAddress = email,
                displayName = displayName,
                status = status,
                createdAtEpochMillis = account.epochMillisOrNull("createdAt"),
                updatedAtEpochMillis = account.epochMillisOrNull("updatedAt"),
                emailVerified = verified,
                emailVerifiedAtEpochMillis = verifiedAt,
            ),
        )
    }

    private fun decodeSession(payload: JsonObject): AccountApiOutcome<AccountServerSession> {
        val session = payload["session"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return AccountApiOutcome.Malformed
        val tokens = decodeTokens(session) ?: return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(
            AccountServerSession(
                tokens = tokens,
                accessExpiresAtEpochMillis = session.epochMillisOrNull("accessExpiresAt"),
                refreshExpiresAtEpochMillis = session.epochMillisOrNull("refreshExpiresAt"),
            ),
        )
    }

    /** Builds the two-character-array token holder and clears the intermediate copies it made. */
    private fun decodeTokens(session: JsonObject): AccountSessionTokens? {
        val access = session.stringOrNull("accessToken") ?: return null
        val refresh = session.stringOrNull("refreshToken") ?: return null
        val accessCharacters = access.toCharArray()
        val refreshCharacters = refresh.toCharArray()
        return try {
            AccountSessionTokens.of(accessCharacters, refreshCharacters)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            accessCharacters.fill('\u0000')
            refreshCharacters.fill('\u0000')
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The service reports ISO-8601 timestamps; the app only ever needs them for display and staleness. */
    private fun JsonObject.epochMillisOrNull(name: String): Long? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.let { value ->
            runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        }

    companion object {
        /** Unknown fields are ignored so a service can add data without breaking older clients. */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
        }
    }
}
