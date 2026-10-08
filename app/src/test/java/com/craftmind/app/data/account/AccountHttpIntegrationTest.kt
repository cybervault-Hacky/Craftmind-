package com.craftmind.app.data.account

import com.craftmind.app.domain.account.AccountApiOutcome
import com.craftmind.app.domain.account.AccountEmailDeliveryStatus
import com.craftmind.app.domain.account.AccountServiceConfiguration
import com.craftmind.app.domain.account.AccountSessionTokens
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional real Android-client → Node HTTP service → file-backed SQLite integration.
 *
 * This is a JVM test of the production HttpAccountApi and AccountApiRequest codecs, not an Android device test. It is
 * opt-in because Node.js is not a normal Android unit-test prerequisite. Enable with
 * CRAFTMIND_LOCAL_AUTH_INTEGRATION=1 on a host with Node 22.5+. The test-only transport maps the client's HTTPS test
 * origin to the isolated loopback Node fixture; no such bypass exists in production sources.
 */
class AccountHttpIntegrationTest {
    @Test
    fun registrationVerificationRecoveryPasswordChangeAndSessionsRoundTripThroughSqlite() {
        assumeTrue(
            "set CRAFTMIND_LOCAL_AUTH_INTEGRATION=1 to run the local Node/SQLite integration",
            System.getenv("CRAFTMIND_LOCAL_AUTH_INTEGRATION") == "1",
        )
        val root = listOf(File("."), File(".."))
            .map { it.canonicalFile }
            .firstOrNull { File(it, "backend/test/local-android-api-fixture.js").isFile }
            ?: error("repository root containing the backend test fixture was not found")
        val database = File.createTempFile("craftmind-auth-integration-", ".db").apply { delete() }
        val configuration = AccountServiceConfiguration.of("https://craftmind-local-integration.invalid")
        var fixture: Fixture? = null
        var firstTokens: AccountSessionTokens? = null
        var secondTokens: AccountSessionTokens? = null
        var thirdTokens: AccountSessionTokens? = null
        var recoveredTokens: AccountSessionTokens? = null
        try {
            fixture = startFixture(root, database)
            var api = HttpAccountApi(configuration, LoopbackFixtureTransport(fixture.port))
            val email = "android-integration@example.test"
            val oldPassword = "InitialPass1!"
            val changedPassword = "ChangedPass2!"
            val recoveredPassword = "RecoveredPass3!"

            val registration = withSecret(oldPassword) {
                success(api.register(email, it, "Android Integration", null))
            }
            assertNullSession(registration.session)
            assertFalse(registration.record.emailVerified)
            assertTrue(registration.verificationRequired)
            assertEquals(AccountEmailDeliveryStatus.DEVELOPMENT_SINK, registration.deliveryStatus)

            val verificationCode = fixture.takeMessage("verification", email)
            val verificationChars = verificationCode.toCharArray()
            val verified = try {
                success(api.verifyEmail(verificationChars))
            } finally {
                verificationChars.fill('\u0000')
            }
            assertTrue(verified.record.emailVerified)
            val usedVerificationCode = verificationCode.toCharArray()
            val usedVerification = try { api.verifyEmail(usedVerificationCode) } finally { usedVerificationCode.fill('\u0000') }
            assertRejected(usedVerification, "EMAIL_VERIFICATION_TOKEN_USED")

            val firstLogin = login(api, email, oldPassword)
            firstTokens = requireNotNull(firstLogin.session).tokens
            val firstSessions = withAccess(firstTokens!!) { success(api.listSessions(it)).sessions }
            assertEquals(1, firstSessions.size)
            assertTrue(firstSessions.single().isCurrent)
            val firstId = firstSessions.single().sessionId

            val secondLogin = login(api, email, oldPassword)
            secondTokens = requireNotNull(secondLogin.session).tokens
            val twoSessions = withAccess(secondTokens!!) { success(api.listSessions(it)).sessions }
            assertEquals(2, twoSessions.size)
            assertEquals(1, twoSessions.count { it.isCurrent })
            assertFalse(twoSessions.any { it.sessionId.contains("access") || it.sessionId.contains("refresh") })

            withAccess(secondTokens!!) { access -> success(api.revokeSession(access, firstId)) }
            val afterRevoke = withAccess(secondTokens!!) { success(api.listSessions(it)).sessions }
            assertEquals(1, afterRevoke.size)
            assertTrue(afterRevoke.single().isCurrent)

            val thirdLogin = login(api, email, oldPassword)
            thirdTokens = requireNotNull(thirdLogin.session).tokens
            val changeResult = withAccess(secondTokens!!) { access ->
                withSecret(oldPassword) { current ->
                    withSecret(changedPassword) { next -> success(api.changePassword(access, current, next)) }
                }
            }
            assertTrue(changeResult.currentSessionRetained)
            assertEquals(1, changeResult.revokedOtherSessions)
            val afterPasswordChange = withAccess(secondTokens!!) { success(api.listSessions(it)).sessions }
            assertEquals(1, afterPasswordChange.size)
            assertTrue(afterPasswordChange.single().isCurrent)

            val recoveryReceipt = success(api.requestPasswordReset(email))
            val neutralUnknownReceipt = success(api.requestPasswordReset("nobody@example.test"))
            assertTrue(recoveryReceipt.accepted)
            assertEquals(recoveryReceipt.deliveryMode, neutralUnknownReceipt.deliveryMode)
            assertEquals(AccountEmailDeliveryStatus.DEVELOPMENT_SINK, recoveryReceipt.deliveryMode)
            val recoveryCode = fixture.takeMessage("password-recovery", email)
            val recoveryChars = recoveryCode.toCharArray()
            val resetReceipt = try {
                withSecret(recoveredPassword) { success(api.confirmPasswordReset(recoveryChars, it)) }
            } finally {
                recoveryChars.fill('\u0000')
            }
            assertTrue(resetReceipt.accepted)
            val reusedRecoveryCode = recoveryCode.toCharArray()
            val reused = try {
                withSecret(recoveredPassword) { api.confirmPasswordReset(reusedRecoveryCode, it) }
            } finally {
                reusedRecoveryCode.fill('\u0000')
            }
            assertRejected(reused, "PASSWORD_RESET_TOKEN_USED")
            val oldSessionResult = withAccess(secondTokens!!) { api.listSessions(it) }
            assertRejected(oldSessionResult, "SESSION_INVALID")
            val oldPasswordResult = loginOutcome(api, email, changedPassword)
            assertRejected(oldPasswordResult, "INVALID_CREDENTIALS")

            val recoveredLogin = login(api, email, recoveredPassword)
            recoveredTokens = requireNotNull(recoveredLogin.session).tokens
            assertTrue(withAccess(recoveredTokens!!) { success(api.listSessions(it)).sessions.single().isCurrent })

            // Restart the actual Node service and reopen the same SQLite file: the new password/account survived process death.
            fixture.stop()
            fixture = startFixture(root, database)
            api = HttpAccountApi(configuration, LoopbackFixtureTransport(fixture.port))
            val afterRestart = login(api, email, recoveredPassword)
            assertNotNull(afterRestart.session)
            requireNotNull(afterRestart.session).tokens.close()

        } finally {
            firstTokens?.close()
            secondTokens?.close()
            thirdTokens?.close()
            recoveredTokens?.close()
            fixture?.stop()
            database.delete()
            File(database.path + "-wal").delete()
            File(database.path + "-shm").delete()
        }
    }

