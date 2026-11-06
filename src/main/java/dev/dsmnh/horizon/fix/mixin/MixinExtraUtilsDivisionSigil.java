package dev.dsmnh.horizon.fix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBeacon;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * 把除法印记取到的附魔台与信标归一到原版方块单例。
 *
 * <p>印记右键同样使用引用相等比较，方块类被替换后仪式无法完成。</p>
 */
@Mixin(targets = "com.rwtema.extrautils.item.ItemDivisionSigil", remap = false)
public class MixinExtraUtilsDivisionSigil {

    @Redirect(method = "func_77648_a",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147439_a(III)Lnet/minecraft/block/Block;"),
            remap = false)
    private Block horizon$normalizeRitualBlocks(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block instanceof BlockEnchantmentTable) {
            return Blocks.enchanting_table;
        }
        if (block instanceof BlockBeacon) {
            return Blocks.beacon;
        }
        return block;
    }
}
