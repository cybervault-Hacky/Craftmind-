package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus
import com.craftmind.bridge.protocol.BridgeProtocol

/** Matrix groups, so every row states which family of runtimes it certifies (or refuses). */
enum class MinecraftCertificationMatrixGroup(val displayName: String) {
    JAVA_PRODUCTION("Java production"),
    JAVA_LEGACY("Java legacy (experimental)"),
    BEDROCK("Bedrock (experimental)"),
    UNSUPPORTED_OR_INVALID("Unsupported or invalid runtime"),
    SECURITY("Security and session integrity"),
    EXECUTION("Execution and failure injection"),
}

/**
 * The perturbation a matrix row applies to an otherwise authoritative runtime report. `NONE` means the reported
 * facts alone decide the outcome. Mutations exist so security cases are exercised through the *production*
 * detection path instead of being asserted from hand-written expectations.
 */
enum class MinecraftCertificationMatrixMutation {
    NONE,
    UNAUTHENTICATED,
    SESSION_CHANGED,
    SESSION_IDENTITY_MISSING,
    BRIDGE_IDENTITY_CHANGED,
    APP_VERSION_MISMATCH,
    PROTOCOL_DOWNGRADE,
    OVERSIZED_REPORT,
    FORGED_CAPABILITY,
    UNDECLARED_CAPABILITY,
    MISSING_LIMITS,
    MISSING_JAVA_RUNTIME,
    RUNTIME_CHANGED_AFTER_PREPARE,
    AMBIGUOUS_ADAPTER_REGISTRY,
    WORLD_SESSION_CHANGED,
}

/** One row of the universal compatibility certification matrix (§4). */
data class MinecraftCertificationMatrixRow(
    val caseId: String,
    val group: MinecraftCertificationMatrixGroup,
    val summary: String,
    // Authoritative runtime facts as a bridge would report them.
    val editionName: String,
    val minecraftVersion: String,
    val loaderName: String,
    val loaderVersion: String?,
    val fabricApiVersion: String?,
    val javaRuntimeMajor: Int?,
    val bridgeVersion: String,
    val bridgeProtocolVersion: Int,
    val mutation: MinecraftCertificationMatrixMutation,
    // Exactly what the pipeline must produce. Every field is asserted by the matrix test.
    val expectedDetectionStatus: MinecraftRuntimeDetectionStatus,
    val expectedSelectionStatus: MinecraftAdapterSelectionStatus,
    val expectedReasonCode: String,
    val expectedCompatibilityReasonCode: MinecraftCompatibilityReasonCode?,
    val expectedAdapterId: String?,
    val expectedCanExecute: Boolean,
    /** Which verification categories cover this row; real-runtime coverage is never claimed here. */
    val applicableCategories: Set<MinecraftVerificationCategory>,
    /**
     * The certification decision CraftMind reports for this runtime after Phase 14. Only the shipped production
     * record is `CERTIFIED`; every other row stays `NOT_CERTIFIED` because no real Minecraft runtime test happened.
     */
    val expectedProfileCertification: MinecraftCertificationDecision,
) {
    val isRealRuntimeCovered: Boolean
        get() = MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION in applicableCategories
}

/** One execution-level failure-injection case (§9): which layer must refuse, and with which stable reason code. */
data class MinecraftExecutionFailureCase(
    val caseId: String,
    val summary: String,
    /** The layer that must fail closed first, so no partial execution can happen. */
    val refusingLayer: MinecraftCertificationMatrixGroup,
    val expectedReasonCode: String,
    val expectedFailClosedBeforeBridgeWrite: Boolean,
    val applicableCategories: Set<MinecraftVerificationCategory>,
)

/**
 * The centralized universal certification matrix.
 *
 * It is data, not prose: the Phase 14 tests iterate every row and assert the exact detection status, selection
 * status, reason code, adapter, and executability, so a missing or weakened case is a failing test rather than an
 * unnoticed gap. No row ever claims real-runtime evidence, because none was performed.
 */
