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
 * CEE 仿真桥接：
 *  - 负极（线1：CEE 节点 0/2）无条件接地 —— CEE 设备（电动机等）的负极回路闭合，
 *    不再依赖 vP>=vC 条件（条件不满足时负极悬空 -> 回路断 -> 不转）。
 *  - 正极（线2：CEE 节点 1/3）注入 PG 电压（energyLimitedSource，保底功率供电）。
 *  - 等效负载（CEE -> PG 供电时挂最高电压节点，能量守恒）。
 *  - postTick 回写 CEE 节点电压。
 */
@Mixin(CutOffSwitchDevice.class)
public class CutOffSwitchDeviceMixin {
    /** 虚拟地节点电导，Siemens（0.01 ohm）。 */
    private static final double GND_S = 100.0;
    /** 电压阈值：低于此值视为无电。 */
    private static final double V_MIN = 1.0;
    /** 正极注入保底功率，Watts（保证电动机等负载供电充足）。 */
    private static final double MIN_POWER = 5000.0;
    /** 双刀开关 = 2 线（4 节点）。 */
    private static final int DOUBLE_SWITCH_LINES = 2;

    @Shadow
    private int lines;

    /** 周期采样日志计数器。 */
    @Unique
    private int gridbridge$logTick;
    /** 正极注入功率平滑。 */
    @Unique
    private final float[] gridbridge$lastPower = new float[4];
    /** 正极注入电压平滑。 */
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
        BridgeCollector.Builder builder = bridges.builder(pos);

        // ===== CEE 极性：正极 = 线1（节点 0/2）！负极 = 线2（节点 1/3）！=====
        // CEE 灯泡/电池的"正"定义在线1（BulbDevice 用 getVoltageAt(0,1)，电池 idealVoltageSource(0,1)）。
        // 之前映射反了（0/2 接地、1/3 注入）-> 灯泡收到 -250V。现在修正：
        //   - 线1（节点 0/2）：注入 +|vPgNet差分|（CEE 正极，灯泡显示 +V）
        //   - 线2（节点 1/3）：无条件接地（0V 参考，回路闭合）
        double vLineP = Math.abs(st.vPgNet[0] - st.vPgNet[1]);   // PG 线电压（取绝对值，方向无关）
        if (vLineP > V_MIN && st.pgPowered) {
            double vInj = Math.max(-2000.0, Math.min(2000.0, vLineP));
            vInj = gridbridge$lastVInj[0] * 0.8 + vInj * 0.2;
            gridbridge$lastVInj[0] = (float) vInj;
            gridbridge$lastVInj[2] = (float) vInj;
            // 注入源用固定能量（5 参版 100000W + 1Ω）——power 计算不再需要；
            // 注意：绝不能在这里写 st.pgPowerInjected（测量值只由 postTick 写——
            // 否则覆盖真实 CEE 消耗 -> loadWire 电阻错误 -> 供给端电流虚高 10 倍）。
            // 注入源（方向1输出）：能量能力 100kW（CEE 负载按需取）+ 内阻 4Ω 固定
            //（R_total = V^2/4E + 4 ≈ 4Ω——压降可测 -> P_cee 测量准确）
            for (int pos2 = 0; pos2 < 4; pos2 += 2) {
                builder.node(10 + pos2);
                bridges.ground(new InWorldNode(10 + pos2, pos), GND_S);
                        // CEE 电压源方向：n2 - n1 = V（n2 是正端）。之前 (pos2, 10+pos2) 把节点当 n1 -> 节点被拉到 -V（负）。
        // 反传 (10+pos2, pos2)：节点 pos2 是正端 -> CEE 侧正极显示 +V（PG 正极 -> CEE 正电压）。
        builder.energyLimitedSource(10 + pos2, pos2, 100000.0, vInj, 1.0);
            }
            // 注入时接地（线2：节点 1/3——CEE 设备负极回路参考）。
            // 注意：只能在注入（PG->CEE）时加——CEE->PG 方向（电池在）绝不接地，
            // 否则电池正极（可能接在节点 1/3）被 0.01Ω 短路 -> 假电流 -> 供给端虚高。
            for (int neg = 1; neg < 4; neg += 2) {
                builder.node(10 + neg);
                bridges.ground(new InWorldNode(10 + neg, pos), GND_S);
                bridges.bridge(new InWorldNode(neg, pos), new InWorldNode(10 + neg, pos),
                        ElectricalProperties.fromThevenin(0.01, 0));
            }
        }

        // ===== CEE 侧等效负载（能量守恒）：R = vLine^2 / P_pg（lineSource 输出功率）=====
        // CEE -> PG 供电：等效负载挂"线间"（节点 0-1 之间）——电池两端回路闭合，
        // 不需要接地（避免电池正极被接地短路 -> 假电流 -> 供给端电流虚高）。
        double vLine = st.vCee[0] - st.vCee[1];
        double pPg = st.pgPowerOut;
        if (Math.abs(vLine) > V_MIN && pPg > 1.0) {
            double rLoad = vLine * vLine / pPg;
            rLoad = Math.max(1.0, Math.min(100000.0, rLoad));
            if (gridbridge$lastLoadR > 0)
                rLoad = gridbridge$lastLoadR * 0.7 + rLoad * 0.3;
            gridbridge$lastLoadR = rLoad;
            bridges.bridge(new InWorldNode(0, pos), new InWorldNode(1, pos),
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
        // 真实 CEE 负载功率：注入源内阻压降估算 I = (vInj - vCee[i]) / R_inj
        // （getCurrentThrough 对电压源支路返回 V_total/R_inj 不扣源电压 -> 读数虚高 32 倍
        //  -> pCee 虚高 -> loadWire R 虚低 -> PG 电流放大 -> 正反馈发散到 280A 烧线）
        // energyLimitedSource 内阻 R_inj = vInj^2 / (4 * power)。
        // 真实 CEE 网络电流（负载驱动）：负极接地支路电流
        // 接地支路是 0.01Ω 纯电阻（fromThevenin(0.01,0)）-> getCurrentThrough 准确（无源支路）
        // 负载要多少电流 -> 准确测到 -> PG 侧 loadWire（R = V^2/P）让创造电源供多少
        double iCee = 0;
        for (int i = 1; i < 4; i += 2)
            iCee += Math.abs(results.getCurrentThrough(self.pos, i, 10 + i));
        double vLineC = Math.abs(st.vCee[0] - st.vCee[1]);
        double pCee = vLineC * iCee;
        double pCap = vLineC * 16.0;                     // 电线容量兜底（16A）
        pCee = Math.max(0, Math.min(pCap, pCee));
        if (pCee > 0.5) {
            st.pgPowerInjected = st.pgPowerInjected * 0.5 + pCee * 0.5;
        }
        if (++gridbridge$logTick % 20 == 0)
            GridBridge.LOGGER.debug("[GridBridge] CEE postTick @ {}: closed={} vCee={} pCee={}",
                    self.pos, self.isClosed, Arrays.toString(st.vCee), pCee);
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