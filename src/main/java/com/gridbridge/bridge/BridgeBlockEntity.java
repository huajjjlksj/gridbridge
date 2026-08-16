package com.gridbridge.bridge;

import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock;
import com.gridbridge.GridBridge;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;

import java.util.Arrays;
import java.util.List;

/**
 * Attached to the CEE double switch block. Exposes the 4 CEE node positions as PowerGrid
 * terminals. 闸刀在 PG 侧是"双刀开关"：
 *  - T0-T2、T1-T3 成对导通（SwitchedWire，CEE double_switch 的 resistor(i, lines+i) 语义）
 *  - 对地转换电压源仅在 CEE 侧电压主导时低阻注入（CEE -> PG）；PG 主导时高阻开路，
 *    绝不与 PG 侧电源（如创造电压源）形成短路。
 *  - CEE 侧转换由 CutOffSwitchDeviceMixin 完成：CEE->PG 用 Norton 抽电，PG->CEE 用
 *    voltageSource 注入。
 */
public class BridgeBlockEntity extends SmartBlockEntity implements IElectric, IElectricEntity {
    public static final int TERMINALS = 4;
    /** 开关导通电阻，Ohms。 */
    public static final float SWITCH_RESISTANCE = 0.01f;
    /** CEE 线间电压源内阻（T0-T1），Ohms —— 适中：能供电且不与 PG 电源硬短路。 */
    public static final float LINE_SOURCE_RESISTANCE = 0.1f;
    /** CEE 主导时对地转换源内阻，Ohms。 */
    public static final float SOURCE_RESISTANCE = 1.0f;
    /** PG 主导时对地源开路电阻，Ohms。 */
    public static final float OPEN_RESISTANCE = 1_000_000.0f;
    /** 开关支路过流熔断阈值，Amperes（同根线短路：电源正负极接同一根导通线）。 */
    public static final double OVERCURRENT_TRIP = 100.0;
    /** 过流复位阈值（迟滞），Amperes。 */
    public static final double OVERCURRENT_RESET = 20.0;

    private static final BehaviourType<CleanupBehaviour> CLEANUP_TYPE = new BehaviourType<>();

    private ElectricBehaviour electricBehaviour;
    private VoltageSourceCoupling[] couplings;
    private FloatingNode[] terminalNodes;
    private SwitchedWire switchWire0;
    private SwitchedWire switchWire1;
    /** CEE 线间电压源：T0-T1 之间 = CEE 线1-线2 差分（vCee[0]-vCee[1]），CEE -> PG 供电。 */
    private VoltageSourceCoupling lineSource;
    /** T0-T1 动态等效负载：让 PG 电源真实供电给 CEE 设备（能量守恒，R 随 CEE 需求反馈）。 */
    private SwitchedWire loadWire;
    /** pgPowered 防抖计数：连续 N tick 同状态才切换，消除"有效/无效"跳动。 */
    private int pgPoweredStableTicks = 0;
    private boolean lastPgPoweredRaw = false;
    private static final int PG_POWERED_DEBOUNCE = 20;
    private double lastLineVoltage;
    private boolean lastClosed;
    private boolean removed;
    private boolean tripped0;
    private boolean tripped1;
    private int logTick;

    public BridgeBlockEntity(BlockPos pos, BlockState state) {
        this(GridBridge.BRIDGE_BE.get(), pos, state);
    }