    private fun startFixture(root: File, database: File): Fixture {
        val process = ProcessBuilder("node", "--no-warnings=ExperimentalWarning", "backend/test/local-android-api-fixture.js")
            .directory(root)
            .apply { environment()["CRAFTMIND_TEST_DATABASE"] = database.absolutePath }
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        val ready = reader.readLine()
        if (ready == null || !ready.startsWith("READY ")) {
            process.destroyForcibly()
            val stderr = process.errorStream.bufferedReader().readText()
            error("local account fixture did not start: ${ready ?: stderr}")
        }
        val port = ready.removePrefix("READY ").toIntOrNull()
            ?: error("local account fixture reported an invalid port")
        return Fixture(process, port, reader, BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)))
    }

    private fun <T> success(outcome: AccountApiOutcome<T>): T {
        assertTrue("expected service success, got $outcome", outcome is AccountApiOutcome.Success)
        @Suppress("UNCHECKED_CAST")
        return (outcome as AccountApiOutcome.Success<T>).value
    }

    private fun assertRejected(outcome: AccountApiOutcome<*>, code: String) {
        assertTrue("expected $code, got $outcome", outcome is AccountApiOutcome.Rejected)
        assertEquals(code, (outcome as AccountApiOutcome.Rejected).code.wireValue)
    }

    private fun assertNullSession(session: Any?) = assertEquals(null, session)

    private fun <T> withAccess(tokens: AccountSessionTokens, operation: (CharArray) -> T): T =
        tokens.useTokens { access, _ -> operation(access) }

    private fun <T> withSecret(value: String, operation: (CharArray) -> T): T {
        val characters = value.toCharArray()
        return try { operation(characters) } finally { characters.fill('\u0000') }
    }

    private fun login(api: HttpAccountApi, email: String, password: String) =
        withSecret(password) { success(api.login(email, it, null)) }

    private fun loginOutcome(api: HttpAccountApi, email: String, password: String) =
        withSecret(password) { api.login(email, it, null) }

    private data class Fixture(
        val process: Process,
        val port: Int,
        val reader: BufferedReader,
        val writer: BufferedWriter,
    ) {
        fun takeMessage(kind: String, email: String): String {
            writer.write("{\"action\":\"take-message\",\"kind\":\"$kind\",\"email\":\"$email\"}")
            writer.newLine()
            writer.flush()
            val response = Json.parseToJsonElement(requireNotNull(reader.readLine())).jsonObject
            return response["token"]?.jsonPrimitive?.content
                ?: error("the local test sink did not contain the expected message")
        }

        fun stop() {
            if (!process.isAlive) return
            writer.write("{\"action\":\"stop\"}")
            writer.newLine()
            writer.flush()
            runCatching { reader.readLine() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** Test-only bridge. It cannot be referenced from the main source set and always targets loopback. */
    private class LoopbackFixtureTransport(private val port: Int) : AccountTransport {
        override fun send(request: AccountTransportRequest): AccountTransportResponse {
            val path = URL(request.url).path
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method.name
            connection.setRequestProperty("Accept", "application/json")
            request.bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            request.jsonBody?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            return try {
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
                AccountTransportResponse(status, body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
