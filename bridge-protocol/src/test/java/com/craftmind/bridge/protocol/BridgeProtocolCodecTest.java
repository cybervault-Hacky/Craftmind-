package com.craftmind.bridge.protocol;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class BridgeProtocolCodecTest {
    @Test
    public void roundTripsVersionedEnvelopeWithCorrelation() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        JsonObject payload = new JsonObject();
        payload.addProperty("challengeId", "challenge-1");
        BridgeEnvelope envelope = BridgeProtocolCodec.newEnvelope(
                "session.challenge", requestId, 1_700_000_000_000L, correlationId, payload);

        BridgeEnvelope parsed = BridgeProtocolCodec.parseEnvelope(
                BridgeProtocolCodec.writeEnvelope(envelope), BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);

        assertEquals(BridgeProtocol.VERSION, parsed.protocolVersion);
        assertEquals("session.challenge", parsed.messageType);
        assertEquals(requestId, parsed.requestId);
        assertEquals(correlationId, parsed.correlationId);
        assertEquals("challenge-1", parsed.payload.get("challengeId").getAsString());
    }

    @Test
    public void rejectsMalformedDuplicateKeysUnknownEnvelopeFieldsAndUnsupportedVersion() throws Exception {
        assertCode(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE,
                "{\"protocolVersion\":1,\"protocolVersion\":1,\"messageType\":\"bridge.info.request\",\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"timestampEpochMillis\":1,\"correlationId\":null,\"payload\":{}}",
                BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);
        assertCode(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE,
                "{\"protocolVersion\":1,\"messageType\":\"bridge.info.request\",\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"timestampEpochMillis\":1,\"correlationId\":null,\"payload\":{},\"extra\":true}",
                BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);
        assertCode(BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL,
                "{\"protocolVersion\":9,\"messageType\":\"bridge.info.request\",\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"timestampEpochMillis\":1,\"correlationId\":null,\"payload\":{}}",
                BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);
        assertCode(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE, "not-json", BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);
    }

    @Test
    public void rejectsOversizedEnvelopeBeforeJsonParsing() throws Exception {
        byte[] oversized = new byte[BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES + 1];
        assertCode(BridgeProtocol.ErrorCode.PAYLOAD_TOO_LARGE, oversized, BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES);
    }

    @Test
    public void tokenLimitCountsPrimitiveItemsInsideArrays() throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index <= BridgeProtocol.MAX_JSON_TOKENS; index++) {
            if (index > 0) json.append(',');
            json.append('0');
        }
        json.append(']');
        try {
            BridgeProtocolCodec.parseJson(json.toString().getBytes(StandardCharsets.UTF_8),
                    BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES);
        } catch (BridgeProtocolException error) {
            assertEquals(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE, error.getCode());
            return;
        }
        throw new AssertionError("Expected JSON token limit rejection");
    }

    private static void assertCode(BridgeProtocol.ErrorCode expected, String json, int maximumBytes) throws Exception {
        assertCode(expected, json.getBytes(StandardCharsets.UTF_8), maximumBytes);
    }

    private static void assertCode(BridgeProtocol.ErrorCode expected, byte[] json, int maximumBytes) throws Exception {
        try {
            BridgeProtocolCodec.parseEnvelope(json, maximumBytes);
        } catch (BridgeProtocolException error) {
            assertEquals(expected, error.getCode());
            return;
        }
        throw new AssertionError("Expected safe protocol rejection");
    }
}
