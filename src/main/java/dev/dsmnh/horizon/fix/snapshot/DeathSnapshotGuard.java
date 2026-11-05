package dev.dsmnh.horizon.fix.snapshot;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.dsmnh.horizon.fix.FixConfig;

/**
 * 清除被取消的死亡留下的背包快照。
 *
 * <p>OpenBlocks 的 {@code PlayerInventoryStore.onPlayerDeath} 在 {@link EventPriority#HIGH} 无条件落盘，
 * 不检查 {@code isCanceled()}；Witchery 的吸血鬼免疫在 {@link EventPriority#NORMAL} 才取消死亡，
 * 顺序在后。于是每次「打不死」都会多出一份 {@code -death-N.dat}，而真正掉物品的
 * {@code PlayerDropsEvent} 并未触发。这些快照若被 {@code /ob_inventory restore} 恢复，等于凭空复制物品。</p>
 *
 * <p>做法是在 {@link EventPriority#HIGHEST}（早于 OpenBlocks）记录目录快照，在
 * {@link EventPriority#LOWEST}（晚于所有取消动作）检查事件是否被取消；被取消则删除本次新增的
 * 该玩家 death 快照。只删本次事件期间新增的文件，不碰历史快照。</p>
 */
public class DeathSnapshotGuard {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 与 OpenBlocks 的 SAFE_CHARS 保持一致，玩家名里的非法字符会被替换为下划线。 */
    private static final Pattern SAFE_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    private static final String PREFIX = "inventory-";

    /** 玩家 UUID → 死亡事件开始时的目录文件集合。 */
    private final Map<UUID, Set<String>> before = new HashMap<UUID, Set<String>>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeathStart(LivingDeathEvent event) {
        EntityPlayer player = playerOf(event);
        if (player == null) return;

        try {
            File folder = dumpFolder(player.worldObj);
            before.put(
                    player.getUniqueID(),
                    folder == null ? Collections.<String>emptySet() : listNames(folder));
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 记录死亡快照目录失败", t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onDeathEnd(LivingDeathEvent event) {
        EntityPlayer player = playerOf(event);
        if (player == null) return;

        Set<String> previous = before.remove(player.getUniqueID());
        if (previous == null) return;
        // 真死保留快照，只有被取消的死亡才需要清理
        if (!event.isCanceled()) return;

        try {
            File folder = dumpFolder(player.worldObj);
            if (folder == null) return;

            String owner = PREFIX + safeName(player) + "-";
            int removed = 0;

            for (String name : listNames(folder)) {
                if (previous.contains(name)) continue;
                if (!name.startsWith(owner) || !name.contains("-death-") || !name.endsWith(".dat")) continue;

                File file = new File(folder, name);
                if (file.delete()) {
                    removed++;
                } else {
                    LOG.warn("[HorizonFix] 无法删除被取消死亡的快照 {}", file.getAbsolutePath());
                }
            }

            if (removed > 0 && FixConfig.deathSnapshotLog) {
                LOG.info(
                        "[HorizonFix] 玩家 {} 的死亡已被取消，清理了 {} 份本不该生成的背包快照",
                        player.getCommandSenderName(),
                        removed);
            }
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 清理被取消死亡的快照时出错", t);
        }
    }

    private static EntityPlayer playerOf(LivingDeathEvent event) {
        if (!(event.entityLiving instanceof EntityPlayer)) return null;

        EntityPlayer player = (EntityPlayer) event.entityLiving;
        if (player instanceof FakePlayer) return null;
        if (player.worldObj == null || player.worldObj.isRemote) return null;
        return player;
    }

    /** 取 OpenBlocks 写入快照的目录：{@code <世界目录>/data}。 */
    private static File dumpFolder(World world) {
        if (world == null || world.getSaveHandler() == null) return null;
        File probe = world.getSaveHandler().getMapFileFromName("horizonfix_probe");
        return probe == null ? null : probe.getParentFile();
    }

    private static Set<String> listNames(File folder) {
        Set<String> names = new HashSet<String>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                names.add(file.getName());
            }
        }
        return names;
    }

    private static String safeName(EntityPlayer player) {
        GameProfile profile = player.getGameProfile();
        String name = profile == null ? null : profile.getName();
        if (name == null || name.isEmpty()) name = player.getCommandSenderName();
        return SAFE_CHARS.matcher(name).replaceAll("_");
    }
}
