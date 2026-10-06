package com.craftmind.app.data.minecraft

import android.content.Context
import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBlockRejectionDetails
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.failureReasonCode
import com.craftmind.bridge.protocol.BridgeCrypto
import com.craftmind.bridge.protocol.BridgeEnvelope
import com.craftmind.bridge.protocol.BridgeNetworkAddressPolicy
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BridgeProtocolException
import com.google.gson.JsonArray
import com.google.gson.JsonNull
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
class AndroidMinecraftBridgePairingRepository(
    context: Context,
    private val compatibilityResolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
) : MinecraftBridgePairingRepository {
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

    override suspend fun refreshCapabilities() {
        try {
            operationLock.withLock {
                withContext(Dispatchers.IO) {
                    val session = activeSession ?: fail("BRIDGE_SESSION_UNAVAILABLE")
                    if (session.expiresAtEpochMillis <= System.currentTimeMillis()) fail("AUTH_SESSION_EXPIRED")
                    val body = createEnvelope("capabilities.request", capabilitiesRequestPayload())
                    try {
                        val response = postAuthenticated(session, CAPABILITIES_PATH, body)
                        requireResponse(response, body.requestId, "capabilities.response")
                        val capabilities = readCapabilities(response.payload, session.profile)
                        activeSession = session.copy(capabilities = capabilities)
                    } finally {
                        BridgeCrypto.zero(body.bytes)
                    }
                }
            }
            publishConnected()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishFailure(error)
            throw error.asBridgeFailure()
        }
    }

    override suspend fun prepareExecution(record: LocalBuildRecord, executionId: String): MinecraftExecutionPreview {
        if (!EXECUTION_ID_PATTERN.matches(executionId)) fail("EXECUTION_ID_INVALID")
        refreshCapabilities()
        return operationLock.withLock {
            withContext(Dispatchers.IO) {
                val session = activeSession ?: fail("BRIDGE_SESSION_UNAVAILABLE")
                val capabilities = session.capabilities ?: fail("BRIDGE_CAPABILITIES_UNAVAILABLE")
                val compatibility = compatibilityResolver.resolve(record.plan, capabilities.runtimeDescriptor)
                if (!compatibility.canExecute) fail(compatibility.failureReasonCode())
                if (record.plan.status != BuildStatus.READY || record.plan.metadata.schemaVersion != BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION ||
                    record.plan.metadata.intent == null || record.plan.operations.isEmpty() ||
                    record.plan.operations.size > capabilities.maximumValidatedOperations) {
                    fail("BUILD_PLAN_NOT_EXECUTABLE")
                }
                val payload = executionRequestPayload(record, executionId, capabilities)
                val body = createEnvelope("execution.prepare.request", payload)
                if (body.bytes.size > capabilities.maximumRequestBytes) {
                    BridgeCrypto.zero(body.bytes)
                    fail("LIMIT_EXCEEDED")
                }
                try {
                    val response = postAuthenticated(session, PREPARE_PATH, body)
                    when (response.messageType) {
                        "execution.preflight.ready" -> {
                            requireResponse(response, body.requestId, "execution.preflight.ready")
                            readExecutionPreview(response.payload, record, executionId, capabilities)
                        }
                        "execution.preflight.rejected" -> {
                            requireResponse(response, body.requestId, "execution.preflight.rejected")
                            throw readRejected(response.payload, executionId)
                        }
                        "execution.status.response" -> {
                            requireResponse(response, body.requestId, "execution.status.response")
                            fail("EXECUTION_ALREADY_EXISTS")
                        }
                        else -> fail("BRIDGE_RESPONSE_MISMATCH")
                    }
                } finally {
                    BridgeCrypto.zero(body.bytes)
                }
            }
        }
    }

    override suspend fun startExecution(preview: MinecraftExecutionPreview): MinecraftExecutionSnapshot {
        if (!EXECUTION_ID_PATTERN.matches(preview.executionId)) fail("EXECUTION_ID_INVALID")
        refreshCapabilities()
        return operationLock.withLock {
            withContext(Dispatchers.IO) {
                val session = activeSession ?: fail("BRIDGE_SESSION_UNAVAILABLE")
                val capabilities = session.capabilities ?: fail("BRIDGE_CAPABILITIES_UNAVAILABLE")
                if (!capabilities.executionCompatible) fail("BRIDGE_CAPABILITIES_UNSUPPORTED")
                if (preview.dimensionId != capabilities.dimensionId || preview.worldSessionId != capabilities.worldSessionId) {
                    fail("WORLD_SESSION_CHANGED")
                }
                if (preview.expiresAtEpochMillis <= System.currentTimeMillis()) fail("PREFLIGHT_EXPIRED")
                val payload = JsonObject().apply {
                    addProperty("executionId", preview.executionId)
                    addProperty("preflightToken", preview.preflightToken)
                }
                val body = createEnvelope("execution.start.request", payload)
                try {
                    val response = postAuthenticated(session, START_PATH, body)
                    when (response.messageType) {
                        "execution.accepted" -> {
                            requireResponse(response, body.requestId, "execution.accepted")
                            readExecutionSnapshot(response.payload, preview.executionId, capabilities)
                                .also { snapshot ->
                                    if (snapshot.planRecordId != preview.planRecordId || snapshot.planVersion != preview.planVersion ||
                                        snapshot.totalOperations != preview.operationCount || snapshot.dimensionId != preview.dimensionId ||
                                        snapshot.worldSessionId != preview.worldSessionId || snapshot.resolvedOrigin != preview.resolvedOrigin) {
                                        fail("BRIDGE_RESPONSE_MISMATCH")
                                    }
                                }
                        }
                        "execution.start.rejected" -> {
                            requireResponse(response, body.requestId, "execution.start.rejected")
                            throw readRejected(response.payload, preview.executionId)
                        }
                        else -> fail("BRIDGE_RESPONSE_MISMATCH")
                    }
                } finally {
                    BridgeCrypto.zero(body.bytes)
                }
            }
        }
    }

    override suspend fun queryExecution(executionId: String): MinecraftExecutionQueryResult = operationLock.withLock {
        withContext(Dispatchers.IO) {
            val session = activeSession ?: fail("BRIDGE_SESSION_UNAVAILABLE")
            if (!EXECUTION_ID_PATTERN.matches(executionId)) fail("EXECUTION_ID_INVALID")
            val body = createEnvelope("execution.status.request", JsonObject().apply { addProperty("executionId", executionId) })
            try {
                val response = postAuthenticated(session, STATUS_PATH, body)
                when (response.messageType) {
                    "execution.status.response" -> {
                        requireResponse(response, body.requestId, "execution.status.response")
                        MinecraftExecutionQueryResult.Found(
                            readExecutionSnapshot(response.payload, executionId, session.capabilities),
                        )
                    }
                    "execution.status.not_found" -> {
                        requireResponse(response, body.requestId, "execution.status.not_found")
                        BridgeProtocolCodec.requireExactKeys(response.payload, "executionId", "reasonCode")
                        if (BridgeProtocolCodec.requiredString(response.payload, "executionId", 36) != executionId ||
                            BridgeProtocolCodec.requiredString(response.payload, "reasonCode", 64) != "EXECUTION_NOT_FOUND") {
                            fail("BRIDGE_RESPONSE_MISMATCH")
                        }
                        MinecraftExecutionQueryResult.NotFound
                    }
                    else -> fail("BRIDGE_RESPONSE_MISMATCH")
                }
            } finally {
                BridgeCrypto.zero(body.bytes)
            }
        }
    }

    override suspend fun cancelExecution(executionId: String): MinecraftCancellationResult {
        if (!EXECUTION_ID_PATTERN.matches(executionId)) fail("EXECUTION_ID_INVALID")
        refreshCapabilities()
        return operationLock.withLock {
            withContext(Dispatchers.IO) {
                val session = activeSession ?: fail("BRIDGE_SESSION_UNAVAILABLE")
                val capabilities = session.capabilities ?: fail("BRIDGE_CAPABILITIES_UNAVAILABLE")
                if (!capabilities.executionCompatible || !capabilities.cancellation) fail("CANCELLATION_UNAVAILABLE")
                val body = createEnvelope("execution.cancel.request", JsonObject().apply { addProperty("executionId", executionId) })
                try {
                    val response = postAuthenticated(session, CANCEL_PATH, body)
                    requireResponse(response, body.requestId, "execution.cancellation.result")
                    BridgeProtocolCodec.requireExactKeys(response.payload, "executionId", "outcome", "state", "reasonCode")
                    if (BridgeProtocolCodec.requiredString(response.payload, "executionId", 36) != executionId) fail("BRIDGE_RESPONSE_MISMATCH")
                    val outcome = BridgeProtocolCodec.requiredString(response.payload, "outcome", 40)
                    if (outcome !in setOf("CANCELLATION_ACCEPTED", "CANCELLATION_REJECTED", "EXECUTION_ALREADY_FINISHED", "EXECUTION_NOT_FOUND")) {
                        fail("BRIDGE_RESPONSE_INVALID")
                    }
                    val state = BridgeProtocolCodec.nullableString(response.payload, "state", 16)
                    MinecraftCancellationResult(outcome, state?.let(::parseExecutionPhase),
                        BridgeProtocolCodec.nullableString(response.payload, "reasonCode", 64))
                } finally {
                    BridgeCrypto.zero(body.bytes)
                }
            }
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
                val active = ActiveSession(endpoint, profile, sessionId, sessionExpires, authenticatedAtEpochMillis = issuedAt)
                val requestBody = createEnvelope("capabilities.request", capabilitiesRequestPayload())
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

    /** Edition-dispatched, strict parsing of the authenticated runtime report. */
    private fun readCapabilities(payload: JsonObject, expectedProfile: TrustedMinecraftBridge?): BridgeCapabilitiesSnapshot =
        BridgeRuntimeReportReader.read(payload, expectedProfile)

    private fun executionRequestPayload(
        record: LocalBuildRecord,
        executionId: String,
        capabilities: BridgeCapabilitiesSnapshot,
    ): JsonObject {
        val plan = record.plan
        if (plan.metadata.schemaVersion != BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION || plan.status != BuildStatus.READY ||
            plan.metadata.intent == null || plan.components.isEmpty() || plan.operations.isEmpty()) {
            fail("BUILD_PLAN_NOT_EXECUTABLE")
        }
        return JsonObject().apply {
            addProperty("executionId", executionId)
            addProperty("buildId", record.buildId)
            addProperty("planRecordId", record.recordId)
            addProperty("planVersion", record.version)
            addProperty("buildPlanSchemaVersion", plan.metadata.schemaVersion)
            add("buildPlan", buildPlanJson(plan))
            add("origin", JsonObject().apply {
                addProperty("kind", "BRIDGE_SELECTED_SAFE")
                addProperty("dimensionId", capabilities.dimensionId)
                addProperty("worldSessionId", capabilities.worldSessionId)
                add("position", JsonNull.INSTANCE)
            })
            add("limits", JsonObject().apply {
                addProperty("maxOperations", minOf(BridgeProtocol.MAX_OPERATIONS, capabilities.maximumValidatedOperations))
                addProperty("maxRequestBytes", minOf(BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES, capabilities.maximumRequestBytes))
            })
        }
    }

    private fun buildPlanJson(plan: BuildPlan): JsonObject {
        val intent = plan.metadata.intent ?: fail("BUILD_PLAN_NOT_EXECUTABLE")
        return JsonObject().apply {
            addProperty("planId", plan.planId)
            add("metadata", JsonObject().apply {
                addProperty("schemaVersion", plan.metadata.schemaVersion)
                addProperty("sourceRequestId", plan.metadata.sourceRequestId)
                addProperty("providerId", plan.metadata.providerId)
                addProperty("modelId", plan.metadata.modelId)
                addProperty("title", plan.metadata.title)
                addProperty("summary", plan.metadata.summary)
                addProperty("generatedAtEpochMillis", plan.metadata.generatedAtEpochMillis)
                add("dimensions", JsonObject().apply {
                    addProperty("width", plan.metadata.dimensions.width)
                    addProperty("height", plan.metadata.dimensions.height)
                    addProperty("depth", plan.metadata.dimensions.depth)
                })
                add("intent", JsonObject().apply {
                    addProperty("structureType", intent.structureType)
                    addNullable("style", intent.style)
                    addNullable("approximateScale", intent.approximateScale)
                    if (intent.floorCount == null) add("floorCount", JsonNull.INSTANCE) else addProperty("floorCount", intent.floorCount)
                    add("rooms", stringArray(intent.rooms))
                    add("specialFeatures", stringArray(intent.specialFeatures))
                    add("materials", stringArray(intent.materials))
                    addNullable("environment", intent.environment)
                    add("constraints", stringArray(intent.constraints))
                })
            })
            addProperty("originStrategy", plan.originStrategy.name)
            add("components", JsonArray().apply {
                plan.components.forEach { component ->
                    val bounds = component.bounds ?: fail("BUILD_PLAN_NOT_EXECUTABLE")
                    add(JsonObject().apply {
                        addProperty("componentId", component.componentId)
                        addProperty("name", component.name)
                        addProperty("purpose", component.purpose)
                        add("bounds", boundsJson(bounds))
                        addProperty("type", component.type.name)
                        addNullable("parentComponentId", component.parentComponentId)
                        addProperty("constructionOrder", component.constructionOrder)
                    })
                }
            })
            add("operations", JsonArray().apply {
                plan.operations.forEach { operation ->
                    add(operationJson(operation))
                }
            })
            addProperty("status", plan.status.name)
        }
    }

    private fun boundsJson(bounds: BlockBounds): JsonObject = JsonObject().apply {
        add("origin", positionJson(bounds.origin))
        add("dimensions", JsonObject().apply {
            addProperty("width", bounds.dimensions.width)
            addProperty("height", bounds.dimensions.height)
            addProperty("depth", bounds.dimensions.depth)
        })
    }

    private fun operationJson(operation: BuildPlanOperation): JsonObject = JsonObject().apply {
        addProperty("sequence", operation.sequence)
        addProperty("kind", operation.kind.name)
        addProperty("blockId", operation.blockId)
        add("position", positionJson(operation.position))
        add("blockState", JsonObject().apply {
            operation.blockState.toSortedMap().forEach { (key, value) -> addProperty(key, value) }
        })
        addNullable("componentId", operation.componentId)
    }

    private fun positionJson(position: BlockPosition): JsonObject = JsonObject().apply {
        addProperty("x", position.x)
        addProperty("y", position.y)
        addProperty("z", position.z)
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().apply {
        values.forEach { add(it) }
    }

    private fun JsonObject.addNullable(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun readExecutionPreview(
        payload: JsonObject,
        record: LocalBuildRecord,
        expectedExecutionId: String,
        capabilities: BridgeCapabilitiesSnapshot,
    ): MinecraftExecutionPreview {
        BridgeProtocolCodec.requireExactKeys(payload, "executionId", "planRecordId", "planVersion", "planTitle",
            "dimensionId", "worldSessionId", "resolvedOrigin", "originStrategy", "operationCount", "createdAtEpochMillis",
            "eventSequence", "expiresAtEpochMillis", "preflightToken")
        val executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36)
        val planRecordId = BridgeProtocolCodec.requiredString(payload, "planRecordId", 128)
        val planVersion = BridgeProtocolCodec.requiredInt(payload, "planVersion")
        val title = BridgeProtocolCodec.requiredString(payload, "planTitle", 100)
        val dimensionId = BridgeProtocolCodec.requiredString(payload, "dimensionId", 130)
        val worldSessionId = BridgeProtocolCodec.requiredString(payload, "worldSessionId", 128)
        val originElement = payload.get("resolvedOrigin")
        if (originElement == null || !originElement.isJsonObject) fail("BRIDGE_RESPONSE_INVALID")
        val origin = readPosition(originElement.asJsonObject)
        val strategy = BridgeProtocolCodec.requiredString(payload, "originStrategy", 32)
        val operations = BridgeProtocolCodec.requiredInt(payload, "operationCount")
        val createdAt = BridgeProtocolCodec.requiredLong(payload, "createdAtEpochMillis")
        val eventSequence = BridgeProtocolCodec.requiredLong(payload, "eventSequence")
        val expiry = BridgeProtocolCodec.requiredLong(payload, "expiresAtEpochMillis")
        val token = BridgeProtocolCodec.requiredString(payload, "preflightToken", 64)
        val now = System.currentTimeMillis()
        if (executionId != expectedExecutionId || planRecordId != record.recordId || planVersion != record.version ||
            title != record.plan.metadata.title || dimensionId != capabilities.dimensionId ||
            worldSessionId != capabilities.worldSessionId || operations != record.plan.operations.size || operations < 1 ||
            createdAt <= 0 || eventSequence < 1 || strategy != "SERVER_SELECTED_ORIGIN" ||
            !token.matches(Regex("[A-Za-z0-9_-]{43}")) || expiry <= now ||
            expiry > now + 120_000L + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS || !validWorldPosition(origin)) {
            fail("BRIDGE_RESPONSE_INVALID")
        }
        return MinecraftExecutionPreview(executionId, token, planRecordId, planVersion, title, dimensionId,
            worldSessionId, origin, strategy, operations, createdAt, eventSequence, expiry)
    }

    private fun readExecutionSnapshot(
        payload: JsonObject,
        expectedExecutionId: String,
        capabilities: BridgeCapabilitiesSnapshot?,
    ): MinecraftExecutionSnapshot {
        BridgeProtocolCodec.requireExactKeys(payload, "executionId", "buildId", "planRecordId", "planVersion", "state",
            "completedOperations", "totalOperations", "eventSequence", "createdAtEpochMillis", "updatedAtEpochMillis",
            "dimensionId", "worldSessionId", "resolvedOrigin", "reasonCode", "failedOperationIndex")
        val executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36)
        val buildId = BridgeProtocolCodec.requiredString(payload, "buildId", 80)
        val planRecordId = BridgeProtocolCodec.requiredString(payload, "planRecordId", 128)
        val planVersion = BridgeProtocolCodec.requiredInt(payload, "planVersion")
        val phase = parseExecutionPhase(BridgeProtocolCodec.requiredString(payload, "state", 16))
        val completed = BridgeProtocolCodec.requiredInt(payload, "completedOperations")
        val total = BridgeProtocolCodec.requiredInt(payload, "totalOperations")
        val eventSequence = BridgeProtocolCodec.requiredLong(payload, "eventSequence")
        val created = BridgeProtocolCodec.requiredLong(payload, "createdAtEpochMillis")
        val updated = BridgeProtocolCodec.requiredLong(payload, "updatedAtEpochMillis")
        val dimension = BridgeProtocolCodec.requiredString(payload, "dimensionId", 130)
        val worldSession = BridgeProtocolCodec.requiredString(payload, "worldSessionId", 128)
        val originElement = payload.get("resolvedOrigin")
        if (originElement == null || !originElement.isJsonObject) fail("BRIDGE_RESPONSE_INVALID")
        val origin = readPosition(originElement.asJsonObject)
        val reason = BridgeProtocolCodec.nullableString(payload, "reasonCode", 64)
        val failedElement = payload.get("failedOperationIndex") ?: fail("BRIDGE_RESPONSE_INVALID")
        val failedIndex = if (failedElement.isJsonNull) null else BridgeProtocolCodec.requiredInt(
            JsonObject().apply { add("value", failedElement) }, "value",
        )
        val maximumOperations = capabilities?.maximumValidatedOperations ?: BridgeProtocol.MAX_OPERATIONS
        if (executionId != expectedExecutionId || !EXECUTION_ID_PATTERN.matches(executionId) ||
            !BUILD_ID_PATTERN.matches(buildId) || !RECORD_ID_PATTERN.matches(planRecordId) || planVersion < 1 ||
            completed !in 0..total || total !in 1..maximumOperations || eventSequence < 1 || created <= 0L || updated < created ||
            !dimension.matches(Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) ||
            !worldSession.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !validWorldPosition(origin) ||
            (phase == MinecraftExecutionPhase.COMPLETED && completed != total) ||
            (phase == MinecraftExecutionPhase.FAILED && reason == null) ||
            (reason != null && !ERROR_CODE_PATTERN.matches(reason)) ||
            (failedIndex != null && failedIndex !in 0 until total)) {
            fail("BRIDGE_RESPONSE_INVALID")
        }
        return MinecraftExecutionSnapshot(executionId, buildId, planRecordId, planVersion, phase, completed, total,
            eventSequence, created, updated, dimension, worldSession, origin, reason, failedIndex)
    }

    private fun readPosition(payload: JsonObject): BlockPosition {
        BridgeProtocolCodec.requireExactKeys(payload, "x", "y", "z")
        return BlockPosition(BridgeProtocolCodec.requiredInt(payload, "x"),
            BridgeProtocolCodec.requiredInt(payload, "y"), BridgeProtocolCodec.requiredInt(payload, "z"))
    }

    private fun validWorldPosition(position: BlockPosition): Boolean =
        position.x in -30_000_000..30_000_000 && position.z in -30_000_000..30_000_000 &&
            position.y in -2048..2048

    private fun parseExecutionPhase(value: String): MinecraftExecutionPhase = try {
        MinecraftExecutionPhase.valueOf(value)
    } catch (_: IllegalArgumentException) {
        fail("BRIDGE_RESPONSE_INVALID")
    }

    private fun readRejected(payload: JsonObject, expectedExecutionId: String): MinecraftBridgeFailure {
        BridgeProtocolCodec.requireExactKeys(
            payload, "requestId", "reasonCode", "safeMessage", "failedOperationIndex", "blockId", "unsupportedStateProperties",
        )
        if (BridgeProtocolCodec.requiredString(payload, "requestId", 64) != expectedExecutionId) fail("BRIDGE_RESPONSE_MISMATCH")
        val reason = BridgeProtocolCodec.requiredString(payload, "reasonCode", 64)
        if (!ERROR_CODE_PATTERN.matches(reason)) fail("BRIDGE_RESPONSE_INVALID")
        val safeMessage = BridgeProtocolCodec.requiredString(payload, "safeMessage", 256)
        val failedOperationIndex = nullableBoundedInt(payload, "failedOperationIndex", 0, BridgeProtocol.MAX_OPERATIONS - 1)
        val blockId = BridgeProtocolCodec.nullableString(payload, "blockId", 160)
        val stateElement = payload.get("unsupportedStateProperties")
        if (stateElement == null || !stateElement.isJsonArray || stateElement.asJsonArray.size() > BridgeProtocol.MAX_BLOCK_STATE_PROPERTIES) {
            fail("BRIDGE_RESPONSE_INVALID")
        }
        val stateProperties = stateElement.asJsonArray.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) fail("BRIDGE_RESPONSE_INVALID")
            BridgeProtocolCodec.requiredString(JsonObject().apply { add("value", item) }, "value", 64)
                .also { if (!STATE_PROPERTY_PATTERN.matches(it)) fail("BRIDGE_RESPONSE_INVALID") }
        }
        if (stateProperties.toSet().size != stateProperties.size) fail("BRIDGE_RESPONSE_INVALID")
        val hasBlockDetails = failedOperationIndex != null || blockId != null || stateProperties.isNotEmpty()
        val blockRejection = when (reason) {
            "UNSUPPORTED_BLOCK", "UNSUPPORTED_BLOCK_STATE" -> {
                if (failedOperationIndex == null || blockId == null || !BLOCK_IDENTIFIER_PATTERN.matches(blockId) ||
                    (reason == "UNSUPPORTED_BLOCK" && stateProperties.isNotEmpty())) {
                    fail("BRIDGE_RESPONSE_INVALID")
                }
                MinecraftBlockRejectionDetails(failedOperationIndex, blockId, stateProperties)
            }
            else -> {
                if (hasBlockDetails) fail("BRIDGE_RESPONSE_INVALID")
                null
            }
        }
        return MinecraftBridgeFailure(reason, safeMessage, blockRejection)
    }

    private fun nullableBoundedInt(objectValue: JsonObject, key: String, minimum: Int, maximum: Int): Int? {
        val element = objectValue.get(key) ?: fail("BRIDGE_RESPONSE_INVALID")
        if (element.isJsonNull) return null
        val value = try {
            BridgeProtocolCodec.requiredInt(JsonObject().apply { add("value", element) }, "value")
        } catch (_: BridgeProtocolException) {
            fail("BRIDGE_RESPONSE_INVALID")
        }
        if (value !in minimum..maximum) fail("BRIDGE_RESPONSE_INVALID")
        return value
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
                if (response.code == 426) fail("BRIDGE_UPDATE_REQUIRED")
                val responseBytes = response.body?.byteStream()?.let { it.readBounded(maximumResponseBytes) }
                    ?: fail("BRIDGE_EMPTY_RESPONSE")
                try {
                    val envelope = try {
                        BridgeProtocolCodec.parseEnvelope(responseBytes, maximumResponseBytes)
                    } catch (error: BridgeProtocolException) {
                        if (!response.isSuccessful) {
                            if (error.code == BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL) fail("BRIDGE_UPDATE_REQUIRED")
                            fail("BRIDGE_HTTP_${response.code}")
                        }
                        throw error
                    }
                    if (envelope.messageType == "protocol.error") {
                        BridgeProtocolCodec.requireExactKeys(
                            envelope.payload, "reasonCode", "safeMessage", "supportedProtocolVersions",
                        )
                        val code = BridgeProtocolCodec.requiredString(envelope.payload, "reasonCode", 64)
                        if (code == BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL.name) fail("BRIDGE_UPDATE_REQUIRED")
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
            val code = if (error.code == BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL) "BRIDGE_UPDATE_REQUIRED" else error.code.name
            throw MinecraftBridgeFailure(code)
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

    private fun capabilitiesRequestPayload(): JsonObject = JsonObject().apply {
        addProperty("clientAppVersion", BuildConfig.VERSION_NAME)
    }

    private fun createEnvelope(messageType: String, payload: JsonObject, timestamp: Long = System.currentTimeMillis()): EncodedEnvelope {
        val requestId = UUID.randomUUID().toString()
        val envelope = BridgeProtocolCodec.newEnvelope(messageType, requestId, timestamp, null, payload)
        return EncodedEnvelope(requestId, timestamp, BridgeProtocolCodec.writeEnvelope(envelope))
    }

    private fun publishConnected() {
        val session = activeSession ?: return
        val capabilities = session.capabilities ?: return
        mutableConnectionState.value = BridgeConnectionState.Connected(
            session.profile, capabilities, session.authenticatedAtEpochMillis,
        )
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
        is BridgeProtocolException -> MinecraftBridgeFailure(
            if (code == BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL) "BRIDGE_UPDATE_REQUIRED" else code.name,
        )
        else -> MinecraftBridgeFailure(message?.takeIf(ERROR_CODE_PATTERN::matches) ?: "BRIDGE_OPERATION_FAILED")
    }

    private fun fail(code: String): Nothing = throw MinecraftBridgeFailure(code)

    private data class ActiveSession(
        val endpoint: BridgeEndpoint,
        val profile: TrustedMinecraftBridge,
        val sessionId: String,
        val expiresAtEpochMillis: Long,
        val authenticatedAtEpochMillis: Long,
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
        const val PREPARE_PATH = "/v1/executions/prepare"
        const val START_PATH = "/v1/executions/start"
        const val STATUS_PATH = "/v1/executions/status"
        const val CANCEL_PATH = "/v1/executions/cancel"
        val EXECUTION_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val BUILD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
        val RECORD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
        val BLOCK_IDENTIFIER_PATTERN = Regex("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,127}")
        val STATE_PROPERTY_PATTERN = Regex("[a-z0-9_]{1,64}")
    }
}
