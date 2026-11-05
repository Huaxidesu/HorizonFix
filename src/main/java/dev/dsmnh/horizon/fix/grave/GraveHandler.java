package dev.dsmnh.horizon.fix.grave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.dsmnh.horizon.fix.FixConfig;
import openblocks.api.GraveSpawnEvent;

/**
 * 在 {@link GraveSpawnEvent} 上改写墓碑落点。
 *
 * <p>只处理两种情况：落点位于液体内部（{@code grave.avoidLiquid}）、未找到落点
 * （{@code grave.rescueMissing}）。选点不依赖 {@code World.blockExists}，因为该方法只查询内存表，
 * 结果随区块调度变化。放置流程与缺陷背景见 README。</p>
 */
public class GraveHandler {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 与 OpenBlocks 配置保持一致的墓碑高度上下限。 */
    private static final int MIN_Y = 6;
    private static final int MAX_Y = 249;

    /** 死亡点缓存上限，防止玩家死亡后没有生成墓碑时条目长期堆积。 */
    private static final int DEATH_CACHE_LIMIT = 64;

    private static final AtomicInteger DIAG_COUNT = new AtomicInteger();

    private static final AtomicInteger TIMING_COUNT = new AtomicInteger();

    /** 时序日志条数上限，固定值，不随诊断开关变化。 */
    private static final int TIMING_LIMIT = 8;

    private final List<Coord> searchOrder;

    /** 玩家 UUID → 上一次生成墓碑的世界时间，用于识别同 tick 重复死亡。 */
    private final Map<UUID, Long> lastGraveTick = new HashMap<UUID, Long>();

