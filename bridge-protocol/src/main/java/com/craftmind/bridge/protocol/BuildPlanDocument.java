package com.craftmind.bridge.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire form of the already-reviewed CraftMind BuildPlan schema v2. */
public final class BuildPlanDocument {
    public String planId;
    public Metadata metadata;
    public String originStrategy;
    public List<Component> components = new ArrayList<>();
    public List<Operation> operations = new ArrayList<>();
    public String status;

    public static final class Metadata {
        public int schemaVersion;
        public String sourceRequestId;
        public String providerId;
        public String modelId;
        public String title;
        public String summary;
        public long generatedAtEpochMillis;
        public Dimensions dimensions;
        public Intent intent;
    }

    public static final class Intent {
        public String structureType;
        public String style;
        public String approximateScale;
        public Integer floorCount;
        public List<String> rooms = new ArrayList<>();
        public List<String> specialFeatures = new ArrayList<>();
        public List<String> materials = new ArrayList<>();
        public String environment;
        public List<String> constraints = new ArrayList<>();
    }

    public static final class Component {
        public String componentId;
        public String name;
        public String purpose;
        public Bounds bounds;
        public String type;
        public String parentComponentId;
        public int constructionOrder;
    }

    public static final class Bounds {
        public Position origin;
        public Dimensions dimensions;
    }

    public static final class Dimensions {
        public int width;
        public int height;
        public int depth;
    }

    public static final class Position {
        public int x;
        public int y;
        public int z;
    }

    public static final class Operation {
        public int sequence;
        public String kind;
        public String blockId;
        public Position position;
        public Map<String, String> blockState = new LinkedHashMap<>();
        public String componentId;
    }
}
