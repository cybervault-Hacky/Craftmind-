package com.craftmind.app.data.minecraft

import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.google.gson.JsonObject

/**
 * Selects the authenticated runtime-report parser by the edition the bridge reports.
 *
 * Both editions share the protocol-v2 envelope, authentication, replay protection, capability negotiation,
 * limits, and execution lifecycle; only the runtime facts differ. The edition field is inspected with a bounded
 * length only, so a malformed report can never select a lenient parser. Unknown or missing editions keep the
 * existing Java path, which fails closed through the resolver instead of guessing a runtime.
 */
internal object BridgeRuntimeReportReader {
    fun read(payload: JsonObject, expectedProfile: TrustedMinecraftBridge?): BridgeCapabilitiesSnapshot {
        val editionElement = payload.get("edition")
        val editionName = editionElement
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        if (editionName == null || editionName.length > MAXIMUM_EDITION_NAME_LENGTH) {
            throw MinecraftBridgeFailure("BRIDGE_CAPABILITIES_INVALID")
        }
        return if (MinecraftEdition.fromWire(editionName) == MinecraftEdition.BEDROCK) {
            BedrockBridgeCapabilitiesWireCodec.read(payload, expectedProfile)
        } else {
            BridgeCapabilitiesWireCodec.read(payload, expectedProfile)
        }
    }

    private const val MAXIMUM_EDITION_NAME_LENGTH = 16
}