    /**
     * 死亡点缓存，按 UUID 取用、用完即删。
     *
     * <p>部分自动重生插件会在放置前把玩家送回重生点，此时事件里的玩家坐标已不在死亡位置。</p>
     */
    private final Map<UUID, DeathSpot> deathSpots = new LinkedHashMap<UUID, DeathSpot>() {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, DeathSpot> eldest) {
            return size() > DEATH_CACHE_LIMIT;
        }
    };

    public GraveHandler() {
        this.searchOrder = buildSearchOrder(Math.max(1, FixConfig.graveSearchRadius));
    }

    private static List<Coord> buildSearchOrder(int radius) {
        List<Coord> coords = new ArrayList<Coord>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    coords.add(new Coord(x, y, z));
                }
            }
        }
        Collections.sort(coords, new Comparator<Coord>() {

            private int sum(Coord c) {
                return Math.abs(c.x) + Math.abs(c.y) + Math.abs(c.z);
            }

            private int max(Coord c) {
                return Math.max(Math.max(Math.abs(c.x), Math.abs(c.y)), Math.abs(c.z));
            }

            @Override
            public int compare(Coord a, Coord b) {
                int diff = sum(a) - sum(b);
                if (diff != 0) return diff;
                return max(b) - max(a);
            }
        });
        return Collections.unmodifiableList(coords);
    }

    /** 记录死亡点，供随后的放置事件使用。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.entityLiving instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.entityLiving;
        if (player instanceof FakePlayer) return;
        if (player.worldObj == null || player.worldObj.isRemote) return;

        try {
            DeathSpot spot = new DeathSpot(
                    player.worldObj.provider.dimensionId,
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ),
                    player.worldObj.getTotalWorldTime());
            deathSpots.put(player.getUniqueID(), spot);
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 记录死亡点失败", t);
        }
    }

    /**
     * 同一玩家在同一 tick 内只允许生成一座墓碑。
     *
     * <p>同一 tick 触发两次死亡时，OpenBlocks 会排队两次放置：第一座拿到全部掉落，
     * 第二座只剩死亡时额外追加的物品（例如大头插件补的玩家头颅），于是「一死两碑」且头颅多一颗。
     * {@code GraveSpawnEvent} 可取消，这里把第二座拦掉；被取消后 OpenBlocks 会把该次的掉落丢回地面，
     * 不再产生第二座墓碑。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGraveSpawnGuard(GraveSpawnEvent event) {
        if (!FixConfig.graveSinglePerTick) return;

        EntityPlayer player = event.entityPlayer;
        if (player == null || player.worldObj == null) return;

        long now = player.worldObj.getTotalWorldTime();
        UUID id = player.getUniqueID();

        Long last = lastGraveTick.get(id);
        if (last != null && last.longValue() == now) {
            event.setCanceled(true);
            LOG.warn(
                    "[HorizonFix] {} 在同一 tick 内重复死亡，已取消第二座墓碑（坐标 {}, {}, {}）",
                    player.getCommandSenderName(),
                    event.getX(),
                    event.getY(),
                    event.getZ());
            return;
        }

        lastGraveTick.put(id, now);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGraveSpawn(GraveSpawnEvent event) {
        if (event.isCanceled()) return;

        try {
            handle(event);
        } catch (Throwable t) {
            // 任何异常都不允许影响墓碑生成：保持 OpenBlocks 的原始结果。
            LOG.error("[HorizonFix] 处理墓碑位置时出现异常，按原逻辑继续", t);
        }
    }

    private void handle(GraveSpawnEvent event) {
        EntityPlayer player = event.entityPlayer;
        if (player == null) return;

        World world = player.worldObj;
        if (world == null || world.isRemote) return;

        int posX = MathHelper.floor_double(player.posX);
        int posY = MathHelper.floor_double(player.posY);
        int posZ = MathHelper.floor_double(player.posZ);

        DeathSpot spot = deathSpots.remove(player.getUniqueID());
        if (spot != null) {
            World deathWorld = DimensionManager.getWorld(spot.dimensionId);
            if (deathWorld != null) world = deathWorld;
            posX = spot.x;
            posY = spot.y;
            posZ = spot.z;
            logTiming(world, player, spot, posX, posY, posZ);
        }

        if (event.hasLocation()) {
            if (!FixConfig.graveAvoidLiquid) return;

            int x = event.getX();
            int y = event.getY();
            int z = event.getZ();
            if (!isLiquid(world, x, y, z)) return;

            diagnose(world, x, y, z, "原落点位于液体内部");
            Coord fixed = find(world, posX, posY, posZ);
            if (fixed == null) {
                LOG.warn(
                        "[HorizonFix] 未能为 {} 找到干燥落点，保留原落点 ({},{},{})",
                        player.getCommandSenderName(),
                        x,
                        y,
                        z);
                return;
            }
            event.setPosition(fixed.x, fixed.y, fixed.z);
            LOG.info(
                    "[HorizonFix] {} 的墓碑由 ({},{},{}) 移至 ({},{},{})，原落点为液体",
                    player.getCommandSenderName(),
                    x,
                    y,
                    z,
                    fixed.x,
                    fixed.y,
                    fixed.z);
            return;
        }

        if (!FixConfig.graveRescueMissing) return;

        diagnoseCandidates(world, player, posX, posY, posZ);
        diagnose(world, posX, posY, posZ, "未找到任何落点");
        Coord fixed = find(world, posX, posY, posZ);
        if (fixed == null) {
            LOG.warn("[HorizonFix] 未能为 {} 补上落点，交由原逻辑处理", player.getCommandSenderName());
            return;
        }
        event.setPosition(fixed.x, fixed.y, fixed.z);
        LOG.info(
                "[HorizonFix] {} 的墓碑补在 ({},{},{})，死亡点 ({},{},{})",
                player.getCommandSenderName(),
                fixed.x,
                fixed.y,
                fixed.z,
                posX,
                posY,
                posZ);
    }

    /**
     * 在死亡点附近寻找可放置墓碑的坐标。
     *
     * <p>第一轮要求下方有实体方块，使墓碑贴合地面；第二轮放宽为只要不是液体。两轮均无结果时
     * 沿死亡点所在列扫描，保证给出坐标。</p>
     */
    private Coord find(World world, int posX, int posY, int posZ) {
        // 中心区块先拉回内存。OpenBlocks 的失败点在于只查询内存表，这里改为直接取区块。
        loadChunk(world, posX >> 4, posZ >> 4);

        int baseY = Math.min(Math.max(posY, MIN_Y), MAX_Y);

        if (FixConfig.graveRequireSupport) {
            Coord supported = scan(world, posX, baseY, posZ, true);
            if (supported != null) return supported;
        }

        Coord free = scan(world, posX, baseY, posZ, false);
        if (free != null) return free;

        return scanColumn(world, posX, baseY, posZ);
    }

    private Coord scan(World world, int posX, int baseY, int posZ, boolean requireSupport) {
        for (Coord offset : searchOrder) {
            int y = baseY + offset.y;
            if (y < MIN_Y || y > MAX_Y) continue;
            int x = posX + offset.x;
            int z = posZ + offset.z;
            if (!isPlaceable(world, x, y, z)) continue;
            if (requireSupport && !hasSupport(world, x, y, z)) continue;
            return new Coord(x, y, z);
        }
        return null;
    }

    /** 后备处理：沿死亡点所在列上下扫描，最后扩到相邻四列。 */
    private Coord scanColumn(World world, int posX, int baseY, int posZ) {
        Coord direct = scanVertical(world, posX, baseY, posZ);
        if (direct != null) return direct;

        int[][] neighbours = new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int[] offset : neighbours) {
            Coord candidate = scanVertical(world, posX + offset[0], baseY, posZ + offset[1]);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Coord scanVertical(World world, int x, int baseY, int z) {
        for (int y = baseY; y <= MAX_Y; y++) {
            if (isPlaceable(world, x, y, z)) return new Coord(x, y, z);
        }
        for (int y = baseY - 1; y >= MIN_Y; y--) {
            if (isPlaceable(world, x, y, z)) return new Coord(x, y, z);
        }
        return null;
    }

    /** 墓碑可以占据该位置：是空气或可替换方块，并且不是液体。 */
    private boolean isPlaceable(World world, int x, int y, int z) {
        if (y < 0 || y > 255) return false;
        Block block = getBlockSafe(world, x, y, z);
        if (block == null) return false;
        if (block.getMaterial().isLiquid()) return false;
        return block.isAir(world, x, y, z) || block.isReplaceable(world, x, y, z);
    }

    /** 下方是可站立的实体方块，避免墓碑悬在空中。 */
    private boolean hasSupport(World world, int x, int y, int z) {
        if (y <= 0) return false;
        Block below = getBlockSafe(world, x, y - 1, z);
        if (below == null) return false;
        if (below.getMaterial().isLiquid()) return false;
        return below.getMaterial().isSolid();
    }

    private boolean isLiquid(World world, int x, int y, int z) {
        Block block = getBlockSafe(world, x, y, z);
        return block != null && block.getMaterial().isLiquid();
    }

    /**
     * 取方块，区块未载入时先同步载入。
     *
     * <p>{@code World.getBlock} 本身就会通过区块提供器载入区块，但为了避免各版本实现差异，
     * 这里显式先取一次区块。</p>
     */
    private Block getBlockSafe(World world, int x, int y, int z) {
        loadChunk(world, x >> 4, z >> 4);
        return world.getBlock(x, y, z);
    }

    private void loadChunk(World world, int chunkX, int chunkZ) {
        try {
            world.getChunkFromChunkCoords(chunkX, chunkZ);
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 载入区块 ({},{}) 失败", chunkX, chunkZ, t);
        }
    }

    /**
     * 输出死亡到放置之间的时序，用于判定「重生与放置撞在同一 tick」是否成立。
     *
     * <p>{@code delta=1} 即同一 tick：世界时间在实体 tick 之后、END 事件之前自增一次。
     * 必须在任何取方块操作之前调用，否则载入区块会让 {@code blockExists} 变成 true。</p>
     */
    private void logTiming(World world, EntityPlayer player, DeathSpot spot, int x, int y, int z) {
        if (TIMING_COUNT.getAndIncrement() >= TIMING_LIMIT) return;

        long now = world.getTotalWorldTime();
        int playerDim = player.worldObj == null ? Integer.MIN_VALUE : player.worldObj.provider.dimensionId;

        boolean exists;
        boolean chunkExists;
        try {
            exists = world.blockExists(x, y, z);
            chunkExists = world.getChunkProvider().chunkExists(x >> 4, z >> 4);
        } catch (Throwable t) {
            exists = false;
            chunkExists = false;
        }

        LOG.info(
                "[墓碑时序] delta={} tick deathDim={} playerDim={} @({},{},{}) blockExists={} chunkExists={}",
                now - spot.tick,
                spot.dimensionId,
                playerDim,
                x,
                y,
                z,
                exists,
                chunkExists);
    }

    /**
     * 复算 OpenBlocks 原始搜索范围内的候选点，分别计数被哪一条判定拒绝。
     *
     * <p>先只查内存表统计 {@code blockExists}，再统计方块与权限，两次采样分开，
     * 避免取方块时载入区块掩盖真实状态。</p>
     */
    private void diagnoseCandidates(World world, EntityPlayer player, int posX, int posY, int posZ) {
        if (!claimDiagnostic()) return;

        // OpenBlocks 的原始范围是 spawnRange/2 = 5，搜索半径配得更小时按实际值统计
        final int radius = Math.min(5, FixConfig.graveSearchRadius);
        final int limitedPosY = Math.min(Math.max(posY, MIN_Y), MAX_Y);

        int total = 0;
        int exists = 0;

        for (Coord c : searchOrder) {
            if (Math.abs(c.x) > radius || Math.abs(c.y) > radius || Math.abs(c.z) > radius) continue;
            int y = limitedPosY + c.y;
            if (y < MIN_Y || y > MAX_Y) continue;
            total++;
            try {
                if (world.blockExists(posX + c.x, y, posZ + c.z)) exists++;
            } catch (Throwable ignored) {
                // 计数失败不影响主流程
            }
        }

        int air = 0;
        int replaceable = 0;
        int liquid = 0;
        int mineable = 0;

        if (exists > 0) {
            for (Coord c : searchOrder) {
                if (Math.abs(c.x) > radius || Math.abs(c.y) > radius || Math.abs(c.z) > radius) continue;
                int y = limitedPosY + c.y;
                if (y < MIN_Y || y > MAX_Y) continue;
                int x = posX + c.x;
                int z = posZ + c.z;
                Block block = getBlockSafe(world, x, y, z);
                if (block == null) continue;
                if (block.isAir(world, x, y, z)) air++;
                else if (block.isReplaceable(world, x, y, z)) replaceable++;
                if (block.getMaterial().isLiquid()) liquid++;
                if (player != null && world.canMineBlock(player, x, y, z)) mineable++;
            }
        }

        LOG.info(
                "[墓碑诊断] 候选统计 r={} total={} blockExists={} canMineBlock={} air={} replaceable={} liquid={}",
                radius,
                total,
                exists,
                mineable,
                air,
                replaceable,
                liquid);
    }

    /**
     * 输出一次放置判定的现场信息，用于确认失败原因。
     *
     * <p>必须在任何取方块操作之前调用，否则载入区块会让 {@code blockExists} 变成 true，
     * 掩盖真实状态。</p>
     */
    private void diagnose(World world, int x, int y, int z, String reason) {
        if (!claimDiagnostic()) return;

        boolean exists;
        boolean chunkExists;
        try {
            exists = world.blockExists(x, y, z);
            chunkExists = world.getChunkProvider().chunkExists(x >> 4, z >> 4);
        } catch (Throwable t) {
            exists = false;
            chunkExists = false;
        }

        Block block = null;
        int meta = 0;
        try {
            block = world.getBlock(x, y, z);
            meta = world.getBlockMetadata(x, y, z);
        } catch (Throwable ignored) {
            // 诊断信息缺失不影响主流程
        }

        LOG.info(
                "[墓碑诊断] {} @({},{},{}) dim={} blockExists={} chunkExists={} block={}:{} liquid={} replaceable={} air={}",
                reason,
                x,
                y,
                z,
                world.provider.dimensionId,
                exists,
                chunkExists,
                block == null ? "?" : block.getUnlocalizedName(),
                meta,
                block != null && block.getMaterial().isLiquid(),
                block != null && block.isReplaceable(world, x, y, z),
                block != null && block.isAir(world, x, y, z));
    }

    private boolean claimDiagnostic() {
        int limit = FixConfig.graveDiagnosticLimit;
        if (limit <= 0) return false;
        return DIAG_COUNT.getAndIncrement() < limit;
    }

    private static class DeathSpot {

        final int dimensionId;
        final int x;
        final int y;
        final int z;
        final long tick;

        DeathSpot(int dimensionId, int x, int y, int z, long tick) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
        }
    }

    private static class Coord {

        final int x;
        final int y;
        final int z;

        Coord(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
