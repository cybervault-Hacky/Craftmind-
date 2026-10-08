package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.bridge.protocol.BridgeCrypto
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Automatic Minecraft runtime detection (Phase 13).
 *
 * The authenticated bridge is the only source of runtime facts. Detection never inspects a launcher name, an
 * executable, an APK setting, a package name, a filename, a port, a previously connected runtime, a manually typed
 * version, or a UI selection, and it never normalizes a runtime to a nearest known version. When the bridge does
 * not report enough authoritative information the result is [MinecraftRuntimeDetectionStatus.UNKNOWN] or
 * [MinecraftRuntimeDetectionStatus.INCOMPLETE] and execution stays blocked.
 */
enum class MinecraftRuntimeDetectionStatus {
    /** Every mandatory identity fact was reported, is internally coherent, and is bound to an authenticated session. */
    DETECTED,

    /** The runtime is identifiable in principle, but a mandatory fact was not reported. */
    INCOMPLETE,

    /** The runtime cannot be identified safely; nothing is guessed and no adapter may be selected. */
    UNKNOWN,

    /** Reported facts contradict each other, violate the protocol, or the report is not authorized. */
    INVALID,
}

/** The descriptor field a detection diagnostic refers to; used by the UI and tests, never for guessing. */
enum class MinecraftRuntimeField(val displayName: String) {
    SESSION("Authenticated session"),
    RUNTIME_IDENTITY("Runtime identity"),
    APP_VERSION("CraftMind app version echo"),
    EDITION("Minecraft edition"),
    VERSION("Minecraft version"),
    RELEASE_CHANNEL("Release channel"),
    LOADER("Loader"),
    LOADER_VERSION("Loader version"),
    FABRIC_API("Fabric API"),
    JAVA_RUNTIME("Java runtime"),
    PLATFORM("Bedrock platform"),
    BRIDGE_VERSION("Bridge version"),
    PROTOCOL_VERSION("Bridge protocol"),
    CAPABILITIES("Bridge capabilities"),
    LIMITS("Runtime limits"),
    DESCRIPTOR("Runtime descriptor"),
}

/** One bounded, structured detection diagnostic. It never contains credentials, tokens, or provider material. */
data class MinecraftRuntimeDetectionDiagnostic(
    val reasonCode: MinecraftCompatibilityReasonCode,
    val field: MinecraftRuntimeField,
    val detail: String,
)

/**
 * The security-sensitive identity a detected runtime is bound to.
 *
 * Compatibility is only meaningful for this exact authenticated session and this exact runtime. If any part of it
 * changes, previously resolved compatibility and execution eligibility are invalidated and detection runs again.
 */
data class MinecraftRuntimeIdentity(
    val bridgeId: String,
    val identityFingerprint: String,
    val sessionId: String,
    val authenticatedAtEpochMillis: Long,
    val bridgeProtocolVersion: Int,
    val bridgeVersion: String,
    val edition: MinecraftEdition,
    val version: MinecraftVersion,
    val releaseChannel: MinecraftVersionChannel,
    val loader: MinecraftLoader,
    val loaderVersion: String?,
    val javaRuntimeMajor: Int?,
    val platform: MinecraftRuntimePlatform,
    val dimensionId: String?,
    val worldSessionId: String?,
) {
    /**
     * Exact Minecraft runtime key: edition, version, channel, loader, loader version, Java runtime, Bedrock
     * platform, bridge version, and protocol. It is compared by exact tokens — never by numeric proximity — so
     * `1.20.1` and `1.19.4` are different runtimes and a change between them invalidates prior eligibility.
     */
    val runtimeKey: String
        get() = listOf(
            edition.wireValue,
            version.displayIdentifier,
            releaseChannel.name,
            loader.wireValue,
            loaderVersion ?: "-",
            javaRuntimeMajor?.toString() ?: "-",
            platform.wireValue,
            bridgeVersion,
            bridgeProtocolVersion.toString(),
        ).joinToString("|")

    /** Paired bridge identity plus the authenticated session; a change here invalidates every cached result. */
    val sessionKey: String get() = "$bridgeId|$identityFingerprint|$sessionId"

    /** True when the same authenticated bridge session is still in use. */
    fun hasSameSessionIdentity(other: MinecraftRuntimeIdentity?): Boolean =
        other != null && sessionKey == other.sessionKey

    /** True when the same Minecraft runtime (edition/version/loader/Java/bridge/protocol) is still reported. */
    fun hasSameRuntime(other: MinecraftRuntimeIdentity?): Boolean =
        other != null && runtimeKey == other.runtimeKey && hasSameSessionIdentity(other)
}

