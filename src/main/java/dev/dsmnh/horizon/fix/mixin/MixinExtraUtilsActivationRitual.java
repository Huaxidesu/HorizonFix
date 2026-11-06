package dev.dsmnh.horizon.fix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * 把仪式判定取到的附魔台归一到原版方块单例。
 *
 * <p>ExtraUtilities 用引用相等比较祭坛方块，整合包替换该方块类后判定会失效。</p>
 */
@Mixin(targets = "com.rwtema.extrautils.EventHandlerServer", remap = false)
public class MixinExtraUtilsActivationRitual {

    @Redirect(method = "ActivationRitual",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147439_a(III)Lnet/minecraft/block/Block;"),
            remap = false)
    private Block horizon$normalizeEnchantingTable(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        return block instanceof BlockEnchantmentTable ? Blocks.enchanting_table : block;
    }
}
