package ddraig.net.azureframelib.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.model.ModelHitboxHelper;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Universal on-the-fly dynamic GeckoLib model and animation loader.
 * Dynamically parses Blockbench GeckoLib models and animations from disk files,
 * bakes them, and hot-injects them directly into GeckoLibCache so any entity
 * or renderer in any framework mod can use them immediately.
 * <p>
 * Files are found through AzureFrameLib's in-memory index. "Not found" answers are remembered
 * until the index changes, so missing models cost almost nothing per frame.
 */
public class GeckoLibModelLoader {

    private static final Map<ResourceLocation, Object> FALLBACK_MODELS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Object> FALLBACK_ANIMATIONS = new ConcurrentHashMap<>();
    private static final java.util.Set<ResourceLocation> FAILED_MODELS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.Set<ResourceLocation> FAILED_ANIMATIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile int seenIndexVersion = -1;

    // ------------------------------------------------------------------
    // Cached GeckoLib reflection (looked up once)
    // ------------------------------------------------------------------

    private static final class GL {
        static final boolean PRESENT;
        static Class<?> cacheClass;
        static Method getBakedModels;
        static Method getBakedAnimations;
        static Field modelsField;
        static Field animationsField;
        static Object geoGson;
        static Method fromJsonElement;
        static Class<?> modelClass;
        static Class<?> bakedAnimationsClass;
        static Class<?> geometryTreeClass;
        static Method geometryFromModel;
        static Method factoryForNamespace;
        static Method constructGeoModel;

