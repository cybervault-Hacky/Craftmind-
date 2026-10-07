package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.data.minecraft.BridgeRuntimeReportReader
import com.craftmind.app.domain.buildplan.MinecraftBlockCatalog
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BuildPlanContractValidator
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A controlled, protocol-faithful **simulated** Minecraft bridge used only by the Phase 14 certification tests.
 *
 * It is not a Minecraft runtime and never claims to be one:
 * - capability reports are emitted as real protocol-v2 JSON payloads, wrapped in real envelopes by
 *   [BridgeProtocolCodec], and parsed by the **production** wire codecs through `BridgeRuntimeReportReader`;
 * - execution requests are validated by the **production** Java [BuildPlanContractValidator];
 * - single-active-build, execution-ID uniqueness, preflight-token, world-session, and cancellation invariants are
 *   enforced here exactly as the real bridge enforces them, so failure injection is meaningful.
 *
 * Evidence produced through this bridge is always [MinecraftEvidenceMode.SIMULATED]; the certification engine caps it
 * at [com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED].
 *
 * One documented simplification: block support comes from CraftMind's own bounded [MinecraftBlockCatalog] instead of
 * a live server registry. That is precisely why simulated evidence can never certify a runtime.
 */
class SimulatedCertificationBridge(
    val trustedBridge: TrustedMinecraftBridge,
    var facts: SimulatedRuntimeFacts = SimulatedRuntimeFacts(),
    var appVersion: String = CERTIFICATION_APP_VERSION,
    initialSessionId: String = DEFAULT_SESSION_ID,
) : MinecraftBridgePairingRepository {

    /** Runtime facts the simulated bridge reports. Wire-level facts only; nothing is inferred CraftMind-side. */
    data class SimulatedRuntimeFacts(
        val editionName: String = "java",
        val minecraftVersion: String = "1.20.1",
        val javaRuntimeMajor: Int = 17,
        val loaderName: String = "Fabric",
        val loaderVersion: String = "0.16.10",
        val fabricApiVersion: String? = "0.92.2+1.20.1",
        val platformName: String = "DEDICATED_SERVER",
        val platformVersion: String? = "1.21.60.3",
        val limitations: List<String> = emptyList(),
        val bridgeVersion: String = "1.2.0",
        val protocolVersion: Int = BridgeProtocol.VERSION,
        val appVersionEcho: String? = CERTIFICATION_APP_VERSION,
        val capabilities: Set<MinecraftCapability> = FULL_CAPABILITIES,
        val schemaVersions: List<Int> = listOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        val maximumValidatedOperations: Int = BridgeProtocol.MAX_OPERATIONS,
        val maximumRequestBytes: Int = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        val maximumOperationsPerTick: Int = 32,
        val maximumExecutionSeconds: Int = 300,
        val dimensionId: String? = "minecraft:overworld",
        val worldSessionId: String? = "world-session-certification",
    )

    // ---------------------------------------------------------------------------------- injection points
    var authenticationFails: Boolean = false
    var sessionExpiresImmediately: Boolean = false
    var rejectPreflight: Boolean = false
    var preflightRejectionCode: String = "BUILD_PLAN_REJECTED_BY_SERVER"
    var disconnectDuringExecution: Boolean = false
    var allowSecondActiveExecution: Boolean = false
    var cancelInsteadOfComplete: Boolean = false

    /** How many operations a cancellation lets through before the bridge honours it at a batch boundary. */
    var cancelAfterOperations: Int = 1
    var failExecution: Boolean = false
    var executionFailureCode: String = "EXECUTION_FAILED_ON_SERVER"

    // ---------------------------------------------------------------------------------- observable state
    /** The simulated world: what was actually written, read back for verification. */
    val worldBlocks: MutableMap<BlockPosition, String> = linkedMapOf()
    val worldBlockStates: MutableMap<BlockPosition, Map<String, String>> = linkedMapOf()
    val events: MutableList<String> = mutableListOf()
    val preparedExecutions: MutableMap<String, PreparedExecution> = linkedMapOf()
    var activeExecutionId: String? = null
        private set
    var sessionId: String? = initialSessionId
        private set
    var authenticatedAtEpochMillis: Long = FIXED_AUTHENTICATED_AT
        private set
    var progressReports: Int = 0
        private set

    data class PreparedExecution(
        val executionId: String,
        val preflightToken: String,
        val record: LocalBuildRecord,
        val requestBytes: Int,
        val createdAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
    )

    private val mutableConnectionState = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
    private val mutableProfile = MutableStateFlow<TrustedMinecraftBridge?>(trustedBridge)
    private var sequence = 0

    override val connectionState: StateFlow<BridgeConnectionState> = mutableConnectionState.asStateFlow()
    override val profile: Flow<TrustedMinecraftBridge?> = mutableProfile

    // ---------------------------------------------------------------------------------- protocol payloads

    /** The exact protocol-v2 capability report this simulated bridge would send, as real JSON. */
    fun capabilitiesPayload(): JsonObject {
        val worldAccess = MinecraftCapability.WORLD_ACCESS in facts.capabilities
        val constructionExecute = MinecraftCapability.BUILD_EXECUTION in facts.capabilities
        val cancellation = MinecraftCapability.CANCELLATION in facts.capabilities
        val originAvailable = facts.dimensionId != null && facts.worldSessionId != null
        return JsonObject().apply {
            addProperty("protocolVersion", facts.protocolVersion)
            addProperty("bridgeId", trustedBridge.bridgeId)
            addProperty("identityFingerprint", trustedBridge.tlsFingerprint)
            addNullable("clientAppVersion", facts.appVersionEcho)
            addProperty("bridgeVersion", facts.bridgeVersion)
            addProperty("edition", facts.editionName)
            addProperty("minecraftVersion", facts.minecraftVersion)
            if (facts.editionName == "bedrock") {
                addProperty("platform", facts.platformName)
                addNullable("platformVersion", facts.platformVersion)
            } else {
                addProperty("javaRuntimeMajor", facts.javaRuntimeMajor)
                addProperty("loaderName", facts.loaderName)
                addProperty("loaderVersion", facts.loaderVersion)
                addNullable("fabricApiVersion", facts.fabricApiVersion)
            }
            add("supportedCapabilities", JsonArray().apply {
                facts.capabilities.sortedBy { it.name }.forEach { add(it.wireValue) }
            })
            addProperty("worldAccess", worldAccess)
            addProperty("constructionExecute", constructionExecute)
            addProperty("cancellation", cancellation)
            addProperty("maximumValidatedOperations", facts.maximumValidatedOperations)
            addProperty("maximumRequestBytes", facts.maximumRequestBytes)
            addProperty("maximumOperationsPerTick", facts.maximumOperationsPerTick)
            addProperty("maximumExecutionSeconds", facts.maximumExecutionSeconds)
            add("supportedBuildPlanSchemaVersions", JsonArray().apply { facts.schemaVersions.forEach { add(it) } })
            if (facts.editionName == "bedrock") {
                add("limitations", JsonArray().apply { facts.limitations.forEach { add(it) } })
            }
            addNullable("dimensionId", facts.dimensionId)
            addNullable("worldSessionId", facts.worldSessionId)
            if (!originAvailable) {
                // keep the both-or-neither wire invariant explicit for readers of this fixture
                require(facts.dimensionId == null && facts.worldSessionId == null) {
                    "dimensionId and worldSessionId must both be present or both absent"
                }
            }
        }
    }

    /**
     * Real envelope bytes, so bounded parsing, the request-ID contract, and the protocol version stamp are exercised
     * by the production codec. The correlation ID stays null: a capability report is not correlated to an execution.
     */
    fun capabilitiesEnvelopeBytes(correlationId: String? = null): ByteArray {
        val envelope = BridgeProtocolCodec.newEnvelope(
            "capabilities.response",
            capabilitiesPayload(),
            correlationId,
        )
        return BridgeProtocolCodec.writeEnvelope(envelope)
    }

    /**
     * The authenticated runtime report as CraftMind production code sees it: envelope bytes parsed by the production
     * codec chain, never a hand-built snapshot.
     */
    fun authenticatedSnapshot(): BridgeCapabilitiesSnapshot {
        val bytes = capabilitiesEnvelopeBytes()
        val envelope = BridgeProtocolCodec.parseEnvelope(bytes, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES)
        if (envelope.messageType != "capabilities.response") throw MinecraftBridgeFailure("BRIDGE_RESPONSE_MISMATCH")
        return BridgeRuntimeReportReader.read(envelope.payload, trustedBridge)
    }

    /** App-side execution request payload, mirroring the production repository shape exactly. */
    fun executionRequestPayload(record: LocalBuildRecord, executionId: String): JsonObject {
        val plan = record.plan
        return JsonObject().apply {
            addProperty("executionId", executionId)
            addProperty("buildId", record.buildId)
            addProperty("planRecordId", record.recordId)
            addProperty("planVersion", record.version)
            addProperty("buildPlanSchemaVersion", plan.metadata.schemaVersion)
            add("buildPlan", buildPlanJson(plan))
            add("origin", JsonObject().apply {
                addProperty("kind", "BRIDGE_SELECTED_SAFE")
                addNullable("dimensionId", facts.dimensionId)
                addNullable("worldSessionId", facts.worldSessionId)
                add("position", JsonNull.INSTANCE)
            })
            add("limits", JsonObject().apply {
                addProperty("maxOperations", minOf(BridgeProtocol.MAX_OPERATIONS, facts.maximumValidatedOperations))
                addProperty(
                    "maxRequestBytes",
                    minOf(BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES, facts.maximumRequestBytes),
                )
            })
        }
    }

    // ---------------------------------------------------------------------------------- repository surface

    override suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String) {
        events += "pair"
        mutableConnectionState.value = BridgeConnectionState.Disconnected
    }

    override suspend fun connect() {
        events += "connect"
        if (authenticationFails) {
            mutableConnectionState.value = BridgeConnectionState.Error("AUTH_SIGNATURE_INVALID")
            throw MinecraftBridgeFailure("AUTH_SIGNATURE_INVALID")
        }
        if (sessionExpiresImmediately) {
            mutableConnectionState.value = BridgeConnectionState.Error("AUTH_SESSION_EXPIRED")
            throw MinecraftBridgeFailure("AUTH_SESSION_EXPIRED")
        }
        sessionId = sessionId ?: DEFAULT_SESSION_ID
        authenticatedAtEpochMillis = FIXED_AUTHENTICATED_AT
        mutableConnectionState.value = BridgeConnectionState.Connected(
            trustedBridge, authenticatedSnapshot(), authenticatedAtEpochMillis, sessionId,
        )
        events += "authenticated:${facts.editionName}/${facts.minecraftVersion}"
    }

    override suspend fun disconnect() {
        events += "disconnect"
        sessionId = null
        activeExecutionId = null
        preparedExecutions.clear()
        mutableConnectionState.value = BridgeConnectionState.Disconnected
    }

    override suspend fun refreshCapabilities() {
        events += "refresh-capabilities"
        requireSession()
        mutableConnectionState.value = BridgeConnectionState.Connected(
            trustedBridge, authenticatedSnapshot(), authenticatedAtEpochMillis, sessionId,
        )
    }

    override suspend fun prepareExecution(
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview {
        requireSession()
        events += "prepare:$executionId"
        if (preparedExecutions.containsKey(executionId) || activeExecutionId == executionId) {
            throw MinecraftBridgeFailure("EXECUTION_ALREADY_EXISTS")
        }
        if (activeExecutionId != null && !allowSecondActiveExecution) {
            throw MinecraftBridgeFailure("EXECUTION_ALREADY_ACTIVE")
        }
        val payload = executionRequestPayload(record, executionId)
        val bytes = BridgeProtocolCodec.writeEnvelope(
            BridgeProtocolCodec.newEnvelope("execution.prepare.request", payload, executionId),
        )
        val contractError = BuildPlanContractValidator.validateExecutionPayload(
            payload,
            bytes.size,
            facts.maximumValidatedOperations,
            facts.maximumRequestBytes,
            CatalogBlockSupport,
        )
        if (contractError != null) {
            events += "prepare-rejected:${contractError.name}"
            throw MinecraftBridgeFailure(contractError.name)
        }
        if (rejectPreflight) {
            events += "preflight-rejected:$preflightRejectionCode"
            throw MinecraftBridgeFailure(preflightRejectionCode)
        }
        sequence += 1
        val preview = MinecraftExecutionPreview(
            executionId = executionId,
            preflightToken = deterministicToken(executionId),
            planRecordId = record.recordId,
            planVersion = record.version,
            planTitle = record.plan.metadata.title,
            dimensionId = facts.dimensionId ?: throw MinecraftBridgeFailure("ORIGIN_UNAVAILABLE"),
            worldSessionId = facts.worldSessionId ?: throw MinecraftBridgeFailure("ORIGIN_UNAVAILABLE"),
            resolvedOrigin = BlockPosition(0, 64, 0),
            originStrategy = "BRIDGE_SELECTED_SAFE",
            operationCount = record.plan.operations.size,
            createdAtEpochMillis = FIXED_AUTHENTICATED_AT,
            eventSequence = sequence.toLong(),
            expiresAtEpochMillis = FIXED_AUTHENTICATED_AT + PREFLIGHT_TTL_MILLIS,
        )
        preparedExecutions[executionId] = PreparedExecution(
            executionId = executionId,
            preflightToken = preview.preflightToken,
            record = record,
            requestBytes = bytes.size,
            createdAtEpochMillis = preview.createdAtEpochMillis,
            expiresAtEpochMillis = preview.expiresAtEpochMillis,
        )
        events += "preflight-ready:$executionId:${record.plan.operations.size}-operations"
        return preview
    }

    override suspend fun startExecution(preview: MinecraftExecutionPreview): MinecraftExecutionSnapshot {
        requireSession()
        val prepared = preparedExecutions[preview.executionId] ?: throw MinecraftBridgeFailure("PREFLIGHT_REQUIRED")
        if (prepared.preflightToken != preview.preflightToken) throw MinecraftBridgeFailure("PREFLIGHT_TOKEN_INVALID")
        if (prepared.expiresAtEpochMillis <= FIXED_AUTHENTICATED_AT) throw MinecraftBridgeFailure("PREFLIGHT_EXPIRED")
        if (preview.worldSessionId != facts.worldSessionId || preview.dimensionId != facts.dimensionId) {
            throw MinecraftBridgeFailure("WORLD_SESSION_CHANGED")
        }
        activeExecutionId = preview.executionId
        events += "execution-started:${preview.executionId}"
        if (disconnectDuringExecution) {
            events += "session-lost-during-execution"
            sessionId = null
            mutableConnectionState.value = BridgeConnectionState.Disconnected
            throw MinecraftBridgeFailure("BRIDGE_SESSION_UNAVAILABLE")
        }
        if (failExecution) {
            events += "execution-failed:$executionFailureCode"
            return snapshot(preview, MinecraftExecutionPhase.FAILED, 0, executionFailureCode)
        }

        // Actually "place" the blocks in the simulated world, reporting progress as the real bridge would. A
        // requested cancellation is honoured at a batch boundary: a bounded prefix is written, progress is reported
        // for exactly that prefix, and the execution terminates CANCELLED instead of completing.
        val plan = prepared.record.plan
        val batchBoundary = if (cancelInsteadOfComplete) {
            cancelAfterOperations.coerceIn(0, plan.operations.size)
        } else {
            plan.operations.size
        }
        var written = 0
        plan.operations.take(batchBoundary).forEach { operation ->
            worldBlocks[operation.position] = operation.blockId
            worldBlockStates[operation.position] = operation.blockState
            written += 1
            progressReports += 1
            events += "block-written:${operation.blockId}@${operation.position.x},${operation.position.y},${operation.position.z}"
        }
        events += "progress-reported:$written/${plan.operations.size}"
        val phase = if (cancelInsteadOfComplete) MinecraftExecutionPhase.CANCELLED else MinecraftExecutionPhase.COMPLETED
        // A terminal execution is no longer the single *active* build, exactly as on a real bridge.
        activeExecutionId = null
        preparedExecutions.remove(preview.executionId)
        if (cancelInsteadOfComplete) {
            events += "execution-cancelled-at-batch-boundary:$written/${plan.operations.size}"
        }
        return snapshot(
            preview,
            phase,
            written,
            if (phase == MinecraftExecutionPhase.COMPLETED) null else "CANCELLATION_ACCEPTED",
        )
    }

    override suspend fun queryExecution(executionId: String): MinecraftExecutionQueryResult {
        requireSession()
        val prepared = preparedExecutions[executionId] ?: return MinecraftExecutionQueryResult.NotFound
        val phase = when {
            worldBlocks.isNotEmpty() && !cancelInsteadOfComplete -> MinecraftExecutionPhase.COMPLETED
            cancelInsteadOfComplete -> MinecraftExecutionPhase.CANCELLED
            else -> MinecraftExecutionPhase.PREPARED
        }
        return MinecraftExecutionQueryResult.Found(
            MinecraftExecutionSnapshot(
                executionId = executionId,
                buildId = prepared.record.buildId,
                planRecordId = prepared.record.recordId,
                planVersion = prepared.record.version,
                phase = phase,
                completedOperations = if (phase == MinecraftExecutionPhase.COMPLETED) worldBlocks.size else 0,
                totalOperations = prepared.record.plan.operations.size,
                eventSequence = sequence.toLong(),
                createdAtEpochMillis = prepared.createdAtEpochMillis,
                updatedAtEpochMillis = prepared.createdAtEpochMillis,
                dimensionId = facts.dimensionId ?: "minecraft:overworld",
                worldSessionId = facts.worldSessionId ?: "world-session-certification",
                resolvedOrigin = BlockPosition(0, 64, 0),
                reasonCode = null,
                failedOperationIndex = null,
            ),
        )
    }

    override suspend fun cancelExecution(executionId: String): MinecraftCancellationResult {
        requireSession()
        events += "cancel:$executionId"
        val prepared = preparedExecutions[executionId]
        if (prepared == null) return MinecraftCancellationResult("EXECUTION_NOT_FOUND", null, null)
        preparedExecutions.remove(executionId)
        if (activeExecutionId == executionId) activeExecutionId = null
        return MinecraftCancellationResult("CANCELLATION_ACCEPTED", MinecraftExecutionPhase.CANCELLED, null)
    }

    override suspend fun revoke() {
        events += "revoke"
        mutableProfile.value = null
        mutableConnectionState.value = BridgeConnectionState.Disconnected
    }

    override suspend fun forgetLocally() {
        events += "forget-locally"
        mutableProfile.value = null
        mutableConnectionState.value = BridgeConnectionState.Disconnected
    }

    /**
     * Test support: models a build that is already running server-side because something other than this run started
     * it. CraftMind must then refuse to open a second concurrent execution.
     */
    fun markExecutionActive(executionId: String) {
        activeExecutionId = executionId
        events += "external-execution-active:$executionId"
    }

    /** Simulates the operator moving world/session, which must invalidate prepared work. */
    fun changeWorldSession(newWorldSessionId: String) {
        facts = facts.copy(worldSessionId = newWorldSessionId)
        events += "world-session-changed:$newWorldSessionId"
    }

    /** Simulates the bridge switching Minecraft runtime, which must invalidate prior eligibility. */
    fun changeRuntime(newFacts: SimulatedRuntimeFacts) {
        facts = newFacts
        events += "runtime-changed:${newFacts.editionName}/${newFacts.minecraftVersion}"
    }

    /** Simulates a reconnect: a new authenticated session with the same or a changed runtime. */
    suspend fun reconnect(newSessionId: String = "session-after-reconnect"): BridgeCapabilitiesSnapshot {
        events += "reconnect:$newSessionId"
        sessionId = newSessionId
        authenticatedAtEpochMillis = FIXED_AUTHENTICATED_AT + RECONNECT_OFFSET_MILLIS
        preparedExecutions.clear()
        activeExecutionId = null
        val snapshot = authenticatedSnapshot()
        mutableConnectionState.value = BridgeConnectionState.Connected(
            trustedBridge, snapshot, authenticatedAtEpochMillis, sessionId,
        )
        return snapshot
    }

    private fun snapshot(
        preview: MinecraftExecutionPreview,
        phase: MinecraftExecutionPhase,
        completedOperations: Int,
        reasonCode: String?,
    ) = MinecraftExecutionSnapshot(
        executionId = preview.executionId,
        buildId = preparedExecutions[preview.executionId]?.record?.buildId ?: CertificationBuildPlan.BUILD_ID,
        planRecordId = preview.planRecordId,
        planVersion = preview.planVersion,
        phase = phase,
        completedOperations = completedOperations,
        totalOperations = preview.operationCount,
        eventSequence = sequence.toLong() + 1,
        createdAtEpochMillis = preview.createdAtEpochMillis,
        updatedAtEpochMillis = preview.createdAtEpochMillis,
        dimensionId = preview.dimensionId,
        worldSessionId = preview.worldSessionId,
        resolvedOrigin = preview.resolvedOrigin,
        reasonCode = reasonCode,
        failedOperationIndex = null,
    )

    private fun requireSession() {
        if (sessionId == null) throw MinecraftBridgeFailure("BRIDGE_SESSION_UNAVAILABLE")
    }

    private fun deterministicToken(executionId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(executionId.toByteArray(Charsets.UTF_8))
        return com.craftmind.bridge.protocol.BridgeCrypto.base64Url(digest).take(43)
    }

    private fun buildPlanJson(plan: BuildPlan): JsonObject {
        val intent = plan.metadata.intent ?: throw MinecraftBridgeFailure("BUILD_PLAN_NOT_EXECUTABLE")
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
                    val bounds: BlockBounds = component.bounds
                        ?: throw MinecraftBridgeFailure("BUILD_PLAN_NOT_EXECUTABLE")
                    add(JsonObject().apply {
                        addProperty("componentId", component.componentId)
                        addProperty("name", component.name)
                        addProperty("purpose", component.purpose)
                        add("bounds", JsonObject().apply {
                            add("origin", positionJson(bounds.origin))
                            add("dimensions", JsonObject().apply {
                                addProperty("width", bounds.dimensions.width)
                                addProperty("height", bounds.dimensions.height)
                                addProperty("depth", bounds.dimensions.depth)
                            })
                        })
                        addProperty("type", component.type.name)
                        addNullable("parentComponentId", component.parentComponentId)
                        addProperty("constructionOrder", component.constructionOrder)
                    })
                }
            })
            add("operations", JsonArray().apply {
                plan.operations.forEach { operation ->
                    add(JsonObject().apply {
                        addProperty("sequence", operation.sequence)
                        addProperty("kind", operation.kind.name)
                        addProperty("blockId", operation.blockId)
                        add("position", positionJson(operation.position))
                        add("blockState", JsonObject().apply {
                            operation.blockState.toSortedMap().forEach { (key, value) -> addProperty(key, value) }
                        })
                        addNullable("componentId", operation.componentId)
                    })
                }
            })
            addProperty("status", plan.status.name)
        }
    }

    private fun positionJson(position: BlockPosition) = JsonObject().apply {
        addProperty("x", position.x)
        addProperty("y", position.y)
        addProperty("z", position.z)
    }

    private fun stringArray(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

    private fun JsonObject.addNullable(key: String, value: String?) {
        if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
    }

    /** Block support backed by CraftMind's own bounded catalog; a real runtime uses the live server registry. */
    private object CatalogBlockSupport : BuildPlanContractValidator.BlockSupport {
        override fun isSupportedBlock(blockId: String): Boolean = MinecraftBlockCatalog.supports(blockId)
        override fun hasValidState(blockId: String, state: MutableMap<String, String>): Boolean =
            MinecraftBlockCatalog.validState(blockId, state)
    }

    companion object {
        const val CERTIFICATION_APP_VERSION = "1.0.0"
        const val DEFAULT_SESSION_ID = "sim-session-1"
        const val FIXED_AUTHENTICATED_AT = 1_700_000_000_000L
        const val PREFLIGHT_TTL_MILLIS = 60_000L
        const val RECONNECT_OFFSET_MILLIS = 30_000L

        val FULL_CAPABILITIES: Set<MinecraftCapability> = setOf(
            MinecraftCapability.WORLD_ACCESS,
            MinecraftCapability.WORLD_VALIDATION,
            MinecraftCapability.BUILD_EXECUTION,
            MinecraftCapability.BLOCK_PLACEMENT,
            MinecraftCapability.BLOCK_STATE_SUPPORT,
            MinecraftCapability.ORIGIN_RESOLUTION,
            MinecraftCapability.STRUCTURE_BATCHING,
            MinecraftCapability.PROGRESS_REPORTING,
            MinecraftCapability.BUILD_STATUS,
            MinecraftCapability.CANCELLATION,
            MinecraftCapability.BUILD_PLAN_V2,
        )

        val TRUSTED_BRIDGE = TrustedMinecraftBridge(
            host = "192.0.2.20",
            port = 19872,
            tlsFingerprint = "aa".repeat(32),
            bridgeId = "bridge-0123456789abcdef0123456789abcdef",
            clientId = "client-certification",
            displayName = "Certification bridge",
            pairedAtEpochMillis = FIXED_AUTHENTICATED_AT,
        )
    }
}