/**
 * One runtime report as received from the authenticated bridge, together with the session facts that authorize
 * detection. Constructing this type is the only way to ask for detection, so an unauthenticated caller cannot
 * request an arbitrary adapter.
 */
data class AuthenticatedMinecraftRuntimeReport(
    val descriptor: MinecraftRuntimeDescriptor,
    val authenticated: Boolean,
    val sessionId: String?,
    val bridgeId: String?,
    val identityFingerprint: String?,
    val authenticatedAtEpochMillis: Long?,
    /** The app version this client sent in the signed request; the bridge must echo exactly this value. */
    val requestedAppVersion: String?,
    /** Paired bridge identity from local secure storage; a difference is an identity change, never a re-pair. */
    val expectedBridgeId: String? = null,
    val expectedIdentityFingerprint: String? = null,
    /** Bedrock/Java world facts reported with the runtime; used for display and world-change awareness. */
    val dimensionId: String? = null,
    val worldSessionId: String? = null,
    /** Size of the authenticated report as parsed, when the transport measured it. */
    val reportedBytes: Int? = null,
) {
    companion object {
        /** A runtime report larger than one bounded protocol-v2 control message is rejected, never truncated. */
        const val MAXIMUM_REPORT_BYTES = BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES

        /** Builds a detection request from an authenticated protocol-v2 runtime report. */
        @Suppress("LongParameterList")
        fun fromSnapshot(
            snapshot: BridgeCapabilitiesSnapshot,
            sessionId: String?,
            requestedAppVersion: String?,
            authenticated: Boolean = true,
            authenticatedAtEpochMillis: Long? = null,
            expectedBridgeId: String? = null,
            expectedIdentityFingerprint: String? = null,
            reportedBytes: Int? = null,
        ): AuthenticatedMinecraftRuntimeReport = AuthenticatedMinecraftRuntimeReport(
            descriptor = snapshot.runtimeDescriptor,
            authenticated = authenticated,
            sessionId = sessionId,
            bridgeId = snapshot.bridgeId,
            identityFingerprint = snapshot.identityFingerprint,
            authenticatedAtEpochMillis = authenticatedAtEpochMillis,
            requestedAppVersion = requestedAppVersion,
            expectedBridgeId = expectedBridgeId ?: snapshot.bridgeId,
            expectedIdentityFingerprint = expectedIdentityFingerprint ?: snapshot.identityFingerprint,
            dimensionId = snapshot.dimensionId,
            worldSessionId = snapshot.worldSessionId,
            reportedBytes = reportedBytes,
        )
    }
}

/**
 * The typed, deterministic detection result: a status, the authoritative descriptor, the bound runtime identity,
 * and bounded diagnostics. It never contains a guessed or repaired runtime.
 */
