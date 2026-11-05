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
import java.util.regex.Pattern;

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
 * <p>本类与 {@link GraveHandler} 同为 {@code LOWEST}，主类先注册 GraveHandler，
 * 因此这里读到的是改写后的最终坐标。</p>
 */
public class GraveTracker {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    private static final String FILE_NAME = "HorizonFix-graves.txt";

    /** 用类名判断，避免为 OpenModsLib 增加编译期依赖。 */
    private static final String GRAVE_CLASS = "openblocks.common.block.BlockGrave";

    /** 快照文件前缀，与 OpenBlocks 的 PlayerInventoryStore 一致。 */
    private static final String PREFIX = "inventory-";

    /** 与 OpenBlocks 一致：玩家名里的非法字符替换为下划线。 */
    private static final Pattern SAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    /** 全局记录上限。 */
    private static final int MAX_RECORDS = 256;

    /** 单个玩家的记录上限，超出时丢弃最旧的。 */
    private static final int MAX_PER_PLAYER = 16;

    private static final GraveTracker INSTANCE = new GraveTracker();

    private final List<Record> records = new ArrayList<Record>();

    /** 放置后待验证的墓碑，用于判断是否真的生成成功。 */
    private final List<Pending> pending = new ArrayList<Pending>();

    /** 放置发生在事件之后同一 tick，留 2 tick 余量再验证。 */
    private static final int VERIFY_DELAY_TICKS = 2;

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
        pending.add(new Pending(record));

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

    /**
     * 校验墓碑是否真的生成成功。
     *
     * <p>{@code GraveSpawnEvent} 只是「准备放置」，OpenBlocks 在事件之后才写方块，失败时会静默丢弃物品。
     * 因此在放置后 2 tick 检查一次方块：不是墓碑就说明没生成，此时把死亡快照的命令发给玩家，
     * 让他能自行找回。</p>
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 命令进入 Bukkit 命令表后清掉权限要求，非 op 才可用
        CommandPermission.ensureNoPermission("graves");

        if (pending.isEmpty()) return;

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending item = it.next();
            if (++item.ticks < VERIFY_DELAY_TICKS) continue;
            it.remove();
            try {
                verify(item.record);
            } catch (Throwable t) {
                LOG.warn("[HorizonFix] 校验墓碑是否生成失败", t);
            }
        }
    }

    private void verify(Record record) {
        World world = DimensionManager.getWorld(record.dimensionId);
        if (world == null) return;
        if (!world.blockExists(record.x, record.y, record.z)) return;
        if (GRAVE_CLASS.equals(world.getBlock(record.x, record.y, record.z).getClass().getName())) {
            return;
        }

        EntityPlayer player = world.func_152378_a(record.owner);
        if (player == null) return;

        String snapshot = newestSnapshot(world, player);
        if (snapshot == null) {
            send(
                    player,
                    EnumChatFormatting.RED + "[墓碑] " + EnumChatFormatting.RESET
                            + "墓碑未能生成，物品已存入死亡快照，请联系管理员用 /ob_inventory 找回");
            return;
        }

        send(
                player,
                EnumChatFormatting.RED + "[墓碑] " + EnumChatFormatting.RESET
                        + "墓碑未能生成，物品已存入死亡快照，可执行 " + EnumChatFormatting.AQUA
                        + "/ob_inventory restore " + player.getCommandSenderName() + " " + snapshot
                        + EnumChatFormatting.RESET + " 找回");
    }

    /** 取该玩家最新的死亡快照 ID，找不到返回 null。 */
    private String newestSnapshot(World world, EntityPlayer player) {
        try {
            File folder = dumpFolder(world);
            if (folder == null) return null;

            String prefix = PREFIX + safeName(player) + "-";
            File newest = null;
            File[] files = folder.listFiles();
            if (files == null) return null;

            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith(prefix) || !name.contains("-grave-") || !name.endsWith(".dat")) continue;
                if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
            }
            if (newest == null) return null;

            String name = newest.getName();
            return name.substring(PREFIX.length(), name.length() - ".dat".length());
        } catch (Throwable t) {
            return null;
        }
    }

    private static File dumpFolder(World world) {
        if (world == null || world.getSaveHandler() == null) return null;
        File probe = world.getSaveHandler().getMapFileFromName("horizonfix_probe");
        return probe == null ? null : probe.getParentFile();
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

    private static String safeName(EntityPlayer player) {
        com.mojang.authlib.GameProfile profile = player.getGameProfile();
        String name = profile == null ? null : profile.getName();
        if (name == null || name.isEmpty()) name = player.getCommandSenderName();
        return SAFE_CHARS.matcher(name).replaceAll("_");
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

    private static class Pending {

        final Record record;
        int ticks;

        Pending(Record record) {
            this.record = record;
        }
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
