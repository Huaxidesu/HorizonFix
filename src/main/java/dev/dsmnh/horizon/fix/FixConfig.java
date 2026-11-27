package dev.dsmnh.horizon.fix;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 统一配置。
 *
 * <p>每一项修复都有独立开关，关闭后对应的监听逻辑直接返回，行为与未安装本模组一致。
 * 配置读取失败时全部使用默认值，不影响服务端启动。</p>
 */
public class FixConfig {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /** 墓碑修正总开关。 */
    public static boolean graveEnabled = true;

    /** 原落点在液体内部时重新选点（缺陷 A）。 */
    public static boolean graveAvoidLiquid = true;

    /** OpenBlocks 没有找到落点时补一个（缺陷 B）。 */
    public static boolean graveRescueMissing = true;

    /** 同一玩家在同一 tick 内只生成一座墓碑。 */
    public static boolean graveSinglePerTick = true;

    /** 搜索半径，按方块计。 */
    public static int graveSearchRadius = 8;

    /** 第一轮搜索要求下方有实体方块，避免墓碑悬空。 */
    public static boolean graveRequireSupport = true;

    /** 诊断日志条数上限，0 表示关闭。 */
    public static int graveDiagnosticLimit = 16;

    /** 墓碑位置记录与提醒总开关。 */
    public static boolean graveTrackerEnabled = true;

    /** 墓碑放置时给玩家发一条带坐标的提示。 */
    public static boolean graveTrackerNotifyPlace = true;

    /** 玩家登录时提醒未取走的墓碑。 */
    public static boolean graveTrackerNotifyLogin = true;

    /** 登录时最多直接列出几座墓碑，超出则只给数量提示。 */
    public static int graveTrackerLoginLimit = 3;

    /** 墓碑未能生成时自动把物品放回玩家背包。 */
    public static boolean graveRecoveryEnabled = true;

    /** 自动返还时移除 OpenBlocks 丢到地面的同一批物品，避免复制。 */
    public static boolean graveRecoveryRemoveDropped = true;

    /** 自动返还后删除本次失败放置留下的备份，避免 {@code /ob_inventory restore} 再复制一份。 */
    public static boolean graveRecoveryRemoveBackup = true;

    /** 被取消的死亡是否清理 OpenBlocks 背包快照。 */
    public static boolean deathSnapshotEnabled = true;

    /** 清理时是否输出日志。 */
    public static boolean deathSnapshotLog = true;

    /** 死亡被取消时是否输出伤害源、攻击者与当前血量。 */
    public static boolean deathSnapshotLogCanceled = true;

    /** 无线红石附加数据补建总开关。 */
    public static boolean wrEnabled = true;

    /** 玩家登录时补建表项。 */
    public static boolean wrRepairOnLogin = true;

    /** 登录路径的补建是否打 INFO 日志（默认只打 DEBUG，避免刷屏）。 */
    public static boolean wrLogLoginRepair = false;

    /** 跨维度路径的补建是否打 INFO 日志（这是真正有诊断价值的一类）。 */
    public static boolean wrLogDimensionRepair = true;

    /** 是否记录区块填充期间触发的相邻区块载入。 */
    public static boolean worldgenDiagnosticEnabled = true;

    /** 单次启动最多输出多少条级联世界生成日志。 */
    public static int worldgenDiagnosticLimit = 16;

    /** ExtraUtilities 分割徽章激活与仪式修复（GTNH 2.9.1+ 已由 Hodgepodge 2.7.223 官方内置修复，默认关闭以避免冲突）。 */
    public static boolean extraUtilsDivisionSigil = false;

    /** ExtraUtilities 垃圾桶读档后重扫库存修复。 */
    public static boolean extraUtilsTrashCan = true;

    /** Botania 特殊花亮度查询空指针修复。 */
    public static boolean botaniaSpecialFlower = true;

    /** MrTJPCore 活跃区块集合防载入修复。 */
    public static boolean mrtjpBlockUpdate = true;

    /** 禁止存储总线读取抽屉管理器（含其下属从站）。 */
    public static boolean storageBusDenyDrawerController = true;

    /** 禁止存储总线读取单个抽屉方块。 */
    public static boolean storageBusDenyDrawers = false;

    /** 拦截生效时向附近玩家发送聊天提示。 */
    public static boolean storageBusNotifyPlayers = true;

    /** 玩家把存储总线贴到抽屉管理器上时立即引爆该抽屉管理器。 */
    public static boolean storageBusExplodeOnPlace = true;

    /** 爆炸强度，取值对应 GT 机器爆炸的强度表（1.0 为低压机器，4.0 与一个 TNT 相当）。 */
    public static double storageBusExplosionPower = 4.0D;

