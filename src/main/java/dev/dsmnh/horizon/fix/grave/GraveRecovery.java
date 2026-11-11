package dev.dsmnh.horizon.fix.grave;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;
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
 * 墓碑未能生成时把物品放回玩家背包。
 *
 * <p>{@link GraveSpawnEvent} 只表示「准备放置」：OpenBlocks 在事件之后才写方块，
 * {@code tryPlaceGrave} 失败时把掉落物丢回死亡位置，死亡点位于液体内部时物品随之销毁。
 * 此前的做法是提示玩家联系管理员用 {@code /ob_inventory restore} 恢复，本类改为自动完成。</p>
 *
 * <p>时序：墓碑放置与死亡同 tick（见 README「关于重生与放置撞在同一 tick」），
 * 因此事件之后固定等待 {@value #VERIFY_DELAY_TICKS} tick 检查该坐标是否为墓碑。
 * 判定失败时依次做三件事：移除 OpenBlocks 丢到地面的同一批掉落物、把物品放回玩家背包、
 * 删除本次失败放置留下的 {@code -grave-} 备份。玩家不在线或停留在死亡界面时先写入
 * {@code config/HorizonFix-returns.dat}，登录或重生后再发放。</p>
 *
 * <p>本类与 {@link GraveHandler}、{@link GraveTracker} 同为 {@link EventPriority#LOWEST}，
 * 主类最后注册，因此读到的是改写后的最终坐标。</p>
 */
public class GraveRecovery {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 用类名判断，避免为 OpenBlocks 内部类增加编译期依赖。 */
    private static final String GRAVE_BLOCK = "openblocks.common.block.BlockGrave";

    private static final String GRAVE_TILE = "openblocks.common.tileentity.TileEntityGrave";

    /** 与 OpenBlocks 的 PlayerInventoryStore 一致。 */
    private static final String PREFIX = "inventory-";

    private static final Pattern SAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    private static final String FILE_NAME = "HorizonFix-returns.dat";

    /** 放置发生在事件之后同一 tick，留 2 tick 余量再校验。 */
    private static final int VERIFY_DELAY_TICKS = 2;

    /** 世界未载入时最多等待的 tick 数，超过后按失败处理并落盘。 */
    private static final int MAX_WAIT_TICKS = 200;

    /** 备份文件的 Created 与死亡时间的允许偏差。 */
    private static final long BACKUP_WINDOW_MS = 10000L;

    /** 待发放记录的玩家数上限，防止长期堆积。 */
    private static final int MAX_WAITING = 64;

    private static final GraveRecovery INSTANCE = new GraveRecovery();

    /** 等待校验的放置尝试。 */
    private final List<Pending> pending = new ArrayList<Pending>();

    /** 玩家 UUID → 待发放物品，写入磁盘，登录或重生后发放。 */
    private final Map<UUID, List<ItemStack>> waiting = new LinkedHashMap<UUID, List<ItemStack>>();

    private File storage;

    public static GraveRecovery get() {
        return INSTANCE;
    }

    /** 由主类在 preInit 调用，指定持久化文件位置并载入尚未发放的物品。 */
    public void init(File configDir) {
        storage = new File(configDir, FILE_NAME);
        load();
    }

    // ------------------------------------------------------------------ 采集

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGraveSpawn(GraveSpawnEvent event) {
        // 被 GraveHandler 取消的第二次死亡不会放置墓碑，其掉落物由 OpenBlocks 丢回地面，属预期行为
        if (event.isCanceled()) return;

        EntityPlayer player = event.entityPlayer;
        if (player == null || player instanceof FakePlayer) return;

        World world = player.worldObj;
        if (world == null || world.isRemote) return;

        List<ItemStack> items = new ArrayList<ItemStack>();
        List<EntityItem> entities = new ArrayList<EntityItem>();

        if (FixConfig.graveRecoveryEnabled) {
            collect(event, items, entities);
            if (items.isEmpty()) return;
        }

        pending.add(
                new Pending(
                        player.getUniqueID(),
                        player.getCommandSenderName(),
                        world.provider.dimensionId,
                        event.hasLocation(),
                        event.hasLocation() ? event.getX() : 0,
                        event.hasLocation() ? event.getY() : 0,
                        event.hasLocation() ? event.getZ() : 0,
                        System.currentTimeMillis(),
                        items,
                        entities));
    }

    private void collect(GraveSpawnEvent event, List<ItemStack> items, List<EntityItem> entities) {
        if (event.loot == null) return;

        for (EntityItem entity : event.loot) {
            if (entity == null) continue;
            ItemStack stack = entity.getEntityItem();
            if (stack == null || stack.stackSize <= 0) continue;
            items.add(stack.copy());
            entities.add(entity);
        }
    }

    // ------------------------------------------------------------------ 校验

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (pending.isEmpty()) return;

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending item = it.next();
            if (++item.ticks < VERIFY_DELAY_TICKS) continue;

            Placement placement;
            try {
                placement = check(item);
            } catch (Throwable t) {
                LOG.warn("[HorizonFix] 校验墓碑是否生成失败", t);
                placement = Placement.UNKNOWN;
            }

            // 世界未载入时先等待；超过上限按已生成处理，不做返还，避免复制物品
            if (placement == Placement.UNKNOWN) {
                if (item.ticks < MAX_WAIT_TICKS) continue;
                LOG.warn("[HorizonFix] 无法判定 {} 的墓碑是否生成，按已生成处理，不改动物品", item.name);
                it.remove();
                continue;
            }

            it.remove();

            if (placement == Placement.PRESENT) continue;

            if (placement == Placement.DESTROYED) {
                LOG.info(
                        "[HorizonFix] {} 的墓碑生成后被破坏，物品已由 OpenBlocks 掉落在原地，不做返还",
                        item.name);
                continue;
            }

            if (placement == Placement.UNDETERMINED) {
                LOG.warn(
                        "[HorizonFix] {} 的墓碑位置为空且没有可用的墓碑备份，无法判定是否放置成功，不改动物品",
                        item.name);
                continue;
            }

            try {
                handleFailure(item);
            } catch (Throwable t) {
                LOG.error("[HorizonFix] 墓碑失败后的处理出现异常", t);
            }
        }
    }

    /**
     * 判定墓碑的实际状态。
     *
     * <p>只查方块是不够的：墓碑可能已经生成、随后被爆炸或凋零破坏。OpenBlocks 破坏墓碑时会把
     * 内容物掉到世界（{@code OpenBlock.breakBlock} 的 {@code getTileBreakDrops}），此时再返还一次
     * 就是复制物品。因此方块不在时改为读取 OpenBlocks 自己写的备份：
     * {@code Placed=true} 表示放置成功过，{@code Placed=false} 才是放置失败。</p>
     */
    private Placement check(Pending item) {
        if (!item.hasPosition) return Placement.MISSING;

        World world = DimensionManager.getWorld(item.dimensionId);
        if (world == null) return Placement.UNKNOWN;

        world.getChunkFromChunkCoords(item.x >> 4, item.z >> 4);

        Block block = world.getBlock(item.x, item.y, item.z);
        if (block != null && isType(block, GRAVE_BLOCK)) {
            // 方块已写入但 TileEntity 创建失败时，OpenBlocks 同样判定放置失败并把物品丢回地面
            TileEntity tile = world.getTileEntity(item.x, item.y, item.z);
            if (tile != null && isType(tile, GRAVE_TILE)) return Placement.PRESENT;
        }

        Boolean placed = readPlacedFlag(item);
        if (placed == null) return Placement.UNDETERMINED;
        return placed.booleanValue() ? Placement.DESTROYED : Placement.MISSING;
    }

    private static boolean isType(Object value, String className) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (className.equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * 读取本次死亡对应的墓碑备份里的 {@code Placed} 标记。
     *
     * @return {@code null} 表示找不到可用的备份（例如 {@code backupGraves=false}）
     */
    private Boolean readPlacedFlag(Pending item) {
        File folder = saveFolder(item.dimensionId);
        if (folder == null) return null;

        File[] files = folder.listFiles();
        if (files == null) return null;

        String prefix = PREFIX + safeName(item.name) + "-";
        Boolean result = null;
        long best = Long.MAX_VALUE;

        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(prefix) || !name.contains("-grave-") || !name.endsWith(".dat")) continue;

            try (InputStream in = new FileInputStream(file)) {
                NBTTagCompound root = CompressedStreamTools.readCompressed(in);
                if (root == null) continue;
                if (!item.owner.toString().equalsIgnoreCase(root.getString("PlayerUUID"))) continue;

                long delta = Math.abs(root.getLong("Created") - item.time);
                if (delta > BACKUP_WINDOW_MS || delta >= best) continue;

                best = delta;
                result = Boolean.valueOf(root.getBoolean("Placed"));
            } catch (Throwable ignored) {
                // 单个文件损坏不影响其余候选
            }
        }

        return result;
    }

    /** 墓碑的判定结果。 */
    private enum Placement {
        /** 方块与 TileEntity 都在。 */
        PRESENT,
        /** 墓碑确实没有生成。 */
        MISSING,
        /** 墓碑生成过、随后被破坏，内容物已由 OpenBlocks 掉到世界。 */
        DESTROYED,
        /** 世界未载入，当前无法判定。 */
        UNKNOWN,
        /** 方块不在且没有可用的备份，无法判定是否放置成功。 */
        UNDETERMINED
    }

    // ------------------------------------------------------------ 失败处理

    private void handleFailure(Pending item) {
        // 墓碑确认不存在，位置记录随之失效
        forget(item);

        if (!FixConfig.graveRecoveryEnabled) {
            notifyManualRestore(item);
            return;
        }

        if (FixConfig.graveRecoveryRemoveDropped) removeDropped(item);

        EntityPlayer player = findOnline(item.owner);

        if (player == null) {
            boolean stored = store(item);
            LOG.info(
                    "[HorizonFix] {} 的墓碑未能生成，玩家不在线，{} 件物品{}",
                    item.name,
                    item.items.size(),
                    stored ? "已暂存，登录后自动放回" : "暂存失败，备份文件保留");
            if (stored && FixConfig.graveRecoveryRemoveBackup) removeBackup(item);
            return;
        }

        if (!player.isEntityAlive()) {
            boolean stored = store(item);
            LOG.info(
                    "[HorizonFix] {} 的墓碑未能生成，玩家处于死亡状态，{} 件物品{}",
                    item.name,
                    item.items.size(),
                    stored ? "已暂存，重生后自动放回" : "暂存失败，备份文件保留");
            if (stored && FixConfig.graveRecoveryRemoveBackup) removeBackup(item);
            return;
        }

        int[] result = deliver(player, item.items);
        notifyReturned(player, result[0], result[1]);
        if (FixConfig.graveRecoveryRemoveBackup) removeBackup(item);

        LOG.info(
                "[HorizonFix] {} 的墓碑未能生成，已把 {} 件物品放回背包（{} 件因背包已满掉在脚下）",
                item.name,
                result[0],
                result[1]);
    }

    /** 移除 OpenBlocks 在放置失败时丢到地面的同一批掉落物，避免与返还的物品重复。 */
    private void removeDropped(Pending item) {
        for (EntityItem entity : item.entities) {
            try {
                if (entity == null || entity.isDead) continue;
                entity.setDead();
            } catch (Throwable t) {
                LOG.warn("[HorizonFix] 移除地面掉落物失败", t);
            }
        }
    }

    private static int[] deliver(EntityPlayer player, List<ItemStack> items) {
        int total = 0;
        int dropped = 0;

        for (ItemStack item : items) {
            if (item == null || item.stackSize <= 0) continue;
            total += item.stackSize;

            ItemStack copy = item.copy();
            boolean stored = player.inventory.addItemStackToInventory(copy);
            if (!stored && copy.stackSize > 0) {
                player.dropPlayerItemWithRandomChoice(copy, false);
                dropped += copy.stackSize;
            }
        }

        try {
            player.inventory.markDirty();
        } catch (Throwable ignored) {
            // 标记失败不影响物品已经进入背包这一事实
        }

        return new int[] { total, dropped };
    }

    private static EntityPlayer findOnline(UUID owner) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;

        List<?> players = server.getConfigurationManager().playerEntityList;
        for (Object candidate : players) {
            if (!(candidate instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) candidate;
            if (owner.equals(player.getUniqueID())) return player;
        }
        return null;
    }

    /** 墓碑已确认不存在，移除位置记录，避免 {@code /graves} 继续列出。 */
    private static void forget(Pending item) {
        if (!item.hasPosition) return;
        GraveTracker.get().forget(item.owner, item.dimensionId, item.x, item.y, item.z);
    }

    // -------------------------------------------------------- 手动恢复提示

    /** 自动返还关闭时保留 1.2.x 的行为：只提示，不改动物品。 */
    private void notifyManualRestore(Pending item) {
        if (!FixConfig.graveTrackerEnabled) return;

        EntityPlayer player = findOnline(item.owner);
        if (player == null) return;

        String snapshot = newestFailedBackup(item);
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

    // ------------------------------------------------------------ 备份文件

    /** 取本次失败放置留下的备份标识，找不到返回 null。 */
    private String newestFailedBackup(Pending item) {
        File folder = saveFolder(item.dimensionId);
        if (folder == null) return null;

        File[] files = folder.listFiles();
        if (files == null) return null;

        File newest = null;
        for (File file : files) {
            if (!isFailedGraveBackup(file, item)) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        if (newest == null) return null;

        String name = newest.getName();
        return name.substring(PREFIX.length(), name.length() - ".dat".length());
    }

    /** 删除本次失败放置留下的备份，避免 {@code /ob_inventory restore} 再复制一份物品。 */
    private void removeBackup(Pending item) {
        File folder = saveFolder(item.dimensionId);
        if (folder == null) return;

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!isFailedGraveBackup(file, item)) continue;
            try {
                if (file.delete()) {
                    LOG.info("[HorizonFix] 已删除墓碑备份 {}", file.getName());
                } else {
                    LOG.warn("[HorizonFix] 无法删除墓碑备份 {}", file.getAbsolutePath());
                }
            } catch (Throwable t) {
                LOG.warn("[HorizonFix] 删除墓碑备份失败 {}", file.getAbsolutePath(), t);
            }
        }
    }

    /**
     * 是否是本次死亡留下的失败备份。
     *
     * <p>成功放置的备份带 {@code Placed=true}，玩家取走墓碑后仍是有效的兜底记录，不能删除；
     * 只有 {@code Placed=false} 且 UUID、写入时间都对得上的那一份才与本次返还重复。</p>
     */
    private boolean isFailedGraveBackup(File file, Pending item) {
        String name = file.getName();
        if (!name.startsWith(PREFIX + safeName(item.name) + "-")) return false;
        if (!name.contains("-grave-") || !name.endsWith(".dat")) return false;

        try (InputStream in = new FileInputStream(file)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(in);
            if (root == null) return false;
            if (root.getBoolean("Placed")) return false;
            if (!item.owner.toString().equalsIgnoreCase(root.getString("PlayerUUID"))) return false;
            return Math.abs(root.getLong("Created") - item.time) <= BACKUP_WINDOW_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    private File saveFolder(int dimensionId) {
        World world = DimensionManager.getWorld(dimensionId);
        if (world == null || world.getSaveHandler() == null) return null;

        File probe = world.getSaveHandler().getMapFileFromName("horizonfix_probe");
        if (probe == null) return null;
        return probe.getParentFile();
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) return "";
        return SAFE_CHARS.matcher(name).replaceAll("_");
    }

    // -------------------------------------------------------------- 暂存发放

    private boolean store(Pending item) {
        List<ItemStack> target = waiting.get(item.owner);
        if (target == null) {
            target = new ArrayList<ItemStack>();
            waiting.put(item.owner, target);
        }
        for (ItemStack stack : item.items) {
            if (stack != null) target.add(stack.copy());
        }

        while (waiting.size() > MAX_WAITING) {
            Iterator<UUID> it = waiting.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }

        return save();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        deliverWaiting(event.player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        deliverWaiting(event.player);
    }

    private void deliverWaiting(EntityPlayer candidate) {
        if (!(candidate instanceof EntityPlayer)) return;
        EntityPlayer player = candidate;
        if (player instanceof FakePlayer) return;
        if (player.worldObj == null || player.worldObj.isRemote) return;
        // 死亡界面上的玩家先不发，等重生事件
        if (!player.isEntityAlive()) return;

        List<ItemStack> items = waiting.remove(player.getUniqueID());
        if (items == null || items.isEmpty()) return;

        int[] result = deliver(player, items);
        notifyReturned(player, result[0], result[1]);
        LOG.info(
                "[HorizonFix] 已把暂存的 {} 件物品放回 {} 的背包（{} 件掉在脚下）",
                result[0],
                player.getCommandSenderName(),
                result[1]);
        save();
    }

    private static void notifyReturned(EntityPlayer player, int total, int dropped) {
        StringBuilder text = new StringBuilder();
        text.append(EnumChatFormatting.YELLOW)
                .append("[墓碑] ")
                .append(EnumChatFormatting.RESET)
                .append("墓碑未能生成，已将 ")
                .append(EnumChatFormatting.AQUA)
                .append(total)
                .append(EnumChatFormatting.RESET)
                .append(" 件物品放回背包");
        if (dropped > 0) {
            text.append("（其中 ").append(dropped).append(" 件因背包已满掉在脚下）");
        }
        send(player, text.toString());
    }

    private static void send(EntityPlayer player, String message) {
        try {
            player.addChatMessage(new ChatComponentText(message));
        } catch (Throwable ignored) {
            // 提示失败不影响物品发放
        }
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (storage == null || !storage.isFile()) return;

        try (InputStream in = new FileInputStream(storage)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(in);
            if (root == null) return;

            NBTTagList entries = root.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);

                UUID owner;
                try {
                    owner = UUID.fromString(entry.getString("UUID"));
                } catch (IllegalArgumentException e) {
                    continue;
                }

                List<ItemStack> items = new ArrayList<ItemStack>();
                NBTTagList list = entry.getTagList("Items", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < list.tagCount(); j++) {
                    ItemStack stack = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(j));
                    if (stack != null) items.add(stack);
                }

                if (!items.isEmpty()) waiting.put(owner, items);
            }
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 读取待返还物品失败，按空记录继续", t);
        }
    }

    private boolean save() {
        if (storage == null) return false;

        try {
            if (waiting.isEmpty()) {
                if (storage.isFile() && !storage.delete()) {
                    LOG.warn("[HorizonFix] 无法删除空的待返还文件 {}", storage.getAbsolutePath());
                }
                return true;
            }

            File parent = storage.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                LOG.warn("[HorizonFix] 无法创建目录 {}", parent.getAbsolutePath());
                return false;
            }

            NBTTagCompound root = new NBTTagCompound();
            NBTTagList entries = new NBTTagList();

            for (Map.Entry<UUID, List<ItemStack>> entry : waiting.entrySet()) {
                NBTTagList items = new NBTTagList();
                for (ItemStack stack : entry.getValue()) {
                    if (stack == null) continue;
                    NBTTagCompound tag = new NBTTagCompound();
                    stack.writeToNBT(tag);
                    items.appendTag(tag);
                }
                if (items.tagCount() == 0) continue;

                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("UUID", entry.getKey().toString());
                tag.setTag("Items", items);
                entries.appendTag(tag);
            }

            root.setTag("Entries", entries);

            try (OutputStream out = new FileOutputStream(storage)) {
                CompressedStreamTools.writeCompressed(root, out);
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 保存待返还物品失败", t);
            return false;
        }
    }

    private static class Pending {

        final UUID owner;
        final String name;
        final int dimensionId;
        final boolean hasPosition;
        final int x;
        final int y;
        final int z;
        final long time;
        final List<ItemStack> items;
        final List<EntityItem> entities;
        int ticks;

        Pending(UUID owner, String name, int dimensionId, boolean hasPosition, int x, int y, int z, long time,
                List<ItemStack> items, List<EntityItem> entities) {
            this.owner = owner;
            this.name = name;
            this.dimensionId = dimensionId;
            this.hasPosition = hasPosition;
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
            this.items = items;
            this.entities = entities;
        }
    }
}
