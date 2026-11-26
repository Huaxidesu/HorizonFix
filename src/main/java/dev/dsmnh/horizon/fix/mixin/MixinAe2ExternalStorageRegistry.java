package dev.dsmnh.horizon.fix.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IExternalStorageHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStackType;

import dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker;

/**
 * 在 AE2 的外部存储注册表上拦截抽屉方块。
 *
 * <p>{@code ExternalStorageRegistry.getHandler} 是存储总线取得邻接库存的唯一入口：
 * 它先遍历各模组注册的处理器，最后回落到把任意 {@code IInventory} 包装成监视器的兜底实现。
 * 抽屉管理器实现了 {@code IInventory}，若在这里返回 {@code null}，总线会退化到兜底路径，
 * 反而更慢，因此改为返回一个恒空的库存处理器。</p>
 */
@Mixin(targets = "appeng.core.features.registries.ExternalStorageRegistry", remap = false)
public class MixinAe2ExternalStorageRegistry {

    @Inject(
            method = "getHandler(Lnet/minecraft/tileentity/TileEntity;"
                    + "Lnet/minecraftforge/common/util/ForgeDirection;"
                    + "Lappeng/api/storage/data/IAEStackType;"
                    + "Lappeng/api/networking/security/BaseActionSource;)"
                    + "Lappeng/api/storage/IExternalStorageHandler;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void horizon$denyDrawerStorage(TileEntity te, ForgeDirection d, IAEStackType<?> type,
            BaseActionSource src, CallbackInfoReturnable<IExternalStorageHandler> cir) {
        try {
            if (StorageDrawerBlocker.INSTANCE.canHandle(te, d, type, src)) {
                cir.setReturnValue(StorageDrawerBlocker.INSTANCE);
            }
        } catch (Throwable ignored) {
            // 判定失败时保持原行为，不阻断存储总线
        }
    }

    /** 保留已废弃的 {@link StorageChannel} 重载，供仍在使用旧接口的调用方。 */
    @Inject(
            method = "getHandler(Lnet/minecraft/tileentity/TileEntity;"
                    + "Lnet/minecraftforge/common/util/ForgeDirection;"
                    + "Lappeng/api/storage/StorageChannel;"
                    + "Lappeng/api/networking/security/BaseActionSource;)"
                    + "Lappeng/api/storage/IExternalStorageHandler;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void horizon$denyDrawerStorageLegacy(TileEntity te, ForgeDirection d, StorageChannel channel,
            BaseActionSource src, CallbackInfoReturnable<IExternalStorageHandler> cir) {
        try {
            if (channel != StorageChannel.ITEMS) return;
            if (StorageDrawerBlocker.shouldBlock(te)) {
                cir.setReturnValue(StorageDrawerBlocker.INSTANCE);
            }
        } catch (Throwable ignored) {
            // 判定失败时保持原行为，不阻断存储总线
        }
    }
}
