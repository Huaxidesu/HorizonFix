package dev.dsmnh.horizon.fix.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.parts.IPart;
import appeng.parts.CableBusContainer;

import dev.dsmnh.horizon.fix.FixConfig;
import dev.dsmnh.horizon.fix.ae2.DrawerControllerExploder;
import dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker;

/**
 * 玩家把存储总线贴到抽屉管理器上时触发爆炸。
 *
 * <p>注入点选在 {@code CableBusContainer.addPart} 返回处：此时部件已经完成落位，
 * 线缆方块、部件与网格连接均已就绪，爆炸不会与放置流程互相干扰。
 * 该方法的 {@code player} 参数在读档时为 {@code null}，据此可以区分「玩家放置」与
 * 「区块载入重建」，因此已存在的结构不会在服务器启动时被误炸。</p>
 *
 * <p>只有这一条路径会做判定，不注册 tick 监听，也不做周期性扫描。</p>
 */
@Mixin(targets = "appeng.parts.CableBusContainer", remap = false)
public class MixinAe2CableBusPlacement {

    // 只按方法名匹配：addPart 在 CableBusContainer 中只有一个重载，
    // 这样 AE2 后续版本即使调整参数顺序也不会因为描述符对不上而在类加载阶段抛异常。
    // require = 0 表示找不到注入点时只记一条警告，不阻断服务端启动。
    @Inject(method = "addPart", at = @At("RETURN"), remap = false, require = 0)
    private void horizon$explodeDrawerController(ItemStack is, ForgeDirection side, EntityPlayer player,
            CallbackInfoReturnable<ForgeDirection> cir) {
        // 读档重建时 player 为 null，不能当作玩家放置处理
        if (player == null) return;
        if (!FixConfig.storageBusExplodeOnPlace) return;

        try {
            final ForgeDirection placed = cir.getReturnValue();
            if (placed == null || placed == ForgeDirection.UNKNOWN) return;

            final CableBusContainer host = (CableBusContainer) (Object) this;
            final IPart part = host.getPart(placed);
            if (!StorageDrawerBlocker.isStorageBus(part)) return;

            final TileEntity hostTile = host.getTile();
            if (hostTile == null) return;
            final World world = hostTile.getWorldObj();
            if (world == null || world.isRemote) return;

            final int tx = hostTile.xCoord + placed.offsetX;
            final int ty = hostTile.yCoord + placed.offsetY;
            final int tz = hostTile.zCoord + placed.offsetZ;
            if (!world.blockExists(tx, ty, tz)) return;

            final TileEntity target = world.getTileEntity(tx, ty, tz);
            if (!StorageDrawerBlocker.isDrawerControllerOrSlave(target)) return;

            DrawerControllerExploder.explode(world, target, player);
        } catch (Throwable ignored) {
            // 判定或爆炸失败时保持原行为，不影响放置流程
        }
    }
}
