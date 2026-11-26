package dev.dsmnh.horizon.fix.ae2;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.networking.security.BaseActionSource;
import appeng.api.parts.IPart;
import appeng.api.storage.IExternalStorageHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStackType;
import appeng.util.item.AEItemStackType;

import dev.dsmnh.horizon.fix.FixConfig;

/**
 * 拦截存储总线读取抽屉管理器。
 *
 * <p>Storage Drawers 向 AE2 注册的外部存储处理器会把整个抽屉组包装成
 * {@code DrawerMEInventory}。存储总线每次轮询都会调用 {@code getAvailableItems}，
 * 该实现遍历管理器下全部抽屉，并对每个非空抽屉执行一次完整的 {@code ItemStack.copy()}
 * （含 NBT）。抽屉数量越多，单个 tick 的耗时越长，服务器 TPS 随之下滑。</p>
 *
 * <p>这里不返回 {@code null}：返回 {@code null} 会让存储总线认为邻接方块暂时不可用，
 * 于是每 tick 重建一次缓存并重新调度，反而放大开销。返回一个恒空的库存可以让总线
 * 得到一个合法 handler，并在没有监视器时进入休眠。</p>
 */
public final class StorageDrawerBlocker implements IExternalStorageHandler {

    public static final StorageDrawerBlocker INSTANCE = new StorageDrawerBlocker();

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 抽屉管理器。 */
    private static final String CLASS_CONTROLLER =
            "com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityController";

    /** 管理器下属的抽屉从站，读写全部代理到管理器，开销与管理器相同。 */
    private static final String CLASS_SLAVE = "com.jaquadro.minecraft.storagedrawers.block.tile.TileEntitySlave";

    /** 存储总线部件。 */
    private static final String CLASS_STORAGE_BUS = "appeng.parts.misc.PartStorageBus";

    /** 单个抽屉方块，其子类为标准抽屉与压缩抽屉。 */
    private static final String CLASS_DRAWERS = "com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityDrawers";

    /** 已提示过的坐标，避免同一次连接被反复提示。 */
    private static final Set<String> NOTIFIED = Collections.synchronizedSet(new LinkedHashSet<String>());

    /** 提示次数上限，超过后只写日志。 */
    private static final int NOTIFY_LIMIT = 32;

    /** 启动自检期间不写日志、不提示玩家，避免留下坐标为 (0, 0, 0) 的误导记录。 */
    private static volatile boolean selfTesting;

    private StorageDrawerBlocker() {}

    @Override
    public boolean canHandle(TileEntity te, ForgeDirection d, IAEStackType<?> type, BaseActionSource src) {
        if (type != AEItemStackType.ITEM_STACK_TYPE) return false;
        return shouldBlock(te);
    }

    @Override
    public IMEInventory getInventory(TileEntity te, ForgeDirection d, IAEStackType<?> type, BaseActionSource src) {
        if (!canHandle(te, d, type, src)) return null;
        return EmptyItemInventory.INSTANCE;
    }

    /** 按类名判定目标是否为抽屉方块，避免加载 Storage Drawers 的类。 */
    public static boolean shouldBlock(TileEntity te) {
        if (te == null) return false;
        if (!FixConfig.storageBusDenyDrawerController && !FixConfig.storageBusDenyDrawers) return false;

        String name = matchDrawerClass(te);
        if (name == null) return false;

        boolean controller = CLASS_CONTROLLER.equals(name) || CLASS_SLAVE.equals(name);
        if (controller && !FixConfig.storageBusDenyDrawerController) return false;
        if (!controller && !FixConfig.storageBusDenyDrawers) return false;

        onBlocked(te, controller);
        return true;
    }

    /** 判定目标是否为抽屉管理器本身。 */
    public static boolean isDrawerController(TileEntity te) {
        return te != null && CLASS_CONTROLLER.equals(matchDrawerClass(te));
    }

    /**
     * 判定目标是否为抽屉管理器或其从站。
     *
     * <p>从站把读写全部代理给管理器，贴到从站上造成的开销与管理器相同，因此一并处理。</p>
     */
    public static boolean isDrawerControllerOrSlave(TileEntity te) {
        if (te == null) return false;
        String name = matchDrawerClass(te);
        return CLASS_CONTROLLER.equals(name) || CLASS_SLAVE.equals(name);
    }

    /** 判定部件是否为存储总线，用于在放置瞬间识别贴附动作。 */
    public static boolean isStorageBus(IPart part) {
        if (part == null) return false;
        // 先转成 Object 再取类，避免编译期解析 IPart 的泛型继承链
        for (Class<?> c = ((Object) part).getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (CLASS_STORAGE_BUS.equals(c.getName())) return true;
        }
        return false;
    }

    /** 沿继承链匹配，兼容标准抽屉与压缩抽屉这类子类。 */
    private static String matchDrawerClass(TileEntity te) {
        for (Class<?> c = te.getClass(); c != null && c != TileEntity.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (CLASS_CONTROLLER.equals(name) || CLASS_SLAVE.equals(name) || CLASS_DRAWERS.equals(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 进入启动自检状态：此期间不写坐标日志、不提示玩家。
     *
     * <p>自检会真实调用一次 AE2 的外部存储入口，该调用同样会经过 mixin 并落到本类，
     * 因此静默范围必须覆盖整个自检流程，不能只覆盖一次判定。</p>
     */
    public static void beginSelfTest() {
        selfTesting = true;
    }

    /** 结束启动自检状态。 */
    public static void endSelfTest() {
        selfTesting = false;
    }

    /** 供启动自检使用：只判定，不写日志也不提示玩家。 */
    public static boolean verifySilently(TileEntity te) {
        return shouldBlock(te);
    }

    private static void onBlocked(TileEntity te, boolean controller) {
        if (selfTesting) return;

        String key = te.getWorldObj() == null
                ? te.xCoord + "," + te.yCoord + "," + te.zCoord
                : te.getWorldObj().provider.dimensionId + ":" + te.xCoord + "," + te.yCoord + "," + te.zCoord;

        boolean first = false;
        synchronized (NOTIFIED) {
            if (NOTIFIED.add(key) && NOTIFIED.size() <= NOTIFY_LIMIT) {
                first = true;
            }
        }
        if (!first) return;

        LOG.info(
                "[HorizonFix] 已禁止存储总线读取{} @dim={} ({}, {}, {})",
                controller ? "抽屉管理器" : "抽屉方块",
                te.getWorldObj() == null ? 0 : te.getWorldObj().provider.dimensionId,
                te.xCoord,
                te.yCoord,
                te.zCoord);

        if (!FixConfig.storageBusNotifyPlayers) return;
        notifyNearby(te);
    }

    /** 提示附近的玩家，否则总线显示为空会被误判为故障。 */
    private static void notifyNearby(TileEntity te) {
        World world = te.getWorldObj();
        if (world == null || world.isRemote) return;

        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                te.xCoord - 8, te.yCoord - 8, te.zCoord - 8, te.xCoord + 9, te.yCoord + 9, te.zCoord + 9);
        List<?> players = world.getEntitiesWithinAABB(EntityPlayer.class, box);
        if (players.isEmpty()) return;

        String message = EnumChatFormatting.YELLOW + "[HorizonFix] " + EnumChatFormatting.RESET
                + "存储总线已禁止读取抽屉：存储总线每次轮询都要遍历全部抽屉并复制物品，会让服务器变慢。"
                + "请改用接口（ME Interface）接入 ME 网络。";
        for (Object o : players) {
            ((EntityPlayer) o).addChatMessage(new ChatComponentText(message));
        }
    }
}
