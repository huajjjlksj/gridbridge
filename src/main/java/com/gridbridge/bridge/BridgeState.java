package com.gridbridge.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared bridge state between the CEE simulation side (CutOffSwitchDevice mixin)
 * and the PowerGrid side (BridgeBlockEntity).
 */
public class BridgeState {
    /** Voltage of the CEE nodes (ground-referenced), written by the device mixin after CEE postTick. */
    public volatile double[] vCee = new double[4];
    /** Voltage of the PowerGrid terminal nodes (after PG solve), written by the BE tick. */
    public volatile double[] vPgNet = new double[4];
    /** Current injected at each node by the PowerGrid voltage source, written by the BE tick. */
    public volatile double[] pgCurrent = new double[4];
    /** Whether the CEE switch is closed. */
    public volatile boolean closed;
    /** Whether PowerGrid wires are attached. */
    public volatile boolean connected;
    /** Whether the bridge BE has been attached and initialized. */
    public volatile boolean bridgeReady;
    /** PG 侧供电有效：闸刀端子 0 和端子 1 都有电线连接（电源正负极都接上）。 */
    public volatile boolean pgPowered;
    /** CEE 侧注入功率（device 记录，BE 用于 PG 侧等效负载反馈），Watts。 */
    public volatile double pgPowerInjected;
    /** PG 侧等效负载实际输出功率（真实能量），Watts。 */
    public volatile double pgPowerOut;
    /** PG 侧开关支路电流（CEE->PG 等效负载用），Amperes。 */
    public volatile double switchCurrent;

    private static final ConcurrentHashMap<Key, BridgeState> MAP = new ConcurrentHashMap<>();

    public record Key(ResourceKey<Level> dim, long pos) {}

    public static BridgeState get(Level level, BlockPos pos) {
        if (level.isClientSide)
            return null;
        return MAP.computeIfAbsent(new Key(level.dimension(), pos.asLong()), k -> new BridgeState());
    }

    public static void remove(Level level, BlockPos pos) {
        if (!level.isClientSide)
            MAP.remove(new Key(level.dimension(), pos.asLong()));
    }
}