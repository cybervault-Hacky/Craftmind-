package com.craftmind.bridge.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict bounded JSON codec shared by the Android and Fabric implementations. */
public final class BridgeProtocolCodec {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Pattern MESSAGE_TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private BridgeProtocolCodec() { }

    public static JsonElement parseJson(byte[] body, int maximumBytes) throws BridgeProtocolException {
        if (body == null || body.length == 0 || body.length > maximumBytes) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.PAYLOAD_TOO_LARGE);
        }
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(body), decoder));
            reader.setLenient(false);
            int[] tokenCount = {0};
            JsonElement root = readStrictValue(reader, 0, tokenCount);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw malformed();
            return root;
        } catch (BridgeProtocolException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE, error);
        }
    }

    public static BridgeEnvelope parseEnvelope(byte[] body, int maximumBytes) throws BridgeProtocolException {
        JsonElement root = parseJson(body, maximumBytes);
        if (!root.isJsonObject()) throw malformed();
        JsonObject object = root.getAsJsonObject();
        requireExactKeys(object, "protocolVersion", "messageType", "requestId", "timestampEpochMillis", "correlationId", "payload");
        int version = requiredInt(object, "protocolVersion");
        String messageType = requiredString(object, "messageType", 64);
        String requestId = requiredString(object, "requestId", BridgeProtocol.MAX_REQUEST_ID_LENGTH);
        long timestamp = requiredLong(object, "timestampEpochMillis");
        JsonElement correlation = object.get("correlationId");
        String correlationId = correlation == null || correlation.isJsonNull() ? null : boundedString(correlation, BridgeProtocol.MAX_REQUEST_ID_LENGTH);
        JsonElement payloadElement = object.get("payload");
        if (payloadElement == null || !payloadElement.isJsonObject()) throw malformed();
        if (!MESSAGE_TYPE.matcher(messageType).matches()) throw malformed();
        if (!UUID_PATTERN.matcher(requestId).matches() || (correlationId != null && !UUID_PATTERN.matcher(correlationId).matches())) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.INVALID_REQUEST_ID);
        }
        if (timestamp <= 0L) throw new BridgeProtocolException(BridgeProtocol.ErrorCode.INVALID_TIMESTAMP);
        if (version != BridgeProtocol.VERSION) throw new BridgeProtocolException(BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL);
        return new BridgeEnvelope(version, messageType, requestId, timestamp, correlationId, payloadElement.getAsJsonObject());
    }

    public static BridgeEnvelope newEnvelope(
            String messageType,
            String requestId,
            long timestampEpochMillis,
            String correlationId,
            JsonObject payload) throws BridgeProtocolException {
        if (messageType == null || !MESSAGE_TYPE.matcher(messageType).matches() ||
                requestId == null || !UUID_PATTERN.matcher(requestId).matches() ||
                (correlationId != null && !UUID_PATTERN.matcher(correlationId).matches()) ||
                timestampEpochMillis <= 0L || payload == null) {
            throw malformed();
        }
        return new BridgeEnvelope(BridgeProtocol.VERSION, messageType, requestId, timestampEpochMillis, correlationId, payload);
    }

    public static BridgeEnvelope newEnvelope(String messageType, JsonObject payload, String correlationId)
            throws BridgeProtocolException {
        return newEnvelope(messageType, UUID.randomUUID().toString(), System.currentTimeMillis(), correlationId, payload);
    }

    public static byte[] writeEnvelope(BridgeEnvelope envelope) {
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonObject payload(Object value) {
        JsonElement element = GSON.toJsonTree(value);
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Protocol payload must be an object");
        return element.getAsJsonObject();
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(JsonElement element, Class<T> type) {
        return GSON.fromJson(element, type);
    }

    public static void requireExactKeys(JsonObject object, String... expected) throws BridgeProtocolException {
        Set<String> expectedSet = new HashSet<>(Arrays.asList(expected));
        if (object == null || !object.keySet().equals(expectedSet)) throw malformed();
    }

    public static String requiredString(JsonObject object, String key, int maxLength) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw malformed();
        String text = value.getAsString();
        if (text.isEmpty() || text.length() > maxLength) throw malformed();
        return text;
    }

    public static String nullableString(JsonObject object, String key, int maxLength) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null) throw malformed();
        if (value.isJsonNull()) return null;
        return boundedString(value, maxLength);
    }

    public static int requiredInt(JsonObject object, String key) throws BridgeProtocolException {
        long value = requiredLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw malformed();
        return (int) value;
    }

    public static long requiredLong(JsonObject object, String key) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw malformed();
        try {
            BigDecimal decimal = value.getAsBigDecimal();
            long result = decimal.longValueExact();
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            throw malformed();
        }
    }

    public static boolean requiredBoolean(JsonObject object, String key) throws BridgeProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw malformed();
        return value.getAsBoolean();
    }

    private static String boundedString(JsonElement value, int maxLength) throws BridgeProtocolException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw malformed();
        String text = value.getAsString();
        if (text.length() > maxLength) throw malformed();
        return text;
    }

    private static JsonElement readStrictValue(JsonReader reader, int depth, int[] tokens)
            throws IOException, BridgeProtocolException {
        if (++tokens[0] > BridgeProtocol.MAX_JSON_TOKENS || depth > BridgeProtocol.MAX_PAYLOAD_DEPTH) throw malformed();
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT: {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw malformed();
                    object.add(name, readStrictValue(reader, depth + 1, tokens));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                reader.beginArray();
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                while (reader.hasNext()) array.add(readStrictValue(reader, depth + 1, tokens));
                reader.endArray();
                return array;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER: {
                String number = reader.nextString();
                try {
                    return new JsonPrimitive(new BigDecimal(number));
                } catch (NumberFormatException error) {
                    throw malformed();
                }
            }
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw malformed();
        }
    }

    private static BridgeProtocolException malformed() {
        return new BridgeProtocolException(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE);
    }
}
