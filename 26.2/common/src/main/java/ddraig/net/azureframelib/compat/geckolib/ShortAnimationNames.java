package ddraig.net.azureframelib.compat.geckolib;

import com.google.common.collect.MapMaker;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets mods ask for an animation by its short name ("idle") when the file names it in full
 * ("animation.dragon.idle"), and ensures model bones are registered with GeckoLib's
 * AnimationProcessor before animating.
 * <p>
 * Uses reflection so AzureFrameLib compiles without GeckoLib and supports both GeckoLib 4.4
 * and 4.5+ (1.21+) method and package layouts.
 */
public final class ShortAnimationNames {

    private static final Map<Object, Map<String, Optional<Object>>> CACHE = new MapMaker().weakKeys().makeMap();
    private static volatile boolean initialized;
    private static Method getBakedAnimationsMethod;
    private static Method bakedAnimationsMapMethod;
    private static Method getModelResource1;
    private static Method getModelResource2;
    private static Method getAnimationResource;
    private static Method getBakedModel;
    private static Method getAnimationProcessor;
    private static Method topLevelBones;
    private static Method boneGetName;
    private static Method processorGetBone;
    private static Method processorSetActiveModel;

    private ShortAnimationNames() {
    }

    private static boolean init() {
        if (initialized) return getBakedAnimationsMethod != null;
        synchronized (ShortAnimationNames.class) {
            if (initialized) return getBakedAnimationsMethod != null;
            try {
                Class<?> cacheCls = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
                getBakedAnimationsMethod = cacheCls.getMethod("getBakedAnimations");

                Class<?> bakedAnimsCls = Class.forName("software.bernie.geckolib.loading.object.BakedAnimations");
                bakedAnimationsMapMethod = bakedAnimsCls.getMethod("animations");

                Class<?> geoModelCls = Class.forName("software.bernie.geckolib.model.GeoModel");
                for (Method m : geoModelCls.getMethods()) {
                    switch (m.getName()) {
                        case "getModelResource" -> {
                            if (m.getParameterCount() == 1) getModelResource1 = m;
                            else if (m.getParameterCount() == 2) getModelResource2 = m;
                        }
                        case "getAnimationResource" -> {
                            if (m.getParameterCount() == 1) getAnimationResource = m;
                        }
                        case "getBakedModel" -> {
                            if (m.getParameterCount() == 1) getBakedModel = m;
                        }
                        case "getAnimationProcessor" -> {
                            if (m.getParameterCount() == 0) getAnimationProcessor = m;
                        }
                    }
                }

                Class<?> bakedGeoModelCls = Class.forName("software.bernie.geckolib.cache.object.BakedGeoModel");
                topLevelBones = bakedGeoModelCls.getMethod("topLevelBones");

                Class<?> geoBoneCls = Class.forName("software.bernie.geckolib.cache.object.GeoBone");
                boneGetName = geoBoneCls.getMethod("getName");

                Class<?> processorCls = findClass(
                        "software.bernie.geckolib.animation.AnimationProcessor",
                        "software.bernie.geckolib.core.animation.AnimationProcessor"
                );
                if (processorCls != null) {
                    processorGetBone = processorCls.getMethod("getBone", String.class);
                    for (Method m : processorCls.getMethods()) {
                        if (m.getName().equals("setActiveModel") && m.getParameterCount() == 1) {
                            processorSetActiveModel = m;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                getBakedAnimationsMethod = null;
            }
            initialized = true;
            return getBakedAnimationsMethod != null;
        }
    }

    private static Class<?> findClass(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static ResourceLocation getModelResourceOf(Object geoModel, Object animatable) {
        if (!init() || geoModel == null) return null;
        try {
            if (getModelResource1 != null) {
                return (ResourceLocation) getModelResource1.invoke(geoModel, animatable);
            }
            if (getModelResource2 != null) {
                return (ResourceLocation) getModelResource2.invoke(geoModel, animatable, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void ensureBonesRegistered(Object geoModel, ResourceLocation modelRes) {
        if (!AzureFrameLibConfig.get().fixAnimationBones || !init() || geoModel == null || modelRes == null) return;
        try {
            if (getBakedModel == null || getAnimationProcessor == null || topLevelBones == null
                    || boneGetName == null || processorGetBone == null || processorSetActiveModel == null) {
                return;
            }
            Object baked = getBakedModel.invoke(geoModel, modelRes);
            if (baked == null) return;
            List<?> top = (List<?>) topLevelBones.invoke(baked);
            if (top == null || top.isEmpty()) return;
            Object processor = getAnimationProcessor.invoke(geoModel);
            if (processor == null) return;
            Object first = top.get(0);
            String name = (String) boneGetName.invoke(first);
            if (processorGetBone.invoke(processor, name) != first) {
                processorSetActiveModel.invoke(processor, baked);
            }
        } catch (Throwable ignored) {
        }
    }

    public static Object findShortAnimation(Object geoModel, Object animatable, String requested) {
        if (requested == null || !AzureFrameLibConfig.get().addShortAnimationNames || !init() || geoModel == null) {
            return null;
        }
        try {
            if (getAnimationResource == null) return null;
            ResourceLocation loc = (ResourceLocation) getAnimationResource.invoke(geoModel, animatable);
            if (loc == null || !AzureAssetIndex.isManagedNamespace(loc.getNamespace())) return null;
            Map<?, ?> allBaked = (Map<?, ?>) getBakedAnimationsMethod.invoke(null);
            if (allBaked == null) return null;
            Object baked = allBaked.get(loc);
            return find(baked, requested);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Object find(Object baked, String requested) {
        if (baked == null || requested == null || !init()) return null;
        try {
            Map<String, Object> animMap = (Map<String, Object>) bakedAnimationsMapMethod.invoke(baked);
            if (animMap == null) return null;
            Map<String, Optional<Object>> perFile = CACHE.computeIfAbsent(baked, b -> new ConcurrentHashMap<>());
            Optional<Object> hit = perFile.get(requested);
            if (hit == null) {
                String match = AzureAssetIndex.matchAnimationName(animMap.keySet(), requested);
                hit = Optional.ofNullable(match != null ? animMap.get(match) : null);
                if (perFile.size() < 512) perFile.put(requested, hit);
            }
            return hit.orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
