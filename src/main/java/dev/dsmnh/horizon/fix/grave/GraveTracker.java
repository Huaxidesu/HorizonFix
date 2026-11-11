package dev.dsmnh.horizon.fix.grave;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.dsmnh.horizon.fix.FixConfig;
import openblocks.api.GraveSpawnEvent;

/**
 * 记录墓碑位置并在玩家登录时提醒。
 *
 * <p>OpenBlocks 只把死亡原因和天数写进墓碑文字，不给玩家任何坐标提示；玩家重生点往往离死亡点很远，
 * 于是出现「墓碑一直在、玩家以为东西丢了」的情况。本类在墓碑放置后记录位置，
 * 放置时发一条聊天提示，登录时再提醒一次未取走的墓碑，并提供 {@code /graves} 查询。</p>
 *
 * <p>「是否已取走」只在区块已载入时判定：区块未载入不做任何载入动作，直接按未取走处理。</p>
 *
 * <p>墓碑是否真的生成由 {@link GraveRecovery} 判定；确认失败时由它调用 {@link #forget} 移除记录。</p>
 *
 * <p>本类与 {@link GraveHandler} 同为 {@code LOWEST}，主类先注册 GraveHandler，
 * 因此这里读到的是改写后的最终坐标。</p>
 */
public class GraveTracker {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    private static final String FILE_NAME = "HorizonFix-graves.txt";

    /** 用类名判断，避免为 OpenModsLib 增加编译期依赖。 */
    private static final String GRAVE_CLASS = "openblocks.common.block.BlockGrave";

    /** 全局记录上限。 */
    private static final int MAX_RECORDS = 256;

    /** 单个玩家的记录上限，超出时丢弃最旧的。 */
    private static final int MAX_PER_PLAYER = 16;

    private static final GraveTracker INSTANCE = new GraveTracker();

    private final List<Record> records = new ArrayList<Record>();

    private File storage;

    public static GraveTracker get() {
        return INSTANCE;
    }

    /** 由主类在 preInit 调用，指定持久化文件位置并载入历史记录。 */
    public void init(File configDir) {
        storage = new File(configDir, FILE_NAME);
        load();
    }

