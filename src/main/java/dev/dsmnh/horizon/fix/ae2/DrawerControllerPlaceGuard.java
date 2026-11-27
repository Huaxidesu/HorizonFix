package dev.dsmnh.horizon.fix.ae2;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

import dev.dsmnh.horizon.fix.FixConfig;

/**
 * 玩家把抽屉管理器放到已有存储总线旁边时触发爆炸。
 *
 * <p>{@link dev.dsmnh.horizon.fix.mixin.MixinAe2CableBusPlacement} 只覆盖「先有抽屉管理器、
 * 后贴存储总线」这一条路径，该路径经过 {@code CableBusContainer.addPart}。反过来的方向
 * ——先有存储总线、再放抽屉管理器——走的是方块放置流程（{@code ItemBlock.placeBlockAt}），
 * 完全不经过 AE2 的部件放置代码，因此需要在这里单独判定。</p>
 *
 * <p>监听 {@code BlockEvent.PlaceEvent}，只在方块放置成功时触发一次，不注册 tick 监听，
 * 也没有周期性扫描。判定顺序为：方块类名 → 目标 TileEntity → 六个邻面的部件，
 * 任一环节不满足立即返回。</p>
 */
public final class DrawerControllerPlaceGuard {

    /** 抽屉管理器方块。 */
    private static final String CLASS_CONTROLLER_BLOCK =
            "com.jaquadro.minecraft.storagedrawers.block.BlockController";

    /** 管理器下属的从站方块。 */
    private static final String CLASS_SLAVE_BLOCK = "com.jaquadro.minecraft.storagedrawers.block.BlockSlave";

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        if (!FixConfig.storageBusExplodeOnPlace) return;
        if (event.world == null || event.world.isRemote) return;
        if (event.player == null) return;

        // 先按方块类名快速排除，绝大多数放置都会停在这一步
        if (!isDrawerControllerBlock(event.placedBlock)) return;

        final World world = event.world;
        final int x = event.x;
        final int y = event.y;
        final int z = event.z;

        final TileEntity placed = world.getTileEntity(x, y, z);
        if (!StorageDrawerBlocker.isDrawerControllerOrSlave(placed)) return;

        if (!hasAdjacentStorageBus(world, x, y, z)) return;

        DrawerControllerExploder.explode(world, placed, event.player);
    }

    /** 按类名判定被放置的方块是否为抽屉管理器或其从站，避免加载 Storage Drawers 的类。 */
    private static boolean isDrawerControllerBlock(Block block) {
        if (block == null) return false;
        for (Class<?> c = block.getClass(); c != null && c != Block.class; c = c.getSuperclass()) {
            final String name = c.getName();
            if (CLASS_CONTROLLER_BLOCK.equals(name) || CLASS_SLAVE_BLOCK.equals(name)) return true;
        }
        return false;
    }

    /** 检查六个邻面是否存在朝向该方块的存储总线。 */
    private static boolean hasAdjacentStorageBus(World world, int x, int y, int z) {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            final int nx = x + dir.offsetX;
            final int ny = y + dir.offsetY;
            final int nz = z + dir.offsetZ;
            if (!world.blockExists(nx, ny, nz)) continue;

            final TileEntity neighbor = world.getTileEntity(nx, ny, nz);
            if (!(neighbor instanceof IPartHost)) continue;

            // 总线贴在邻块朝向本方块的那一面
            final IPart part = ((IPartHost) neighbor).getPart(dir.getOpposite());
            if (StorageDrawerBlocker.isStorageBus(part)) return true;
        }
        return false;
    }
}
