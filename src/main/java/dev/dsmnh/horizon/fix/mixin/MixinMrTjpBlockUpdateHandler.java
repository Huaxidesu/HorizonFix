package dev.dsmnh.horizon.fix.mixin;

import java.util.HashSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

/**
 * 只把已加载的区块加入活跃区块集合。
 *
 * <p>原实现无条件加入坐标，后续处理可能因此载入尚未加载的区块。</p>
 */
@Mixin(targets = "mrtjp.core.world.BlockUpdateHandler$", remap = false)
public class MixinMrTjpBlockUpdateHandler {

    @Redirect(method = "getActiveChunkSet",
            at = @At(value = "INVOKE", target = "Ljava/util/HashSet;add(Ljava/lang/Object;)Z"),
            remap = false)
    private boolean horizon$addLoadedChunk(HashSet<ChunkCoordIntPair> chunkSet, Object value, World world) {
        if (!(value instanceof ChunkCoordIntPair)) {
            return false;
        }
        ChunkCoordIntPair pair = (ChunkCoordIntPair) value;
        if (world == null || world.getChunkProvider().chunkExists(pair.chunkXPos, pair.chunkZPos)) {
            return chunkSet.add(pair);
        }
        return false;
    }
}
