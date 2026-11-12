package ddraig.net.azureframelib.geckomixin;

import ddraig.net.azureframelib.client.GeckoLibModelLoader;
import ddraig.net.azureframelib.compat.geckolib.ShortAnimationNames;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes for GeckoLib models that use AzureFrameLib assets (only in AzureFrameLib's namespaces).
 * Supports GeckoLib 4.x, 5.4 (software.bernie.geckolib) and 5.5+ (com.geckolib).
 */
@Pseudo
@Mixin(targets = {
        "software.bernie.geckolib.model.GeoModel",
        "com.geckolib.model.GeoModel"
}, remap = false)
public abstract class GeoModelMixin {

    @Unique
    private Identifier azureframelib$lastModelRes;
    @Unique
    private boolean azureframelib$managed;

    @Inject(method = "getBakedModel", at = @At("HEAD"), require = 0)
    private void azureframelib$ensureModelLoaded(Identifier location, CallbackInfoReturnable<Object> cir) {
        if (location == null || !AzureAssetIndex.isManagedNamespace(location.getNamespace())) return;
        try {
            GeckoLibModelLoader.ensureModelInCache(location);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleAnimations", at = @At("HEAD"), require = 0)
    private void azureframelib$registerBones(@Coerce Object animatable, long instanceId, @Coerce Object animationState, CallbackInfo ci) {
        if (!AzureFrameLibConfig.get().fixAnimationBones) return;
        try {
            Identifier res = ShortAnimationNames.getModelResourceOf(this, animatable);
            if (res == null) return;
            if (!res.equals(this.azureframelib$lastModelRes)) {
                this.azureframelib$lastModelRes = res;
                this.azureframelib$managed = AzureAssetIndex.isManagedNamespace(res.getNamespace());
            }
            if (!this.azureframelib$managed) return;
            ShortAnimationNames.ensureBonesRegistered(this, res);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"getAnimation", "getBakedAnimation"}, at = @At("HEAD"), require = 0)
    private void azureframelib$ensureAnimationLoaded(@Coerce Object animatable, String name, CallbackInfoReturnable<Object> cir) {
        if (name == null) return;
        try {
            ShortAnimationNames.ensureAnimationLoaded(this, animatable);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"getAnimation", "getBakedAnimation"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void azureframelib$shortAnimationNames(@Coerce Object animatable, String name, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() != null || name == null || !AzureFrameLibConfig.get().addShortAnimationNames) return;
        try {
            Object found = ShortAnimationNames.findShortAnimation(this, animatable, name);
            if (found != null) cir.setReturnValue(found);
        } catch (Throwable ignored) {
        }
    }
}
