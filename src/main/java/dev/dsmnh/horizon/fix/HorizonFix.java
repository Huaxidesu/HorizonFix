package dev.dsmnh.horizon.fix;

import java.io.File;

import net.minecraft.tileentity.TileEntity;
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
import dev.dsmnh.horizon.fix.grave.CommandPermission;
import dev.dsmnh.horizon.fix.grave.GraveHandler;
import dev.dsmnh.horizon.fix.grave.GraveRecovery;
import dev.dsmnh.horizon.fix.grave.GraveTracker;
import dev.dsmnh.horizon.fix.snapshot.DeathSnapshotGuard;
import dev.dsmnh.horizon.fix.world.CascadeWorldgenDiagnostic;
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
        dependencies = "after:OpenBlocks;after:appliedenergistics2;after:StorageDrawers")
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
        GraveRecovery.get().init(event.getModConfigurationDirectory());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        registerGrave();
        registerGraveTracker();
        registerGraveRecovery();
        registerDeathSnapshot();
        registerWirelessRedstone();
        registerWorldgenDiagnostic();
        checkAe2StorageBusGuard();
    }

    /** 确认存储总线拦截的 mixin 已注入，注入失败时明确告警，避免误以为生效。 */
    private void checkAe2StorageBusGuard() {
        if (!FixConfig.storageBusDenyDrawerController && !FixConfig.storageBusDenyDrawers) {
            LOG.info("[HorizonFix] 存储总线拦截已关闭");
            return;
        }
        if (!Loader.isModLoaded("appliedenergistics2") || !Loader.isModLoaded("StorageDrawers")) {
            LOG.info("[HorizonFix] 未检测到 AppliedEnergistics2 或 StorageDrawers，存储总线拦截不注册");
            return;
        }
        dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker.beginSelfTest();
        try {
            // 直接构造一个抽屉管理器，走一遍 AE2 的取处理器入口：返回拦截器即说明注入生效
            TileEntity probe = (TileEntity) Class
                    .forName("com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityController")
                    .newInstance();
            boolean blocked = dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker.verifySilently(probe);
            Object handler = appeng.api.AEApi.instance().registries().externalStorage().getHandler(
                    probe,
                    net.minecraftforge.common.util.ForgeDirection.UNKNOWN,
                    appeng.util.item.AEItemStackType.ITEM_STACK_TYPE,
                    new appeng.api.networking.security.MachineSource(null));
            boolean intercepted = handler == dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker.INSTANCE;
            if (blocked && intercepted) {
                LOG.info("[HorizonFix] 存储总线拦截已生效（抽屉管理器={}，单个抽屉={}）",
                        FixConfig.storageBusDenyDrawerController ? "禁止" : "允许",
                        FixConfig.storageBusDenyDrawers ? "禁止" : "允许");
            } else {
                LOG.warn("[HorizonFix] 存储总线拦截未生效（判定={}，入口={}），请检查 mixin 是否注入",
                        blocked, intercepted);
            }
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 存储总线拦截自检失败，无法确认是否生效", t);
        } finally {
            dev.dsmnh.horizon.fix.ae2.StorageDrawerBlocker.endSelfTest();
        }
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

    private void registerGraveRecovery() {
        if (!FixConfig.graveRecoveryEnabled && !FixConfig.graveTrackerEnabled) {
            LOG.info("[HorizonFix] 墓碑失败后的处理已关闭");
            return;
        }
        if (!Loader.isModLoaded("OpenBlocks")) {
            LOG.info("[HorizonFix] 未检测到 OpenBlocks，墓碑失败处理不注册");
            return;
        }
        GraveRecovery recovery = GraveRecovery.get();
        MinecraftForge.EVENT_BUS.register(recovery);
        FMLCommonHandler.instance().bus().register(recovery);
        LOG.info(
                "[HorizonFix] 墓碑失败{}已注册",
                FixConfig.graveRecoveryEnabled ? "自动返还" : "提示");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (!FixConfig.graveTrackerEnabled) return;
        try {
            event.registerServerCommand(new CommandGraves());
            CommandPermission.allowEveryone("bukkit.command.graves");
            CommandPermission.resetAttempts();
            CommandPermission.ensureNoPermission("graves");
            LOG.info("[HorizonFix] 命令 /graves 已注册（权限节点 bukkit.command.graves）");
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

    private void registerWorldgenDiagnostic() {
        if (!FixConfig.worldgenDiagnosticEnabled || FixConfig.worldgenDiagnosticLimit <= 0) {
            LOG.info("[HorizonFix] 级联世界生成诊断已关闭");
            return;
        }
        MinecraftForge.EVENT_BUS.register(new CascadeWorldgenDiagnostic(FixConfig.worldgenDiagnosticLimit));
        LOG.info("[HorizonFix] 级联世界生成诊断已注册");
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