    public BridgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        electricBehaviour = new ElectricBehaviour(this);
        behaviours.add(electricBehaviour);
        // BE 被移除（闸刀破坏/爆炸/区块卸载）时连带断开 PG 电线并清理状态。
        behaviours.add(new CleanupBehaviour(this));
    }

    // ================= IElectric =================

    @Override
    public int terminalCount() {
        return TERMINALS;
    }

    @Override
    public boolean accepts(ItemStack wireStack) {
        return true;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        if (index < 0 || index >= TERMINALS)
            return null;
        if (level == null)
            return null;
        if (!(state.getBlock() instanceof CutOffSwitchBlock csb))
            return null;
        Vec3 p = csb.getNodePosition(level, worldPosition, state, index);
        if (p == null)
            return null;
        double px = p.x * 16, py = p.y * 16, pz = p.z * 16;
        return new TerminalBoundingBox(Component.literal("Grid " + index),
                px - 2.5, py - 2.5, pz - 2.5, px + 2.5, py + 2.5, pz + 2.5);
    }

    // ================= IElectricEntity =================

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        // 构造期间（super 链中）addBehaviours -> ElectricBehaviour -> buildCircuit 就会调用，
        // 字段初始化器尚未执行，数组必须惰性创建。
        if (couplings == null)
            couplings = new VoltageSourceCoupling[TERMINALS];
        if (terminalNodes == null)
            terminalNodes = new FloatingNode[TERMINALS];
        builder.setTerminalCount(TERMINALS);
        for (int i = 0; i < TERMINALS; i++)
            terminalNodes[i] = builder.terminalNode(i);

        // 双刀开关导通语义：T0-T2（线1）、T1-T3（线2）
        switchWire0 = new SwitchedWire(SWITCH_RESISTANCE, terminalNodes[0], terminalNodes[2]);
        switchWire1 = new SwitchedWire(SWITCH_RESISTANCE, terminalNodes[1], terminalNodes[3]);
        builder.add(switchWire0);
        builder.add(switchWire1);
        // CEE 线间电压源：T0-T1 之间注入 CEE 线1-线2 差分电压（节点间源，不涉及"地"，
        // 不与 PG 网络中电压源的负极锚点冲突）。CEE 电池 250V -> 源 -> 开关 -> 电动机。
        lineSource = new VoltageSourceCoupling(terminalNodes[0], terminalNodes[1], LINE_SOURCE_RESISTANCE);
        builder.add(lineSource);
        // PG 侧动态等效负载（常通）：R = vLineP^2 / P_cee_in，让创造电源看到负载并真实供能。
        loadWire = new SwitchedWire(1000.0f, terminalNodes[0], terminalNodes[1], true);
        builder.add(loadWire);

        // 注意：不再创建"对地电压源"（negative=null）——它以全局地为参考，而 PG 网络
        // 的实际参考是网络中电压源的负极（锚点，如创造电源的负极）。对地源低阻时会
        // 与锚点冲突：电压错乱（创造电源显示 14V）并产生 250A 级大电流烧毁电线。
        // PG 侧只保留双刀开关（T0-T2 / T1-T3），CEE 电压经 CutOffSwitchDeviceMixin
        // 注入 CEE 节点（PG -> CEE），反向由 Norton 抽电（CEE -> PG）。
    }

    // ================= ticking =================

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || removed)
            return;

        if (!(level.getBlockState(worldPosition).getBlock() instanceof CutOffSwitchBlock)) {
            selfDestroy();
            return;
        }

        BridgeState st = BridgeState.get(level, worldPosition);
        if (st == null)
            return;

        boolean closed = level.getBlockState(worldPosition).getValue(CutOffSwitchBlock.CLOSED);
        boolean connected = electricBehaviour != null && !electricBehaviour.getConnections().isEmpty();

        // 防 CEE 求解异常值锁死：CEE 节点电压超出合理范围（>5000V）即复位，
        // 避免异常 vCee 经反馈回路放大（如 Norton 误触发导致的 778kV 锁死）。
        for (int i = 0; i < TERMINALS; i++) {
            if (Math.abs(st.vCee[i]) > 5000.0) {
                GridBridge.LOGGER.warn("[GridBridge] CEE 节点电压异常 {}V @ {} 节点{}，已复位", st.vCee[i], worldPosition, i);
                st.vCee[i] = 0;
            }
        }

        if (closed != lastClosed) {
            lastClosed = closed;
            st.closed = closed;
            GridBridge.LOGGER.info("[GridBridge] 开关状态 @ {}: {} ({}导线)",
                    worldPosition, closed ? "闭合" : "断开", connected ? "已接" : "未接");
            electricBehaviour.rebuildCircuit(false);
        }
        if (connected != st.connected) {
            st.connected = connected;
            GridBridge.LOGGER.info("[GridBridge] 导线连接 @ {}: {}", worldPosition, connected ? "已连接" : "已断开");
        }

        // 物理严格：PG 侧供电有效 = 端子 0 与端子 1 都有电线（电源正负极都接上）。
        // 只接正极（负极悬空）不算供电 -> CEE 侧不注入 -> 电动机不转。
        boolean hasT0 = false, hasT1 = false;
        if (electricBehaviour != null) {
            for (var ep : electricBehaviour.getConnections().keySet()) {
                int t = ep.getTerminal();
                if (t == 0) hasT0 = true;
                else if (t == 1) hasT1 = true;
            }
        }
        boolean pgPoweredRaw = hasT0 && hasT1;
        // 防抖：连续 20 tick 同状态才切换（消除连接检测跳动 -> 消除 lineSource 反复横跳 -> 消除烧线循环）
        if (pgPoweredRaw == lastPgPoweredRaw) {
            if (++pgPoweredStableTicks >= PG_POWERED_DEBOUNCE && pgPoweredRaw != st.pgPowered) {
                st.pgPowered = pgPoweredRaw;
                GridBridge.LOGGER.info("[GridBridge] PG 供电状态 @ {}: {}", worldPosition, pgPoweredRaw ? "有效（正负极已接）" : "无效（负极未接）");
            }
        } else {
            lastPgPoweredRaw = pgPoweredRaw;
            pgPoweredStableTicks = 0;
        }

        // CEE 线间电压源（短路根治——lineSource 绝不当"正负对接"的旁路）：
        //  - PG 网络存在"外部源"电压（创造电源/发电机）且与 CEE 电压明显不同
        //    （|vLineP - vLineC| > 10V）-> lineSource 彻底开路（1MΩ）。
        //    否则 lineSource（1Ω 电压源）跨在电源正负极 T0-T1 之间 = 低阻旁路，
        //    电流走捷径（250V/1Ω = 250A）绕过负载 -> 烧线（有电器也短路）。
        //  - CEE -> PG 供电：vLineP ≈ vLineC（lineSource 自己的电压，差≈0）-> 低阻，
        //    此时 lineSource 是"唯一源"（给 PG 负载供电，不是旁路），电流由负载决定。
        //  - 两侧都无电 -> 高阻（绝不 0V 低阻短接）。
        if (lineSource != null) {
            double vLineC = st.vCee[0] - st.vCee[1];
            if (st.pgPowered) {
                // PG 有自己的电源（创造电源）-> lineSource 完全开路（绝不当正负对接的旁路）
                lineSource.setResistance(1_000_000f);
            } else if (Math.abs(vLineC) > 1.0) {
                // CEE -> PG 供电（lineSource 作为唯一源，内阻 0.1Ω 压降极小 -> 电压 1:1）
                lineSource.setResistance(LINE_SOURCE_RESISTANCE);
                double vLine = Math.max(-2000.0, Math.min(2000.0, vLineC));
                vLine = lastLineVoltage * 0.7 + vLine * 0.3;
                lastLineVoltage = vLine;
                lineSource.setVoltage(vLine);
            } else {
                lineSource.setResistance(1_000_000f);
            }
        }

        // 开关通断 + 过流保护（熔断）：检测"同根线"短路（如电源正负极接到同一根
        // 导通线的两端，T0-T2 或 T1-T3），过流立即断开该线防烧毁，回落自动复位。
        if (switchWire0 != null) {
            double i0 = switchWire0.current();
            double i1 = switchWire1.current();
            if (Math.abs(i0) > OVERCURRENT_TRIP) {
                if (!tripped0)
                    GridBridge.LOGGER.error("[GridBridge] 过流熔断 @ {} 线1(T0-T2): 电流 {}A，疑似同根线短路（正负极接同一根线），已断开", worldPosition, i0);
                tripped0 = true;
            } else if (Math.abs(i0) < OVERCURRENT_RESET) {
                tripped0 = false;
            }
            if (Math.abs(i1) > OVERCURRENT_TRIP) {
                if (!tripped1)
                    GridBridge.LOGGER.error("[GridBridge] 过流熔断 @ {} 线2(T1-T3): 电流 {}A，疑似同根线短路（正负极接同一根线），已断开", worldPosition, i1);
                tripped1 = true;
            } else if (Math.abs(i1) < OVERCURRENT_RESET) {
                tripped1 = false;
            }
            switchWire0.setState(closed && !tripped0);
            switchWire1.setState(closed && !tripped1);
        }

        if (closed && switchWire0 != null) {
            // 开关支路电流（PG 侧设备电流，CEE->PG 等效负载用）
            double i0 = switchWire0.current();
            double i1 = switchWire1.current();
            st.switchCurrent = Math.abs(i0);
            // PG 侧动态等效负载：R = vLineC^2 / P_cee（真实 CEE 负载功率，postTick 反馈）。
            // 灯泡场景 P=62.5W -> R=1000Ω -> PG 电流 0.25A（电线安全）；不再 1000W 保底。
            if (loadWire != null) {
                double vLineC = st.vCee[0] - st.vCee[1];
                double vLineP = st.vPgNet[0] - st.vPgNet[1];
                double pIn = st.pgPowerInjected > 0.5 ? st.pgPowerInjected : 10.0;
                pIn = Math.min(pIn, 100000.0);   // clamp：防异常放大导致 loadWire 电阻过小
                double rLoad;
                if (Math.abs(vLineC) > 1.0) {
                    rLoad = vLineC * vLineC / pIn;
                    rLoad = Math.max(1.0, Math.min(1000000.0, rLoad));
                } else {
                    rLoad = 1000000.0;  // CEE 无电：负载开路，不干扰 PG
                }
                loadWire.setResistance(rLoad);
                double iLoad = loadWire.current();
                // pgPowerOut = lineSource 输出功率（CEE -> PG 方向的真实功率，PG 设备消耗）；
                // PG 有电（创造电源）时 lineSource 高阻 current≈0 -> 等效负载不挂（无虚假消耗）。
                st.pgPowerOut = Math.abs(vLineP) * Math.abs(lineSource.getCurrent());
                // pgCurrent = 等效负载电流（PG->CEE 注入功率用，真实能量）
                st.pgCurrent[0] = iLoad;
                st.pgCurrent[1] = iLoad;
                st.pgCurrent[2] = iLoad;
                st.pgCurrent[3] = iLoad;
            } else {
                st.pgCurrent[0] = i0;
                st.pgCurrent[2] = i0;
                st.pgCurrent[1] = i1;
                st.pgCurrent[3] = i1;
            }
            // 指数平滑：抑制跨仿真器（PG <-> CEE）反馈回路振荡
            for (int i = 0; i < TERMINALS; i++) {
                st.vPgNet[i] = st.vPgNet[i] * 0.7 + terminalNodes[i].getStateValue() * 0.3;
            }
        } else {
            for (int i = 0; i < TERMINALS; i++) {
                st.pgCurrent[i] = 0;
                st.vPgNet[i] = 0;
            }
        }

        if (++logTick % 40 == 0) {
            GridBridge.LOGGER.info("[GridBridge] 采样 @ {}: closed={} connected={} vCee={} vPgNet={} pgCurrent={}",
                    worldPosition, closed, connected,
                    Arrays.toString(st.vCee), Arrays.toString(st.vPgNet), Arrays.toString(st.pgCurrent));
        }
    }

    private void selfDestroy() {
        GridBridge.LOGGER.info("[GridBridge] BE 自毁 @ {}: 方块已不再是隔离开关", worldPosition);
        removed = true;
        if (electricBehaviour != null)
            electricBehaviour.remove();
        BridgeState st = BridgeState.get(level, worldPosition);
        if (st != null)
            st.bridgeReady = false;
        BridgeState.remove(level, worldPosition);
        level.removeBlockEntity(worldPosition);
    }

    @Override
    public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide) {
            lastClosed = level.getBlockState(worldPosition).getValue(CutOffSwitchBlock.CLOSED);
            BridgeState st = BridgeState.get(level, worldPosition);
            if (st != null) {
                st.closed = lastClosed;
                st.bridgeReady = true;
                GridBridge.LOGGER.info("[GridBridge] BE 初始化 @ {}: bridgeReady，初始状态 closed={}",
                        worldPosition, lastClosed);
            }
        }
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (isRemoved()) {
            BridgeState st = BridgeState.get(level, worldPosition);
            if (st != null)
                st.bridgeReady = false;
            BridgeState.remove(level, worldPosition);
        }
    }

    private class CleanupBehaviour extends BlockEntityBehaviour {
        CleanupBehaviour(SmartBlockEntity be) {
            super(be);
        }

        @Override
        public BehaviourType<?> getType() {
            return CLEANUP_TYPE;
        }

        @Override
        public void destroy() {
            if (electricBehaviour != null)
                electricBehaviour.remove();
            BridgeState.remove(level, worldPosition);
        }
    }
}