data class MinecraftRuntimeDetectionResult(
    val status: MinecraftRuntimeDetectionStatus,
    val descriptor: MinecraftRuntimeDescriptor,
    /** Null unless the report came from an authenticated session with a usable bridge identity. */
    val runtimeIdentity: MinecraftRuntimeIdentity?,
    val diagnostics: List<MinecraftRuntimeDetectionDiagnostic> = emptyList(),
    val detectedAtEpochMillis: Long? = null,
    /**
     * Release channel declared by the exactly matching registered profile, when that declaration is coherent with
     * the reported identifier. This is how a Java `1.7.10` runtime is reported as `JAVA` + `LEGACY` instead of
     * inventing a third Minecraft edition: the identifier itself parses as a release token, and only an explicit
     * registry decision reclassifies it. Null when no registered profile declares a channel for this identity.
     */
    val declaredReleaseChannel: MinecraftVersionChannel? = null,
) {
    val isDetected: Boolean get() = status == MinecraftRuntimeDetectionStatus.DETECTED

    val reasonCodes: Set<MinecraftCompatibilityReasonCode>
        get() = diagnostics.mapTo(linkedSetOf()) { it.reasonCode }

    /** Edition/version/channel/loader/Java facts are exposed only when they were genuinely detected. */
    val detectedEdition: MinecraftEdition
        get() = if (isDetected) descriptor.edition else MinecraftEdition.UNKNOWN

    val detectedVersion: MinecraftVersion
        get() = if (isDetected) descriptor.version else MinecraftVersion.UNKNOWN

    /**
     * Effective release channel: the explicitly declared channel of the exactly matching registered profile when
     * one exists, otherwise the channel securely derived from the authoritative version identifier. It is never
     * taken from a UI selection and never "corrected" towards a release.
     */
    val detectedReleaseChannel: MinecraftVersionChannel
        get() = if (!isDetected) {
            MinecraftVersionChannel.UNKNOWN
        } else {
            declaredReleaseChannel ?: descriptor.releaseChannel
        }

    val detectedLoader: MinecraftLoader
        get() = if (isDetected) descriptor.loader else MinecraftLoader.UNKNOWN

    val detectedLoaderVersion: String? get() = if (isDetected) descriptor.loaderVersion else null

    val detectedJavaRuntimeMajor: Int? get() = if (isDetected) descriptor.javaRuntimeMajor else null

    /**
     * Adapter selection requires a detected runtime bound to an authenticated session. This is *not* an execution
     * authorization: only the resolver's compatibility result plus the execution gate can authorize a build.
     */
    val canSelectAdapter: Boolean get() = isDetected && runtimeIdentity != null

    /** Stable machine-readable code for logs, UI, and the execution gate. */
    fun failureReasonCode(): String = when {
        isDetected -> "RUNTIME_DETECTED"
        MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED in reasonCodes -> "RUNTIME_DETECTION_UNAUTHORIZED"
        MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH in reasonCodes -> "APP_VERSION_MISMATCH"
        MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in reasonCodes -> "SESSION_IDENTITY_MISMATCH"
        MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in reasonCodes -> "BRIDGE_PROTOCOL_UNSUPPORTED"
        MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in reasonCodes -> "RUNTIME_DESCRIPTOR_INVALID"
        status == MinecraftRuntimeDetectionStatus.UNKNOWN -> "RUNTIME_UNKNOWN"
        status == MinecraftRuntimeDetectionStatus.INCOMPLETE -> "RUNTIME_INCOMPLETE"
        else -> "RUNTIME_NOT_DETECTED"
    }
}

/**
 * The detector. It is deterministic: the same authenticated report always produces the same status, identity, and
 * diagnostics, in the same order, with no randomness, no clock-dependent branching, and no fallbacks.
 */
