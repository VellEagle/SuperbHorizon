package com.atsuishio.superbhorizon;

import net.minecraftforge.common.ForgeConfigSpec;

public class SuperbHorizonConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue TICK_INTERVAL;
    public static final ForgeConfigSpec.IntValue SAVE_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue POSITION_EPSILON;
    public static final ForgeConfigSpec.DoubleValue ROTATION_EPSILON;
    public static final ForgeConfigSpec.DoubleValue MAX_SYNC_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue GHOST_SWITCH_DISTANCE;
    public static final ForgeConfigSpec.IntValue STALE_TICKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_POLYMESH;
    public static final ForgeConfigSpec.BooleanValue PREFER_ANIMATED_ENTITY_FALLBACK;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("sync");
        TICK_INTERVAL = builder
                .comment("How often vehicle ghosts are considered for network updates, in server ticks.")
                .defineInRange("tickInterval", 2, 1, 20);
        SAVE_INTERVAL = builder
                .comment("How often moving vehicle ghost data is marked dirty for world saving, in server ticks.")
                .defineInRange("saveInterval", 20, 1, 200);
        POSITION_EPSILON = builder
                .comment("Minimum position change before a tick packet is sent.")
                .defineInRange("positionEpsilon", 0.01D, 0.0D, 1.0D);
        ROTATION_EPSILON = builder
                .comment("Minimum rotation change in degrees before a tick packet is sent.")
                .defineInRange("rotationEpsilon", 0.25D, 0.0D, 10.0D);
        MAX_SYNC_DISTANCE = builder
                .comment("Maximum distance from a player for ghost updates. Set to 0 to disable distance filtering.")
                .defineInRange("maxSyncDistance", 4096.0D, 0.0D, 32000.0D);
        builder.pop();

        builder.push("render");
        GHOST_SWITCH_DISTANCE = builder
                .comment("Distance where the ghost renderer takes over from the normal entity renderer.")
                .defineInRange("ghostSwitchDistance", 96.0D, 16.0D, 1024.0D);
        STALE_TICKS = builder
                .comment("Hide a ghost if no update has arrived for this many client ticks. Set to 0 to disable.")
                .defineInRange("staleTicks", 200, 0, 1200);
        ENABLE_POLYMESH = builder
                .comment("Enable PolyMesh reflection renderer when the optional mesh loader is present.")
                .define("enablePolyMesh", true);
        PREFER_ANIMATED_ENTITY_FALLBACK = builder
                .comment("Prefer Superb Warfare's entity renderer for animated distant ghosts when animation state is available.")
                .define("preferAnimatedEntityFallback", true);
        builder.pop();

        SPEC = builder.build();
    }

    private SuperbHorizonConfig() {
    }
}
