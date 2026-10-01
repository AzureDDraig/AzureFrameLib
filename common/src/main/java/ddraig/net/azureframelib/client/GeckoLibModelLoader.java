package ddraig.net.azureframelib.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Universal on-the-fly dynamic GeckoLib model and animation loader.
 * Dynamically parses Blockbench GeckoLib models and animations from disk files,
 * bakes them, and hot-injects them directly into GeckoLibCache so any entity
 * or renderer in any framework mod can use them immediately.
 */
public class GeckoLibModelLoader {

    private static final Map<ResourceLocation, Object> FALLBACK_MODELS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Object> FALLBACK_ANIMATIONS = new ConcurrentHashMap<>();
    private static final java.util.Set<ResourceLocation> FAILED_MODELS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.Set<ResourceLocation> FAILED_ANIMATIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void clearCaches() {
        FALLBACK_MODELS.clear();
        FALLBACK_ANIMATIONS.clear();
        FAILED_MODELS.clear();
        FAILED_ANIMATIONS.clear();
        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getModelsMethod = cacheClass.getMethod("getBakedModels");
            Map<?, ?> models = (Map<?, ?>) getModelsMethod.invoke(null);
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && isManagedNamespace(rl.getNamespace()));
            }

            Method getAnimsMethod = cacheClass.getMethod("getBakedAnimations");
            Map<?, ?> anims = (Map<?, ?>) getAnimsMethod.invoke(null);
            if (anims != null) {
                anims.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && isManagedNamespace(rl.getNamespace()));
            }
        } catch (Throwable ignored) {}
    }

    public static void clearModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) return;
        String clean = AzureResourceManager.sanitizePath(modelId);
        FAILED_MODELS.removeIf(loc -> loc.getPath().contains(clean));
        FAILED_ANIMATIONS.removeIf(loc -> loc.getPath().contains(clean));

        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getModelsMethod = cacheClass.getMethod("getBakedModels");
            Map<?, ?> models = (Map<?, ?>) getModelsMethod.invoke(null);
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && rl.getPath().contains(clean));
            }

            Method getAnimsMethod = cacheClass.getMethod("getBakedAnimations");
            Map<?, ?> anims = (Map<?, ?>) getAnimsMethod.invoke(null);
            if (anims != null) {
                anims.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && rl.getPath().contains(clean));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Retrieves an existing baked model or bakes it on the fly from any registered framework config folder.
     */
    public static Object getOrLoadBakedModel(ResourceLocation location) {
        if (location == null || FAILED_MODELS.contains(location)) return null;

        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getModelsMethod = cacheClass.getMethod("getBakedModels");
            Map<?, ?> models = (Map<?, ?>) getModelsMethod.invoke(null);
            if (models != null && models.containsKey(location)) {
                return models.get(location);
            }
        } catch (Throwable ignored) {}

        if (FALLBACK_MODELS.containsKey(location)) {
            return FALLBACK_MODELS.get(location);
        }

        String path = location.getPath();
        String modelId = extractIdFromPath(path, "geo/", ".geo.json");
        if (modelId.isEmpty()) {
            modelId = extractIdFromPath(path, "models/", ".geo.json");
        }
        if (modelId.isEmpty()) {
            modelId = extractIdFromPath(path, "", ".json");
        }

        File file = AzureResourceManager.findModelFile(modelId);
        if (file == null) {
            file = AzureResourceManager.findModelFile(location.toString());
        }
        if (file == null) {
            file = AzureResourceManager.findModelFile(path);
        }
        if (file == null && path.contains("/")) {
            file = AzureResourceManager.findModelFile(path.substring(path.lastIndexOf('/') + 1));
        }

        if (file != null && file.exists() && file.isFile()) {
            Object baked = bakeModelFromFile(location, file);
            if (baked != null) {
                injectModel(location, baked);
                return baked;
            }
        }

        FAILED_MODELS.add(location);
        return null;
    }

    /**
     * Retrieves existing baked animations or bakes them on the fly from any registered framework config folder.
     */
    public static Object getOrLoadBakedAnimations(ResourceLocation location) {
        if (location == null || FAILED_ANIMATIONS.contains(location)) return null;

        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getAnimsMethod = cacheClass.getMethod("getBakedAnimations");
            Map<?, ?> anims = (Map<?, ?>) getAnimsMethod.invoke(null);
            if (anims != null && anims.containsKey(location)) {
                return anims.get(location);
            }
        } catch (Throwable ignored) {}

        if (FALLBACK_ANIMATIONS.containsKey(location)) {
            return FALLBACK_ANIMATIONS.get(location);
        }

        String path = location.getPath();
        String animId = extractIdFromPath(path, "animations/", ".animation.json");
        if (animId.isEmpty()) {
            animId = extractIdFromPath(path, "", ".json");
        }

        File file = AzureResourceManager.findAnimationFile(animId);
        if (file == null) {
            file = AzureResourceManager.findAnimationFile(location.toString());
        }
        if (file == null) {
            file = AzureResourceManager.findAnimationFile(path);
        }
        if (file == null && path.contains("/")) {
            file = AzureResourceManager.findAnimationFile(path.substring(path.lastIndexOf('/') + 1));
        }

        if (file != null && file.exists() && file.isFile()) {
            Object baked = bakeAnimationsFromFile(location, file);
            if (baked != null) {
                injectAnimations(location, baked);
                return baked;
            }
        }

        FAILED_ANIMATIONS.add(location);
        return null;
    }

    public static Object bakeModelFromFile(ResourceLocation location, File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".animation.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) {
            return null;
        }
        if (!AzureResourceManager.isValidGeoModelFile(file)) return null;
        try {
            String content = Files.readString(file.toPath());
            return bakeModelFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib model file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeModelFromJson(ResourceLocation location, String jsonContent) {
        if (jsonContent == null || jsonContent.trim().isEmpty()) return null;

        try {
            // Validate JSON
            JsonElement parsed = JsonParser.parseString(jsonContent);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            boolean hasGeo = root.has("minecraft:geometry");
            if (!hasGeo) {
                for (String k : root.keySet()) {
                    if (k.startsWith("geometry.")) {
                        hasGeo = true;
                        break;
                    }
                }
            }
            if (!hasGeo && !root.has("format_version") && !root.has("bones")) {
                AzureFrameLib.LOGGER.warn("[AzureFrameLib] Model JSON for {} does not appear to contain geometry definition", location);
                return null;
            }

            Class<?> jsonUtilClass = Class.forName("software.bernie.geckolib.util.JsonUtil");
            Field geoGsonField = jsonUtilClass.getField("GEO_GSON");
            Object geoGson = geoGsonField.get(null);

            Class<?> modelClass = Class.forName("software.bernie.geckolib.loading.json.raw.Model");
            Method fromJsonMethod = geoGson.getClass().getMethod("fromJson", String.class, Class.class);
            Object rawModel = fromJsonMethod.invoke(geoGson, jsonContent, modelClass);
            if (rawModel == null) return null;

            Class<?> geomTreeClass = Class.forName("software.bernie.geckolib.loading.object.GeometryTree");
            Method fromModelMethod = geomTreeClass.getMethod("fromModel", modelClass);
            Object tree = fromModelMethod.invoke(null, rawModel);
            if (tree == null) return null;

            Class<?> modelFactoryClass = Class.forName("software.bernie.geckolib.loading.object.BakedModelFactory");
            Method getFactoryMethod = modelFactoryClass.getMethod("getForNamespace", String.class);
            String ns = (location != null) ? location.getNamespace() : AzureFrameLib.MOD_ID;
            Object factoryObj = getFactoryMethod.invoke(null, ns);

            Method constructGeoModelMethod = modelFactoryClass.getMethod("constructGeoModel", geomTreeClass);
            Object bakedGeoModel = constructGeoModelMethod.invoke(factoryObj, tree);

            if (bakedGeoModel != null && location != null) {
                injectModel(location, bakedGeoModel);
            }
            return bakedGeoModel;
        } catch (ClassNotFoundException cnfe) {
            // Headless / fallback environment
            try {
                JsonElement parsed = JsonParser.parseString(jsonContent);
                if (parsed.isJsonObject() && location != null) {
                    FALLBACK_MODELS.put(location, parsed);
                }
                return parsed;
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to bake GeckoLib model " + location + ": " + t.getMessage());
            return null;
        }
    }

    public static Object bakeAnimationsFromFile(ResourceLocation location, File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".geo.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) {
            return null;
        }
        if (!AzureResourceManager.isValidAnimationFile(file)) return null;
        try {
            String content = Files.readString(file.toPath());
            return bakeAnimationsFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib animation file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeAnimationsFromJson(ResourceLocation location, String jsonContent) {
        if (jsonContent == null || jsonContent.trim().isEmpty()) return null;

        try {
            JsonElement parsed = JsonParser.parseString(jsonContent);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            JsonObject animObj = root.has("animations") ? root.getAsJsonObject("animations") : root;

            Class<?> jsonUtilClass = Class.forName("software.bernie.geckolib.util.JsonUtil");
            Field geoGsonField = jsonUtilClass.getField("GEO_GSON");
            Object geoGson = geoGsonField.get(null);

            Class<?> bakedAnimsClass = Class.forName("software.bernie.geckolib.loading.object.BakedAnimations");
            Method fromJsonElementMethod = geoGson.getClass().getMethod("fromJson", JsonElement.class, Class.class);
            Object bakedAnimObj = fromJsonElementMethod.invoke(geoGson, animObj, bakedAnimsClass);

            if (bakedAnimObj != null && location != null) {
                injectAnimations(location, bakedAnimObj);
            }
            return bakedAnimObj;
        } catch (ClassNotFoundException cnfe) {
            try {
                JsonElement parsed = JsonParser.parseString(jsonContent);
                if (location != null) {
                    FALLBACK_ANIMATIONS.put(location, parsed);
                }
                return parsed;
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to bake GeckoLib animations " + location + ": " + t.getMessage());
            return null;
        }
    }

    public static void injectModel(ResourceLocation location, Object bakedModel) {
        if (location == null || bakedModel == null) return;
        Map<ResourceLocation, Object> map = ensureModifiableModelMap();

        Set<ResourceLocation> targets = new HashSet<>();
        targets.add(location);

        String ns = location.getNamespace();
        String path = location.getPath();

        if (path.startsWith("models/")) {
            targets.add(new ResourceLocation(ns, "geo/" + path.substring(7)));
            targets.add(new ResourceLocation(ns, path.substring(7)));
        } else if (path.startsWith("geo/")) {
            targets.add(new ResourceLocation(ns, "models/" + path.substring(4)));
            targets.add(new ResourceLocation(ns, path.substring(4)));
        } else {
            targets.add(new ResourceLocation(ns, "geo/" + path));
            targets.add(new ResourceLocation(ns, "models/" + path));
        }

        if (path.contains("/")) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            targets.add(new ResourceLocation(ns, base));
            targets.add(new ResourceLocation(ns, "geo/" + base));
            targets.add(new ResourceLocation(ns, "models/" + base));
        }

        // Also register under azureframelib
        Set<ResourceLocation> commonTargets = new HashSet<>();
        for (ResourceLocation loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                commonTargets.add(new ResourceLocation("azureframelib", loc.getPath()));
            }
        }
        targets.addAll(commonTargets);

        for (ResourceLocation loc : targets) {
            if (map != null) {
                try {
                    map.put(loc, bakedModel);
                } catch (Throwable ignored) {}
            }
            FALLBACK_MODELS.put(loc, bakedModel);
        }
    }

    public static void injectAnimations(ResourceLocation location, Object bakedAnimations) {
        if (location == null || bakedAnimations == null) return;
        Map<ResourceLocation, Object> map = ensureModifiableAnimationMap();

        Set<ResourceLocation> targets = new HashSet<>();
        targets.add(location);

        String ns = location.getNamespace();
        String path = location.getPath();

        if (path.startsWith("animations/")) {
            targets.add(new ResourceLocation(ns, path.substring(11)));
        } else {
            targets.add(new ResourceLocation(ns, "animations/" + path));
        }

        if (path.contains("/")) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            targets.add(new ResourceLocation(ns, base));
            targets.add(new ResourceLocation(ns, "animations/" + base));
        }

        Set<ResourceLocation> commonTargets = new HashSet<>();
        for (ResourceLocation loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                commonTargets.add(new ResourceLocation("azureframelib", loc.getPath()));
            }
        }
        targets.addAll(commonTargets);

        for (ResourceLocation loc : targets) {
            if (map != null) {
                try {
                    map.put(loc, bakedAnimations);
                } catch (Throwable ignored) {}
            }
            FALLBACK_ANIMATIONS.put(loc, bakedAnimations);
        }
    }

    public static boolean isModelBaked(ResourceLocation location) {
        if (location == null) return false;
        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getModelsMethod = cacheClass.getMethod("getBakedModels");
            Map<?, ?> models = (Map<?, ?>) getModelsMethod.invoke(null);
            if (models != null && checkModelPresence(models, location)) return true;
        } catch (Throwable ignored) {}
        return checkModelPresence(FALLBACK_MODELS, location);
    }

    public static boolean isAnimationBaked(ResourceLocation location) {
        if (location == null) return false;
        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Method getAnimsMethod = cacheClass.getMethod("getBakedAnimations");
            Map<?, ?> anims = (Map<?, ?>) getAnimsMethod.invoke(null);
            if (anims != null && checkAnimationPresence(anims, location)) return true;
        } catch (Throwable ignored) {}
        return checkAnimationPresence(FALLBACK_ANIMATIONS, location);
    }

    private static boolean checkModelPresence(Map<?, ?> map, ResourceLocation loc) {
        if (map == null || loc == null) return false;
        if (map.containsKey(loc)) return true;
        String path = loc.getPath();
        String ns = loc.getNamespace();
        if (path.isEmpty()) return false;
        if (path.startsWith("geo/")) {
            if (map.containsKey(new ResourceLocation(ns, path.substring(4)))) return true;
        } else {
            if (map.containsKey(new ResourceLocation(ns, "geo/" + path))) return true;
        }
        if (path.startsWith("models/")) {
            if (map.containsKey(new ResourceLocation(ns, path.substring(7)))) return true;
        } else {
            if (map.containsKey(new ResourceLocation(ns, "models/" + path))) return true;
        }
        return false;
    }

    private static boolean checkAnimationPresence(Map<?, ?> map, ResourceLocation loc) {
        if (map == null || loc == null) return false;
        if (map.containsKey(loc)) return true;
        String path = loc.getPath();
        String ns = loc.getNamespace();
        if (path.isEmpty()) return false;
        if (path.startsWith("animations/")) {
            if (map.containsKey(new ResourceLocation(ns, path.substring(11)))) return true;
        } else {
            if (map.containsKey(new ResourceLocation(ns, "animations/" + path))) return true;
        }
        return false;
    }

    public static Map<ResourceLocation, Object> ensureModifiableModelMap() {
        return ensureModifiableMap("MODELS");
    }

    public static Map<ResourceLocation, Object> ensureModifiableAnimationMap() {
        return ensureModifiableMap("ANIMATIONS");
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Object> ensureModifiableMap(String fieldName) {
        try {
            Class<?> cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
            Field field = cacheClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) field.get(null);

            boolean needsSwap = (map == null)
                    || map.getClass().getName().contains("EmptyMap")
                    || map.getClass().getName().contains("Unmodifiable")
                    || !(map instanceof ConcurrentMap);

            if (needsSwap) {
                Map<ResourceLocation, Object> mutableMap = new ConcurrentHashMap<>();
                if (map != null) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() instanceof ResourceLocation loc) {
                            mutableMap.put(loc, entry.getValue());
                        }
                    }
                }
                field.set(null, mutableMap);
                return mutableMap;
            }
            return (Map<ResourceLocation, Object>) map;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isManagedNamespace(String ns) {
        return "azureframelib".equals(ns) || "custom_mobs".equals(ns) || "rpg_mounts".equals(ns) || "customraces".equals(ns);
    }

    private static String extractIdFromPath(String path, String prefix, String suffix) {
        String p = path.toLowerCase(Locale.ROOT);
        String pfx = prefix.toLowerCase(Locale.ROOT);
        String sfx = suffix.toLowerCase(Locale.ROOT);

        int start = pfx.isEmpty() ? 0 : (p.startsWith(pfx) ? pfx.length() : -1);
        if (start < 0) return "";

        int end = sfx.isEmpty() ? p.length() : (p.endsWith(sfx) ? p.length() - sfx.length() : -1);
        if (end < start) return "";

        return path.substring(start, end);
    }
}
