package com.craftmind.app.data.minecraft

import android.content.Context
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.bridge.protocol.BridgeCrypto
import com.craftmind.bridge.protocol.BridgeEnvelope
import com.craftmind.bridge.protocol.BridgeNetworkAddressPolicy
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BridgeProtocolException
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Strict private-LAN HTTPS client. Sessions are memory-only; endpoint pins and Android Keystore identity persist. */
class AndroidMinecraftBridgePairingRepository(context: Context) : MinecraftBridgePairingRepository {
    private val profileRepository = DataStoreMinecraftBridgeProfileRepository(context.applicationContext)
    private val signingKey = AndroidBridgeSigningKey()
    private val operationLock = Mutex()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableConnectionState = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
    private var activeSession: ActiveSession? = null
    private var expiryJob: Job? = null

    override val connectionState = mutableConnectionState.asStateFlow()
    override val profile: Flow<TrustedMinecraftBridge?> = profileRepository.profile

    override suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String) {
        mutableConnectionState.value = BridgeConnectionState.Connecting
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    if (profileRepository.profile.first() != null) fail("BRIDGE_ALREADY_CONFIGURED")
                    val endpoint = BridgeEndpoint.create(host, port, tlsFingerprint)
                    if (pairingCode.length != 43 || !pairingCode.matches(Regex("[A-Za-z0-9_-]{43}"))) {
                        fail("PAIRING_CODE_INVALID")
                    }
                    val info = fetchBridgeInfo(endpoint)
                    val clientId = signingKey.clientId()
                    val publicKey = signingKey.publicKeyX509()
                    val nonceBytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                    val nonce = BridgeCrypto.base64Url(nonceBytes)
                    val issuedAt = System.currentTimeMillis()
                    val proof = BridgeCrypto.pairingProof(info.bridgeId, clientId, issuedAt, nonce, publicKey)
                    val signature = signingKey.sign(proof)
                    val payload = JsonObject().apply {
                        addProperty("pairingCode", pairingCode)
                        addProperty("clientId", clientId)
                        addProperty("displayName", CLIENT_DISPLAY_NAME)
                        addProperty("publicKeyBase64Url", BridgeCrypto.base64Url(publicKey))
                        addProperty("clientNonce", nonce)
                        addProperty("issuedAtEpochMillis", issuedAt)
                        addProperty("proofSignatureBase64Url", BridgeCrypto.base64Url(signature))
                    }
                    BridgeCrypto.zero(publicKey)
                    BridgeCrypto.zero(nonceBytes)
                    BridgeCrypto.zero(proof)
                    BridgeCrypto.zero(signature)
                    val requestBody = createEnvelope("pair.request", payload, issuedAt)
                    payload.addProperty("pairingCode", "")
                    try {
                        val response = post(endpoint, PAIR_PATH, requestBody.bytes, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES)
                        requireResponse(response, requestBody.requestId, "pair.accepted")
                        BridgeProtocolCodec.requireExactKeys(response.payload, "bridgeId", "clientId", "pairedAtEpochMillis")
                        val acceptedBridgeId = BridgeProtocolCodec.requiredString(response.payload, "bridgeId", 80)
                        val acceptedClientId = BridgeProtocolCodec.requiredString(response.payload, "clientId", 40)
                        val pairedAt = BridgeProtocolCodec.requiredLong(response.payload, "pairedAtEpochMillis")
                        if (acceptedBridgeId != info.bridgeId || acceptedClientId != clientId || pairedAt <= 0L ||
                            pairedAt > System.currentTimeMillis() + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS) {
                            fail("PAIRING_RESPONSE_INVALID")
                        }
                        val profile = TrustedMinecraftBridge(
                            host = endpoint.host,
                            port = endpoint.port,
                            tlsFingerprint = endpoint.canonicalFingerprint,
                            bridgeId = info.bridgeId,
                            clientId = clientId,
                            displayName = CLIENT_DISPLAY_NAME,
                            pairedAtEpochMillis = pairedAt,
                        )
                        try {
                            profileRepository.save(profile)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            fail("LOCAL_PAIRING_SAVE_FAILED_AFTER_SERVER_PAIR")
                        }
                        activeSession = authenticateAndReadCapabilities(endpoint, profile)
                    } finally {
                        BridgeCrypto.zero(requestBody.bytes)
                    }
                }
            }
            publishConnected()
        } catch (error: CancellationException) {
            clearActiveSession()
            throw error
        } catch (error: Exception) {
            publishFailure(error)
            throw error.asBridgeFailure()
        }
    }

    override suspend fun connect() {
        mutableConnectionState.value = BridgeConnectionState.Connecting
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    val profile = profileRepository.profile.first() ?: fail("BRIDGE_NOT_PAIRED")
                    val endpoint = BridgeEndpoint.fromProfile(profile)
                    val info = fetchBridgeInfo(endpoint)
                    verifyProfileIdentity(profile, info)
                    activeSession = authenticateAndReadCapabilities(endpoint, profile)
                }
            }
            publishConnected()
        } catch (error: CancellationException) {
            clearActiveSession()
            throw error
        } catch (error: Exception) {
            clearActiveSession()
            publishFailure(error)
            throw error.asBridgeFailure()
        }
    }

    override suspend fun disconnect() {
        var remoteConfirmed = false
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    val session = activeSession
                    if (session != null) {
                        val payload = JsonObject()
                        val body = createEnvelope("session.disconnect.request", payload)
                        try {
                            val response = postAuthenticated(session, DISCONNECT_PATH, body)
                            requireResponse(response, body.requestId, "session.disconnect.accepted")
                            BridgeProtocolCodec.requireExactKeys(response.payload, "disconnected")
                            if (!BridgeProtocolCodec.requiredBoolean(response.payload, "disconnected")) {
                                fail("DISCONNECT_NOT_CONFIRMED")
                            }
                            remoteConfirmed = true
                        } finally {
                            BridgeCrypto.zero(body.bytes)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The local session is cleared regardless. The UI distinguishes a remote confirmation from local-only cleanup.
        } finally {
            clearActiveSession()
            mutableConnectionState.value = BridgeConnectionState.Disconnected
        }
        if (!remoteConfirmed && profileRepository.profile.first() != null) fail("LOCAL_DISCONNECT_ONLY")
    }

    override suspend fun forgetLocally() {
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    profileRepository.clear()
                    signingKey.delete()
                }
            }
        } finally {
            clearActiveSession()
            mutableConnectionState.value = BridgeConnectionState.Disconnected
        }
    }

    override suspend fun revoke() {
        mutableConnectionState.value = BridgeConnectionState.Connecting
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    val profile = profileRepository.profile.first() ?: fail("BRIDGE_NOT_PAIRED")
                    var session = activeSession
                    if (session == null || session.expiresAtEpochMillis <= System.currentTimeMillis()) {
                        clearActiveSession()
                        val endpoint = BridgeEndpoint.fromProfile(profile)
                        val info = fetchBridgeInfo(endpoint)
                        verifyProfileIdentity(profile, info)
                        session = authenticateAndReadCapabilities(endpoint, profile)
                        activeSession = session
                    }
                    val authenticatedSession = session ?: fail("BRIDGE_SESSION_UNAVAILABLE")
                    val payload = JsonObject().apply { addProperty("clientId", profile.clientId) }
                    val body = createEnvelope("pair.revoke.request", payload)
                    try {
                        val response = postAuthenticated(authenticatedSession, REVOKE_PATH, body)
                        requireResponse(response, body.requestId, "pair.revoke.accepted")
                        BridgeProtocolCodec.requireExactKeys(response.payload, "clientId", "revoked")
                        if (BridgeProtocolCodec.requiredString(response.payload, "clientId", 40) != profile.clientId ||
                            !BridgeProtocolCodec.requiredBoolean(response.payload, "revoked")) {
                            fail("REVOCATION_NOT_CONFIRMED")
                        }
                        profileRepository.clear()
                        signingKey.delete()
                    } finally {
                        BridgeCrypto.zero(body.bytes)
                    }
                }
            }
            clearActiveSession()
            mutableConnectionState.value = BridgeConnectionState.Disconnected
        } catch (error: CancellationException) {
            clearActiveSession()
            throw error
        } catch (error: Exception) {
            clearActiveSession()
            publishFailure(error)
            throw error.asBridgeFailure()
        }
    }

    private fun authenticateAndReadCapabilities(endpoint: BridgeEndpoint, profile: TrustedMinecraftBridge): ActiveSession {
        val clientId = profile.clientId
        val challengePayload = JsonObject().apply { addProperty("clientId", clientId) }
        val challengeRequest = createEnvelope("session.challenge.request", challengePayload)
        val challenge: BridgeEnvelope
        try {
            challenge = post(endpoint, CHALLENGE_PATH, challengeRequest.bytes, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES)
            requireResponse(challenge, challengeRequest.requestId, "session.challenge.response")
            BridgeProtocolCodec.requireExactKeys(challenge.payload, "challengeId", "nonce", "expiresAtEpochMillis")
            val challengeId = BridgeProtocolCodec.requiredString(challenge.payload, "challengeId", 36)
            val nonce = BridgeProtocolCodec.requiredString(challenge.payload, "nonce", 128)
            val expiresAt = BridgeProtocolCodec.requiredLong(challenge.payload, "expiresAtEpochMillis")
            val challengeNow = System.currentTimeMillis()
            if (expiresAt <= challengeNow) fail("AUTH_CHALLENGE_EXPIRED")
            if (expiresAt > challengeNow + BridgeProtocol.AUTH_CHALLENGE_MILLIS + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS) {
                fail("AUTH_CHALLENGE_INVALID")
            }
            val nonceBytes = BridgeCrypto.decodeBase64Url(nonce, 128)
            try {
                if (nonceBytes.size != 32) fail("AUTH_CHALLENGE_INVALID")
            } finally {
                BridgeCrypto.zero(nonceBytes)
            }
            val issuedAt = System.currentTimeMillis()
            val proof = BridgeCrypto.sessionProof(profile.bridgeId, clientId, challengeId, nonce, issuedAt)
            val signature = signingKey.sign(proof)
            val loginPayload = JsonObject().apply {
                addProperty("clientId", clientId)
                addProperty("challengeId", challengeId)
                addProperty("challengeNonce", nonce)
                addProperty("issuedAtEpochMillis", issuedAt)
                addProperty("proofSignatureBase64Url", BridgeCrypto.base64Url(signature))
            }
            BridgeCrypto.zero(proof)
            BridgeCrypto.zero(signature)
            val loginRequest = createEnvelope("session.authenticate.request", loginPayload, issuedAt)
            val login: BridgeEnvelope
            try {
                login = post(endpoint, SESSION_PATH, loginRequest.bytes, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES)
                requireResponse(login, loginRequest.requestId, "session.authenticate.response")
                BridgeProtocolCodec.requireExactKeys(login.payload, "sessionId", "expiresAtEpochMillis")
                val sessionId = BridgeProtocolCodec.requiredString(login.payload, "sessionId", 64)
                val sessionExpires = BridgeProtocolCodec.requiredLong(login.payload, "expiresAtEpochMillis")
                val sessionNow = System.currentTimeMillis()
                if (sessionExpires <= sessionNow) fail("AUTH_SESSION_EXPIRED")
                if (sessionExpires > sessionNow + BridgeProtocol.SESSION_MAX_AGE_MILLIS + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS) {
                    fail("AUTH_SESSION_INVALID")
                }
                val active = ActiveSession(endpoint, profile, sessionId, sessionExpires)
                val requestBody = createEnvelope("capabilities.request", JsonObject())
                try {
                    val capabilitiesResponse = postAuthenticated(active, CAPABILITIES_PATH, requestBody)
                    requireResponse(capabilitiesResponse, requestBody.requestId, "capabilities.response")
                    val capabilities = readCapabilities(capabilitiesResponse.payload, profile)
                    return active.copy(capabilities = capabilities)
                } finally {
                    BridgeCrypto.zero(requestBody.bytes)
                }
            } finally {
                BridgeCrypto.zero(loginRequest.bytes)
            }
        } finally {
            BridgeCrypto.zero(challengeRequest.bytes)
        }
    }

    private fun fetchBridgeInfo(endpoint: BridgeEndpoint): BridgeCapabilitiesSnapshot {
        val body = createEnvelope("bridge.info.request", JsonObject())
        try {
            val envelope = post(endpoint, INFO_PATH, body.bytes, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES)
            requireResponse(envelope, body.requestId, "bridge.info.response")
            val info = readCapabilities(envelope.payload, expectedProfile = null)
            if (info.identityFingerprint != endpoint.canonicalFingerprint) fail("BRIDGE_IDENTITY_MISMATCH")
            return info
        } finally {
            BridgeCrypto.zero(body.bytes)
        }
    }

    private fun readCapabilities(payload: JsonObject, expectedProfile: TrustedMinecraftBridge?): BridgeCapabilitiesSnapshot {
        BridgeProtocolCodec.requireExactKeys(
            payload,
            "protocolVersion", "bridgeId", "identityFingerprint", "bridgeVersion", "minecraftVersion",
            "loaderName", "loaderVersion", "worldAccess", "constructionExecute", "cancellation",
            "maximumValidatedOperations", "maximumRequestBytes", "supportedBuildPlanSchemaVersions",
            "dimensionId", "worldSessionId",
        )
        val protocolVersion = BridgeProtocolCodec.requiredInt(payload, "protocolVersion")
        val bridgeId = BridgeProtocolCodec.requiredString(payload, "bridgeId", 80)
        val fingerprint = BridgeCrypto.normalizeFingerprint(
            BridgeProtocolCodec.requiredString(payload, "identityFingerprint", 95),
        )
        val bridgeVersion = BridgeProtocolCodec.requiredString(payload, "bridgeVersion", 64)
        val minecraftVersion = BridgeProtocolCodec.requiredString(payload, "minecraftVersion", 32)
        val loaderName = BridgeProtocolCodec.requiredString(payload, "loaderName", 32)
        val loaderVersion = BridgeProtocolCodec.requiredString(payload, "loaderVersion", 64)
        val worldAccess = BridgeProtocolCodec.requiredBoolean(payload, "worldAccess")
        val constructionExecute = BridgeProtocolCodec.requiredBoolean(payload, "constructionExecute")
        val cancellation = BridgeProtocolCodec.requiredBoolean(payload, "cancellation")
        val maxOperations = BridgeProtocolCodec.requiredInt(payload, "maximumValidatedOperations")
        val maxRequestBytes = BridgeProtocolCodec.requiredInt(payload, "maximumRequestBytes")
        val dimensionId = BridgeProtocolCodec.nullableString(payload, "dimensionId", 130)
        val worldSessionId = BridgeProtocolCodec.nullableString(payload, "worldSessionId", 128)
        if (protocolVersion != BridgeProtocol.VERSION || !bridgeId.matches(BRIDGE_ID_PATTERN) || fingerprint.isEmpty() ||
            bridgeVersion.isBlank() || minecraftVersion != "1.20.1" || loaderName != "Fabric" || loaderVersion.isBlank() ||
            worldAccess || constructionExecute || cancellation || maxOperations !in 1..BridgeProtocol.MAX_OPERATIONS ||
            maxRequestBytes !in 1024..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES || dimensionId != null || worldSessionId != null) {
            fail("BRIDGE_CAPABILITIES_UNSUPPORTED")
        }
        val versionsElement = payload.get("supportedBuildPlanSchemaVersions")
        if (versionsElement == null || !versionsElement.isJsonArray) fail("BRIDGE_CAPABILITIES_INVALID")
        val versions = (versionsElement as JsonArray).map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isNumber) fail("BRIDGE_CAPABILITIES_INVALID")
            try {
                BridgeProtocolCodec.requiredInt(JsonObject().apply { add("version", item) }, "version")
                    .also { if (it <= 0) fail("BRIDGE_CAPABILITIES_INVALID") }
            } catch (_: BridgeProtocolException) {
                fail("BRIDGE_CAPABILITIES_INVALID")
            }
        }
        if (versions.isEmpty() || versions.size > 8 || BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION !in versions) {
            fail("BRIDGE_CAPABILITIES_UNSUPPORTED")
        }
        if (expectedProfile != null && (bridgeId != expectedProfile.bridgeId ||
                fingerprint != BridgeCrypto.normalizeFingerprint(expectedProfile.tlsFingerprint))) {
            fail("BRIDGE_IDENTITY_CHANGED")
        }
        return BridgeCapabilitiesSnapshot(
            bridgeId = bridgeId,
            identityFingerprint = BridgeCrypto.formatFingerprint(fingerprint),
            bridgeVersion = bridgeVersion,
            minecraftVersion = minecraftVersion,
            loaderName = loaderName,
            loaderVersion = loaderVersion,
            worldAccess = worldAccess,
            constructionExecute = constructionExecute,
            cancellation = cancellation,
            maximumValidatedOperations = maxOperations,
            maximumRequestBytes = maxRequestBytes,
            supportedBuildPlanSchemaVersions = versions,
        )
    }

    private fun verifyProfileIdentity(profile: TrustedMinecraftBridge, info: BridgeCapabilitiesSnapshot) {
        if (profile.bridgeId != info.bridgeId ||
            BridgeCrypto.normalizeFingerprint(profile.tlsFingerprint) != BridgeCrypto.normalizeFingerprint(info.identityFingerprint)) {
            fail("BRIDGE_IDENTITY_CHANGED")
        }
    }

    private fun postAuthenticated(session: ActiveSession, path: String, body: EncodedEnvelope): BridgeEnvelope {
        if (session.expiresAtEpochMillis <= System.currentTimeMillis()) fail("AUTH_SESSION_EXPIRED")
        val sequence = session.nextSequence++
        val timestamp = body.timestampEpochMillis
        val proof = BridgeCrypto.requestProof(
            session.profile.bridgeId, session.sessionId, sequence, body.requestId, timestamp,
            "POST", path, body.bytes,
        )
        val signature = signingKey.sign(proof)
        val headers = mapOf(
            "X-CraftMind-Session-Id" to session.sessionId,
            "X-CraftMind-Sequence" to sequence.toString(),
            "X-CraftMind-Signature" to BridgeCrypto.base64Url(signature),
        )
        BridgeCrypto.zero(proof)
        BridgeCrypto.zero(signature)
        val responseLimit = if (path == EXECUTION_PATH) BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES
            else BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES
        return post(session.endpoint, path, body.bytes, responseLimit, timestamp, headers)
    }

    private fun post(
        endpoint: BridgeEndpoint,
        path: String,
        bytes: ByteArray,
        maximumResponseBytes: Int,
        timestamp: Long? = null,
        headers: Map<String, String> = emptyMap(),
    ): BridgeEnvelope {
        val client = endpoint.httpClient()
        val url = "https://${endpoint.host}:${endpoint.port}$path"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .post(bytes.toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBytes = response.body?.byteStream()?.let { it.readBounded(maximumResponseBytes) }
                    ?: fail("BRIDGE_EMPTY_RESPONSE")
                try {
                    val envelope = try {
                        BridgeProtocolCodec.parseEnvelope(responseBytes, maximumResponseBytes)
                    } catch (error: BridgeProtocolException) {
                        if (!response.isSuccessful) fail("BRIDGE_HTTP_${response.code}")
                        throw error
                    }
                    if (envelope.messageType == "protocol.error") {
                        BridgeProtocolCodec.requireExactKeys(
                            envelope.payload, "reasonCode", "safeMessage", "supportedProtocolVersions",
                        )
                        val code = BridgeProtocolCodec.requiredString(envelope.payload, "reasonCode", 64)
                        fail(code.takeIf(ERROR_CODE_PATTERN::matches) ?: "BRIDGE_PROTOCOL_ERROR")
                    }
                    if (!response.isSuccessful) fail("BRIDGE_HTTP_${response.code}")
                    val responseNow = System.currentTimeMillis()
                    if (envelope.timestampEpochMillis < responseNow - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                        envelope.timestampEpochMillis > responseNow + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                        (timestamp != null && envelope.timestampEpochMillis < timestamp - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS)) {
                        fail("BRIDGE_RESPONSE_TIMESTAMP_INVALID")
                    }
                    return envelope
                } finally {
                    BridgeCrypto.zero(responseBytes)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MinecraftBridgeFailure) {
            throw error
        } catch (error: BridgeProtocolException) {
            throw MinecraftBridgeFailure(error.code.name)
        } catch (error: Exception) {
            val code = when {
                error.causes().any { it is SSLPeerUnverifiedException || it is CertificateException } -> "BRIDGE_IDENTITY_MISMATCH"
                error.causes().any { it is java.net.SocketTimeoutException } -> "BRIDGE_TIMEOUT"
                else -> "BRIDGE_UNAVAILABLE"
            }
            throw MinecraftBridgeFailure(code)
        }
    }

    private fun requireResponse(envelope: BridgeEnvelope, requestId: String, expectedType: String) {
        if (envelope.messageType != expectedType || envelope.correlationId != requestId) {
            fail("BRIDGE_RESPONSE_MISMATCH")
        }
    }

    private fun createEnvelope(messageType: String, payload: JsonObject, timestamp: Long = System.currentTimeMillis()): EncodedEnvelope {
        val requestId = UUID.randomUUID().toString()
        val envelope = BridgeProtocolCodec.newEnvelope(messageType, requestId, timestamp, null, payload)
        return EncodedEnvelope(requestId, timestamp, BridgeProtocolCodec.writeEnvelope(envelope))
    }

    private fun publishConnected() {
        val session = activeSession ?: return
        val capabilities = session.capabilities ?: return
        val authenticatedAt = System.currentTimeMillis()
        mutableConnectionState.value = BridgeConnectionState.Connected(session.profile, capabilities, authenticatedAt)
        expiryJob?.cancel()
        expiryJob = sessionScope.launch {
            delay(BridgeProtocol.SESSION_IDLE_TIMEOUT_MILLIS - CLIENT_IDLE_SAFETY_MARGIN_MILLIS)
            operationLock.withLock {
                if (activeSession?.sessionId == session.sessionId) {
                    activeSession = null
                    expiryJob = null
                    mutableConnectionState.value = BridgeConnectionState.Disconnected
                }
            }
        }
    }

    private fun clearActiveSession() {
        expiryJob?.cancel()
        expiryJob = null
        activeSession = null
    }

    private fun publishFailure(error: Exception) {
        val code = error.asBridgeFailure().reasonCode
        mutableConnectionState.value = BridgeConnectionState.Error(code)
    }

    private fun Exception.asBridgeFailure(): MinecraftBridgeFailure = when (this) {
        is MinecraftBridgeFailure -> this
        is BridgeProtocolException -> MinecraftBridgeFailure(code.name)
        else -> MinecraftBridgeFailure(message?.takeIf(ERROR_CODE_PATTERN::matches) ?: "BRIDGE_OPERATION_FAILED")
    }

    private fun fail(code: String): Nothing = throw MinecraftBridgeFailure(code)

    private data class ActiveSession(
        val endpoint: BridgeEndpoint,
        val profile: TrustedMinecraftBridge,
        val sessionId: String,
        val expiresAtEpochMillis: Long,
        var nextSequence: Long = 1L,
        val capabilities: BridgeCapabilitiesSnapshot? = null,
    )

    private data class EncodedEnvelope(val requestId: String, val timestampEpochMillis: Long, val bytes: ByteArray)

    private data class BridgeEndpoint(
        val host: String,
        val port: Int,
        val canonicalFingerprint: String,
        private val pinnedTrustManager: X509TrustManager,
    ) {
        private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(pinnedTrustManager), java.security.SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, pinnedTrustManager)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        fun httpClient(): OkHttpClient = client

        companion object {
            fun create(hostText: String, port: Int, fingerprintText: String): BridgeEndpoint {
                val host = hostText.trim()
                if (port !in 1024..65535) throw MinecraftBridgeFailure("BRIDGE_PORT_INVALID")
                val address: Inet4Address = try {
                    BridgeNetworkAddressPolicy.parsePrivateIpv4(host)
                } catch (_: Exception) {
                    throw MinecraftBridgeFailure("BRIDGE_ADDRESS_MUST_BE_PRIVATE_IPV4")
                }
                val normalized = BridgeCrypto.normalizeFingerprint(fingerprintText)
                if (normalized.isEmpty()) throw MinecraftBridgeFailure("BRIDGE_FINGERPRINT_INVALID")
                val canonical = BridgeCrypto.formatFingerprint(normalized)
                return BridgeEndpoint(address.hostAddress, port, canonical, PinnedTrustManager(address, normalized))
            }

            fun fromProfile(profile: TrustedMinecraftBridge): BridgeEndpoint =
                create(profile.host, profile.port, profile.tlsFingerprint)
        }
    }

    private class PinnedTrustManager(
        private val address: Inet4Address,
        private val expectedFingerprint: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            throw CertificateException("BRIDGE_CLIENT_CERTIFICATE_NOT_ACCEPTED")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.size != 1) throw CertificateException("BRIDGE_IDENTITY_MISMATCH")
            val certificate = chain[0]
            try {
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
                if (!"EC".equals(certificate.publicKey.algorithm, ignoreCase = true) ||
                    !BridgeCrypto.isP256PublicKey(certificate.publicKey.encoded) ||
                    !BridgeCrypto.fingerprintMatches(certificate.encoded, expectedFingerprint) ||
                    !hasExpectedIpSan(certificate, address.hostAddress)) {
                    throw CertificateException("BRIDGE_IDENTITY_MISMATCH")
                }
            } catch (error: CertificateException) {
                throw error
            } catch (_: Exception) {
                throw CertificateException("BRIDGE_IDENTITY_MISMATCH")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun hasExpectedIpSan(certificate: X509Certificate, expected: String): Boolean {
            val names = certificate.subjectAlternativeNames ?: return false
            return names.any { entry ->
                if (entry.size < 2 || entry[0] != 7) return@any false
                when (val value = entry[1]) {
                    is ByteArray -> runCatching { java.net.InetAddress.getByAddress(value).hostAddress == expected }.getOrDefault(false)
                    else -> value.toString() == expected
                }
            }
        }
    }

    private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        try {
            while (true) {
                val read = read(buffer, 0, minOf(buffer.size, maximumBytes + 1 - total))
                if (read < 0) break
                total += read
                if (total > maximumBytes) fail("BRIDGE_RESPONSE_TOO_LARGE")
                output.write(buffer, 0, read)
            }
            if (total == 0) fail("BRIDGE_EMPTY_RESPONSE")
            return output.toByteArray()
        } finally {
            BridgeCrypto.zero(buffer)
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private companion object {
        const val CLIENT_DISPLAY_NAME = "CraftMind Android"
        const val CLIENT_IDLE_SAFETY_MARGIN_MILLIS = 10_000L
        const val INFO_PATH = "/v1/bridge/info"
        const val PAIR_PATH = "/v1/pair"
        const val CHALLENGE_PATH = "/v1/session/challenge"
        const val SESSION_PATH = "/v1/session"
        const val DISCONNECT_PATH = "/v1/session/disconnect"
        const val CAPABILITIES_PATH = "/v1/capabilities"
        const val REVOKE_PATH = "/v1/pair/revoke"
        const val EXECUTION_PATH = "/v1/executions"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val BRIDGE_ID_PATTERN = Regex("bridge-[0-9a-f]{32}")
        val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}
