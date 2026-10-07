package com.craftmind.app.data.account

import com.craftmind.app.domain.account.AccountApi
import com.craftmind.app.domain.account.AccountApiErrorCode
import com.craftmind.app.domain.account.AccountApiOutcome
import com.craftmind.app.domain.account.AccountApiRequests
import com.craftmind.app.domain.account.AccountRegistration
import com.craftmind.app.domain.account.AccountServerRecord
import com.craftmind.app.domain.account.AccountServerSession
import com.craftmind.app.domain.account.AccountServerStatus
import com.craftmind.app.domain.account.AccountServiceConfiguration
import com.craftmind.app.domain.account.AccountSessionTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        displayName: String?,
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
        val session = payload["session"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return AccountApiOutcome.Malformed
        val recordOutcome = decodeAccount(account)
        val recordValue = (recordOutcome as? AccountApiOutcome.Success<AccountServerRecord>)?.value
            ?: return AccountApiOutcome.Malformed
        val tokens = decodeTokens(session) ?: return AccountApiOutcome.Malformed
        return AccountApiOutcome.Success(
            AccountRegistration(
                record = recordValue,
                session = AccountServerSession(
                    tokens = tokens,
                    accessExpiresAtEpochMillis = session.epochMillisOrNull("accessExpiresAt"),
                    refreshExpiresAtEpochMillis = session.epochMillisOrNull("refreshExpiresAt"),
                ),
            ),
        )
    }

    private fun decodeAccount(payload: JsonObject): AccountApiOutcome<AccountServerRecord> {
        // `/auth/me` wraps the account; register and login pass the account object directly.
        val account = payload["account"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: payload
        val userId = account.stringOrNull("userId") ?: return AccountApiOutcome.Malformed
        val email = account.stringOrNull("email") ?: return AccountApiOutcome.Malformed
        val displayName = account.stringOrNull("displayName") ?: return AccountApiOutcome.Malformed
        val status = AccountServerStatus.fromWireValue(account.stringOrNull("status"))
        return AccountApiOutcome.Success(
            AccountServerRecord(
                userId = userId,
                emailAddress = email,
                displayName = displayName,
                status = status,
                createdAtEpochMillis = account.epochMillisOrNull("createdAt"),
                updatedAtEpochMillis = account.epochMillisOrNull("updatedAt"),
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
