package com.craftmind.bridge.fabric;

import java.util.List;

public interface TrustedClientRepository {
    TrustedBridgeClient find(String clientId);
    List<TrustedBridgeClient> all();
    void add(TrustedBridgeClient client) throws Exception;
    boolean remove(String clientId) throws Exception;
}
