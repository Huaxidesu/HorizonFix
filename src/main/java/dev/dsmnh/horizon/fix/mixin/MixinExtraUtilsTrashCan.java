package dev.dsmnh.horizon.fix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 读档后强制垃圾桶在下一次 tick 重扫库存。
 *
 * <p>原实现只在物品投入时置位，读档后残留物品不会进入库存处理流程。</p>
 */
@Mixin(targets = "com.rwtema.extrautils.tileentity.TileEntityTrashCan", remap = false)
public class MixinExtraUtilsTrashCan {

    @Shadow(remap = false)
    private boolean added;

    @Inject(method = "func_145839_a", at = @At("TAIL"), remap = false)
    private void horizon$forceRescan(NBTTagCompound tag, CallbackInfo ci) {
        added = true;
    }
}