    /** 爆炸向所在区块追加的污染量，GT 未安装时忽略。 */
    public static int storageBusExplosionPollution = 30000000;

    public static void load(File file) {
        if (file == null) return;
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();

            graveEnabled = cfg.getBoolean(
                    "enabled",
                    "grave",
                    graveEnabled,
                    "Master switch for the grave placement fix.");
            graveAvoidLiquid = cfg.getBoolean(
                    "avoidLiquid",
                    "grave",
                    graveAvoidLiquid,
                    "Relocate the grave when OpenBlocks picked a spot inside lava or water.");
            graveRescueMissing = cfg.getBoolean(
                    "rescueMissing",
                    "grave",
                    graveRescueMissing,
                    "Supply a position when OpenBlocks failed to find one ('No location for grave found').");
            graveSinglePerTick = cfg.getBoolean(
                    "singlePerTick",
                    "grave",
                    graveSinglePerTick,
                    "Only one grave per player per tick. A player dying twice in the same tick otherwise "
                            + "produces two graves and duplicates the extra drops.");
            graveSearchRadius = cfg.getInt(
                    "searchRadius",
                    "grave",
                    graveSearchRadius,
                    1,
                    32,
                    "Search radius in blocks around the death point.");
            graveRequireSupport = cfg.getBoolean(
                    "requireSupport",
                    "grave",
                    graveRequireSupport,
                    "First pass requires a solid block below the grave.");
            graveDiagnosticLimit = cfg.getInt(
                    "diagnosticLimit",
                    "grave",
                    graveDiagnosticLimit,
                    0,
                    256,
                    "How many diagnostic log lines to emit per server start. 0 disables them.");

            graveTrackerEnabled = cfg.getBoolean(
                    "enabled",
                    "graveTracker",
                    graveTrackerEnabled,
                    "Remember grave positions and remind players, so graves are not left behind unnoticed.");
            graveTrackerNotifyPlace = cfg.getBoolean(
                    "notifyOnPlace",
                    "graveTracker",
                    graveTrackerNotifyPlace,
                    "Send the grave coordinates to the player in chat right after placement.");
            graveTrackerNotifyLogin = cfg.getBoolean(
                    "notifyOnLogin",
                    "graveTracker",
                    graveTrackerNotifyLogin,
                    "Remind the player of unclaimed graves when they log in.");
            graveTrackerLoginLimit = cfg.getInt(
                    "loginLimit",
                    "graveTracker",
                    graveTrackerLoginLimit,
                    1,
                    16,
                    "How many graves to list on login. Above this only the count is shown.");

            graveRecoveryEnabled = cfg.getBoolean(
                    "enabled",
                    "graveRecovery",
                    graveRecoveryEnabled,
                    "Return the items to the player when the grave could not be placed. "
                            + "Disabling it restores the old behaviour of only telling the player to ask an admin.");
            graveRecoveryRemoveDropped = cfg.getBoolean(
                    "removeDroppedItems",
                    "graveRecovery",
                    graveRecoveryRemoveDropped,
                    "Remove the loot OpenBlocks dropped on the ground after the failed placement, "
                            + "so the returned items are not duplicated.");
            graveRecoveryRemoveBackup = cfg.getBoolean(
                    "removeGraveBackup",
                    "graveRecovery",
                    graveRecoveryRemoveBackup,
                    "Delete the '-grave-' backup written for the failed placement. Restoring it with "
                            + "'/ob_inventory restore' would duplicate the returned items.");

            deathSnapshotEnabled = cfg.getBoolean(
                    "enabled",
                    "deathSnapshot",
                    deathSnapshotEnabled,
                    "Remove the inventory snapshots OpenBlocks writes for deaths that were later cancelled "
                            + "(e.g. Witchery vampires). Restoring such a snapshot would duplicate items.");
            deathSnapshotLog = cfg.getBoolean(
                    "logCleanup",
                    "deathSnapshot",
                    deathSnapshotLog,
                    "Log every cleanup of cancelled-death snapshots.");

            deathSnapshotLogCanceled = cfg.getBoolean(
                    "logCanceled",
                    "deathSnapshot",
                    deathSnapshotLogCanceled,
                    "Log damage type, attacker and health whenever a death is cancelled. "
                            + "Used to diagnose repeated cancelled deaths.");

            wrEnabled = cfg.getBoolean(
                    "enabled",
                    "wirelessredstone",
                    wrEnabled,
                    "Master switch for the WR-CBE addon NPE fix.");
            wrRepairOnLogin = cfg.getBoolean(
                    "repairOnLogin",
                    "wirelessredstone",
                    wrRepairOnLogin,
                    "Create the missing player entry on login as well. WR-CBE does this itself, "
                            + "so disabling it only saves one redundant call.");
            wrLogLoginRepair = cfg.getBoolean(
                    "logLoginRepair",
                    "wirelessredstone",
                    wrLogLoginRepair,
                    "Log login-path repairs at INFO. They are expected on every login, so they are DEBUG by default.");
            wrLogDimensionRepair = cfg.getBoolean(
                    "logDimensionRepair",
                    "wirelessredstone",
                    wrLogDimensionRepair,
                    "Log dimension-change repairs at INFO. These are the ones that used to crash the server.");

            worldgenDiagnosticEnabled = cfg.getBoolean(
                    "enabled",
                    "worldgen",
                    worldgenDiagnosticEnabled,
                    "Log chunk loads triggered while another chunk is being populated.");
            worldgenDiagnosticLimit = cfg.getInt(
                    "logLimit",
                    "worldgen",
                    worldgenDiagnosticLimit,
                    0,
                    256,
                    "Maximum number of cascading worldgen log lines per server start. 0 disables them.");

            extraUtilsDivisionSigil = cfg.getBoolean(
                    "divisionSigil",
                    "extrautils",
                    extraUtilsDivisionSigil,
                    "Fix ExtraUtilities Division Sigil activation ritual and right-click on enchantment tables/beacons under Crucible/Thermos. "
                            + "GTNH 2.9.1+ includes this fix natively in Hodgepodge (PR #1058), so it is disabled by default. "
                            + "Enable this option if running GTNH 2.9.0.");
            extraUtilsTrashCan = cfg.getBoolean(
                    "trashCan",
                    "extrautils",
                    extraUtilsTrashCan,
                    "Force ExtraUtilities Trash Can to rescan inventory after loading save.");

            botaniaSpecialFlower = cfg.getBoolean(
                    "specialFlower",
                    "botania",
                    botaniaSpecialFlower,
                    "Fix Botania special flower NPE when querying light value without TileEntity.");

            mrtjpBlockUpdate = cfg.getBoolean(
                    "blockUpdate",
                    "mrtjp",
                    mrtjpBlockUpdate,
                    "Prevent MrTJPCore from loading unloaded chunks when computing active chunk set.");

            storageBusDenyDrawerController = cfg.getBoolean(
                    "denyDrawerController",
                    "ae2",
                    storageBusDenyDrawerController,
                    "Stop AE2 storage buses from reading a Storage Drawers drawer controller. "
                            + "Every polling pass walks all connected drawers and copies each stored item stack with its NBT, "
                            + "which scales with drawer count and is the main cause of the TPS drop. "
                            + "Use an ME Interface instead of a storage bus.");
            storageBusDenyDrawers = cfg.getBoolean(
                    "denyDrawers",
                    "ae2",
                    storageBusDenyDrawers,
                    "Also stop storage buses from reading standalone drawer blocks. "
                            + "Disabled by default because a single drawer is cheap to poll.");
            storageBusNotifyPlayers = cfg.getBoolean(
                    "notifyPlayers",
                    "ae2",
                    storageBusNotifyPlayers,
                    "Send a chat hint to nearby players when a storage bus is denied.");
            storageBusExplodeOnPlace = cfg.getBoolean(
                    "explodeOnPlace",
                    "ae2",
                    storageBusExplodeOnPlace,
                    "Detonate the drawer controller the moment a player attaches a storage bus to it, "
                            + "mirroring the GT machine explosion flow: clear the block, create the explosion, then add pollution. "
                            + "Only fires on placement, never on chunk load, so existing builds survive a restart. "
                            + "The runtime read block below still applies as a fallback.");
            storageBusExplosionPower = cfg.getFloat(
                    "explosionPower",
                    "ae2",
                    (float) storageBusExplosionPower,
                    0.0F,
                    20.0F,
                    "Explosion strength, using the GT machine explosion scale: 1.0 is a low voltage machine, "
                            + "4.0 equals one block of TNT, 20.0 is the maximum GT uses. 0 disables the explosion "
                            + "while keeping the pollution and the broadcast.");
            storageBusExplosionPollution = cfg.getInt(
                    "explosionPollution",
                    "ae2",
                    storageBusExplosionPollution,
                    0,
                    1000000000,
                    "Pollution added to the chunk on detonation. GT defaults to 100000 per machine explosion; "
                            + "30000000 is 300 times that and exceeds every GT pollution threshold (smog, poison, "
                            + "vegetation death, acid rain) by more than an order of magnitude, and spreads to "
                            + "neighbouring chunks. 0 disables it. Requires GregTech.");
        } catch (Throwable t) {
            LOG.warn("[HorizonFix] 读取配置失败，全部使用默认值", t);
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
