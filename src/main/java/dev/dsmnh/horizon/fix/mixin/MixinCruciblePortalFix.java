package dev.dsmnh.horizon.fix.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.WorldServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Crucible 的 {@code transferPlayerToDimension} 绕过 Hodgepodge
 * {@code findingSpawnPoint} 保护导致的死锁。
 *
 * <p>Hodgepodge 的 {@code ServerConfigurationManager_FixTeleporter} 只覆盖了
 * 原版 Forge 五参数的 {@code transferEntityToWorld}；Crucible/Bukkit 走的是
 * 三参数的 {@code transferPlayerToDimension(EntityPlayerMP, int, TeleportCause)}，
 * 不经过 Hodgepodge 的修复，导致传送门搜索期间 EntityGuard 拒绝区块加载。</p>
 *
 * <p>本 Mixin 在该方法前后临时设置目标世界的 {@code findingSpawnPoint = true}，
 * 与 Hodgepodge 对原版路径的处理完全一致。</p>
 */
@Mixin(value = ServerConfigurationManager.class, remap = false)
public class MixinCruciblePortalFix {

    private static final Logger LOG = LogManager.getLogger("HorizonFix");

    /**
     * 保存目标世界原有的 {@code findingSpawnPoint} 状态。
     *
     * <p>HorizonFix 未引入 MixinExtras 依赖，因此使用 {@link ThreadLocal} 替代
     * {@code @Share}；服务端传送逻辑运行在主线程上，无并发竞争。</p>
     */
    private static final ThreadLocal<Boolean> PREV_FINDING = new ThreadLocal<Boolean>();

    @Inject(
            method = "transferPlayerToDimension("
                    + "Lnet/minecraft/entity/player/EntityPlayerMP;"
                    + "I"
                    + "Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;"
                    + ")V",
            at = @At("HEAD"),
            remap = false,
            require = 0)
    private void horizonfix$setFindingSpawnPoint(EntityPlayerMP player, int targetDim, @Coerce Object cause,
            CallbackInfo ci) {
        if (player == null || player.mcServer == null) {
            return;
        }
        WorldServer destination = player.mcServer.worldServerForDimension(targetDim);
        if (destination != null) {
            PREV_FINDING.set(destination.findingSpawnPoint);
            destination.findingSpawnPoint = true;
            LOG.debug("[HorizonFix] Crucible 传送：临时启用 findingSpawnPoint（玩家={}，目标维度={}）",
                    player.getCommandSenderName(), targetDim);
        }
    }

    @Inject(
            method = "transferPlayerToDimension("
                    + "Lnet/minecraft/entity/player/EntityPlayerMP;"
                    + "I"
                    + "Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;"
                    + ")V",
            at = @At("RETURN"),
            remap = false,
            require = 0)
    private void horizonfix$restoreFindingSpawnPoint(EntityPlayerMP player, int targetDim, @Coerce Object cause,
            CallbackInfo ci) {
        Boolean prev = PREV_FINDING.get();
        if (prev == null) {
            return;
        }
        PREV_FINDING.remove();
        if (player == null || player.mcServer == null) {
            return;
        }
        WorldServer destination = player.mcServer.worldServerForDimension(targetDim);
        if (destination != null) {
            destination.findingSpawnPoint = prev;
        }
    }
}
