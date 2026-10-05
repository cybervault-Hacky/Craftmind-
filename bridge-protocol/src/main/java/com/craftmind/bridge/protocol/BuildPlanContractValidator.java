package com.craftmind.bridge.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent structural/semantic validation for the versioned execution transport contract. */
public final class BuildPlanContractValidator {
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern COMPONENT_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final Pattern BLOCK_ID = Pattern.compile("minecraft:[a-z0-9_./-]{1,128}");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern STATE_VALUE = Pattern.compile("[a-z0-9_./-]{1,32}");
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");
    private static final Set<String> COMPONENT_TYPES = immutableSet(
            "BUILDING", "FOUNDATION", "FLOOR", "ROOM", "ROOF", "INTERIOR_FEATURE",
            "EXTERIOR_FEATURE", "LANDSCAPE", "UTILITY", "DECORATION");
    private static final Set<String> ORIGIN_KINDS = immutableSet(
            "PLAYER_SELECTED", "WORLD_SPAWN", "EXPLICIT", "BRIDGE_SELECTED_SAFE");
    private static final Set<String> BUILD_ORIGIN_STRATEGIES = immutableSet("CENTERED_GROUND", "WORLD_ORIGIN");

    private BuildPlanContractValidator() { }

    public interface BlockSupport {
        boolean isSupportedBlock(String blockId);
        boolean hasValidState(String blockId, Map<String, String> state);
    }

