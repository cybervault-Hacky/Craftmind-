package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.JavaFabric1201Adapter
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BridgeProtocolException
import com.craftmind.bridge.protocol.BuildPlanContractValidator
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Phase 14 §10: security certification regression.
 *
 * Certification must not become a way around the security controls Phases 9–13 established. Every test here asserts a
 * control *and* that violating it can never produce certification evidence: no certification decision is granted to an
 * unauthenticated runtime, a forged capability report, a protocol or schema downgrade, a raised limit, a replayed
 * preflight token, or an artifact that carries identity, host, or secret material.
 *
 * The pinned-HTTPS transport itself lives in Android/OkHttp code that this JVM harness cannot execute, so that control
 * is asserted as STATIC verification over the production source (and skipped, not passed, when the source tree is not
 * reachable from the test working directory).
 */
class CertificationSecurityRegressionTest {
    private val resolver = DefaultMinecraftCompatibility.resolver
    private val adapter = JavaFabric1201Adapter()

    // --------------------------------------------------------------------------- §10 secrets never in artifacts

    @Test
    fun certificationEvidenceRejectsSecretsKeysHostsAndNetworkIdentity() {
        // Every probe is a synthetic *shape*, never a real credential: short enough that no repository scanner reads
        // it as a live secret, long enough that the certification sanitizer must reject it.
        val forbidden = listOf(
            "-----BEGIN TEST PRIVATE KEY-----",
            "api_key=not-a-real-key",
            "password: not-a-real-password",
            "keystorePassword=not-a-real-password",
            "Authorization: Bearer notarealtoken",
            "AIza0123456789",
            "ghp_TESTTESTTEST",
            "sk-TESTTESTTESTTEST",
            "192.0.2.20:19872",
            "bridge.invalid",
            "craftmind-bridge.example.com",
            "runtime.local",
            "release.keystore",
            "server.pem",
            "aa:bb:cc:dd:ee:ff:00:11:22:33",
        )
        forbidden.forEach { value ->
            assertFalse("must be rejected as evidence text: $value", MinecraftCertificationSanitizer.isSanitized(value))
            val thrown = runCatching { MinecraftCertificationSanitizer.requireSanitized("field", value) }
            assertTrue("requireSanitized must fail closed for: $value", thrown.isFailure)
        }
        // Ordinary certification text is still accepted; the sanitizer is not a blanket refusal.
        assertTrue(MinecraftCertificationSanitizer.isSanitized("java-fabric-1.20.1"))
        assertTrue(MinecraftCertificationSanitizer.isSanitized("Simulated bridge · no Minecraft runtime"))
        assertNull(MinecraftCertificationSanitizer.violation(null))
        // Unbounded text is rejected rather than truncated into a plausible-looking record.
        assertFalse(MinecraftCertificationSanitizer.isSanitized("x".repeat(201)))
        assertTrue(MinecraftCertificationSanitizer.isSanitized("x".repeat(201), 480))
    }

