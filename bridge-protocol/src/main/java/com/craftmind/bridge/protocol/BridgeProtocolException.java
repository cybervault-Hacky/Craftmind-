package com.craftmind.bridge.protocol;

/** Safe protocol failure; never carries raw request bodies, credentials, or cryptographic material. */
public final class BridgeProtocolException extends Exception {
    private final BridgeProtocol.ErrorCode code;

    public BridgeProtocolException(BridgeProtocol.ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public BridgeProtocolException(BridgeProtocol.ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public BridgeProtocol.ErrorCode getCode() {
        return code;
    }
}
