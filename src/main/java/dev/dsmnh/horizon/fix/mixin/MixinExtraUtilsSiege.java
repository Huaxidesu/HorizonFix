package dev.dsmnh.horizon.fix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把围城事件的信标类相等判定改为可赋值判定。
 *
 * <p>严格类相等会在信标 TileEntity 被子类化后失效，且原实现强转可能抛异常。</p>
 */
@Mixin(targets = "com.rwtema.extrautils.EventHandlerSiege", remap = false)
public class MixinExtraUtilsSiege {

    @Redirect(method = "golemDeath",
            at = @At(value = "INVOKE", target = "Ljava/lang/Object;equals(Ljava/lang/Object;)Z"),
            remap = false)
    private boolean horizon$allowBeaconSubclass(Object subject, Object other) {
        if (subject instanceof Class && other instanceof Class) {
            return ((Class<?>) other).isAssignableFrom((Class<?>) subject);
        }
        return subject != null && subject.equals(other);
    }
}