        static {
            boolean ok;
            try {
                cacheClass = Class.forName("software.bernie.geckolib.cache.GeckoLibCache");
                getBakedModels = cacheClass.getMethod("getBakedModels");
                getBakedAnimations = cacheClass.getMethod("getBakedAnimations");
                modelsField = cacheClass.getDeclaredField("MODELS");
                modelsField.setAccessible(true);
                animationsField = cacheClass.getDeclaredField("ANIMATIONS");
                animationsField.setAccessible(true);

                Class<?> jsonUtil = Class.forName("software.bernie.geckolib.util.JsonUtil");
                geoGson = jsonUtil.getField("GEO_GSON").get(null);
                fromJsonElement = geoGson.getClass().getMethod("fromJson", JsonElement.class, Class.class);
                modelClass = Class.forName("software.bernie.geckolib.loading.json.raw.Model");
                bakedAnimationsClass = Class.forName("software.bernie.geckolib.loading.object.BakedAnimations");
                geometryTreeClass = Class.forName("software.bernie.geckolib.loading.object.GeometryTree");
                geometryFromModel = geometryTreeClass.getMethod("fromModel", modelClass);
                Class<?> factory = Class.forName("software.bernie.geckolib.loading.object.BakedModelFactory");
                factoryForNamespace = factory.getMethod("getForNamespace", String.class);
                constructGeoModel = factory.getMethod("constructGeoModel", geometryTreeClass);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            PRESENT = ok;
        }

        static Map<?, ?> models() {
            if (!PRESENT) return null;
            try {
                return (Map<?, ?>) getBakedModels.invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }

        static Map<?, ?> animations() {
            if (!PRESENT) return null;
            try {
                return (Map<?, ?>) getBakedAnimations.invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Forgets "not found" answers and fallback copies when AzureFrameLib's index changes. */
    private static void checkIndexVersion() {
        int v = AzureResourceManager.getIndexVersion();
        if (v != seenIndexVersion) {
            seenIndexVersion = v;
            FAILED_MODELS.clear();
            FAILED_ANIMATIONS.clear();
            FALLBACK_MODELS.clear();
            FALLBACK_ANIMATIONS.clear();
        }
    }

    /** Called after every client resource reload: GeckoLib has fresh data, so start clean. */
    public static void onResourceReloadFinished() {
        FAILED_MODELS.clear();
        FAILED_ANIMATIONS.clear();
        FALLBACK_MODELS.clear();
        FALLBACK_ANIMATIONS.clear();
    }

    public static void clearCaches() {
        FALLBACK_MODELS.clear();
        FALLBACK_ANIMATIONS.clear();
        FAILED_MODELS.clear();
        FAILED_ANIMATIONS.clear();
        try {
            Map<?, ?> models = GL.models();
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && isManagedNamespace(rl.getNamespace()));
            }
            Map<?, ?> anims = GL.animations();
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
        FALLBACK_MODELS.keySet().removeIf(loc -> loc.getPath().contains(clean));
        FALLBACK_ANIMATIONS.keySet().removeIf(loc -> loc.getPath().contains(clean));

        try {
            Map<?, ?> models = GL.models();
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && rl.getPath().contains(clean));
            }
            Map<?, ?> anims = GL.animations();
            if (anims != null) {
                anims.keySet().removeIf(loc -> loc instanceof ResourceLocation rl && rl.getPath().contains(clean));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Retrieves an existing baked model or bakes it on the fly from any registered framework config folder.
     */
    public static Object getOrLoadBakedModel(ResourceLocation location) {
        if (location == null) return null;
        checkIndexVersion();
        if (FAILED_MODELS.contains(location)) return null;

        Map<?, ?> models = GL.models();
        if (models != null) {
            Object existing = models.get(location);
            if (existing != null) return existing;
        }

        Object fallback = FALLBACK_MODELS.get(location);
        if (fallback != null) {
            // GeckoLib replaced its maps (resource reload): put our copy back so GeckoLib can find it too.
            if (GL.PRESENT && models != null && !(fallback instanceof JsonElement)) {
                injectModel(location, fallback);
            }
            return fallback;
        }

        File file = findSourceFile(location, true);
        if (file != null && file.isFile()) {
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
        if (location == null) return null;
        checkIndexVersion();
        if (FAILED_ANIMATIONS.contains(location)) return null;

        Map<?, ?> anims = GL.animations();
        if (anims != null) {
            Object existing = anims.get(location);
            if (existing != null) return existing;
        }

        Object fallback = FALLBACK_ANIMATIONS.get(location);
        if (fallback != null) {
            if (GL.PRESENT && anims != null && !(fallback instanceof JsonElement)) {
                injectAnimations(location, fallback);
            }
            return fallback;
        }

        File file = findSourceFile(location, false);
        if (file != null && file.isFile()) {
            Object baked = bakeAnimationsFromFile(location, file);
            if (baked != null) {
                injectAnimations(location, baked);
                return baked;
            }
        }

        FAILED_ANIMATIONS.add(location);
        return null;
    }

    /** Finds the file for a model/animation location: index first (memory only), then the older name search. */
    private static File findSourceFile(ResourceLocation location, boolean model) {
        String path = location.getPath();
        try {
            Optional<File> direct = AzureAssetIndex.getSourceFile(location);
            if (direct.isPresent()) {
                File f = direct.get();
                if (model ? AzureResourceManager.isValidGeoModelFile(f) : AzureResourceManager.isValidAnimationFile(f)) {
                    return f;
                }
            }
            Optional<ResourceLocation> canon = model ? AzureAssetIndex.findModel(location.toString()) : AzureAssetIndex.findAnimation(location.toString());
            if (canon.isPresent()) {
                Optional<File> f = AzureAssetIndex.getSourceFile(canon.get());
                if (f.isPresent()) return f.get();
            }
        } catch (Throwable ignored) {
        }

        File file;
        if (model) {
            String modelId = extractIdFromPath(path, "geo/", ".geo.json");
            if (modelId.isEmpty()) modelId = extractIdFromPath(path, "models/", ".geo.json");
            if (modelId.isEmpty()) modelId = extractIdFromPath(path, "", ".json");
            file = AzureResourceManager.findModelFile(modelId);
            if (file == null) file = AzureResourceManager.findModelFile(location.toString());
            if (file == null) file = AzureResourceManager.findModelFile(path);
            if (file == null && path.contains("/")) file = AzureResourceManager.findModelFile(path.substring(path.lastIndexOf('/') + 1));
        } else {
            String animId = extractIdFromPath(path, "animations/", ".animation.json");
            if (animId.isEmpty()) animId = extractIdFromPath(path, "", ".json");
            file = AzureResourceManager.findAnimationFile(animId);
            if (file == null) file = AzureResourceManager.findAnimationFile(location.toString());
            if (file == null) file = AzureResourceManager.findAnimationFile(path);
            if (file == null && path.contains("/")) file = AzureResourceManager.findAnimationFile(path.substring(path.lastIndexOf('/') + 1));
        }
        return file;
    }

    public static Object bakeModelFromFile(ResourceLocation location, File file) {
        if (file == null || !file.isFile()) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".animation.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) {
            return null;
        }
        if (!AzureResourceManager.isValidGeoModelFile(file)) return null;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return bakeModelFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib model file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeModelFromJson(ResourceLocation location, String jsonContent) {
        if (jsonContent == null || jsonContent.trim().isEmpty()) return null;

        JsonObject root;
        try {
            // Validate JSON
            JsonElement parsed = parseLenient(jsonContent);
            if (!parsed.isJsonObject()) return null;
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] Model JSON for {} could not be read: {}", location, t.getMessage());
            return null;
        }

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

        // Hitbox bones only describe the mob's size; never draw them.
        if (AzureFrameLibConfig.get().hideHitboxBones) {
            ModelHitboxHelper.stripHitboxCubes(root);
        }

        if (!GL.PRESENT) {
            // Headless / fallback environment
            if (location != null) {
                FALLBACK_MODELS.put(location, root);
            }
            return root;
        }

        try {
            Object rawModel = GL.fromJsonElement.invoke(GL.geoGson, root, GL.modelClass);
            if (rawModel == null) return null;

            Object tree = GL.geometryFromModel.invoke(null, rawModel);
            if (tree == null) return null;

            String ns = (location != null) ? location.getNamespace() : AzureFrameLib.MOD_ID;
            Object factoryObj = GL.factoryForNamespace.invoke(null, ns);
            Object bakedGeoModel = GL.constructGeoModel.invoke(factoryObj, tree);

            if (bakedGeoModel != null && location != null) {
                injectModel(location, bakedGeoModel);
            }
            return bakedGeoModel;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to bake GeckoLib model " + location + ": " + cause.getMessage());
            return null;
        }
    }

    public static Object bakeAnimationsFromFile(ResourceLocation location, File file) {
        if (file == null || !file.isFile()) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".geo.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) {
            return null;
        }
        if (!AzureResourceManager.isValidAnimationFile(file)) return null;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return bakeAnimationsFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib animation file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeAnimationsFromJson(ResourceLocation location, String jsonContent) {
        if (jsonContent == null || jsonContent.trim().isEmpty()) return null;

        JsonObject root;
        try {
            JsonElement parsed = parseLenient(jsonContent);
            if (!parsed.isJsonObject()) return null;
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] Animation JSON for {} could not be read: {}", location, t.getMessage());
            return null;
        }
        JsonObject animObj = root.has("animations") && root.get("animations").isJsonObject() ? root.getAsJsonObject("animations") : root;

        if (!GL.PRESENT) {
            if (location != null) {
                FALLBACK_ANIMATIONS.put(location, root);
            }
            return root;
        }

        try {
            Object bakedAnimObj = GL.fromJsonElement.invoke(GL.geoGson, animObj, GL.bakedAnimationsClass);

            if (bakedAnimObj != null && location != null) {
                injectAnimations(location, bakedAnimObj);
            }
            return bakedAnimObj;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to bake GeckoLib animations " + location + ": " + cause.getMessage());
            return null;
        }
    }

    private static JsonElement parseLenient(String content) {
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        JsonReader reader = new JsonReader(new StringReader(content));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    public static void injectModel(ResourceLocation location, Object bakedModel) {
        if (location == null || bakedModel == null) return;
        Map<ResourceLocation, Object> map = ensureModifiableModelMap();

        Set<ResourceLocation> targets = new HashSet<>();
        targets.add(location);

        String ns = location.getNamespace();
        String path = location.getPath();

        if (path.startsWith("models/")) {
            addTarget(targets, ns, "geo/" + path.substring(7));
            addTarget(targets, ns, path.substring(7));
        } else if (path.startsWith("geo/")) {
            addTarget(targets, ns, "models/" + path.substring(4));
            addTarget(targets, ns, path.substring(4));
        } else {
            addTarget(targets, ns, "geo/" + path);
            addTarget(targets, ns, "models/" + path);
        }

        if (path.contains("/")) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            addTarget(targets, ns, base);
            addTarget(targets, ns, "geo/" + base);
            addTarget(targets, ns, "models/" + base);
        }

        // Also register under azureframelib
        Set<ResourceLocation> commonTargets = new HashSet<>();
        for (ResourceLocation loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                addTarget(commonTargets, "azureframelib", loc.getPath());
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
            addTarget(targets, ns, path.substring(11));
        } else {
            addTarget(targets, ns, "animations/" + path);
        }

        if (path.contains("/")) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            addTarget(targets, ns, base);
            addTarget(targets, ns, "animations/" + base);
        }

        Set<ResourceLocation> commonTargets = new HashSet<>();
        for (ResourceLocation loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                addTarget(commonTargets, "azureframelib", loc.getPath());
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

    private static void addTarget(Set<ResourceLocation> targets, String ns, String path) {
        ResourceLocation rl = ResourceLocation.tryBuild(ns, path);
        if (rl != null) targets.add(rl);
    }

    public static boolean isModelBaked(ResourceLocation location) {
        if (location == null) return false;
        Map<?, ?> models = GL.models();
        if (models != null && checkModelPresence(models, location)) return true;
        return checkModelPresence(FALLBACK_MODELS, location);
    }

    public static boolean isAnimationBaked(ResourceLocation location) {
        if (location == null) return false;
        Map<?, ?> anims = GL.animations();
        if (anims != null && checkAnimationPresence(anims, location)) return true;
        return checkAnimationPresence(FALLBACK_ANIMATIONS, location);
    }

    /** The baked model if it is already loaded (never loads or bakes anything). */
    public static Object getCachedBakedModel(ResourceLocation location) {
        if (location == null) return null;
        Map<?, ?> models = GL.models();
        Object o = models != null ? models.get(location) : null;
        return o != null ? o : FALLBACK_MODELS.get(location);
    }

    /** The baked animations if they are already loaded (never loads or bakes anything). */
    public static Object getCachedBakedAnimations(ResourceLocation location) {
        if (location == null) return null;
        Map<?, ?> anims = GL.animations();
        Object o = anims != null ? anims.get(location) : null;
        return o != null ? o : FALLBACK_ANIMATIONS.get(location);
    }

    /**
     * Copy of all loaded GeckoLib models (GeckoLib's own plus the ones AzureFrameLib baked).
     * Safe to call from any thread. Returns a snapshot.
     */
    public static Map<ResourceLocation, Object> getBakedModelsSnapshot() {
        return snapshotOf(GL.models(), FALLBACK_MODELS);
    }

    /**
     * Copy of all loaded GeckoLib animation files (GeckoLib's own plus the ones AzureFrameLib baked).
     * Safe to call from any thread. Returns a snapshot.
     */
    public static Map<ResourceLocation, Object> getBakedAnimationsSnapshot() {
        return snapshotOf(GL.animations(), FALLBACK_ANIMATIONS);
    }

    private static Map<ResourceLocation, Object> snapshotOf(Map<?, ?> live, Map<ResourceLocation, Object> fallback) {
        Map<ResourceLocation, Object> out = new HashMap<>(fallback);
        if (live != null) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Map<ResourceLocation, Object> copy = new HashMap<>();
                    for (Map.Entry<?, ?> e : live.entrySet()) {
                        if (e.getKey() instanceof ResourceLocation rl && e.getValue() != null) copy.put(rl, e.getValue());
                    }
                    out.putAll(copy);
                    break;
                } catch (ConcurrentModificationException ignored) {
                    // GeckoLib's map changed while copying; try again.
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean checkModelPresence(Map<?, ?> map, ResourceLocation loc) {
        if (map == null || loc == null) return false;
        if (map.containsKey(loc)) return true;
        String path = loc.getPath();
        String ns = loc.getNamespace();
        if (path.isEmpty()) return false;
        if (path.startsWith("geo/")) {
            if (containsKey(map, ns, path.substring(4))) return true;
        } else {
            if (containsKey(map, ns, "geo/" + path)) return true;
        }
        if (path.startsWith("models/")) {
            if (containsKey(map, ns, path.substring(7))) return true;
        } else {
            if (containsKey(map, ns, "models/" + path)) return true;
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
            if (containsKey(map, ns, path.substring(11))) return true;
        } else {
            if (containsKey(map, ns, "animations/" + path)) return true;
        }
        return false;
    }

    private static boolean containsKey(Map<?, ?> map, String ns, String path) {
        ResourceLocation rl = ResourceLocation.tryBuild(ns, path);
        return rl != null && map.containsKey(rl);
    }

    public static Map<ResourceLocation, Object> ensureModifiableModelMap() {
        return ensureModifiableMap(GL.modelsField);
    }

    public static Map<ResourceLocation, Object> ensureModifiableAnimationMap() {
        return ensureModifiableMap(GL.animationsField);
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<ResourceLocation, Object> ensureModifiableMap(Field field) {
        if (!GL.PRESENT || field == null) return null;
        try {
            Map<?, ?> map = (Map<?, ?>) field.get(null);

            boolean needsSwap = (map == null)
                    || map.getClass().getName().contains("EmptyMap")
                    || map.getClass().getName().contains("Unmodifiable")
                    || !(map instanceof ConcurrentMap);

            if (needsSwap) {
                Map<ResourceLocation, Object> mutableMap = new ConcurrentHashMap<>();
                if (map != null) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() instanceof ResourceLocation loc && entry.getValue() != null) {
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
        return AzureAssetIndex.isManagedNamespace(ns);
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
