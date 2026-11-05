package dev.dsmnh.horizon.fix;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.EventBus;
import dev.dsmnh.horizon.fix.grave.CommandGraves;
import dev.dsmnh.horizon.fix.grave.GraveHandler;
import dev.dsmnh.horizon.fix.grave.GraveTracker;
import dev.dsmnh.horizon.fix.snapshot.DeathSnapshotGuard;
import dev.dsmnh.horizon.fix.wr.WrNpeHandler;

/**
 * 服务端修复合集。
 *
 * <p>每个修复项都在 {@link FixConfig} 中有独立开关，关闭后对应的监听器不会被注册，
 * 运行期开销为零。所有修复都不修改目标模组的字节码，卸载 jar 即完全回滚。</p>
 */
@Mod(
        modid = HorizonFix.MODID,
        name = HorizonFix.NAME,
        version = HorizonFix.VERSION,
        acceptableRemoteVersions = "*",
        dependencies = "after:OpenBlocks")
public class HorizonFix {

    public static final String MODID = "horizonfix";
    public static final String NAME = "Horizon Fix";
    public static final String VERSION = "@VERSION@";

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File file = new File(event.getModConfigurationDirectory(), "HorizonFix.cfg");
        FixConfig.load(file);
        GraveTracker.get().init(event.getModConfigurationDirectory());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        registerGrave();
        registerGraveTracker();
        registerDeathSnapshot();
        registerWirelessRedstone();
    }

    private void registerGrave() {
        if (!FixConfig.graveEnabled) {
            LOG.info("[HorizonFix] 墓碑修正已关闭");
            return;
        }
        if (!Loader.isModLoaded("OpenBlocks")) {
            LOG.info("[HorizonFix] 未检测到 OpenBlocks，墓碑修正不注册");
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(new GraveHandler());
            LOG.info("[HorizonFix] 墓碑修正已注册");
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 墓碑修正注册失败，该功能不生效", t);
        }
    }

    private void registerGraveTracker() {
        if (!FixConfig.graveTrackerEnabled) {
            LOG.info("[HorizonFix] 墓碑位置提醒已关闭");
            return;
        }
        if (!Loader.isModLoaded("OpenBlocks")) {
            LOG.info("[HorizonFix] 未检测到 OpenBlocks，墓碑位置提醒不注册");
            return;
        }
        GraveTracker tracker = GraveTracker.get();
        MinecraftForge.EVENT_BUS.register(tracker);
        FMLCommonHandler.instance().bus().register(tracker);
        LOG.info("[HorizonFix] 墓碑位置提醒已注册");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (!FixConfig.graveTrackerEnabled) return;
        try {
            event.registerServerCommand(new CommandGraves());
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] /graves 命令注册失败，可能是命令名冲突", t);
        }
    }

    private void registerDeathSnapshot() {
        if (!FixConfig.deathSnapshotEnabled) {
            LOG.info("[HorizonFix] 被取消死亡的快照清理已关闭");
            return;
        }
        if (!Loader.isModLoaded("OpenBlocks")) {
            LOG.info("[HorizonFix] 未检测到 OpenBlocks，快照清理不注册");
            return;
        }
        MinecraftForge.EVENT_BUS.register(new DeathSnapshotGuard());
        LOG.info("[HorizonFix] 被取消死亡的快照清理已注册");
    }

    private void registerWirelessRedstone() {
        if (!FixConfig.wrEnabled) {
            LOG.info("[HorizonFix] 无线红石补建已关闭");
            return;
        }
        WrNpeHandler handler = new WrNpeHandler();
        if (!handler.arm()) {
            return;
        }
        EventBus fmlBus = FMLCommonHandler.instance().bus();
        fmlBus.register(handler);
        if (MinecraftForge.EVENT_BUS != fmlBus) {
            MinecraftForge.EVENT_BUS.register(handler);
        }
        LOG.info("[HorizonFix] 无线红石补建已注册");
    }
}
