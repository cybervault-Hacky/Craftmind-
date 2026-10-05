package com.craftmind.bridge.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BuildPlanContractValidatorTest {
    private static final BuildPlanContractValidator.BlockSupport VANILLA_TEST_BLOCKS = new BuildPlanContractValidator.BlockSupport() {
        @Override public boolean isSupportedBlock(String blockId) { return "minecraft:stone".equals(blockId); }
        @Override public boolean hasValidState(String blockId, Map<String, String> state) { return state.isEmpty(); }
    };

    @Test
    public void acceptsOnlyAValidVersionedBuildPlanRequest() {
        JsonObject payload = request();
        assertNull(BuildPlanContractValidator.validateExecutionPayload(
                payload, payload.toString().getBytes(StandardCharsets.UTF_8).length,
                BridgeProtocol.MAX_OPERATIONS, BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES, VANILLA_TEST_BLOCKS));
    }

    @Test
    public void rejectsUnsupportedSchemaDimensionsCoordinatesBlocksAndOperationLimits() {
        JsonObject payload = request();
        payload.addProperty("buildPlanSchemaVersion", 3);
        assertEquals(BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA,
                validate(payload));

        payload = request();
        payload.getAsJsonObject("buildPlan").getAsJsonObject("metadata").getAsJsonObject("dimensions")
                .addProperty("width", BridgeProtocol.MAX_DIMENSION_WIDTH + 1);
        assertEquals(BridgeProtocol.ErrorCode.BUILD_TOO_LARGE, validate(payload));

        payload = request();
        payload.getAsJsonObject("buildPlan").getAsJsonArray("operations").get(0).getAsJsonObject()
                .getAsJsonObject("position").addProperty("x", 96);
        assertEquals(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN, validate(payload));

        payload = request();
        payload.getAsJsonObject("buildPlan").getAsJsonArray("operations").get(0).getAsJsonObject()
                .addProperty("blockId", "minecraft:command_block");
        assertEquals(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN, validate(payload));

        payload = request();
        JsonArray operations = payload.getAsJsonObject("buildPlan").getAsJsonArray("operations");
        for (int index = 0; index < BridgeProtocol.MAX_OPERATIONS; index++) {
            operations.add(operations.get(0).deepCopy());
        }
        assertEquals(BridgeProtocol.ErrorCode.BUILD_TOO_LARGE, validate(payload));
    }

    @Test
    public void rejectsUnknownPlanFieldsInvalidBlockStatesAndInconsistentLimits() {
        JsonObject payload = request();
        payload.getAsJsonObject("buildPlan").addProperty("command", "say hello");
        assertEquals(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN, validate(payload));

        payload = request();
        payload.getAsJsonObject("buildPlan").getAsJsonArray("operations").get(0).getAsJsonObject()
                .getAsJsonObject("blockState").addProperty("facing", "north");
        assertEquals(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN, validate(payload));

        payload = request();
        payload.getAsJsonObject("limits").addProperty("maxOperations", BridgeProtocol.MAX_OPERATIONS + 1);
        assertEquals(BridgeProtocol.ErrorCode.LIMIT_EXCEEDED, validate(payload));

        payload = request();
        payload.getAsJsonObject("limits").addProperty("maxRequestBytes", 1024);
        assertEquals(BridgeProtocol.ErrorCode.LIMIT_EXCEEDED, validate(payload, 1025));
    }

    private static BridgeProtocol.ErrorCode validate(JsonObject payload) {
        return validate(payload, payload.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    private static BridgeProtocol.ErrorCode validate(JsonObject payload, int actualRequestBytes) {
        return BuildPlanContractValidator.validateExecutionPayload(
                payload, actualRequestBytes,
                BridgeProtocol.MAX_OPERATIONS, BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES, VANILLA_TEST_BLOCKS);
    }

    private static JsonObject request() {
        String json = """
                {
                  "buildId":"build_demo",
                  "buildPlanSchemaVersion":2,
                  "buildPlan":{
                    "planId":"plan_demo",
                    "metadata":{
                      "schemaVersion":2,"sourceRequestId":"request_demo","providerId":"google","modelId":"gemini-test",
                      "title":"Stone pavilion","summary":"A small stone pavilion.","generatedAtEpochMillis":1700000000000,
                      "dimensions":{"width":4,"height":4,"depth":4},
                      "intent":{"structureType":"pavilion","style":"stone","approximateScale":"small","floorCount":1,
                        "rooms":[],"specialFeatures":[],"materials":["stone"],"environment":null,"constraints":[]}
                    },
                    "originStrategy":"WORLD_ORIGIN",
                    "components":[{"componentId":"main","name":"Main structure","purpose":"A pavilion.",
                      "bounds":{"origin":{"x":0,"y":0,"z":0},"dimensions":{"width":4,"height":4,"depth":4}},
                      "type":"BUILDING","parentComponentId":null,"constructionOrder":0}],
                    "operations":[{"sequence":0,"kind":"PLACE_BLOCK","blockId":"minecraft:stone",
                      "position":{"x":0,"y":0,"z":0},"blockState":{},"componentId":"main"}],
                    "status":"READY"
                  },
                  "origin":{"kind":"WORLD_SPAWN","dimensionId":"minecraft:overworld","worldSessionId":null,"position":null},
                  "limits":{"maxOperations":4096,"maxRequestBytes":1048576}
                }
                """;
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
