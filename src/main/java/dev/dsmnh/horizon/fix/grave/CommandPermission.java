package dev.dsmnh.horizon.fix.grave;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 让 {@code /graves} 对所有人可用。
 *
 * <p>混合端会把 Forge 命令包装成 Bukkit 命令，执行前走 {@code Command.testPermissionSilent}：
 * 只要命令对象带 permission 字段，非 op 就会被拒。这里用纯反射做两件事——</p>
 *
 * <ol>
 * <li>把 {@code bukkit.command.graves} 注册为默认允许，供按权限插件默认值判定的服务端使用；</li>
 * <li>直接把命令表里 {@code graves} 条目的 permission 字段置空，不依赖任何权限插件配置。</li>
 * </ol>
 *
 * <p>服务端没有 Bukkit 时整段静默跳过，不影响纯 Forge 环境。</p>
 */
public class CommandPermission {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 命令表内部字段可能的命名，按顺序尝试。 */
    private static final String[] MAP_FIELDS = { "knownCommands", "field_96573_b", "backingMap" };

    /** CraftServer 上可能存在的命令表字段。 */
    private static final String[] SERVER_FIELDS = { "craftCommandMap", "commandMap" };

    private static int attempts;

    private static boolean done;

    /** 幂等入口：命令还没进入命令表时由 tick 循环重试。 */
    public static void ensureNoPermission(String commandName) {
        if (done || attempts >= 200) return;
        attempts++;
        done = clearPermission(commandName);
        if (!done && attempts >= 200) {
            LOG.warn(
                    "[HorizonFix] 多次尝试后仍未在命令表里找到 {}，请手动授予 bukkit.command.{}",
                    commandName,
                    commandName);
        }
    }

    /** 每次服务端启动重新计数。 */
    public static void resetAttempts() {
        attempts = 0;
        done = false;
    }

    /** 把权限节点注册为默认允许。 */
    public static void allowEveryone(String node) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);

            Class<?> permissionClass = Class.forName("org.bukkit.permissions.Permission");
            Method getPermission = pluginManager.getClass().getMethod("getPermission", String.class);

            Object existing = getPermission.invoke(pluginManager, node);
            if (existing != null) {
                pluginManager.getClass().getMethod("removePermission", permissionClass).invoke(pluginManager, existing);
            }

            Class<?> defaultClass = Class.forName("org.bukkit.permissions.PermissionDefault");
            Object allow = defaultClass.getField("TRUE").get(null);
            Object permission = permissionClass.getConstructor(String.class, defaultClass).newInstance(node, allow);

            pluginManager.getClass().getMethod("addPermission", permissionClass).invoke(pluginManager, permission);
            LOG.info("[HorizonFix] 权限节点 {} 已注册为默认允许", node);
        } catch (ClassNotFoundException e) {
            LOG.debug("[HorizonFix] 非 Bukkit 服务端，跳过权限节点注册");
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 注册权限节点 {} 失败: {}", node, t.toString());
        }
    }

    /**
     * 清掉命令对象上的权限要求。
     *
     * @return 是否至少清掉了一条；命令尚未进入命令表时返回 false
     */
    public static boolean clearPermission(String commandName) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object server = bukkit.getMethod("getServer").invoke(null);
            if (server == null) return false;

            int cleared = 0;
            for (Object commandMap : commandMaps(server)) {
                cleared += clearInMap(commandMap, commandName);
            }

            if (cleared > 0) {
                LOG.info("[HorizonFix] 已清除 {} 条 {} 命令的权限要求", cleared, commandName);
                return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            LOG.debug("[HorizonFix] 非 Bukkit 服务端，跳过权限清除");
            return true;
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 清除命令 {} 的权限失败: {}", commandName, t.toString());
            return false;
        }
    }

    /** CraftServer 上有两张表：Bukkit 的 commandMap 与混合端的 craftCommandMap，两张都要处理。 */
    private static List<Object> commandMaps(Object server) {
        List<Object> maps = new ArrayList<Object>();

        try {
            Object map = server.getClass().getMethod("getCommandMap").invoke(server);
            if (map != null) maps.add(map);
        } catch (Throwable ignored) {
            // 没有该方法时只走字段
        }

        for (String name : SERVER_FIELDS) {
            try {
                Field field = server.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object map = field.get(server);
                if (map != null && !containsSame(maps, map)) maps.add(map);
            } catch (Throwable ignored) {
                // 继续尝试下一个字段
            }
        }
        return maps;
    }

    private static boolean containsSame(List<Object> maps, Object candidate) {
        for (Object map : maps) {
            if (map == candidate) return true;
        }
        return false;
    }

    private static int clearInMap(Object commandMap, String commandName) {
        int cleared = 0;

        Object direct = null;
        try {
            direct = commandMap.getClass().getMethod("getCommand", String.class).invoke(commandMap, commandName);
        } catch (Throwable ignored) {
            // 走下面的遍历
        }
        if (direct != null && clear(direct)) cleared++;

        Object raw = readMapField(commandMap);
        if (!(raw instanceof Map)) return cleared;

        for (Object entryObject : ((Map<?, ?>) raw).entrySet()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (!key.equals(commandName) && !key.endsWith(":" + commandName)) continue;

            Object command = entry.getValue();
            if (command == null || command == direct) continue;
            if (clear(command)) cleared++;
        }
        return cleared;
    }

    private static Object readMapField(Object commandMap) {
        for (String name : MAP_FIELDS) {
            try {
                Field field = commandMap.getClass().getField(name);
                return field.get(commandMap);
            } catch (Throwable ignored) {
                // 继续尝试下一个名字
            }
            try {
                Field field = commandMap.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(commandMap);
            } catch (Throwable ignored) {
                // 继续尝试下一个名字
            }
        }
        return null;
    }

    private static boolean clear(Object command) {
        try {
            command.getClass().getMethod("setPermission", String.class).invoke(command, new Object[] { null });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