    // ------------------------------------------------------------------ 记录

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGraveSpawn(GraveSpawnEvent event) {
        if (!FixConfig.graveTrackerEnabled) return;
        // 被取消的第二次死亡不会放置墓碑，记录它会让 /graves 多出一个不存在的位置
        if (event.isCanceled()) return;
        if (!event.hasLocation()) return;

        EntityPlayer player = event.entityPlayer;
        if (player == null || player instanceof FakePlayer) return;

        World world = player.worldObj;
        if (world == null || world.isRemote) return;

        int x = event.getX();
        int y = event.getY();
        int z = event.getZ();

        Record record = new Record(
                player.getUniqueID(),
                world.provider.dimensionId,
                x,
                y,
                z,
                System.currentTimeMillis());

        add(record);

        if (FixConfig.graveTrackerNotifyPlace) {
            send(
                    player,
                    EnumChatFormatting.YELLOW + "[墓碑] " + EnumChatFormatting.RESET
                            + "你的物品放在 " + EnumChatFormatting.AQUA
                            + describe(record) + EnumChatFormatting.RESET
                            + "，取走前不会消失。输入 " + EnumChatFormatting.AQUA + "/graves"
                            + EnumChatFormatting.RESET + " 可随时查询");
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 命令进入 Bukkit 命令表后清掉权限要求，非 op 才可用
        CommandPermission.ensureNoPermission("graves");
    }

    /**
     * 移除一条记录。
     *
     * <p>{@link GraveRecovery} 确认墓碑没有生成时调用，避免 {@code /graves} 与登录提醒
     * 继续列出不存在的位置。</p>
     */
    public void forget(UUID owner, int dimensionId, int x, int y, int z) {
        boolean changed = false;

        synchronized (records) {
            Iterator<Record> it = records.iterator();
            while (it.hasNext()) {
                Record record = it.next();
                if (!record.owner.equals(owner)) continue;
                if (record.dimensionId != dimensionId || record.x != x || record.y != y || record.z != z) continue;
                it.remove();
                changed = true;
            }
        }

        if (changed) save();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!FixConfig.graveTrackerEnabled || !FixConfig.graveTrackerNotifyLogin) return;
        if (!(event.player instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.player;
        if (player instanceof FakePlayer) return;

        List<Record> pending = pendingOf(player);
        if (pending.isEmpty()) return;

        int limit = Math.max(1, FixConfig.graveTrackerLoginLimit);
        if (pending.size() <= limit) {
            for (Record record : pending) {
                send(
                        player,
                        EnumChatFormatting.YELLOW + "[墓碑] " + EnumChatFormatting.RESET
                                + "未取走的墓碑：" + EnumChatFormatting.AQUA
                                + describe(record) + EnumChatFormatting.RESET
                                + "（" + age(record) + "）");
            }
        } else {
            send(
                    player,
                    EnumChatFormatting.YELLOW + "[墓碑] " + EnumChatFormatting.RESET
                            + "你有 " + EnumChatFormatting.RED + pending.size() + EnumChatFormatting.RESET
                            + " 座未取走的墓碑，输入 " + EnumChatFormatting.AQUA + "/graves"
                            + EnumChatFormatting.RESET + " 查看坐标");
        }
    }

    /** 取该玩家所有尚未确认取走的墓碑，新的在前。 */
    public List<Record> pendingOf(EntityPlayer player) {
        List<Record> mine = new ArrayList<Record>();
        UUID owner = player.getUniqueID();

        synchronized (records) {
            Iterator<Record> it = records.iterator();
            while (it.hasNext()) {
                Record record = it.next();
                if (!record.owner.equals(owner)) continue;
                if (isClaimed(record)) {
                    it.remove();
                    continue;
                }
                mine.add(record);
            }
        }

        Collections.sort(mine, (a, b) -> Long.compare(b.time, a.time));
        return mine;
    }

    /**
     * 墓碑是否已经不在了。
     *
     * <p>只在区块已载入时判定；未载入一律视为仍在，避免为了检查而载入远处区块。</p>
     */
    private boolean isClaimed(Record record) {
        try {
            World world = DimensionManager.getWorld(record.dimensionId);
            if (world == null) return false;
            if (!world.blockExists(record.x, record.y, record.z)) return false;
            return !GRAVE_CLASS.equals(world.getBlock(record.x, record.y, record.z).getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void add(Record record) {
        synchronized (records) {
            records.add(record);

            int count = 0;
            for (int i = records.size() - 1; i >= 0; i--) {
                if (!records.get(i).owner.equals(record.owner)) continue;
                count++;
                if (count > MAX_PER_PLAYER) {
                    records.remove(i);
                    count--;
                }
            }
            while (records.size() > MAX_RECORDS) {
                records.remove(0);
            }
        }
        save();
    }

    // ------------------------------------------------------------ 持久化

    private void load() {
        if (storage == null || !storage.isFile()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(storage))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length != 6) continue;
                try {
                    records.add(new Record(
                            UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]),
                            Long.parseLong(parts[5])));
                } catch (IllegalArgumentException ignored) {
                    // 单行损坏不影响其余记录
                }
            }
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 读取墓碑记录失败，按空记录继续", t);
        }
    }

    private void save() {
        if (storage == null) return;

        try {
            File parent = storage.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                LOG.warn("[HorizonFix] 无法创建目录 {}", parent.getAbsolutePath());
                return;
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(storage))) {
                synchronized (records) {
                    for (Record r : records) {
                        writer.write(r.owner + "|" + r.dimensionId + "|" + r.x + "|" + r.y + "|" + r.z + "|" + r.time);
                        writer.newLine();
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 保存墓碑记录失败", t);
        }
    }

    // ---------------------------------------------------------------- 展示

    private static void send(EntityPlayer player, String message) {
        try {
            player.addChatMessage(new ChatComponentText(message));
        } catch (Throwable ignored) {
            // 提示失败不影响游戏
        }
    }

    public static String describe(Record record) {
        return dimensionName(record.dimensionId) + " (" + record.x + ", " + record.y + ", " + record.z + ")";
    }

    public static String dimensionName(int dim) {
        if (dim == 0) return "主世界";
        if (dim == -1) return "下界";
        if (dim == 1) return "末地";
        try {
            String name = DimensionManager.getProvider(dim).getDimensionName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // 未注册的维度退回编号
        }
        return "维度 " + dim;
    }

    public static String age(Record record) {
        long minutes = Math.max(0, (System.currentTimeMillis() - record.time) / 60000L);
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return (hours / 24) + " 天前";
    }

    public static class Record {

        public final UUID owner;
        public final int dimensionId;
        public final int x;
        public final int y;
        public final int z;
        public final long time;

        Record(UUID owner, int dimensionId, int x, int y, int z, long time) {
            this.owner = owner;
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }
}