object MinecraftCompatibilityCertificationMatrix {
    private val UNIT_AND_STATIC = setOf(
        MinecraftVerificationCategory.STATIC,
        MinecraftVerificationCategory.UNIT,
    )
    private val SIMULATED_PIPELINE = setOf(
        MinecraftVerificationCategory.STATIC,
        MinecraftVerificationCategory.UNIT,
        MinecraftVerificationCategory.PROTOCOL,
        MinecraftVerificationCategory.SIMULATED_INTEGRATION,
    )
    private val SIMULATED_EXECUTION = SIMULATED_PIPELINE + MinecraftVerificationCategory.END_TO_END_EXECUTION

    val runtimeRows: List<MinecraftCertificationMatrixRow> = listOf(
        // ------------------------------------------------------------------ Java production
        row(
            caseId = "java-production-1.20.1",
            group = MinecraftCertificationMatrixGroup.JAVA_PRODUCTION,
            summary = "Java 1.20.1, Java 17, Fabric 0.16.10, Fabric API 0.92.2+1.20.1, Bridge 1.2.0, protocol 2, schema 2",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "RUNTIME_READY",
            expectedAdapterId = "java-fabric-1.20.1",
            expectedCanExecute = true,
            expectedProfileCertification = MinecraftCertificationDecision.CERTIFIED,
            applicableCategories = SIMULATED_EXECUTION + MinecraftVerificationCategory.CERTIFICATION_DECISION,
        ),
        // ------------------------------------------------------------------ Java legacy
        row(
            caseId = "java-legacy-forge-1.7.10",
            group = MinecraftCertificationMatrixGroup.JAVA_LEGACY,
            summary = "Forge 1.7.10 on Java 8 with the declared legacy bridge contract",
            loaderName = "Forge",
            loaderVersion = "10.13.4.1614",
            fabricApiVersion = null,
            javaRuntimeMajor = 8,
            minecraftVersion = "1.7.10",
            bridgeVersion = "1.0.0-legacy",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "RUNTIME_NOT_CERTIFIED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED,
            expectedAdapterId = "java-legacy-experimental",
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "java-legacy-forge-1.12.2",
            group = MinecraftCertificationMatrixGroup.JAVA_LEGACY,
            summary = "Forge 1.12.2 on Java 8 with the declared legacy bridge contract",
            loaderName = "Forge",
            loaderVersion = "14.23.5.2859",
            fabricApiVersion = null,
            javaRuntimeMajor = 8,
            minecraftVersion = "1.12.2",
            bridgeVersion = "1.0.0-legacy",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "RUNTIME_NOT_CERTIFIED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED,
            expectedAdapterId = "java-legacy-experimental",
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "java-legacy-forge-1.7.10-wrong-java-runtime",
            group = MinecraftCertificationMatrixGroup.JAVA_LEGACY,
            summary = "Legacy Forge 1.7.10 reporting Java 17 instead of the required Java 8",
            loaderName = "Forge",
            loaderVersion = "10.13.4.1614",
            fabricApiVersion = null,
            javaRuntimeMajor = 17,
            minecraftVersion = "1.7.10",
            bridgeVersion = "1.0.0-legacy",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_JAVA_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        // ------------------------------------------------------------------ Bedrock
        row(
            caseId = "bedrock-native-contract",
            group = MinecraftCertificationMatrixGroup.BEDROCK,
            summary = "Bedrock Native contract on a dedicated-server platform; recognized, never certified",
            editionName = "bedrock",
            minecraftVersion = "1.21.60",
            loaderName = "Bedrock Native",
            loaderVersion = null,
            fabricApiVersion = null,
            javaRuntimeMajor = null,
            bridgeVersion = "1.0.0",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "BEDROCK_RUNTIME_NOT_CERTIFIED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED,
            expectedAdapterId = "bedrock-bridge-contract",
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        // ------------------------------------------------------------------ Unsupported / invalid
        row(
            caseId = "unsupported-unknown-version",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Bridge reports an unrecognized Minecraft version; nothing is guessed",
            minecraftVersion = "unknown",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.UNKNOWN,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_UNKNOWN",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNKNOWN_MINECRAFT_VERSION,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-unregistered-release-1.19.4",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "A valid but unregistered release; no nearest adapter is selected",
            minecraftVersion = "1.19.4",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-near-version-1.20.2",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "1.20.2 must never be treated as the registered 1.20.1 profile",
            minecraftVersion = "1.20.2",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-java-runtime-21",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Java 21 on the 1.20.1 profile, which requires Java 17 exactly",
            javaRuntimeMajor = 21,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_JAVA_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-wrong-loader-forge",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Forge on 1.20.1 where only Fabric 0.16.10 is registered",
            loaderName = "Forge",
            loaderVersion = "47.2.20",
            fabricApiVersion = null,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-wrong-loader-version",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Fabric 0.16.11 where the registered profile requires 0.16.10 exactly",
            loaderVersion = "0.16.11",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_RUNTIME_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER_VERSION,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-wrong-fabric-api",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Fabric API 0.93.0+1.20.1 where 0.92.2+1.20.1 is registered exactly",
            fabricApiVersion = "0.93.0+1.20.1",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_FABRIC_API_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-wrong-bridge-version",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Bridge 1.3.0 where 1.2.0 is registered for protocol 2",
            bridgeVersion = "1.3.0",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "BRIDGE_VERSION_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "invalid-protocol-downgrade",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Correct Minecraft version but protocol 1; never silently downgraded",
            mutation = MinecraftCertificationMatrixMutation.PROTOCOL_DOWNGRADE,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "BRIDGE_PROTOCOL_UNSUPPORTED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "invalid-java-edition-with-bedrock-loader",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Java Edition reporting the Bedrock Native runtime: an incoherent pair, never auto-corrected",
            loaderName = "Bedrock Native",
            loaderVersion = null,
            fabricApiVersion = null,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "invalid-cross-edition-bedrock-with-fabric",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            // The Bedrock capability payload carries no loader field at all, so this contradiction cannot even be
            // expressed on the wire; it is asserted at descriptor level, where the shared structural validator makes
            // an edition/loader mismatch invalid instead of silently re-reading it as Bedrock Native.
            summary = "Bedrock descriptor reporting a Fabric loader: editions never cross-report",
            editionName = "bedrock",
            minecraftVersion = "1.21.60",
            loaderName = "Fabric",
            loaderVersion = null,
            fabricApiVersion = null,
            javaRuntimeMajor = null,
            bridgeVersion = "1.0.0",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = UNIT_AND_STATIC,
        ),
        row(
            caseId = "invalid-java-reporting-bedrock-native-loader",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Java descriptor reporting the Bedrock Native loader: the mirror of the cross-edition case",
            editionName = "java",
            minecraftVersion = "1.20.1",
            loaderName = "Bedrock Native",
            loaderVersion = null,
            fabricApiVersion = null,
            javaRuntimeMajor = 17,
            bridgeVersion = "1.2.0",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = UNIT_AND_STATIC,
        ),
        row(
            caseId = "invalid-malformed-runtime-identity",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "A loader version token containing a newline; unsafe tokens are rejected, not trimmed",
            loaderVersion = "0.16.10\n",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = UNIT_AND_STATIC,
        ),
        row(
            caseId = "invalid-legacy-edition-never-routed",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "A runtime claiming the LEGACY edition is incomplete and is never routed through a Java adapter",
            editionName = "legacy",
            loaderName = "unknown",
            loaderVersion = null,
            fabricApiVersion = null,
            minecraftVersion = "b1.7.3",
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INCOMPLETE,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_INCOMPLETE",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-release-channel-snapshot",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Snapshot 24w14a keeps its channel and is never matched to a release",
            minecraftVersion = "24w14a",
            loaderName = "Vanilla",
            loaderVersion = "1.0.0",
            fabricApiVersion = null,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "UNSUPPORTED_RELEASE_CHANNEL",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-release-channel-beta",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Beta b1.7.3 is recognized as beta and never mapped to a release",
            minecraftVersion = "b1.7.3",
            loaderName = "Vanilla",
            loaderVersion = "1.0.0",
            fabricApiVersion = null,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "UNSUPPORTED_RELEASE_CHANNEL",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "unsupported-release-channel-alpha",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Alpha a1.2.6 is recognized as alpha and never mapped to a release",
            minecraftVersion = "a1.2.6",
            loaderName = "Vanilla",
            loaderVersion = "1.0.0",
            fabricApiVersion = null,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "UNSUPPORTED_RELEASE_CHANNEL",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "incomplete-missing-java-runtime",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "No reported server Java runtime; never inferred from build settings",
            mutation = MinecraftCertificationMatrixMutation.MISSING_JAVA_RUNTIME,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INCOMPLETE,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_INCOMPLETE",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "incomplete-missing-limits",
            group = MinecraftCertificationMatrixGroup.UNSUPPORTED_OR_INVALID,
            summary = "Runtime limits absent; limits are never defaulted and execution stays blocked",
            mutation = MinecraftCertificationMatrixMutation.MISSING_LIMITS,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INCOMPLETE,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_INCOMPLETE",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        // ------------------------------------------------------------------ Security / session integrity
        row(
            caseId = "security-unauthenticated-report",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "An unauthenticated caller can never ask for an adapter",
            mutation = MinecraftCertificationMatrixMutation.UNAUTHENTICATED,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DETECTION_UNAUTHORIZED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-session-identity-missing",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "No authenticated session identity, so compatibility cannot be bound",
            mutation = MinecraftCertificationMatrixMutation.SESSION_IDENTITY_MISSING,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "SESSION_IDENTITY_MISMATCH",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-bridge-identity-changed",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "The paired bridge identity changed; never silently re-paired",
            mutation = MinecraftCertificationMatrixMutation.BRIDGE_IDENTITY_CHANGED,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "SESSION_IDENTITY_MISMATCH",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-app-version-mismatch",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "The bridge echoed a different CraftMind app version",
            mutation = MinecraftCertificationMatrixMutation.APP_VERSION_MISMATCH,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "APP_VERSION_MISMATCH",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-forged-capability",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "World availability claimed without the matching authenticated capability",
            mutation = MinecraftCertificationMatrixMutation.FORGED_CAPABILITY,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-undeclared-capability",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "A capability this build does not define is never widened into support",
            mutation = MinecraftCertificationMatrixMutation.UNDECLARED_CAPABILITY,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-oversized-report",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "A runtime report larger than one bounded protocol-v2 control message",
            mutation = MinecraftCertificationMatrixMutation.OVERSIZED_REPORT,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.INVALID,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.INVALID,
            expectedReasonCode = "RUNTIME_DESCRIPTOR_INVALID",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = UNIT_AND_STATIC,
        ),
        row(
            caseId = "security-ambiguous-adapter",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "Two adapters claiming one exact runtime block selection instead of ordering it",
            mutation = MinecraftCertificationMatrixMutation.AMBIGUOUS_ADAPTER_REGISTRY,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.AMBIGUOUS,
            expectedReasonCode = "AMBIGUOUS_ADAPTER_MATCH",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
        ),
        row(
            caseId = "security-runtime-changed-after-prepare",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "The runtime changed between resolution and execution (TOCTOU)",
            mutation = MinecraftCertificationMatrixMutation.RUNTIME_CHANGED_AFTER_PREPARE,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.NO_MATCH,
            expectedReasonCode = "RUNTIME_IDENTITY_CHANGED",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED,
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = SIMULATED_PIPELINE,
        ),
        row(
            caseId = "security-session-changed-after-prepare",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "A different authenticated session invalidates the resolved compatibility",
            mutation = MinecraftCertificationMatrixMutation.SESSION_CHANGED,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "SESSION_IDENTITY_MISMATCH",
            expectedCompatibilityReasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
            expectedAdapterId = "java-fabric-1.20.1",
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = SIMULATED_PIPELINE,
        ),
        row(
            caseId = "security-world-session-changed-after-prepare",
            group = MinecraftCertificationMatrixGroup.SECURITY,
            summary = "A changed world session invalidates prepared work",
            mutation = MinecraftCertificationMatrixMutation.WORLD_SESSION_CHANGED,
            expectedDetectionStatus = MinecraftRuntimeDetectionStatus.DETECTED,
            expectedSelectionStatus = MinecraftAdapterSelectionStatus.SELECTED,
            expectedReasonCode = "WORLD_SESSION_CHANGED",
            expectedAdapterId = "java-fabric-1.20.1",
            expectedProfileCertification = MinecraftCertificationDecision.NOT_CERTIFIED,
            applicableCategories = SIMULATED_PIPELINE,
        ),
    )

    /** Execution-level failure injections (§9). Each must fail closed before any block is written. */
    val executionFailureCases: List<MinecraftExecutionFailureCase> = listOf(
        executionCase(
            "execution-authentication-failure",
            "Authentication failure: a refused signature yields no session, no capabilities, no detection",
            "AUTH_SIGNATURE_INVALID",
        ),
        executionCase(
            "execution-invalid-session",
            "An expired or unknown session cannot prepare or start a build",
            "AUTH_SESSION_EXPIRED",
        ),
        executionCase(
            "execution-duplicate-execution-id",
            "A reused execution ID is rejected by the simulated bridge",
            "EXECUTION_ALREADY_EXISTS",
        ),
        executionCase(
            "execution-second-simultaneous-build",
            "Only one active build exists; a second execution is refused",
            "EXECUTION_ALREADY_ACTIVE",
        ),
        executionCase(
            "execution-preflight-rejected",
            "Server preflight rejects the plan; nothing is placed",
            "BUILD_PLAN_REJECTED_BY_SERVER",
        ),
        executionCase(
            "execution-invalid-build-plan",
            "An invalid BuildPlan never reaches an adapter",
            "BUILD_PLAN_NOT_EXECUTABLE",
        ),
        executionCase(
            "execution-unsupported-block",
            "A block with no verified target representation is refused",
            "UNSUPPORTED_BLOCK",
        ),
        executionCase(
            "execution-unsupported-block-state",
            "A block state that cannot be represented is refused, never substituted",
            "UNSUPPORTED_BLOCK_STATE",
        ),
        executionCase(
            "execution-schema-downgrade",
            "BuildPlan schema 1 is rejected; schema downgrade never happens",
            "BUILD_PLAN_SCHEMA_UNSUPPORTED",
        ),
        executionCase(
            "execution-limit-cannot-be-raised",
            "A runtime claiming a larger limit cannot raise CraftMind's global ceiling",
            "LIMIT_EXCEEDED",
        ),
        executionCase(
            "execution-cancellation",
            "Cancellation is honoured at a batch boundary and reported by the bridge",
            "CANCELLATION_ACCEPTED",
        ),
        executionCase(
            "execution-disconnect-during-build",
            "A lost session during execution fails closed and is reported, never resumed silently",
            "BRIDGE_SESSION_UNAVAILABLE",
        ),
        executionCase(
            "execution-stale-binding",
            "A stale execution binding (the world session moved) cannot authorize a build",
            "WORLD_SESSION_CHANGED",
        ),
        executionCase(
            "execution-legacy-refusal",
            "The legacy adapter refuses before any bridge call",
            "RUNTIME_NOT_CERTIFIED",
        ),
        executionCase(
            "execution-bedrock-refusal",
            "The Bedrock adapter refuses before any bridge call",
            "BEDROCK_RUNTIME_NOT_CERTIFIED",
        ),
    )

    val allCaseIds: List<String> get() = runtimeRows.map { it.caseId } + executionFailureCases.map { it.caseId }

    fun row(caseId: String): MinecraftCertificationMatrixRow? = runtimeRows.firstOrNull { it.caseId == caseId }

    fun rowsFor(group: MinecraftCertificationMatrixGroup): List<MinecraftCertificationMatrixRow> =
        runtimeRows.filter { it.group == group }

    /** No row in this repository may claim real-runtime coverage; certification requires a real host first. */
    fun rowsClaimingRealRuntimeEvidence(): List<MinecraftCertificationMatrixRow> =
        runtimeRows.filter { it.isRealRuntimeCovered }

    @Suppress("LongParameterList")
    private fun row(
        caseId: String,
        group: MinecraftCertificationMatrixGroup,
        summary: String,
        editionName: String = "java",
        minecraftVersion: String = "1.20.1",
        loaderName: String = "Fabric",
        loaderVersion: String? = "0.16.10",
        fabricApiVersion: String? = "0.92.2+1.20.1",
        javaRuntimeMajor: Int? = 17,
        bridgeVersion: String = "1.2.0",
        bridgeProtocolVersion: Int = BridgeProtocol.VERSION,
        mutation: MinecraftCertificationMatrixMutation = MinecraftCertificationMatrixMutation.NONE,
        expectedDetectionStatus: MinecraftRuntimeDetectionStatus,
        expectedSelectionStatus: MinecraftAdapterSelectionStatus,
        expectedReasonCode: String,
        expectedCompatibilityReasonCode: MinecraftCompatibilityReasonCode? = null,
        expectedAdapterId: String? = null,
        expectedCanExecute: Boolean = false,
        expectedProfileCertification: MinecraftCertificationDecision,
        applicableCategories: Set<MinecraftVerificationCategory> = SIMULATED_PIPELINE,
    ) = MinecraftCertificationMatrixRow(
        caseId = caseId,
        group = group,
        summary = summary,
        editionName = editionName,
        minecraftVersion = minecraftVersion,
        loaderName = loaderName,
        loaderVersion = loaderVersion,
        fabricApiVersion = fabricApiVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        bridgeVersion = bridgeVersion,
        bridgeProtocolVersion = bridgeProtocolVersion,
        mutation = mutation,
        expectedDetectionStatus = expectedDetectionStatus,
        expectedSelectionStatus = expectedSelectionStatus,
        expectedReasonCode = expectedReasonCode,
        expectedCompatibilityReasonCode = expectedCompatibilityReasonCode,
        expectedAdapterId = expectedAdapterId,
        expectedCanExecute = expectedCanExecute,
        applicableCategories = applicableCategories,
        expectedProfileCertification = expectedProfileCertification,
    )

    private fun executionCase(
        caseId: String,
        summary: String,
        expectedReasonCode: String,
    ) = MinecraftExecutionFailureCase(
        caseId = caseId,
        summary = summary,
        refusingLayer = MinecraftCertificationMatrixGroup.EXECUTION,
        expectedReasonCode = expectedReasonCode,
        expectedFailClosedBeforeBridgeWrite = true,
        applicableCategories = SIMULATED_EXECUTION,
    )

    /** Edition/loader names a matrix row uses; kept next to the data so the driver cannot invent new ones. */
    fun editionForRow(row: MinecraftCertificationMatrixRow): MinecraftEdition = when (row.editionName) {
        "java" -> MinecraftEdition.JAVA
        "bedrock" -> MinecraftEdition.BEDROCK
        "legacy" -> MinecraftEdition.LEGACY
        else -> MinecraftEdition.UNKNOWN
    }

    fun loaderForRow(row: MinecraftCertificationMatrixRow): MinecraftLoader =
        MinecraftLoader.fromWire(row.loaderName)
}
