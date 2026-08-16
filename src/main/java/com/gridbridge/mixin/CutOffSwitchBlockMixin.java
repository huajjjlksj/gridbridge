package com.gridbridge.mixin;

import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock;
import com.gridbridge.GridBridge;
import com.gridbridge.bridge.BridgeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 让 CEE 的双刀开关方块对 bridge 方块实体返回 ticker，并在方块层面暴露 PowerGrid 端子。
 *
 * 1. ticker：1.21.1 里方块实体的 tick 不再由 BlockEntityType 携带，而是由
 *    {@code BlockStateBase.getTicker} -> {@code EntityBlock.getTicker} 决定：
 *    CEE 的方块没有实现 EntityBlock（也不实现 Create 的 IBE），因此即使
 *    BridgeBlockEntity 被挂上去也永远不会 tick —— ElectricBehaviour 不会
 *    initialize（不注册节点、不 unpause），PowerGrid 侧完全无法工作。
 *    这里让 CutOffSwitchBlock 实现 EntityBlock，并只在查询的是我们的 bridge
 *    方块实体类型时返回 ticker；其余情况返回 null，不影响 CEE 自身逻辑。
 *
 * 2. IElectric（方块级）：客户端不一定会同步到 BridgeBlockEntity（BE 是服务端
 *    懒挂的），而 PowerGrid 的 TerminalHandler 靠 {@code IElectric.getAt} 找端子
 *    来渲染"待连接灰色虚框"。在方块上直接实现 IElectric，即使客户端没有 BE，
 *    悬停双刀开关也能显示 4 个接线端子的虚框与 HUD 名称；服务端接线交互也
 *    由同一接口处理（BE 由 mixin/事件自愈挂载后行为照常）。
 */
@Mixin(CutOffSwitchBlock.class)
public abstract class CutOffSwitchBlockMixin implements EntityBlock, IElectric {

    /** 调用 BridgeBlockEntity.tick()（其内部先 super.tick() 驱动 Create 的初始化/懒 tick/behaviour）。 */
    @SuppressWarnings("unchecked")
    private static final BlockEntityTicker<BridgeBlockEntity> BRIDGE_TICKER =
            (lvl, pos, state, be) -> be.tick();

    /**
     * EntityBlock 的抽象方法：直接创建 bridge 方块实体（仅双刀开关）。
     * 由方块创建的 BE 在客户端/服务端都会存在（chunk/放置同步），客户端因此也能
     * 找到 ElectricBehaviour —— 否则 PowerGrid 的客户端校验会报  connection failed。
     * 服务端重复挂载（放置事件 / attachBridge 自愈）有判空保护，不会重复创建。
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (((CutOffSwitchBlock) (Object) this).isDouble)
            return new BridgeBlockEntity(pos, state);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == GridBridge.BRIDGE_BE.get())
            return (BlockEntityTicker<T>) (BlockEntityTicker<?>) BRIDGE_TICKER;
        return null;
    }

    // ===================== IElectric（方块级端子） =====================

    @Override
    public int terminalCount() {
        return ((CutOffSwitchBlock) (Object) this).isDouble ? 4 : 2;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        CutOffSwitchBlock self = (CutOffSwitchBlock) (Object) this;
        if (index < 0 || index >= terminalCount())
            return null;
        // getNodePosition 只依赖 state 的 FACING/ROLL，level/pos 传 null/ZERO 即可。
        Vec3 p = self.getNodePosition(null, BlockPos.ZERO, state, index);
        if (p == null)
            return null;
        double px = p.x * 16, py = p.y * 16, pz = p.z * 16;
        return new TerminalBoundingBox(Component.literal("Grid " + index),
                px - 2.5, py - 2.5, pz - 2.5, px + 2.5, py + 2.5, pz + 2.5);
    }

    /** 双刀开关接受任意 PowerGrid 电线（轻线/重线均可）。 */
    @Override
    public boolean accepts(ItemStack wireStack) {
        return true;
    }
}