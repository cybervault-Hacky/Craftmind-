package com.craftmind.bridge.fabric;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Small bounded fixed-window limiter for private LAN requests. */
final class BridgeRateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_ADDRESSES = 256;
    private final Map<String, Bucket> buckets = new HashMap<>();

    synchronized boolean allow(InetAddress address, String route, long nowMillis) {
        String key = address.getHostAddress() + '|' + route;
        int maximum;
        if ("/v1/pair".equals(route)) maximum = 5;
        else if ("/v1/executions/status".equals(route)) maximum = 40;
        else if ("/v1/executions/cancel".equals(route)) maximum = 12;
        else if (route.startsWith("/v1/executions")) maximum = 6;
        else maximum = 30;
        Bucket bucket = buckets.get(key);
        if (bucket == null || nowMillis - bucket.windowStartMillis >= WINDOW_MILLIS || nowMillis < bucket.windowStartMillis) {
            if (buckets.size() >= MAX_TRACKED_ADDRESSES) removeOneBucket();
            bucket = new Bucket(nowMillis, 0);
            buckets.put(key, bucket);
        }
        if (bucket.count >= maximum) return false;
        bucket.count++;
        return true;
    }

    private void removeOneBucket() {
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class Bucket {
        private final long windowStartMillis;
        private int count;
        private Bucket(long windowStartMillis, int count) {
            this.windowStartMillis = windowStartMillis;
            this.count = count;
        }
    }
}
