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
import net.minecraft.resources.Identifier;

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

    private static final Map<Identifier, Object> FALLBACK_MODELS = new ConcurrentHashMap<>();
    private static final Map<Identifier, Object> FALLBACK_ANIMATIONS = new ConcurrentHashMap<>();
    private static final Map<File, Object> BAKED_MODELS_BY_FILE = new ConcurrentHashMap<>();
    private static final Map<File, Object> BAKED_ANIMATIONS_BY_FILE = new ConcurrentHashMap<>();
    private static final java.util.Set<Identifier> FAILED_MODELS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.Set<Identifier> FAILED_ANIMATIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile Object emptyBakedModel;
    private static volatile Object emptyBakedAnimations;
    private static volatile int seenIndexVersion = -1;

    private static final class LazyGeckoMap extends ConcurrentHashMap<Identifier, Object> {
        private final boolean modelMap;
        private final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> Boolean.FALSE);

        LazyGeckoMap(boolean modelMap) {
            this.modelMap = modelMap;
        }

        Object getRaw(Object key) {
            return super.get(key);
        }

        @Override
        public Object get(Object key) {
            Object v = super.get(key);
            if (v != null || !(key instanceof Identifier rl)) return v;
            if (!isManagedNamespace(rl.getNamespace())) return null;
            if (Boolean.TRUE.equals(loading.get())) return null;
            loading.set(Boolean.TRUE);
            try {
                return modelMap ? getOrLoadBakedModel(rl) : getOrLoadBakedAnimations(rl);
            } catch (Throwable ignored) {
                return null;
            } finally {
                loading.set(Boolean.FALSE);
            }
        }
    }

    private static Object getRaw(Map<?, ?> map, Identifier key) {
        if (map == null || key == null) return null;
        if (map instanceof LazyGeckoMap lazy) return lazy.getRaw(key);
        return map.get(key);
    }

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
            BAKED_MODELS_BY_FILE.clear();
            BAKED_ANIMATIONS_BY_FILE.clear();
        }
    }

    /** Called after every client resource reload: GeckoLib has fresh data, so start clean. */
    public static void onResourceReloadFinished() {
        FAILED_MODELS.clear();
        FAILED_ANIMATIONS.clear();
        FALLBACK_MODELS.clear();
        FALLBACK_ANIMATIONS.clear();
        BAKED_MODELS_BY_FILE.clear();
        BAKED_ANIMATIONS_BY_FILE.clear();
        ensureModifiableModelMap();
        ensureModifiableAnimationMap();
    }

    public static void clearCaches() {
        FALLBACK_MODELS.clear();
        FALLBACK_ANIMATIONS.clear();
        BAKED_MODELS_BY_FILE.clear();
        BAKED_ANIMATIONS_BY_FILE.clear();
        FAILED_MODELS.clear();
        FAILED_ANIMATIONS.clear();
        try {
            Map<?, ?> models = GL.models();
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof Identifier rl && isManagedNamespace(rl.getNamespace()));
            }
            Map<?, ?> anims = GL.animations();
            if (anims != null) {
                anims.keySet().removeIf(loc -> loc instanceof Identifier rl && isManagedNamespace(rl.getNamespace()));
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
        BAKED_MODELS_BY_FILE.keySet().removeIf(f -> AzureResourceManager.sanitizePath(f.getName()).contains(clean));
        BAKED_ANIMATIONS_BY_FILE.keySet().removeIf(f -> AzureResourceManager.sanitizePath(f.getName()).contains(clean));

        try {
            Map<?, ?> models = GL.models();
            if (models != null) {
                models.keySet().removeIf(loc -> loc instanceof Identifier rl && rl.getPath().contains(clean));
            }
            Map<?, ?> anims = GL.animations();
            if (anims != null) {
                anims.keySet().removeIf(loc -> loc instanceof Identifier rl && rl.getPath().contains(clean));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Retrieves an existing baked model or bakes it on the fly from any registered framework config folder.
     */
    public static Object getOrLoadBakedModel(Identifier location) {
        if (location == null) return null;
        checkIndexVersion();
        if (FAILED_MODELS.contains(location)) return null;

        Map<?, ?> models = GL.models();
        if (models != null) {
            Object existing = getRaw(models, location);
            if (existing != null) return existing;
        }

        Object fallback = FALLBACK_MODELS.get(location);
        if (fallback != null) {
            if (GL.PRESENT && models != null && !(fallback instanceof JsonElement)) {
                injectModel(location, fallback);
            }
            return fallback;
        }

        File file = findSourceFile(location, true);
        if (file != null && file.isFile()) {
            Object byFile = BAKED_MODELS_BY_FILE.get(file);
            if (byFile != null) {
                injectModel(location, byFile);
                return byFile;
            }
            Object baked = bakeModelFromFile(location, file);
            if (baked != null) {
                BAKED_MODELS_BY_FILE.put(file, baked);
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
    public static Object getOrLoadBakedAnimations(Identifier location) {
        if (location == null) return null;
        checkIndexVersion();
        if (FAILED_ANIMATIONS.contains(location)) return null;

        Map<?, ?> anims = GL.animations();
        if (anims != null) {
            Object existing = getRaw(anims, location);
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
            Object byFile = BAKED_ANIMATIONS_BY_FILE.get(file);
            if (byFile != null) {
                injectAnimations(location, byFile);
                return byFile;
            }
            Object baked = bakeAnimationsFromFile(location, file);
            if (baked != null) {
                BAKED_ANIMATIONS_BY_FILE.put(file, baked);
                injectAnimations(location, baked);
                return baked;
            }
        }

        FAILED_ANIMATIONS.add(location);
        return null;
    }

    /**
     * Ensures that a model in a managed namespace is present in GeckoLibCache before GeoModel.getBakedModel reads it.
     * If the model file is missing or corrupt, a tiny shared empty model is placed under that key so GeckoLib never crashes.
     */
    public static void ensureModelInCache(Identifier location) {
        if (location == null || !GL.PRESENT) return;
        Map<?, ?> models = GL.models();
        if (getRaw(models, location) != null) return;
        if (getOrLoadBakedModel(location) != null) return;
        Object empty = getOrCreateEmptyModel();
        if (empty != null) {
            Map<Identifier, Object> map = ensureModifiableModelMap();
            if (map != null) map.putIfAbsent(location, empty);
        }
    }

    /**
     * Ensures that animations in a managed namespace are present in GeckoLibCache before GeoModel.getAnimation reads them.
     * If the animation file is missing or corrupt, a shared empty BakedAnimations is placed under that key so GeckoLib never crashes.
     */
    public static void ensureAnimationInCache(Identifier location) {
        if (location == null || !GL.PRESENT) return;
        Map<?, ?> anims = GL.animations();
        if (getRaw(anims, location) != null) return;
        if (getOrLoadBakedAnimations(location) != null) return;
        Object empty = getOrCreateEmptyAnimations();
        if (empty != null) {
            Map<Identifier, Object> map = ensureModifiableAnimationMap();
            if (map != null) map.putIfAbsent(location, empty);
        }
    }

    private static Object getOrCreateEmptyModel() {
        Object cached = emptyBakedModel;
        if (cached != null) return cached;
        try {
            Identifier dummy = Identifier.tryBuild(AzureFrameLib.MOD_ID, "geo/empty.geo.json");
            String json = new String(AzureResourceManager.getEmptyGeoModelFallbackBytes(dummy), StandardCharsets.UTF_8);
            JsonElement parsed = parseLenient(json);
            Object rawModel = GL.fromJsonElement.invoke(GL.geoGson, parsed.getAsJsonObject(), GL.modelClass);
            Object tree = GL.geometryFromModel.invoke(null, rawModel);
            Object factoryObj = GL.factoryForNamespace.invoke(null, AzureFrameLib.MOD_ID);
            cached = GL.constructGeoModel.invoke(factoryObj, tree);
            emptyBakedModel = cached;
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getOrCreateEmptyAnimations() {
        Object cached = emptyBakedAnimations;
        if (cached != null) return cached;
        try {
            JsonObject emptyObj = new JsonObject();
            cached = GL.fromJsonElement.invoke(GL.geoGson, emptyObj, GL.bakedAnimationsClass);
            emptyBakedAnimations = cached;
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds the file for a model/animation location: index first (memory only), then the older name search. */
    private static File findSourceFile(Identifier location, boolean model) {
        String path = location.getPath();
        try {
            Optional<Identifier> canon = model ? AzureAssetIndex.findModel(location.toString()) : AzureAssetIndex.findAnimation(location.toString());
            if (canon.isPresent()) {
                Optional<File> f = AzureAssetIndex.getSourceFile(canon.get());
                if (f.isPresent()) return f.get();
            }
            Optional<File> direct = AzureAssetIndex.getSourceFile(location);
            if (direct.isPresent()) {
                File f = direct.get();
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".json") && (model ? !lower.endsWith(".animation.json") : !lower.endsWith(".geo.json"))) {
                    return f;
                }
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

    public static Object bakeModelFromFile(Identifier location, File file) {
        if (file == null || !file.isFile() || file.length() == 0) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json") || name.endsWith(".animation.json")) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return bakeModelFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib model file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeModelFromJson(Identifier location, String jsonContent) {
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

    public static Object bakeAnimationsFromFile(Identifier location, File file) {
        if (file == null || !file.isFile() || file.length() == 0) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json") || name.endsWith(".geo.json")) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return bakeAnimationsFromJson(location, content);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed reading GeckoLib animation file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static Object bakeAnimationsFromJson(Identifier location, String jsonContent) {
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

    public static void injectModel(Identifier location, Object bakedModel) {
        if (location == null || bakedModel == null) return;
        Map<Identifier, Object> map = ensureModifiableModelMap();

        Set<Identifier> targets = new HashSet<>();
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
        Set<Identifier> commonTargets = new HashSet<>();
        for (Identifier loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                addTarget(commonTargets, "azureframelib", loc.getPath());
            }
        }
        targets.addAll(commonTargets);

        for (Identifier loc : targets) {
            if (map != null) {
                try {
                    map.put(loc, bakedModel);
                } catch (Throwable ignored) {}
            } else {
                FALLBACK_MODELS.put(loc, bakedModel);
            }
        }
    }

    public static void injectAnimations(Identifier location, Object bakedAnimations) {
        if (location == null || bakedAnimations == null) return;
        Map<Identifier, Object> map = ensureModifiableAnimationMap();

        Set<Identifier> targets = new HashSet<>();
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

        Set<Identifier> commonTargets = new HashSet<>();
        for (Identifier loc : targets) {
            if (!loc.getNamespace().equalsIgnoreCase("azureframelib")) {
                addTarget(commonTargets, "azureframelib", loc.getPath());
            }
        }
        targets.addAll(commonTargets);

        for (Identifier loc : targets) {
            if (map != null) {
                try {
                    map.put(loc, bakedAnimations);
                } catch (Throwable ignored) {}
            } else {
                FALLBACK_ANIMATIONS.put(loc, bakedAnimations);
            }
        }
    }

    private static void addTarget(Set<Identifier> targets, String ns, String path) {
        Identifier rl = Identifier.tryBuild(ns, path);
        if (rl != null) targets.add(rl);
    }

    public static boolean isModelBaked(Identifier location) {
        if (location == null) return false;
        Map<?, ?> models = GL.models();
        if (models != null && checkModelPresence(models, location)) return true;
        return checkModelPresence(FALLBACK_MODELS, location);
    }

    public static boolean isAnimationBaked(Identifier location) {
        if (location == null) return false;
        Map<?, ?> anims = GL.animations();
        if (anims != null && checkAnimationPresence(anims, location)) return true;
        return checkAnimationPresence(FALLBACK_ANIMATIONS, location);
    }

    /** The baked model if it is already loaded (never loads or bakes anything). */
    public static Object getCachedBakedModel(Identifier location) {
        if (location == null) return null;
        Map<?, ?> models = GL.models();
        Object o = models != null ? getRaw(models, location) : null;
        return o != null ? o : FALLBACK_MODELS.get(location);
    }

    /** The baked animations if they are already loaded (never loads or bakes anything). */
    public static Object getCachedBakedAnimations(Identifier location) {
        if (location == null) return null;
        Map<?, ?> anims = GL.animations();
        Object o = anims != null ? getRaw(anims, location) : null;
        return o != null ? o : FALLBACK_ANIMATIONS.get(location);
    }

    /**
     * Copy of all loaded GeckoLib models (GeckoLib's own plus the ones AzureFrameLib baked).
     * Safe to call from any thread. Returns a snapshot.
     */
    public static Map<Identifier, Object> getBakedModelsSnapshot() {
        return snapshotOf(GL.models(), FALLBACK_MODELS);
    }

    /**
     * Copy of all loaded GeckoLib animation files (GeckoLib's own plus the ones AzureFrameLib baked).
     * Safe to call from any thread. Returns a snapshot.
     */
    public static Map<Identifier, Object> getBakedAnimationsSnapshot() {
        return snapshotOf(GL.animations(), FALLBACK_ANIMATIONS);
    }

    private static Map<Identifier, Object> snapshotOf(Map<?, ?> live, Map<Identifier, Object> fallback) {
        Map<Identifier, Object> out = new HashMap<>(fallback);
        if (live != null) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Map<Identifier, Object> copy = new HashMap<>();
                    for (Map.Entry<?, ?> e : live.entrySet()) {
                        if (e.getKey() instanceof Identifier rl && e.getValue() != null) copy.put(rl, e.getValue());
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

    private static boolean checkModelPresence(Map<?, ?> map, Identifier loc) {
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

    private static boolean checkAnimationPresence(Map<?, ?> map, Identifier loc) {
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
        Identifier rl = Identifier.tryBuild(ns, path);
        return rl != null && map.containsKey(rl);
    }

    public static Map<Identifier, Object> ensureModifiableModelMap() {
        return ensureModifiableMap(GL.modelsField, true);
    }

    public static Map<Identifier, Object> ensureModifiableAnimationMap() {
        return ensureModifiableMap(GL.animationsField, false);
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<Identifier, Object> ensureModifiableMap(Field field, boolean modelMap) {
        if (!GL.PRESENT || field == null) return null;
        try {
            Map<?, ?> map = (Map<?, ?>) field.get(null);

            if (!(map instanceof LazyGeckoMap)) {
                LazyGeckoMap mutableMap = new LazyGeckoMap(modelMap);
                if (map != null) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() instanceof Identifier loc && entry.getValue() != null) {
                            mutableMap.put(loc, entry.getValue());
                        }
                    }
                }
                field.set(null, mutableMap);
                return mutableMap;
            }
            return (Map<Identifier, Object>) map;
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
