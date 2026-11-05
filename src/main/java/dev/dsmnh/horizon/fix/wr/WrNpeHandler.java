package dev.dsmnh.horizon.fix.wr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import net.minecraftforge.common.util.FakePlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import dev.dsmnh.horizon.fix.FixConfig;

/**
 * WR-CBE Addons 空指针崩溃修复。
 *
 * <p>崩溃点：{@code RedstoneEtherServerAddons.deactivateRemote} 取不到玩家附加数据时未判空，
 * 而 {@code onDimensionChange} 先 deactivate 后 put。本类在登录与切维度事件上以
 * {@link EventPriority#HIGHEST} 先于 WR-CBE 的 NORMAL 监听器补建条目。</p>
 *
 * <p>全部通过反射访问 WR-CBE 内部结构，任何一步失败都只让补丁静默失效。崩溃路径见 README。</p>
 */
public class WrNpeHandler {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    private static final String ETHER_ADDONS = "codechicken.wirelessredstone.addons.RedstoneEtherAddons";
    private static final String SERVER_ADDONS = "codechicken.wirelessredstone.addons.RedstoneEtherServerAddons";

    private Method methodServer;
    private Method methodOnLogin;
    private Field fieldPlayerInfos;
    private Field fieldEventPlayer;
    private Method methodSenderName;

    private int loginRepairs;
    private int dimensionRepairs;

    /**
     * 完成反射挂钩。
     *
     * @return 挂钩是否全部命中；返回 false 时调用方不应注册本监听器
     */
    public boolean arm() {
        try {
            Class<?> clsEtherAddons = Class.forName(ETHER_ADDONS);
            Class<?> clsServerAddons = Class.forName(SERVER_ADDONS);

            methodServer = clsEtherAddons.getMethod("server");

            fieldPlayerInfos = clsServerAddons.getDeclaredField("playerInfos");
            fieldPlayerInfos.setAccessible(true);

            for (Method candidate : clsServerAddons.getMethods()) {
                if (candidate.getName().equals("onLogin") && candidate.getParameterTypes().length == 1) {
                    methodOnLogin = candidate;
                    break;
                }
            }

            fieldEventPlayer = PlayerEvent.class.getDeclaredField("player");
            fieldEventPlayer.setAccessible(true);

            boolean armed = methodServer != null && methodOnLogin != null && fieldPlayerInfos != null
                    && fieldEventPlayer != null;

            if (armed) {
                LOG.info(
                        "[HorizonFix] 无线红石挂钩目标: {}.onLogin({}), 字段 {} ({})",
                        clsServerAddons.getSimpleName(),
                        methodOnLogin.getParameterTypes()[0].getName(),
                        fieldPlayerInfos.getName(),
                        fieldPlayerInfos.getType().getSimpleName());
            } else {
                LOG.warn("[HorizonFix] 无线红石挂钩不完整，该功能不生效");
            }
            return armed;
        } catch (ClassNotFoundException e) {
            LOG.info("[HorizonFix] 未检测到 WR-CBE Addons，无线红石补建不注册");
            return false;
        } catch (NoClassDefFoundError e) {
            LOG.info("[HorizonFix] 未检测到 WR-CBE Addons，无线红石补建不注册");
            return false;
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 无线红石挂钩失败，该功能不生效: {}", t.toString());
            return false;
        }
    }

    /** 传送门、/home、/tpa 等跨维度传送都会走这个事件，必须抢在 WR-CBE 之前。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        guard(event, true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!FixConfig.wrRepairOnLogin) return;
        guard(event, false);
    }

    private void guard(Object event, boolean dimensionChange) {
        try {
            Object player = fieldEventPlayer.get(event);
            if (player == null || player instanceof FakePlayer) return;

            Object ether = methodServer.invoke(null);
            if (ether == null) return;

            Object rawMap = fieldPlayerInfos.get(ether);
            if (!(rawMap instanceof Map)) return;
            Map<?, ?> map = (Map<?, ?>) rawMap;

            String name = senderNameOf(player);
            if (name != null && map.get(name) != null) return;

            methodOnLogin.invoke(ether, player);

            if (dimensionChange) {
                dimensionRepairs++;
                if (FixConfig.wrLogDimensionRepair) {
                    LOG.info(
                            "[HorizonFix] [dim] 玩家 {} 缺少无线红石附加数据，已在使用前补建（第 {} 次）",
                            name == null ? "?" : name,
                            dimensionRepairs);
                }
            } else {
                loginRepairs++;
                if (FixConfig.wrLogLoginRepair) {
                    LOG.info(
                            "[HorizonFix] [login] 玩家 {} 补建无线红石附加数据（第 {} 次）",
                            name == null ? "?" : name,
                            loginRepairs);
                } else {
                    LOG.debug("[HorizonFix] [login] 玩家 {} 补建无线红石附加数据", name == null ? "?" : name);
                }
            }
        } catch (Throwable ignored) {
            // 补丁自身绝不能把异常抛回服务器主线程
        }
    }

    private String senderNameOf(Object player) throws Exception {
        Method m = methodSenderName;
        if (m == null) {
            for (Method candidate : player.getClass().getMethods()) {
                if (candidate.getParameterTypes().length != 0) continue;
                if (candidate.getReturnType() != String.class) continue;
                String n = candidate.getName();
                if (n.equals("func_70005_c_") || n.equals("getCommandSenderName")) {
                    m = candidate;
                    break;
                }
            }
            methodSenderName = m;
        }
        return m == null ? null : (String) m.invoke(player);
    }
}
