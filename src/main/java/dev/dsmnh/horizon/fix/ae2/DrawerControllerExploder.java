package dev.dsmnh.horizon.fix.ae2;

import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.dsmnh.horizon.fix.FixConfig;

/**
 * 存储总线贴附抽屉管理器时的爆炸惩罚。
 *
 * <p>行为对齐 GT5 机器的爆炸流程：先清除方块，再在方块中心生成一次爆炸，最后向所在区块追加污染。
 * 区别只在触发条件与数值：触发条件是玩家把存储总线贴到抽屉管理器（或其从站）上，
 * 污染量为配置值（默认 3000000，为 GT 默认机器爆炸污染的 30 倍）。</p>
 *
 * <p>本类只在放置成功的瞬间被调用一次，不注册任何 tick 监听，也不做周期性扫描。
 * 运行期的读取拦截由 {@link StorageDrawerBlocker} 负责，那是一个按类名判定的常量级开销。</p>
 */
public final class DrawerControllerExploder {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** GT5 的污染入口；通过反射调用，不引入编译期依赖。 */
    private static final String CLASS_POLLUTION = "gregtech.common.pollution.Pollution";

    private DrawerControllerExploder() {}

    /**
     * 炸掉被贴附的抽屉管理器并追加污染。
     *
     * @param world  目标所在世界，调用方需保证为主线程且非客户端
     * @param target 被贴附的抽屉管理器或其从站
     * @param player 放置存储总线的玩家，读档与自动生成时为 {@code null}
     */
    public static void explode(World world, TileEntity target, EntityPlayer player) {
        if (world == null || world.isRemote || target == null) return;

        final int x = target.xCoord;
        final int y = target.yCoord;
        final int z = target.zCoord;
        final int dim = world.provider.dimensionId;
        final boolean controller = StorageDrawerBlocker.isDrawerController(target);
        final String what = controller ? "抽屉管理器" : "抽屉管理器从站";

        // 与 GT 一致：先把方块清空，再生成爆炸
        world.setBlockToAir(x, y, z);

        final float power = (float) FixConfig.storageBusExplosionPower;
        if (power > 0F) {
            world.createExplosion(null, x + 0.5D, y + 0.5D, z + 0.5D, power, true);
        }

        final int pollution = FixConfig.storageBusExplosionPollution;
        final boolean polluted = addPollution(world, x, z, pollution);

        LOG.info(
                "[HorizonFix] 存储总线贴附{}触发爆炸 @dim={} ({}, {}, {}) 放置者={} 爆炸强度={} 污染={}（{}）",
                what,
                dim,
                x,
                y,
                z,
                player == null ? "未知" : player.getCommandSenderName(),
                power,
                pollution,
                polluted ? "已施加" : "未施加");

        broadcast(what, dim, x, y, z, player, polluted);
    }

    /** 向目标所在区块追加污染，GT 未安装或调用失败时返回 {@code false}。 */
    private static boolean addPollution(World world, int x, int z, int amount) {
        if (amount == 0) return false;
        try {
            Class<?> pollution = Class.forName(CLASS_POLLUTION);
            Method add = pollution.getMethod("addPollution", World.class, int.class, int.class, int.class);
            add.invoke(null, world, x >> 4, z >> 4, amount);
            return true;
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 追加污染失败，GregTech 未安装或接口不匹配: {}", t.toString());
            return false;
        }
    }

    /** 向全服公屏播报，让在线玩家知道这里发生过什么。 */
    private static void broadcast(String what, int dim, int x, int y, int z, EntityPlayer player,
            boolean polluted) {
        if (!FixConfig.storageBusNotifyPlayers) return;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;

        StringBuilder message = new StringBuilder();
        message.append(EnumChatFormatting.YELLOW)
                .append("[HorizonFix] ")
                .append(EnumChatFormatting.RESET)
                .append("坐标 ")
                .append(EnumChatFormatting.AQUA)
                .append('(')
                .append(x)
                .append(", ")
                .append(y)
                .append(", ")
                .append(z)
                .append(')')
                .append(EnumChatFormatting.RESET)
                .append("（维度 ")
                .append(dim)
                .append("）的")
                .append(what)
                .append("无法承受 AE 外星科技的灌注，当场解体");
        if (polluted) {
            message.append("并美化了周围的环境");
        }
        message.append('。');
        if (player != null) {
            message.append("放置者为 ").append(player.getCommandSenderName()).append("。");
        }
        message.append("存储总线每次轮询都要遍历全部抽屉并复制物品，服务器无法承担这样的开销。");

        server.getConfigurationManager().sendChatMsg(new ChatComponentText(message.toString()));
    }
}