class MinecraftRuntimeDetector(
    private val registry: MinecraftAdapterRegistry,
    private val maximumReportBytes: Int = AuthenticatedMinecraftRuntimeReport.MAXIMUM_REPORT_BYTES,
) {
    fun detect(report: AuthenticatedMinecraftRuntimeReport): MinecraftRuntimeDetectionResult =
        detect(report, detectedAtEpochMillis = null)

    fun detect(
        report: AuthenticatedMinecraftRuntimeReport,
        detectedAtEpochMillis: Long?,
    ): MinecraftRuntimeDetectionResult {
        val descriptor = report.descriptor
        val diagnostics = mutableListOf<MinecraftRuntimeDetectionDiagnostic>()

        // 1. Authorization first: an unauthenticated report is never inspected for runtime facts, because that
        //    would let any client ask CraftMind to select an arbitrary adapter.
        if (!report.authenticated) {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED,
                field = MinecraftRuntimeField.SESSION,
                detail = "Runtime detection requires an authenticated bridge session; this report is not authenticated.",
            )
            return MinecraftRuntimeDetectionResult(
                status = MinecraftRuntimeDetectionStatus.INVALID,
                descriptor = descriptor,
                runtimeIdentity = null,
                diagnostics = diagnostics,
                detectedAtEpochMillis = detectedAtEpochMillis,
            )
        }

        // 2. Session identity binding.
        val sessionId = report.sessionId
        val bridgeId = report.bridgeId
        val fingerprint = report.identityFingerprint?.let(BridgeCrypto::normalizeFingerprint)
        if (sessionId.isNullOrEmpty() || bridgeId.isNullOrEmpty() || fingerprint.isNullOrEmpty() ||
            report.authenticatedAtEpochMillis == null
        ) {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
                field = MinecraftRuntimeField.SESSION,
                detail = "The authenticated session identity is incomplete; runtime compatibility cannot be bound to it.",
            )
        }
        report.expectedBridgeId?.takeIf { it.isNotEmpty() && bridgeId != null && it != bridgeId }?.let {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
                field = MinecraftRuntimeField.RUNTIME_IDENTITY,
                detail = "The paired bridge identity changed. CraftMind never re-pairs or re-identifies silently.",
            )
        }
        report.expectedIdentityFingerprint?.takeIf { expected ->
            fingerprint != null && expected.isNotEmpty() &&
                BridgeCrypto.normalizeFingerprint(expected) != fingerprint
        }?.let {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
                field = MinecraftRuntimeField.RUNTIME_IDENTITY,
                detail = "The pinned TLS identity does not match the authenticated report.",
            )
        }

        // 3. Bounded report: an oversized descriptor is rejected instead of truncated or partially trusted.
        report.reportedBytes?.takeIf { it < 0 || it > maximumReportBytes }?.let {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
                field = MinecraftRuntimeField.DESCRIPTOR,
                detail = "The runtime report exceeds the bounded protocol-v2 control message size.",
            )
        }

        // 4. Application-version echo (Phase 10/11 mechanism, re-verified here).
        val requestedAppVersion = report.requestedAppVersion
        if (requestedAppVersion != null && descriptor.appVersion != requestedAppVersion) {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH,
                field = MinecraftRuntimeField.APP_VERSION,
                detail = "The authenticated bridge echoed app version ${descriptor.appVersion ?: "nothing"}; this client " +
                    "requested $requestedAppVersion. CraftMind never silently reconnects with another protocol.",
            )
        }

        // 5. Bridge identity and protocol: a correct Minecraft version with an incompatible bridge still fails.
        if (descriptor.bridgeProtocolVersion != BridgeProtocol.VERSION) {
            diagnostics += MinecraftRuntimeDetectionDiagnostic(
                reasonCode = MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH,
                field = MinecraftRuntimeField.PROTOCOL_VERSION,
                detail = "The bridge reports protocol ${descriptor.bridgeProtocolVersion ?: "nothing"}; this app build " +
                    "supports protocol ${BridgeProtocol.VERSION} exactly. There is no downgrade.",
            )
        }

        // 6. Structural coherence (shared with the resolver; never auto-corrected).
        MinecraftRuntimeDescriptorValidation.invalidReasons(descriptor, registry.allProfiles()).forEach { (code, detail) ->
            diagnostics += MinecraftRuntimeDetectionDiagnostic(code, fieldFor(code, detail, descriptor), detail)
        }

        // 6b. Capability validation against the authenticated report only: forged or undeclared capabilities make
        // the runtime undetectable. UI declarations and registry declarations are never a capability source.
        MinecraftRuntimeDescriptorValidation.forgedCapabilityReasons(descriptor).forEach { (code, detail) ->
            diagnostics += MinecraftRuntimeDetectionDiagnostic(code, MinecraftRuntimeField.CAPABILITIES, detail)
        }

        // 7. Mandatory runtime facts.
        val missing = MinecraftRuntimeDescriptorValidation.missingReasons(descriptor)
        MinecraftRuntimeDescriptorValidation.missingLimitReasons(descriptor).forEach { (code, detail) ->
            diagnostics += MinecraftRuntimeDetectionDiagnostic(code, MinecraftRuntimeField.LIMITS, detail)
        }

        val invalid = diagnostics.any { it.reasonCode == MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR ||
            it.reasonCode == MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH ||
            it.reasonCode == MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH ||
            it.reasonCode == MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH ||
            it.reasonCode == MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED }

        // Explicit registry decision only: a declared channel is used when it is coherent with the reported
        // identifier, so legacy Java profiles report JAVA + LEGACY while snapshots/betas keep their own channel.
        val declaredReleaseChannel = registry.allProfiles()
            .firstOrNull { it.matchesRuntimeIdentity(descriptor) }
            ?.releaseChannel
            ?.takeIf { MinecraftRuntimeDescriptorValidation.releaseChannelCoherent(it, descriptor.releaseChannel) }
        val effectiveReleaseChannel = declaredReleaseChannel ?: descriptor.releaseChannel

        val identity = if (sessionId != null && bridgeId != null && fingerprint != null &&
            report.authenticatedAtEpochMillis != null
        ) {
            MinecraftRuntimeIdentity(
                bridgeId = bridgeId,
                identityFingerprint = BridgeCrypto.formatFingerprint(fingerprint),
                sessionId = sessionId,
                authenticatedAtEpochMillis = report.authenticatedAtEpochMillis,
                bridgeProtocolVersion = descriptor.bridgeProtocolVersion ?: -1,
                bridgeVersion = descriptor.bridgeVersion ?: "unknown",
                edition = descriptor.edition,
                version = descriptor.version,
                releaseChannel = effectiveReleaseChannel,
                loader = descriptor.loader,
                loaderVersion = descriptor.loaderVersion,
                javaRuntimeMajor = descriptor.javaRuntimeMajor,
                platform = descriptor.platform,
                dimensionId = report.dimensionId,
                worldSessionId = report.worldSessionId,
            )
        } else {
            null
        }

        missing.forEach { (code, detail) ->
            diagnostics += MinecraftRuntimeDetectionDiagnostic(code, fieldFor(code, detail, descriptor), detail)
        }

        val status = when {
            invalid -> MinecraftRuntimeDetectionStatus.INVALID
            descriptor.edition == MinecraftEdition.UNKNOWN || !descriptor.version.isKnown ->
                MinecraftRuntimeDetectionStatus.UNKNOWN
            missing.isNotEmpty() || !descriptor.hasReportedLimits -> MinecraftRuntimeDetectionStatus.INCOMPLETE
            identity == null -> MinecraftRuntimeDetectionStatus.INVALID
            else -> MinecraftRuntimeDetectionStatus.DETECTED
        }

        return MinecraftRuntimeDetectionResult(
            status = status,
            descriptor = descriptor,
            runtimeIdentity = identity,
            diagnostics = diagnostics,
            detectedAtEpochMillis = detectedAtEpochMillis,
            declaredReleaseChannel = declaredReleaseChannel,
        )
    }

    /** Maps a shared validation finding onto the descriptor field it describes, for bounded UI diagnostics. */
    private fun fieldFor(
        code: MinecraftCompatibilityReasonCode,
        detail: String,
        descriptor: MinecraftRuntimeDescriptor,
    ): MinecraftRuntimeField = when (code) {
        MinecraftCompatibilityReasonCode.UNKNOWN_MINECRAFT_VERSION -> MinecraftRuntimeField.VERSION
        else -> when {
            detail.contains("edition and loader") -> MinecraftRuntimeField.LOADER
            detail.contains("Java runtime") -> MinecraftRuntimeField.JAVA_RUNTIME
            detail.contains("protocol") -> MinecraftRuntimeField.PROTOCOL_VERSION
            detail.contains("Bedrock runtime platform") || detail.contains("platform version") ->
                MinecraftRuntimeField.PLATFORM
            detail.contains("Fabric API") -> MinecraftRuntimeField.FABRIC_API
            detail.contains("Loader version") || detail.contains("loader version") -> MinecraftRuntimeField.LOADER_VERSION
            detail.contains("Bridge version") -> MinecraftRuntimeField.BRIDGE_VERSION
            detail.contains("limit") -> MinecraftRuntimeField.LIMITS
            detail.contains("capability") -> MinecraftRuntimeField.CAPABILITIES
            detail.contains("release channel") -> MinecraftRuntimeField.RELEASE_CHANNEL
            detail.contains("Bedrock") && descriptor.isBedrock -> MinecraftRuntimeField.DESCRIPTOR
            detail.contains("Edition") -> MinecraftRuntimeField.EDITION
            else -> MinecraftRuntimeField.DESCRIPTOR
        }
    }
}
