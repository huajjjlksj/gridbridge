package com.gridbridge.mixin;

import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock;
import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.BridgeCollector;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.gridbridge.GridBridge;
import com.gridbridge.bridge.BridgeBlockEntity;
import com.gridbridge.bridge.BridgeState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * 桥接 CEE 仿真与 PowerGrid：
 *  - preTick：PG -> CEE 电压注入（CEE 节点显示 PG 电压）+ CEE 侧等效负载（能量守恒：
 *    R = vLine^2/P_pg，CEE 电池放电 = PG 侧电动机消耗，两表读数正常）。
 *  - postTick：回写 CEE 节点电压到 BridgeState。
 * 不做 Norton 反向电流注入（CEE 孤立节点上会电压爆炸，见 778kV 事故）。
 */
@Mixin(CutOffSwitchDevice.class)
public class CutOffSwitchDeviceMixin {
    /** 虚拟地节点电导，Siemens（0.01 ohm）。 */
    private static final double GND_S = 100.0;
    /** 电压阈值：低于此值视为无电。 */
    private static final double V_MIN = 1.0;
    /** 双刀开关 = 2 线（4 节点）。 */
    private static final int DOUBLE_SWITCH_LINES = 2;

    @Shadow
    private int lines;

    /** 周期采样日志计数器。 */
    @Unique
    private int gridbridge$logTick;
    /** PG->CEE 注入功率平滑。 */
    @Unique
    private final float[] gridbridge$lastPower = new float[4];
    /** PG->CEE 注入电压平滑。 */
    @Unique
    private final float[] gridbridge$lastVInj = new float[4];
    /** CEE 侧等效负载电阻平滑。 */
    @Unique
    private double gridbridge$lastLoadR = -1;

    @Inject(method = "preTick", at = @At("TAIL"), remap = false)
    private void gridbridge$preTick(BridgeCollector bridges, CallbackInfo ci) {
        CutOffSwitchDevice self = (CutOffSwitchDevice) (Object) this;
        if (lines < DOUBLE_SWITCH_LINES)
            return;
        BlockPos pos = self.pos;
        BridgeState st = BridgeState.get(self.level, pos);
        if (st == null)
            return;
        if (!st.bridgeReady)
            attachBridge(self.level, pos);
        if (++gridbridge$logTick % 20 == 0)
            GridBridge.LOGGER.debug("[GridBridge] CEE preTick @ {}: connected={} closed={} pgCurrent={}",
                    pos, st.connected, st.closed, Arrays.toString(st.pgCurrent));
        if (!st.connected || !st.closed)
            return;
        int nodes = lines * 2;
        BridgeCollector.Builder builder = bridges.builder(pos);

        // ===== PG -> CEE：把 PG 电压注入 CEE 节点 =====
        // 无条件注入（vP >= vC 时）：CEE 有真实电源时，注入源与电源并存，
        // 电源钳制电压（同压无叠加错乱），PG 高压时注入源给 CEE 充电 -> 电流表有读数。
        for (int i = 0; i < nodes; i++) {
            double vC = Math.abs(st.vCee[i]);
            double vP = Math.abs(st.vPgNet[i]);
            if (vP > V_MIN && vP >= vC) {
                double vInj = Math.max(-2000.0, Math.min(2000.0, st.vPgNet[i]));
                vInj = gridbridge$lastVInj[i] * 0.8 + vInj * 0.2;
                gridbridge$lastVInj[i] = (float) vInj;
                double power = Math.max(Math.abs(vInj * st.pgCurrent[i]), 500.0);
                power = gridbridge$lastPower[i] * 0.7 + power * 0.3;
                gridbridge$lastPower[i] = (float) power;
                builder.node(10 + i);
                bridges.ground(new InWorldNode(10 + i, pos), GND_S);
                builder.energyLimitedSource(i, 10 + i, power, vInj);
            }
        }

        // ===== CEE 侧等效负载（能量守恒）：R = vLine^2 / P_pg =====
        // PG 侧电动机消耗 P_pg = |vLine| * |iSwitch|；CEE 侧挂一个等效电阻，
        // 让 CEE 电池/电源真正放电，两边的功率表读数一致。
        double vLine = st.vCee[0] - st.vCee[1];
        double iSw = Math.abs(st.pgCurrent[0]);
        double pPg = Math.abs(vLine) * iSw;
        if (Math.abs(vLine) > V_MIN && pPg > 1.0) {
            double rLoad = vLine * vLine / pPg;
            rLoad = Math.max(1.0, Math.min(100000.0, rLoad));
            if (gridbridge$lastLoadR > 0)
                rLoad = gridbridge$lastLoadR * 0.7 + rLoad * 0.3;
            gridbridge$lastLoadR = rLoad;
            // 挂在"线1 高电位侧"节点（电池正极侧）
            int hi = Math.abs(st.vCee[0]) >= Math.abs(st.vCee[2]) ? 0 : 2;
            builder.node(10 + hi);
            bridges.ground(new InWorldNode(10 + hi, pos), GND_S);
            bridges.bridge(new InWorldNode(hi, pos), new InWorldNode(10 + hi, pos),
                    ElectricalProperties.fromThevenin(rLoad, 0));
        }
    }

    @Inject(method = "postTick", at = @At("TAIL"), remap = false)
    private void gridbridge$postTick(SimulationResults results, CallbackInfo ci) {
        CutOffSwitchDevice self = (CutOffSwitchDevice) (Object) this;
        if (lines < DOUBLE_SWITCH_LINES)
            return;
        BridgeState st = BridgeState.get(self.level, self.pos);
        if (st == null)
            return;
        st.closed = self.isClosed;
        int nodes = lines * 2;
        for (int i = 0; i < nodes; i++)
            st.vCee[i] = results.getVoltageAt(self.pos, i);
        if (++gridbridge$logTick % 20 == 0)
            GridBridge.LOGGER.debug("[GridBridge] CEE postTick @ {}: closed={} vCee={}",
                    self.pos, self.isClosed, Arrays.toString(st.vCee));
    }

    /** 服务端主线程执行：若该位置还没有 bridge 方块实体，且方块确实是双刀开关，则补挂。 */
    private static void attachBridge(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) != null)
            return;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CutOffSwitchBlock csb && csb.isDouble) {
            level.setBlockEntity(new BridgeBlockEntity(pos, state));
            GridBridge.LOGGER.info("[GridBridge] 自愈挂载：为双刀开关补挂 BE @ {}", pos);
        }
    }
}