package dev.dsmnh.horizon.fix.world;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 记录区块填充期间触发的相邻区块载入，用于定位级联世界生成。
 *
 * <p>只输出日志，不改变生成行为；单次启动的日志条数由配置限制。</p>
 */
public class CascadeWorldgenDiagnostic {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    private final ThreadLocal<int[]> activePopulate = new ThreadLocal<int[]>();
    private int remaining;

    public CascadeWorldgenDiagnostic(int limit) {
        this.remaining = limit;
    }

    @SubscribeEvent
    public void onPopulatePre(PopulateChunkEvent.Pre event) {
        if (event.world == null || event.world.isRemote) {
            return;
        }
        activePopulate.set(new int[]{event.world.provider.dimensionId, event.chunkX, event.chunkZ});
    }

    @SubscribeEvent
    public void onPopulatePost(PopulateChunkEvent.Post event) {
        activePopulate.remove();
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (remaining <= 0 || event.world == null || event.world.isRemote) {
            return;
        }
        int[] source = activePopulate.get();
        Chunk chunk = event.getChunk();
        if (source == null || chunk == null) {
            return;
        }
        if (source[0] != event.world.provider.dimensionId) {
            return;
        }
        if (source[1] == chunk.xPosition && source[2] == chunk.zPosition) {
            return;
        }
        remaining--;
        LOG.info("[HorizonFix] 级联世界生成 dim={} 来源区块=({},{}) 触发载入=({},{})",
                source[0], source[1], source[2], chunk.xPosition, chunk.zPosition);
    }
}