    @Test
    fun aMachineReadableReportCarriesNoSessionBridgeHostOrSecretMaterial() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        val run = SimulatedCertificationPipeline(
            bridge = bridge,
            resolver = resolver,
            testRunId = "security-report-run",
        ).run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))
        assertTrue("the simulated run must complete for this assertion to mean anything", run.completedEveryStage)

        val report = MinecraftCertificationReportBuilder(
            testSuiteId = SimulatedCertificationPipeline.TEST_SUITE_ID,
            testRunId = "security-report-run",
            generatedByVersion = "1.0.0",
            generatedByEnvironment = SimulatedCertificationPipeline.JVM_UNIT_ENVIRONMENT,
            reproducibility = MinecraftCertificationReproducibility(
                harness = "SimulatedCertificationPipeline",
                toolchain = listOf("kotlinc-jvm"),
                commands = listOf("bash /home/user/harness/run-tests.sh certification"),
                sourceRevision = null,
                notes = listOf("Security regression run; simulated bridge only."),
            ),
        )
            .addEvidence(
                run.evidence,
                run.evaluation,
                declaredStatus = com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus.SUPPORTED,
                declaredCertification = MinecraftRuntimeCertification.CERTIFIED,
            )
            .build()
        val json = MinecraftCertificationReport.toJson(report)

        // Round-trips exactly: the committed artifact is deterministic, sanitized data and nothing else.
        assertEquals(report, MinecraftCertificationReport.fromJson(json))

        val neverPresent = listOf(
            SimulatedCertificationBridge.DEFAULT_SESSION_ID,
            SimulatedCertificationBridge.TRUSTED_BRIDGE.bridgeId,
            SimulatedCertificationBridge.TRUSTED_BRIDGE.tlsFingerprint,
            SimulatedCertificationBridge.TRUSTED_BRIDGE.host,
            SimulatedCertificationBridge.TRUSTED_BRIDGE.clientId,
            "192.0.2.20",
            "world-session-certification",
            "apiKey",
            "password",
            "BEGIN",
        )
        neverPresent.forEach { value ->
            assertFalse("a certification report must never contain '$value'", json.contains(value))
        }
        assertTrue(json.contains("\"schemaVersion\": ${MinecraftCertificationReport.REPORT_SCHEMA_VERSION}"))
        assertTrue(json.contains("\"generatedByVersion\": \"1.0.0\""))
        assertFalse("a report must never claim a real runtime test", report.summary.realRuntimeTestsPerformed)
    }

    // --------------------------------------------------------------------- §10 authentication and identity binding

    @Test
    fun anUnauthenticatedOrIdentityMismatchedRuntimeIsNeverDetectedAndNeverCertified() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val authenticated = bridge.authenticatedSnapshot().runtimeReport(
            sessionId = bridge.sessionId,
            requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            authenticated = true,
            authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
        )
        assertTrue("the control case must be detectable", resolver.runtimeGate.resolveRuntime(authenticated).canExecute)

        val unauthenticated = authenticated.copy(authenticated = false)
        val unauthenticatedResolution = resolver.runtimeGate.resolveRuntime(unauthenticated)
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, unauthenticatedResolution.detection.status)
        assertEquals("RUNTIME_DETECTION_UNAUTHORIZED", unauthenticatedResolution.detection.failureReasonCode())
        assertFalse(unauthenticatedResolution.canExecute)
        assertFalse(unauthenticatedResolution.selection.isSelected)

        val wrongBridge = authenticated.copy(expectedBridgeId = "bridge-ffffffffffffffffffffffffffffffff")
        val wrongBridgeResolution = resolver.runtimeGate.resolveRuntime(wrongBridge)
        assertFalse(wrongBridgeResolution.canExecute)
        assertTrue(
            "a changed bridge identity must be reported, got ${wrongBridgeResolution.reasonCodes}",
            MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in wrongBridgeResolution.reasonCodes,
        )

        val wrongFingerprint = authenticated.copy(expectedIdentityFingerprint = "bb".repeat(32))
        val wrongFingerprintResolution = resolver.runtimeGate.resolveRuntime(wrongFingerprint)
        assertFalse(wrongFingerprintResolution.canExecute)
        assertTrue(
            "a pinned TLS identity mismatch must be reported, got ${wrongFingerprintResolution.reasonCodes}",
            MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in wrongFingerprintResolution.reasonCodes,
        )

        val sessionless = authenticated.copy(sessionId = null)
        val sessionlessResolution = resolver.runtimeGate.resolveRuntime(sessionless)
        assertFalse(sessionlessResolution.canExecute)
        assertTrue(
            MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in sessionlessResolution.reasonCodes,
        )

        // None of these runtimes can be turned into certification evidence, and none of them authorizes execution.
        listOf(
            unauthenticatedResolution, wrongBridgeResolution, wrongFingerprintResolution, sessionlessResolution,
        ).forEach { resolution ->
            assertFalse(resolution.canExecute)
            assertNotNull(resolution.failureReasonCode())
            val authorization = resolver.runtimeGate.authorizeExecution(
                report = when (resolution) {
                    unauthenticatedResolution -> unauthenticated
                    wrongBridgeResolution -> wrongBridge
                    wrongFingerprintResolution -> wrongFingerprint
                    else -> sessionless
                },
                requirements = com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements.runtimeExecution,
                previousBinding = null,
            )
            assertFalse("an identity violation must never authorize execution", authorization.authorized)
        }

        val evaluation = MinecraftCertificationRuleEngine().evaluate(null)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertTrue(MinecraftCertificationReasonCode.NO_EVIDENCE in evaluation.reasonCodes)
    }

    @Test
    fun forgedCapabilitiesAreRefusedAndCannotBeTurnedIntoCertification() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        val run = SimulatedCertificationPipeline(
            bridge = bridge,
            resolver = resolver,
            testRunId = "security-forged-capability",
        ).run(
            SimulatedPipelineRequest(
                profileId = "java-fabric-1.20.1",
                // Forged after the wire codec: the report claims a world is available while the authenticated
                // capability set no longer contains WORLD_ACCESS. Detection must refuse it; nothing may be repaired.
                descriptorMutation = { descriptor ->
                    descriptor.copy(capabilities = descriptor.capabilities - MinecraftCapability.WORLD_ACCESS)
                },
            ),
        )

        assertEquals(MinecraftVerificationOutcome.FAILED, run.stage(SimulatedPipelineStage.RUNTIME_DETECTION)?.outcome)
        assertEquals(
            MinecraftRuntimeDetectionStatus.INVALID,
            run.resolution?.detection?.status,
        )
        assertFalse(run.authorized)
        assertTrue("a forged capability report must never write a block", bridge.worldBlocks.isEmpty())
        assertFalse(run.evidence.realRuntimeTested)
        // A forged capability report is a failed verification: the decision is FAILED, never NOT_CERTIFIED-by-omission.
        assertEquals(MinecraftCertificationDecision.FAILED, run.evaluation.decision)
        assertFalse(run.evaluation.authorizesExecution)
        assertTrue(run.evidence.failedCategories.isNotEmpty())
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, run.evidence.evidenceLevel)
    }

    // ------------------------------------------------------------------------ §10 protocol and schema downgrades

    @Test
    fun protocolAndSchemaDowngradesAreRefusedByProductionCode() {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        runBlocking { bridge.connect() }
        val executionId = CertificationExecutionIds.next("security-downgrade")
        val payload = bridge.executionRequestPayload(CertificationBuildPlan.localRecord(), executionId)

        fun envelope(
            protocolVersion: Int = BridgeProtocol.VERSION,
            timestampEpochMillis: Long = SimulatedCertificationBridge.FIXED_AUTHENTICATED_AT,
            body: JsonObject = payload,
        ): ByteArray {
            val json = JsonObject().apply {
                addProperty("protocolVersion", protocolVersion)
                addProperty("messageType", "execution.prepare.request")
                addProperty("requestId", executionId)
                addProperty("timestampEpochMillis", timestampEpochMillis)
                add("correlationId", JsonNull.INSTANCE)
                add("payload", body)
            }
            // serializeNulls is required: an explicit `"correlationId": null` is part of the protocol envelope, and a
            // default Gson would drop the member and make the envelope merely malformed instead of downgraded.
            return GsonBuilder().serializeNulls().create().toJson(json).toByteArray(Charsets.UTF_8)
        }

        // Protocol downgrade: a peer offering an older protocol is refused, never negotiated down.
        val downgrade = runCatching {
            BridgeProtocolCodec.parseEnvelope(envelope(protocolVersion = BridgeProtocol.VERSION - 1), Int.MAX_VALUE)
        }
        assertTrue("a protocol downgrade must be refused", downgrade.isFailure)
        assertEquals(
            BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL,
            (downgrade.exceptionOrNull() as BridgeProtocolException).code,
        )

        // Replay marker: an envelope without a usable timestamp is refused instead of being trusted.
        val staleTimestamp = runCatching {
            BridgeProtocolCodec.parseEnvelope(envelope(timestampEpochMillis = 0L), Int.MAX_VALUE)
        }
        assertTrue("a zero timestamp must be refused", staleTimestamp.isFailure)
        assertEquals(
            BridgeProtocol.ErrorCode.INVALID_TIMESTAMP,
            (staleTimestamp.exceptionOrNull() as BridgeProtocolException).code,
        )

        // Schema downgrade: a schema-1 plan document is refused by the production contract validator.
        val schemaOne = JsonObject().apply {
            payload.entrySet().forEach { (key, value) -> add(key, value) }
            addProperty("buildPlanSchemaVersion", LEGACY_BUILD_PLAN_SCHEMA)
        }
        val bytes = envelope(body = schemaOne)
        assertEquals(
            BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA,
            BuildPlanContractValidator.validateExecutionPayload(
                schemaOne,
                bytes.size,
                BridgeProtocol.MAX_OPERATIONS,
                BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
                CatalogBlockSupport,
            ),
        )
        assertTrue("a refused downgrade must never write a block", bridge.worldBlocks.isEmpty())
    }

    @Test
    fun aRuntimeReportingAnOlderProtocolIsRefusedBeforeDetectionAndNeverCertified() = runBlocking {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                protocolVersion = BridgeProtocol.VERSION - 1,
            ),
        )
        val run = SimulatedCertificationPipeline(
            bridge = bridge,
            resolver = resolver,
            testRunId = "security-protocol-mismatch",
        ).run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))

        // The production codec refuses the envelope, so no runtime fact from a downgraded peer is ever inspected.
        assertEquals(
            MinecraftVerificationOutcome.FAILED,
            run.stage(SimulatedPipelineStage.BRIDGE_AUTHENTICATION)?.outcome,
        )
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", run.stage(SimulatedPipelineStage.BRIDGE_AUTHENTICATION)?.reasonCode)
        assertNull("no capability report may be accepted from a downgraded peer", run.snapshot)
        assertNull("no runtime may be detected from a downgraded peer", run.resolution)
        assertEquals(
            MinecraftVerificationOutcome.NOT_RUN,
            run.stage(SimulatedPipelineStage.RUNTIME_DETECTION)?.outcome,
        )
        assertEquals(
            MinecraftVerificationOutcome.NOT_RUN,
            run.stage(SimulatedPipelineStage.EXECUTION_AUTHORIZATION)?.outcome,
        )
        assertFalse(run.authorized)
        assertTrue("a downgraded peer must never write a block", bridge.worldBlocks.isEmpty())
        assertFalse(run.evidence.realRuntimeTested)
        // A downgraded peer is a failed verification, not merely insufficient evidence.
        assertEquals(MinecraftCertificationDecision.FAILED, run.evaluation.decision)
        assertFalse(run.evaluation.authorizesExecution)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, run.evidence.evidenceLevel)
        assertTrue(MinecraftVerificationCategory.PROTOCOL in run.evidence.failedCategories)
    }

    // ------------------------------------------------------------------------------- §10 limits are server-owned

    @Test
    fun missingRuntimeLimitsFailClosedInsteadOfFallingBackToDefaults() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val report = bridge.authenticatedSnapshot().runtimeReport(
            sessionId = bridge.sessionId,
            requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            authenticated = true,
            authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
        )
        val withoutLimits = report.copy(
            descriptor = report.descriptor.copy(
                maximumValidatedOperations = null,
                maximumRequestBytes = null,
                maximumOperationsPerTick = null,
                maximumExecutionSeconds = null,
            ),
        )
        val resolution = resolver.runtimeGate.resolveRuntime(withoutLimits)
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, resolution.detection.status)
        assertFalse(resolution.canExecute)
        val authorization = resolver.runtimeGate.authorizeExecution(
            report = withoutLimits,
            requirements = com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements.runtimeExecution,
            previousBinding = null,
        )
        assertFalse("missing limits must never authorize execution", authorization.authorized)
    }

    // ------------------------------------------------------- §10 no command, shell, credential, or replay surface

    @Test
    fun theExecutionProtocolCarriesNoCommandShellOrCredentialSurface() = runBlocking {
        // The domain has exactly two block operations; there is no command, script, or entity operation to certify.
        assertEquals(
            setOf(BuildPlanOperationKind.PLACE_BLOCK, BuildPlanOperationKind.REMOVE_BLOCK),
            BuildPlanOperationKind.entries.toSet(),
        )
        val plan = CertificationBuildPlan.build()
        assertTrue(plan.operations.all { it.kind == BuildPlanOperationKind.PLACE_BLOCK })

        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val payload = bridge.executionRequestPayload(
            CertificationBuildPlan.localRecord(),
            CertificationExecutionIds.next("security-surface"),
        )
        val keys = payload.collectKeyNames()
        val forbiddenSubstrings = listOf(
            "command", "shell", "script", "console", "apikey", "api_key", "secret",
            "password", "credential", "authorization", "cookie", "privatekey",
        )
        forbiddenSubstrings.forEach { needle ->
            assertTrue(
                "the execution payload must never carry '$needle' (keys: ${keys.sorted()})",
                keys.none { it.lowercase().contains(needle) },
            )
        }
        // No credential-bearing key may appear under any name, and the preflight token never travels in the plan.
        val forbiddenKeys = setOf("token", "preflightToken", "accessToken", "apiKey", "auth", "sessionToken")
        assertTrue(
            "the execution payload must never carry a credential key (keys: ${keys.sorted()})",
            keys.none { it in forbiddenKeys },
        )
        // The payload is exactly the documented execution request; nothing extra can be smuggled to the bridge.
        assertEquals(
            setOf(
                "executionId", "buildId", "planRecordId", "planVersion",
                "buildPlanSchemaVersion", "buildPlan", "origin", "limits",
            ),
            payload.keySet(),
        )
    }

    @Test
    fun aReplayedOrForgedPreflightTokenCannotStartAnExecution() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val record = CertificationBuildPlan.localRecord()

        // No preflight at all: execution is refused before anything is written.
        val prepared = adapter.preflight(bridge, record, CertificationExecutionIds.next("security-unprepared"))
        bridge.preparedExecutions.clear()
        val unpreparedRun = runCatching { adapter.execute(bridge, prepared) }
        assertTrue(unpreparedRun.isFailure)
        assertEquals("PREFLIGHT_REQUIRED", (unpreparedRun.exceptionOrNull() as MinecraftBridgeFailure).reasonCode)
        assertTrue(bridge.worldBlocks.isEmpty())

        // A forged token on an otherwise valid preview is refused: the token binds the execution to the preflight.
        val preview = adapter.preflight(bridge, record, CertificationExecutionIds.next("security-token"))
        val forged = preview.copy(preflightToken = preview.preflightToken.reversed() + "x")
        val forgedRun = runCatching { adapter.execute(bridge, forged) }
        assertTrue(forgedRun.isFailure)
        assertEquals("PREFLIGHT_TOKEN_INVALID", (forgedRun.exceptionOrNull() as MinecraftBridgeFailure).reasonCode)
        assertTrue("a forged preflight token must never write a block", bridge.worldBlocks.isEmpty())

        // The genuine token still works, so the control is a binding and not a blanket refusal.
        val genuine = adapter.execute(bridge, preview)
        assertEquals(
            com.craftmind.app.domain.minecraft.MinecraftExecutionPhase.COMPLETED,
            genuine.phase,
        )
        assertEquals(CertificationBuildPlan.OPERATION_COUNT, bridge.worldBlocks.size)
    }

    // ----------------------------------------------------------- §10 certification requires a declared environment

    @Test
    fun anUndeclaredEnvironmentCanNeverMintRealRuntimeCertification() {
        val engine = MinecraftCertificationRuleEngine()
        val evidence = CertificationEvidenceFixtures.realRuntime(
            executionEnvironment = SimulatedCertificationPipeline.JVM_UNIT_ENVIRONMENT,
        )
        val evaluation = engine.evaluate(evidence)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertTrue(
            MinecraftCertificationReasonCode.ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME in evaluation.reasonCodes,
        )
        assertFalse(evaluation.realRuntimeTested)
        assertEquals(MinecraftEvidenceMode.SIMULATED, evaluation.evidenceMode)
        assertTrue(evaluation.evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL))

        // The same evidence in an environment the policy declares real-runtime capable does certify, which proves the
        // refusal above is a policy gate and not a hard-coded impossibility.
        val declaredEngine = MinecraftCertificationRuleEngine(
            MinecraftCertificationPolicy.DEFAULT.copy(
                realRuntimeCapableEnvironments = setOf("declared-real-runtime-lab"),
            ),
        )
        val declared = declaredEngine.evaluate(
            CertificationEvidenceFixtures.realRuntime(executionEnvironment = "declared-real-runtime-lab"),
        )
        assertEquals(MinecraftCertificationDecision.CERTIFIED, declared.decision)
        assertTrue(declared.realRuntimeTested)
        assertTrue(declared.authorizesExecution)

        // The shipped default policy declares no such environment, so this build cannot certify anything new.
        assertTrue(MinecraftCertificationPolicy.DEFAULT.realRuntimeCapableEnvironments.isEmpty())
    }

    // -------------------------------------------------------------------------- §10 static transport verification

    @Test
    fun thePairingTransportStaysPinnedToHttpsAndAFingerprint() {
        val source = productionSource("data/minecraft/AndroidMinecraftBridgePairingRepository.kt")
        assumeTrue(
            "STATIC verification skipped: the Android source tree is not reachable from this working directory",
            source != null,
        )
        val text = source!!.readText()
        assertTrue("pairing must build https URLs", text.contains("\"https://\${endpoint.host}:\${endpoint"))
        assertTrue("pairing must pin the bridge certificate", text.contains("pinnedTrustManager"))
        assertTrue("pairing must install a trust manager", text.contains("sslSocketFactory"))
        assertFalse("pairing must never use cleartext http", Regex("\"http://").containsMatchIn(text))
        assertFalse("pairing must never disable hostname verification silently", text.contains("ALLOW_ALL_HOSTNAME"))
        assertTrue(
            "the pinned TLS fingerprint must remain part of the authenticated identity",
            text.contains("tlsFingerprint"),
        )
    }

    // --------------------------------------------------------------------------------------------- helpers

    private fun productionSource(relativePath: String): File? = listOf(
        File("src/main/java/com/craftmind/app/$relativePath"),
        File("app/src/main/java/com/craftmind/app/$relativePath"),
        File("../app/src/main/java/com/craftmind/app/$relativePath"),
    ).firstOrNull { it.isFile }

    private fun JsonObject.collectKeyNames(): Set<String> {
        val names = linkedSetOf<String>()
        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                    names += key
                    walk(value)
                }

                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }
        walk(this)
        return names
    }

    private companion object {
        const val LEGACY_BUILD_PLAN_SCHEMA = 1

        val CatalogBlockSupport = object : BuildPlanContractValidator.BlockSupport {
            override fun isSupportedBlock(blockId: String): Boolean =
                com.craftmind.app.domain.buildplan.MinecraftBlockCatalog.supports(blockId)

            override fun hasValidState(blockId: String, state: MutableMap<String, String>): Boolean =
                com.craftmind.app.domain.buildplan.MinecraftBlockCatalog.validState(blockId, state)
        }
    }
}