    public static BridgeProtocol.ErrorCode validateExecutionPayload(
            JsonObject payload,
            int actualPayloadBytes,
            int maximumSupportedOperations,
            int maximumSupportedRequestBytes,
            BlockSupport blockSupport) {
        try {
            BridgeProtocolCodec.requireExactKeys(payload,
                    "executionId", "buildId", "planRecordId", "planVersion", "buildPlanSchemaVersion",
                    "buildPlan", "origin", "limits");
            String executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36);
            if (!executionId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
                return BridgeProtocol.ErrorCode.EXECUTION_ID_INVALID;
            }
            String buildId = BridgeProtocolCodec.requiredString(payload, "buildId", BridgeProtocol.MAX_BUILD_ID_LENGTH);
            String planRecordId = BridgeProtocolCodec.requiredString(payload, "planRecordId", BridgeProtocol.MAX_PLAN_RECORD_ID_LENGTH);
            int planVersion = BridgeProtocolCodec.requiredInt(payload, "planVersion");
            if (!BUILD_ID.matcher(buildId).matches() || !BUILD_ID.matcher(planRecordId).matches() || planVersion < 1 || planVersion > 100_000) {
                return BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST;
            }
            int schemaVersion = BridgeProtocolCodec.requiredInt(payload, "buildPlanSchemaVersion");
            if (schemaVersion != BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION) {
                return BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA;
            }
            JsonObject plan = requiredObject(payload, "buildPlan");
            BridgeProtocol.ErrorCode planError = validatePlan(plan, maximumSupportedOperations, blockSupport);
            if (planError != null) return planError;
            JsonObject origin = requiredObject(payload, "origin");
            BridgeProtocol.ErrorCode originError = validateOrigin(origin);
            if (originError != null) return originError;
            JsonObject limits = requiredObject(payload, "limits");
            BridgeProtocolCodec.requireExactKeys(limits, "maxOperations", "maxRequestBytes");
            int requestedOperations = BridgeProtocolCodec.requiredInt(limits, "maxOperations");
            int requestedBytes = BridgeProtocolCodec.requiredInt(limits, "maxRequestBytes");
            if (requestedOperations < 1 || requestedOperations > maximumSupportedOperations ||
                    requestedBytes < 1024 || requestedBytes > maximumSupportedRequestBytes ||
                    actualPayloadBytes > requestedBytes) {
                return BridgeProtocol.ErrorCode.LIMIT_EXCEEDED;
            }
            int actualOperations = requiredArray(plan, "operations").size();
            if (actualOperations > requestedOperations) return BridgeProtocol.ErrorCode.BUILD_TOO_LARGE;
            return null;
        } catch (BridgeProtocolException error) {
            return error.getCode() == BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA ||
                    error.getCode() == BridgeProtocol.ErrorCode.BUILD_TOO_LARGE
                    ? error.getCode() : BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST;
        } catch (RuntimeException error) {
            return BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST;
        }
    }

    public static BridgeProtocol.ErrorCode validatePlan(
            JsonObject plan,
            int maximumSupportedOperations,
            BlockSupport blockSupport) {
        try {
            BridgeProtocolCodec.requireExactKeys(plan,
                    "planId", "metadata", "originStrategy", "components", "operations", "status");
            String planId = BridgeProtocolCodec.requiredString(plan, "planId", 64);
            if (!BUILD_ID.matcher(planId).matches()) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
            String originStrategy = BridgeProtocolCodec.requiredString(plan, "originStrategy", 32);
            if (!BUILD_ORIGIN_STRATEGIES.contains(originStrategy)) {
                return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
            }
            if (!"READY".equals(BridgeProtocolCodec.requiredString(plan, "status", 16))) {
                return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
            }
            JsonObject metadata = requiredObject(plan, "metadata");
            validateMetadata(metadata);
            JsonObject dimensions = requiredObject(metadata, "dimensions");
            BridgeProtocolCodec.requireExactKeys(dimensions, "width", "height", "depth");
            int width = dimension(dimensions, "width", BridgeProtocol.MAX_DIMENSION_WIDTH);
            int height = dimension(dimensions, "height", BridgeProtocol.MAX_DIMENSION_HEIGHT);
            int depth = dimension(dimensions, "depth", BridgeProtocol.MAX_DIMENSION_DEPTH);
            JsonObject intent = requiredObject(metadata, "intent");
            validateIntent(intent);

            JsonArray componentArray = requiredArray(plan, "components");
            JsonArray operationArray = requiredArray(plan, "operations");
            if (componentArray.size() < 1 || componentArray.size() > BridgeProtocol.MAX_COMPONENTS ||
                    operationArray.size() < 1 || operationArray.size() > maximumSupportedOperations ||
                    operationArray.size() > BridgeProtocol.MAX_OPERATIONS) {
                return BridgeProtocol.ErrorCode.BUILD_TOO_LARGE;
            }
            List<BuildPlanDocument.Component> components = new ArrayList<>(componentArray.size());
            Map<String, BuildPlanDocument.Component> byId = new HashMap<>();
            Set<Integer> orders = new HashSet<>();
            for (JsonElement element : componentArray) {
                if (!element.isJsonObject()) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                JsonObject component = element.getAsJsonObject();
                BridgeProtocolCodec.requireExactKeys(component,
                        "componentId", "name", "purpose", "bounds", "type", "parentComponentId", "constructionOrder");
                String componentId = BridgeProtocolCodec.requiredString(component, "componentId", 48);
                String type = BridgeProtocolCodec.requiredString(component, "type", 32);
                String name = BridgeProtocolCodec.requiredString(component, "name", 80);
                String purpose = BridgeProtocolCodec.requiredString(component, "purpose", 240);
                int order = BridgeProtocolCodec.requiredInt(component, "constructionOrder");
                String parentId = BridgeProtocolCodec.nullableString(component, "parentComponentId", 48);
                if (!COMPONENT_ID.matcher(componentId).matches() || !COMPONENT_TYPES.contains(type) ||
                        name.trim().isEmpty() || purpose.trim().isEmpty() || byId.containsKey(componentId) ||
                        order < 0 || order >= componentArray.size() || !orders.add(order) ||
                        (parentId != null && !COMPONENT_ID.matcher(parentId).matches())) {
                    return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                }
                JsonObject bounds = requiredObject(component, "bounds");
                BridgeProtocolCodec.requireExactKeys(bounds, "origin", "dimensions");
                BuildPlanDocument.Bounds parsedBounds = parseBounds(bounds, width, height, depth);
                BuildPlanDocument.Component parsed = new BuildPlanDocument.Component();
                parsed.componentId = componentId;
                parsed.type = type;
                parsed.name = name;
                parsed.purpose = purpose;
                parsed.bounds = parsedBounds;
                parsed.parentComponentId = parentId;
                parsed.constructionOrder = order;
                byId.put(componentId, parsed);
                components.add(parsed);
            }
            if (orders.size() != componentArray.size()) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
            for (BuildPlanDocument.Component component : components) {
                if (component.parentComponentId != null) {
                    BuildPlanDocument.Component parent = byId.get(component.parentComponentId);
                    if (parent == null || parent.componentId.equals(component.componentId) ||
                            parent.constructionOrder >= component.constructionOrder ||
                            !boundsWithin(component.bounds, parent.bounds)) {
                        return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                    }
                }
            }
            if (hasParentCycle(byId)) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;

            Map<String, Integer> operationCounts = new HashMap<>();
            Set<Long> occupied = new HashSet<>();
            int previousConstructionOrder = -1;
            for (int index = 0; index < operationArray.size(); index++) {
                JsonElement element = operationArray.get(index);
                if (!element.isJsonObject()) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                JsonObject operation = element.getAsJsonObject();
                BridgeProtocolCodec.requireExactKeys(operation,
                        "sequence", "kind", "blockId", "position", "blockState", "componentId");
                if (BridgeProtocolCodec.requiredInt(operation, "sequence") != index ||
                        !"PLACE_BLOCK".equals(BridgeProtocolCodec.requiredString(operation, "kind", 32))) {
                    return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                }
                String blockId = BridgeProtocolCodec.requiredString(operation, "blockId", 160);
                String componentId = BridgeProtocolCodec.requiredString(operation, "componentId", 48);
                if (!BLOCK_ID.matcher(blockId).matches() || !byId.containsKey(componentId) ||
                        !blockSupport.isSupportedBlock(blockId)) {
                    return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                }
                JsonObject positionObject = requiredObject(operation, "position");
                BridgeProtocolCodec.requireExactKeys(positionObject, "x", "y", "z");
                int x = BridgeProtocolCodec.requiredInt(positionObject, "x");
                int y = BridgeProtocolCodec.requiredInt(positionObject, "y");
                int z = BridgeProtocolCodec.requiredInt(positionObject, "z");
                if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                    return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                }
                long coordinate = ((long) x * BridgeProtocol.MAX_DIMENSION_HEIGHT + y) * BridgeProtocol.MAX_DIMENSION_DEPTH + z;
                if (!occupied.add(coordinate)) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                BuildPlanDocument.Component component = byId.get(componentId);
                BuildPlanDocument.Position point = new BuildPlanDocument.Position();
                point.x = x;
                point.y = y;
                point.z = z;
                if (!inside(point, component.bounds)) {
                    return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                }
                if (component.constructionOrder < previousConstructionOrder) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                previousConstructionOrder = component.constructionOrder;
                JsonObject stateObject = requiredObject(operation, "blockState");
                if (stateObject.size() > BridgeProtocol.MAX_BLOCK_STATE_PROPERTIES) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                Map<String, String> state = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : stateObject.entrySet()) {
                    if (!PROPERTY.matcher(entry.getKey()).matches() || !entry.getValue().isJsonPrimitive() ||
                            !entry.getValue().getAsJsonPrimitive().isString()) {
                        return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                    }
                    String value = entry.getValue().getAsString();
                    if (!STATE_VALUE.matcher(value).matches()) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                    state.put(entry.getKey(), value);
                }
                if (!blockSupport.hasValidState(blockId, state)) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
                operationCounts.put(componentId, operationCounts.getOrDefault(componentId, 0) + 1);
            }
            for (BuildPlanDocument.Component component : components) {
                if (operationCounts.getOrDefault(component.componentId, 0) < 1) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
            }
            return null;
        } catch (BridgeProtocolException error) {
            return error.getCode() == BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA ||
                    error.getCode() == BridgeProtocol.ErrorCode.BUILD_TOO_LARGE
                    ? error.getCode() : BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
        } catch (RuntimeException error) {
            return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN;
        }
    }

    private static void validateMetadata(JsonObject metadata) throws BridgeProtocolException {
        BridgeProtocolCodec.requireExactKeys(metadata,
                "schemaVersion", "sourceRequestId", "providerId", "modelId", "title", "summary",
                "generatedAtEpochMillis", "dimensions", "intent");
        if (BridgeProtocolCodec.requiredInt(metadata, "schemaVersion") != BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA);
        }
        BridgeProtocolCodec.requiredString(metadata, "sourceRequestId", 128);
        BridgeProtocolCodec.requiredString(metadata, "providerId", 64);
        BridgeProtocolCodec.requiredString(metadata, "modelId", 200);
        String title = BridgeProtocolCodec.requiredString(metadata, "title", 100);
        String summary = BridgeProtocolCodec.requiredString(metadata, "summary", 1200);
        if (title.trim().isEmpty() || summary.trim().isEmpty() ||
                BridgeProtocolCodec.requiredLong(metadata, "generatedAtEpochMillis") <= 0L) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN);
        }
    }

    private static void validateIntent(JsonObject intent) throws BridgeProtocolException {
        BridgeProtocolCodec.requireExactKeys(intent,
                "structureType", "style", "approximateScale", "floorCount", "rooms", "specialFeatures",
                "materials", "environment", "constraints");
        validateRequiredText(BridgeProtocolCodec.requiredString(intent, "structureType", 100), 100);
        validateNullableText(intent, "style", 100);
        validateNullableText(intent, "approximateScale", 100);
        validateNullableText(intent, "environment", 100);
        JsonElement floorCount = intent.get("floorCount");
        if (floorCount == null) throw invalidPlan();
        if (!floorCount.isJsonNull()) {
            JsonObject single = new JsonObject();
            single.add("value", floorCount);
            int count = BridgeProtocolCodec.requiredInt(single, "value");
            if (count < 1 || count > BridgeProtocol.MAX_DIMENSION_HEIGHT) throw invalidPlan();
        }
        validateStringList(intent, "rooms");
        validateStringList(intent, "specialFeatures");
        validateStringList(intent, "materials");
        validateStringList(intent, "constraints");
    }

    private static void validateNullableText(JsonObject object, String key, int maxLength) throws BridgeProtocolException {
        String value = BridgeProtocolCodec.nullableString(object, key, maxLength);
        if (value != null) validateRequiredText(value, maxLength);
    }

    private static void validateRequiredText(String value, int maxLength) throws BridgeProtocolException {
        if (value.trim().isEmpty() || value.length() > maxLength) throw invalidPlan();
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) throw invalidPlan();
        }
    }

    private static void validateStringList(JsonObject object, String key) throws BridgeProtocolException {
        JsonArray values = requiredArray(object, key);
        if (values.size() > 24) throw invalidPlan();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalidPlan();
            validateRequiredText(value.getAsString(), 80);
        }
    }

    private static int dimension(JsonObject dimensions, String key, int max) throws BridgeProtocolException {
        int value = BridgeProtocolCodec.requiredInt(dimensions, key);
        if (value < 1 || value > max) throw new BridgeProtocolException(BridgeProtocol.ErrorCode.BUILD_TOO_LARGE);
        return value;
    }

    private static BuildPlanDocument.Bounds parseBounds(JsonObject bounds, int width, int height, int depth)
            throws BridgeProtocolException {
        JsonObject origin = requiredObject(bounds, "origin");
        JsonObject dimensions = requiredObject(bounds, "dimensions");
        BridgeProtocolCodec.requireExactKeys(origin, "x", "y", "z");
        BridgeProtocolCodec.requireExactKeys(dimensions, "width", "height", "depth");
        BuildPlanDocument.Bounds result = new BuildPlanDocument.Bounds();
        result.origin = new BuildPlanDocument.Position();
        result.origin.x = BridgeProtocolCodec.requiredInt(origin, "x");
        result.origin.y = BridgeProtocolCodec.requiredInt(origin, "y");
        result.origin.z = BridgeProtocolCodec.requiredInt(origin, "z");
        result.dimensions = new BuildPlanDocument.Dimensions();
        result.dimensions.width = dimension(dimensions, "width", BridgeProtocol.MAX_DIMENSION_WIDTH);
        result.dimensions.height = dimension(dimensions, "height", BridgeProtocol.MAX_DIMENSION_HEIGHT);
        result.dimensions.depth = dimension(dimensions, "depth", BridgeProtocol.MAX_DIMENSION_DEPTH);
        if (!boundsFitsWorld(result, width, height, depth)) throw invalidPlan();
        return result;
    }

    private static boolean boundsFitsWorld(BuildPlanDocument.Bounds bounds, int width, int height, int depth) {
        BuildPlanDocument.Position origin = bounds.origin;
        BuildPlanDocument.Dimensions size = bounds.dimensions;
        return origin.x >= 0 && origin.y >= 0 && origin.z >= 0 &&
                (long) origin.x + size.width <= width && (long) origin.y + size.height <= height &&
                (long) origin.z + size.depth <= depth;
    }

    private static boolean boundsWithin(BuildPlanDocument.Bounds child, BuildPlanDocument.Bounds parent) {
        return child.origin.x >= parent.origin.x && child.origin.y >= parent.origin.y && child.origin.z >= parent.origin.z &&
                (long) child.origin.x + child.dimensions.width <= (long) parent.origin.x + parent.dimensions.width &&
                (long) child.origin.y + child.dimensions.height <= (long) parent.origin.y + parent.dimensions.height &&
                (long) child.origin.z + child.dimensions.depth <= (long) parent.origin.z + parent.dimensions.depth;
    }

    private static boolean inside(BuildPlanDocument.Position point, BuildPlanDocument.Bounds bounds) {
        return point.x >= bounds.origin.x && point.y >= bounds.origin.y && point.z >= bounds.origin.z &&
                (long) point.x < (long) bounds.origin.x + bounds.dimensions.width &&
                (long) point.y < (long) bounds.origin.y + bounds.dimensions.height &&
                (long) point.z < (long) bounds.origin.z + bounds.dimensions.depth;
    }

    private static boolean hasParentCycle(Map<String, BuildPlanDocument.Component> components) {
        for (String start : components.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!seen.add(current)) return true;
                BuildPlanDocument.Component component = components.get(current);
                current = component == null ? null : component.parentComponentId;
            }
        }
        return false;
    }

    private static JsonObject requiredObject(JsonObject object, String key) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) throw invalidPlan();
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String key) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) throw invalidPlan();
        return value.getAsJsonArray();
    }

    private static BridgeProtocol.ErrorCode validateOrigin(JsonObject origin) throws BridgeProtocolException {
        BridgeProtocolCodec.requireExactKeys(origin, "kind", "dimensionId", "worldSessionId", "position");
        String kind = BridgeProtocolCodec.requiredString(origin, "kind", 32);
        String dimension = BridgeProtocolCodec.requiredString(origin, "dimensionId", 130);
        if (!ORIGIN_KINDS.contains(kind) || !DIMENSION_ID.matcher(dimension).matches()) {
            return BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN;
        }
        String worldSession = BridgeProtocolCodec.nullableString(origin, "worldSessionId", 128);
        if (worldSession != null && !worldSession.matches("[A-Za-z0-9_-]{1,128}")) {
            return BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN;
        }
        JsonElement positionElement = origin.get("position");
        if (positionElement == null) return BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST;
        boolean needsPosition = "PLAYER_SELECTED".equals(kind) || "EXPLICIT".equals(kind);
        if (needsPosition != !positionElement.isJsonNull()) return BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN;
        if (!positionElement.isJsonNull()) {
            JsonObject position = positionElement.getAsJsonObject();
            BridgeProtocolCodec.requireExactKeys(position, "x", "y", "z");
            int x = BridgeProtocolCodec.requiredInt(position, "x");
            int y = BridgeProtocolCodec.requiredInt(position, "y");
            int z = BridgeProtocolCodec.requiredInt(position, "z");
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000 || y < -2048 || y > 2048) {
                return BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN;
            }
        }
        return null;
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static BridgeProtocolException invalidPlan() {
        return new BridgeProtocolException(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN);
    }
}
