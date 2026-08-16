package com.gridbridge;

import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock;
import com.gridbridge.bridge.BridgeBlockEntity;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grid Bridge — 模组入口与初始化。
 *
 * 作用：让 PowerGrid 的电网线可以直接接到 Create Electro Energetics (CEE) 的
 * 双刀隔离开关（double cut-off switch）上，两个独立模拟器之间的电力可以双向流动。
 *
 * 初始化分三块：
 * 1. 注册：通过 CreateRegistrate 注册一个挂在 CEE 双刀开关方块上的方块实体
 *    {@link BridgeBlockEntity}（本模组没有自己的方块/物品，只"骑"在 CEE 的方块上）。
 * 2. 模组生命周期：commonSetup 里（enqueueWork 延迟到注册表冻结后）校验目标方块
 *    仍然存在且是双刀变体，并输出启动日志。
 * 3. 游戏事件：方块被实体放置时立刻挂载 bridge 方块实体（其余途径出现的方块由
 *    CutOffSwitchDeviceMixin 在服务端线程自愈挂载）。
 */
@Mod(GridBridge.MOD_ID)
public class GridBridge {
    public static final String MOD_ID = "gridbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Create Registrate 实例；本模组所有注册都经由它。 */
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    /**
     * bridge 方块实体：有效方块是 CEE 的 DOUBLE_SWITCH。它把双刀开关的 4 个电气节点
     * 暴露成 PowerGrid 端子，并作为电压源把 CEE 节点电压注入 PowerGrid 电路。
     */
    @SuppressWarnings("unchecked")
    public static final BlockEntityEntry<BridgeBlockEntity> BRIDGE_BE =
            (BlockEntityEntry<BridgeBlockEntity>) (BlockEntityEntry<?>) REGISTRATE
                    .blockEntity("bridge", BridgeBlockEntity::new)
                    .validBlock(() -> CEEBlocks.DOUBLE_SWITCH.get())
                    .register();

    public GridBridge(IEventBus modBus) {
        // 模组生命周期总线：Registrate 注册事件 + common setup。
        REGISTRATE.registerEventListeners(modBus);
        modBus.addListener(this::commonSetup);

        // 游戏总线：世界事件（方块放置）。
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[GridBridge] 模组加载：Registrate + commonSetup + 游戏事件已注册");
    }

    // ===================== 模组生命周期 =====================

    private void commonSetup(FMLCommonSetupEvent event) {
        // 注册表在此时尚未冻结，读注册表内容要延迟到 enqueueWork（跨线程排队）。
        event.enqueueWork(GridBridge::verifyTargetBlock);
    }

    /** 启动自检：确认我们要"骑"的 CEE 双刀开关方块存在且是双刀变体。 */
    private static void verifyTargetBlock() {
        BlockState state = CEEBlocks.DOUBLE_SWITCH.get().defaultBlockState();
        if (isTarget(state)) {
            LOGGER.info("[GridBridge] 初始化完成：PowerGrid <-> CEE 双刀开关双向桥接已就绪。");
        } else {
            LOGGER.error("[GridBridge] 未找到 CEE 双刀开关方块（或方块类型已变化），"
                    + "桥接将无法工作，请检查 electroenergetics 依赖版本。");
        }
    }

    // ===================== 游戏事件 =====================

    private static boolean isTarget(BlockState state) {
        return state.getBlock() instanceof CutOffSwitchBlock csb && csb.isDouble;
    }

    /**
     * 双刀开关被实体放置时立刻挂载 bridge 方块实体（玩家放置是主要途径）。
     * 非实体途径（setblock 命令、世界生成、安装本模组之前就存在的存档）没有这个事件，
     * 由 {@code CutOffSwitchDeviceMixin} 检测到缺少方块实体后，在服务端线程补挂。
     */
    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!isTarget(event.getPlacedBlock()))
            return;
        BlockPos pos = event.getPos();
        if (level.getBlockEntity(pos) == null) {
            level.setBlockEntity(new BridgeBlockEntity(pos, event.getPlacedBlock()));
            LOGGER.info("[GridBridge] 放置事件：为双刀开关挂载 BE @ {}", pos);
        }
    }
}
