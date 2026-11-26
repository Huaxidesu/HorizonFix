package dev.dsmnh.horizon.fix.mixin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraft.launchwrapper.Launch;

import dev.dsmnh.horizon.fix.FixConfig;

/**
 * 按目标模组的 jar 是否存在决定加载哪些适配 mixin。
 *
 * <p>插件运行在启动早期，此处只做文件名判定，不加载任何目标模组类。</p>
 */
public class MixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LogManager.getLogger("HorizonFix mixins");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(java.util.Set<String> myTargets, java.util.Set<String> otherTargets) {
    }

    private static boolean configLoaded = false;

    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        configLoaded = true;
        try {
            File configDir;
            if (Launch.minecraftHome != null) {
                configDir = new File(Launch.minecraftHome, "config");
            } else {
                String userDir = System.getProperty("user.dir");
                if (userDir != null && !userDir.isEmpty()) {
                    configDir = new File(userDir, "config");
                } else {
                    configDir = new File("config");
                }
            }
            File configFile = new File(configDir, "HorizonFix.cfg");
            FixConfig.load(configFile);
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] mixin 阶段读取配置失败，使用默认配置: {}", t.toString());
        }
    }

    @Override
    public List<String> getMixins() {
        ensureConfigLoaded();
        LOG.info("[HorizonFix] mixin 探测 minecraftHome={} user.dir={}",
                Launch.minecraftHome, System.getProperty("user.dir"));
        List<String> result = new ArrayList<String>();
        if (isModPresent("ExtraUtilities", "com.rwtema.extrautils.EventHandlerServer")) {
            if (FixConfig.extraUtilsDivisionSigil) {
                result.add("MixinExtraUtilsActivationRitual");
                result.add("MixinExtraUtilsDivisionSigil");
                result.add("MixinExtraUtilsSiege");
            } else {
                LOG.info("[HorizonFix] ExtraUtilities 分割徽章修复已在配置中关闭（GTNH 2.9.1+ 已由 Hodgepodge 原生支持）");
            }
            if (FixConfig.extraUtilsTrashCan) {
                result.add("MixinExtraUtilsTrashCan");
            }
        }
        if (FixConfig.botaniaSpecialFlower && isModPresent("Botania", "vazkii.botania.common.block.BlockSpecialFlower")) {
            result.add("MixinBotaniaSpecialFlower");
        }
        if (FixConfig.mrtjpBlockUpdate && isModPresent("MrTJPCore", "mrtjp.core.world.BlockUpdateHandler$")) {
            result.add("MixinMrTjpBlockUpdateHandler");
        }
        if ((FixConfig.storageBusDenyDrawerController || FixConfig.storageBusDenyDrawers)
                && isModPresent("appliedenergistics2", "appeng.api.AEApi")
                && isModPresent("StorageDrawers", "com.jaquadro.minecraft.storagedrawers.StorageDrawers")) {
            result.add("MixinAe2ExternalStorageRegistry");
        }
        LOG.info("[HorizonFix] 适配 mixin 已启用 {} 项：{}", result.size(), result);
        return result;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /** 优先按类路径资源判定；启动早期 jar 尚未加入类路径时回退到多个候选 mods 目录。 */
    private static boolean isModPresent(String namePart, String probeClass) {
        try {
            if (Launch.classLoader != null
                    && Launch.classLoader.getResource(probeClass.replace('.', '/') + ".class") != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // 类路径探测失败时继续按文件名判定
        }
        for (File mods : candidateModsDirs()) {
            if (containsJar(mods, namePart, 1)) {
                return true;
            }
        }
        return false;
    }

    private static List<File> candidateModsDirs() {
        List<File> dirs = new ArrayList<File>();
        if (Launch.minecraftHome != null) {
            dirs.add(new File(Launch.minecraftHome, "mods"));
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            dirs.add(new File(userDir, "mods"));
        }
        dirs.add(new File("mods").getAbsoluteFile());
        return dirs;
    }

    private static boolean containsJar(File dir, String namePart, int depth) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory() && depth > 0) {
                if (containsJar(file, namePart, depth - 1)) {
                    return true;
                }
            } else if (file.isFile()
                    && file.getName().toLowerCase().endsWith(".jar")
                    && file.getName().toLowerCase().contains(namePart.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
