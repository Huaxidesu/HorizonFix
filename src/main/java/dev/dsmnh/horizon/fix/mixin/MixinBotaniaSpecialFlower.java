package dev.dsmnh.horizon.fix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.IBlockAccess;

/**
 * 目标位置没有 TileEntity 时直接给出缺省亮度。
 *
 * <p>区块卸载竞态下，原实现会取得空引用并在亮度查询中抛出空指针异常。</p>
 */
@Mixin(targets = "vazkii.botania.common.block.BlockSpecialFlower", remap = false)
public class MixinBotaniaSpecialFlower {

    @Inject(method = "getLightValue", at = @At("HEAD"), cancellable = true, remap = false)
    private void horizon$avoidNullTile(IBlockAccess world, int x, int y, int z,
                                       CallbackInfoReturnable<Integer> cir) {
        try {
            if (world == null || world.getTileEntity(x, y, z) == null) {
                cir.setReturnValue(-1);
            }
        } catch (Throwable t) {
            cir.setReturnValue(-1);
        }
    }
}
