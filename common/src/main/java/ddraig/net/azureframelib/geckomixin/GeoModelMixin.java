package ddraig.net.azureframelib.geckomixin;

import ddraig.net.azureframelib.compat.geckolib.ShortAnimationNames;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.model.GeoModel;

import java.util.List;

/**
 * Fixes for GeckoLib models that use AzureFrameLib assets (only in AzureFrameLib's namespaces).
 * <ul>
 *   <li>Many models replace {@code getBakedModel} without calling GeckoLib's version, so GeckoLib
 *   never learns the model's bones and skips every animation. Before animating, this makes sure
 *   the bones of the model being drawn are known to the animation system.</li>
 *   <li>Lets mods ask for "idle" when the animation file calls it "animation.dragon.idle".</li>
 * </ul>
 * Everything is wrapped so a problem here can never crash rendering.
 */
@Mixin(value = GeoModel.class, remap = false)
public abstract class GeoModelMixin<T extends GeoAnimatable> {

    @Unique
    private ResourceLocation azureframelib$lastModelRes;
    @Unique
    private boolean azureframelib$managed;

    @Inject(method = "handleAnimations", at = @At("HEAD"))
    private void azureframelib$registerBones(T animatable, long instanceId, AnimationState<T> animationState, CallbackInfo ci) {
        if (!AzureFrameLibConfig.get().fixAnimationBones) return;
        try {
            @SuppressWarnings("unchecked")
            GeoModel<T> self = (GeoModel<T>) (Object) this;
            ResourceLocation res = self.getModelResource(animatable);
            if (res == null) return;
            if (!res.equals(this.azureframelib$lastModelRes)) {
                this.azureframelib$lastModelRes = res;
                this.azureframelib$managed = AzureAssetIndex.isManagedNamespace(res.getNamespace());
            }
            if (!this.azureframelib$managed) return;

            BakedGeoModel baked = self.getBakedModel(res);
            if (baked == null) return;
            List<GeoBone> top = baked.topLevelBones();
            if (top == null || top.isEmpty()) return;
            AnimationProcessor<T> processor = self.getAnimationProcessor();
            GeoBone first = top.get(0);
            if (processor.getBone(first.getName()) != first) {
                processor.setActiveModel(baked);
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getAnimation", at = @At("RETURN"), cancellable = true)
    private void azureframelib$shortAnimationNames(T animatable, String name, CallbackInfoReturnable<Animation> cir) {
        if (cir.getReturnValue() != null || name == null || !AzureFrameLibConfig.get().addShortAnimationNames) return;
        try {
            @SuppressWarnings("unchecked")
            GeoModel<T> self = (GeoModel<T>) (Object) this;
            ResourceLocation loc = self.getAnimationResource(animatable);
            if (loc == null || !AzureAssetIndex.isManagedNamespace(loc.getNamespace())) return;
            BakedAnimations baked = GeckoLibCache.getBakedAnimations().get(loc);
            if (baked == null) return;
            Animation found = ShortAnimationNames.find(baked, name);
            if (found != null) cir.setReturnValue(found);
        } catch (Throwable ignored) {
        }
    }
}
