package ddraig.net.azureframelib.resource;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import ddraig.net.azureframelib.AzureFrameLib;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central resource manager that links and shares custom resources
 * (models, textures, sounds, animations) across all framework mods.
 * Maintains an exact in-memory ResourceLocation index for instant O(1) lookups.
 */
public class AzureResourceManager {
    private static final Gson GSON = new Gson();
    private static final AzureResourceManager INSTANCE = new AzureResourceManager();

    private static final Set<String> IGNORED_CONFIG_JSONS = Set.of(
            "mount.json", "mob.json", "projectile.json", "entity.json", "config.json",
            "data.json", "metadata.json", "sounds.json", "pack.mcmeta"
    );

    public enum ResourceCategory {
        MODEL,
        TEXTURE,
        ANIMATION,
        SOUND,
        UNPACKED_BUNDLE // Folder containing model, animation, textures, and sounds together
    }

    public static class ResourceRoot {
        public final ResourceCategory category;
        public final String namespace;
        public final File directory;

        public ResourceRoot(ResourceCategory category, String namespace, File directory) {
            this.category = category;
            this.namespace = namespace;
            this.directory = directory;
        }
    }

    private static final List<ResourceRoot> RESOURCE_ROOTS = new CopyOnWriteArrayList<>();

    // Cached discovered assets
    private static final Set<String> CACHED_MODELS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CACHED_TEXTURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> CACHED_ANIMATIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CACHED_SOUNDS = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<String>> CACHED_MODEL_ANIM_KEYS = new ConcurrentHashMap<>();

    // High-speed ResourceLocation index mapping exact locations to files
    private static final Map<ResourceLocation, File> RESOURCE_INDEX = new ConcurrentHashMap<>();
    private static final Set<String> INDEXED_NAMESPACES = ConcurrentHashMap.newKeySet();

    private static boolean initialized = false;

    public static AzureResourceManager get() {
        return INSTANCE;
    }

    public static synchronized void init() {
        if (initialized) return;
        registerDefaultFrameworkFolders();
        reload();
        initialized = true;
    }

    public static void registerFolder(ResourceCategory category, String namespace, File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        RESOURCE_ROOTS.add(new ResourceRoot(category, namespace, dir));
    }

    private static void registerDefaultFrameworkFolders() {
        RESOURCE_ROOTS.clear();
        File configDir = Platform.getConfigFolder().toFile();

        // 1. AzureFrameLib Central Shared Folders
        File azureBase = new File(configDir, "AzureFrameLib");
        registerFolder(ResourceCategory.MODEL, "azureframelib", new File(azureBase, "models"));
        registerFolder(ResourceCategory.TEXTURE, "azureframelib", new File(azureBase, "textures"));
        registerFolder(ResourceCategory.ANIMATION, "azureframelib", new File(azureBase, "animations"));
        registerFolder(ResourceCategory.SOUND, "azureframelib", new File(azureBase, "sounds"));
        registerFolder(ResourceCategory.UNPACKED_BUNDLE, "azureframelib", new File(azureBase, "unpacked"));

        // 2. Custom Mobs Folders
        File cmBase = new File(configDir, "CustomMobs");
        registerFolder(ResourceCategory.UNPACKED_BUNDLE, "custom_mobs", new File(cmBase, "Mobs/Unpacked"));
        registerFolder(ResourceCategory.UNPACKED_BUNDLE, "custom_mobs", new File(cmBase, "Projectiles/Unpacked"));
        registerFolder(ResourceCategory.SOUND, "custom_mobs", new File(cmBase, "Sounds"));

        // 3. RPG Mounts Folders
        File mountBase = new File(configDir, "RPG Mounts");
        registerFolder(ResourceCategory.UNPACKED_BUNDLE, "rpg_mounts", new File(mountBase, "Mounts/Unpacked"));
        registerFolder(ResourceCategory.SOUND, "rpg_mounts", new File(mountBase, "Mounts/Sounds"));

        // 4. Custom Races Folders
        File raceBase = new File(configDir, "custom_races");
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "models"));
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "geo"));
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "models/parts"));
        registerFolder(ResourceCategory.TEXTURE, "customraces", new File(raceBase, "textures"));
        registerFolder(ResourceCategory.ANIMATION, "customraces", new File(raceBase, "animations"));
        registerFolder(ResourceCategory.SOUND, "customraces", new File(raceBase, "sounds"));
    }

    public static List<ResourceRoot> getRoots() {
        return Collections.unmodifiableList(RESOURCE_ROOTS);
    }

    public static Map<ResourceLocation, File> getResourceIndex() {
        return Collections.unmodifiableMap(RESOURCE_INDEX);
    }

    public static Set<String> getIndexedNamespaces() {
        return Collections.unmodifiableSet(INDEXED_NAMESPACES);
    }

    public static synchronized void reload() {
        CACHED_MODELS.clear();
        CACHED_TEXTURES.clear();
        CACHED_ANIMATIONS.clear();
        CACHED_SOUNDS.clear();
        CACHED_MODEL_ANIM_KEYS.clear();
        RESOURCE_INDEX.clear();
        INDEXED_NAMESPACES.clear();

        INDEXED_NAMESPACES.add("azureframelib");

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (!root.directory.exists() || !root.directory.isDirectory()) continue;
            INDEXED_NAMESPACES.add(root.namespace.toLowerCase(Locale.ROOT));

            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                scanBundleDirectory(root);
            } else if (root.category == ResourceCategory.MODEL) {
                scanModelDirectory(root);
            } else if (root.category == ResourceCategory.TEXTURE) {
                scanTextureDirectory(root);
            } else if (root.category == ResourceCategory.ANIMATION) {
                scanAnimationDirectory(root);
            } else if (root.category == ResourceCategory.SOUND) {
                scanSoundDirectory(root);
            }
        }

        AzureFrameLib.LOGGER.info("[AzureFrameLib] Scanned shared resources: {} models, {} textures, {} animations, {} sounds, {} indexed entries across {} namespaces",
                CACHED_MODELS.size(), CACHED_TEXTURES.size(), CACHED_ANIMATIONS.size(), CACHED_SOUNDS.size(),
                RESOURCE_INDEX.size(), INDEXED_NAMESPACES.size());
    }

    private static void indexResource(String namespace, String path, File file) {
        if (file == null || !file.exists()) return;
        try {
            ResourceLocation loc = ResourceLocation.tryBuild(namespace.toLowerCase(Locale.ROOT), path.toLowerCase(Locale.ROOT));
            if (loc != null) {
                RESOURCE_INDEX.put(loc, file);
            }
            if (!namespace.equalsIgnoreCase("azureframelib")) {
                ResourceLocation commonLoc = ResourceLocation.tryBuild("azureframelib", path.toLowerCase(Locale.ROOT));
                if (commonLoc != null) {
                    RESOURCE_INDEX.put(commonLoc, file);
                }
            }
        } catch (Exception ignored) {}
    }

    public static boolean isValidGeoModelFile(File file) {
        if (file == null || !file.exists() || !file.isFile() || file.length() < 10) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".animation.json") || name.endsWith(".java")) return false;
        if (IGNORED_CONFIG_JSONS.contains(name)) return false;
        if (!name.endsWith(".geo.json") && !name.endsWith(".json")) return false;

        try {
            String content = Files.readString(file.toPath());
            if (!content.contains("minecraft:geometry")) return false;

            JsonElement elem = JsonParser.parseString(content);
            if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (obj.has("minecraft:geometry")) {
                    JsonElement geo = obj.get("minecraft:geometry");
                    return geo.isJsonArray() && geo.getAsJsonArray().size() > 0;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isValidAnimationFile(File file) {
        if (file == null || !file.exists() || !file.isFile() || file.length() < 10) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (IGNORED_CONFIG_JSONS.contains(name)) return false;
        if (!name.endsWith(".animation.json") && !name.endsWith(".json")) return false;

        try {
            String content = Files.readString(file.toPath());
            if (!content.contains("animations")) return false;

            JsonElement elem = JsonParser.parseString(content);
            if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                return obj.has("animations");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static byte[] getEmptyGeoModelFallbackBytes(ResourceLocation location) {
        String id = location != null ? sanitizePath(stripExtension(location.getPath().replace("geo/", "").replace("models/", ""))) : "empty";
        String json = "{\n" +
                "  \"format_version\": \"1.12.0\",\n" +
                "  \"minecraft:geometry\": [\n" +
                "    {\n" +
                "      \"description\": {\n" +
                "        \"identifier\": \"geometry." + id + "\",\n" +
                "        \"texture_width\": 1,\n" +
                "        \"texture_height\": 1,\n" +
                "        \"visible_bounds_width\": 1,\n" +
                "        \"visible_bounds_height\": 1,\n" +
                "        \"visible_bounds_offset\": [0, 0, 0]\n" +
                "      },\n" +
                "      \"bones\": [\n" +
                "        {\n" +
                "          \"name\": \"root\",\n" +
                "          \"pivot\": [0, 0, 0]\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static File findGeoModelInBundle(File folder) {
        File geoFile = findFileWithExtensions(folder, ".geo.json");
        if (geoFile != null && isValidGeoModelFile(geoFile)) {
            return geoFile;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    if (isValidGeoModelFile(f)) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private static File findAnimationInBundle(File folder) {
        File animFile = findFileWithExtensions(folder, ".animation.json");
        if (animFile != null && isValidAnimationFile(animFile)) {
            return animFile;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    if (isValidAnimationFile(f)) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private static void scanBundleDirectory(ResourceRoot root) {
        File[] folders = root.directory.listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            String rawId = folder.getName();
            String cleanId = sanitizePath(rawId);

            // 1. GeckoLib Models
            File geoFile = findGeoModelInBundle(folder);
            if (geoFile != null) {
                CACHED_MODELS.add(cleanId);
                CACHED_MODELS.add(root.namespace + ":" + cleanId);

                String rawName = stripExtension(geoFile.getName());
                String cleanName = sanitizePath(rawName);

                indexResource(root.namespace, "geo/" + cleanId + ".geo.json", geoFile);
                indexResource(root.namespace, "geo/" + cleanId + ".json", geoFile);
                indexResource(root.namespace, "models/" + cleanId + ".geo.json", geoFile);
                indexResource(root.namespace, "models/" + cleanId + ".json", geoFile);

                if (!cleanName.equals(cleanId)) {
                    indexResource(root.namespace, "geo/" + cleanName + ".geo.json", geoFile);
                    indexResource(root.namespace, "geo/" + cleanName + ".json", geoFile);
                    indexResource(root.namespace, "models/" + cleanName + ".geo.json", geoFile);
                    indexResource(root.namespace, "models/" + cleanName + ".json", geoFile);
                }
            } else {
                // Check if it's a Java model (.java)
                File javaFile = findFileWithExtensions(folder, ".java");
                if (javaFile != null) {
                    CACHED_MODELS.add(cleanId);
                    CACHED_MODELS.add(root.namespace + ":" + cleanId);
                    indexResource(root.namespace, "models/" + cleanId + ".java", javaFile);
                }
            }

            // 2. Animations (.animation.json)
            File animFile = findAnimationInBundle(folder);
            if (animFile != null) {
                CACHED_ANIMATIONS.add(cleanId);
                CACHED_ANIMATIONS.add(root.namespace + ":" + cleanId);

                // Index valid animation files
                indexResource(root.namespace, "animations/" + cleanId + ".animation.json", animFile);
                indexResource(root.namespace, "animations/" + cleanId + ".json", animFile);

                String animName = animFile.getName();
                int idx = animName.toLowerCase(Locale.ROOT).indexOf(".animation.json");
                if (idx > 0) {
                    String cleanAnimName = sanitizePath(animName.substring(0, idx));
                    if (!cleanAnimName.equals(cleanId)) {
                        indexResource(root.namespace, "animations/" + cleanAnimName + ".animation.json", animFile);
                        indexResource(root.namespace, "animations/" + cleanAnimName + ".json", animFile);
                    }
                }
            }

            // 3. Textures (.png)
            scanFilesRecursive(folder, file -> {
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    String rel = getRelativePath(folder, file);
                    String cleanRel = sanitizePath(rel);
                    String baseName = sanitizePath(file.getName());

                    CACHED_TEXTURES.add(cleanRel);
                    CACHED_TEXTURES.add(root.namespace + ":" + cleanRel);

                    indexResource(root.namespace, "textures/entity/" + cleanId + "/" + cleanRel, file);
                    indexResource(root.namespace, "textures/" + cleanId + "/" + cleanRel, file);
                    indexResource(root.namespace, "textures/" + cleanRel, file);
                    indexResource(root.namespace, "textures/" + baseName, file);
                }
            });

            // 4. Sounds (.ogg)
            scanFilesRecursive(folder, file -> {
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                    String rel = getRelativePath(folder, file);
                    String cleanRel = sanitizePath(stripExtension(rel));
                    CACHED_SOUNDS.add(root.namespace + ":unpacked/" + cleanId + "/" + cleanRel);

                    indexResource(root.namespace, "sounds/" + cleanId + "/" + cleanRel + ".ogg", file);
                    indexResource(root.namespace, "sounds/" + cleanRel + ".ogg", file);
                }
            });
        }
    }

    private static void scanModelDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            String name = file.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".geo.json") || lower.endsWith(".json")) {
                if (isValidGeoModelFile(file)) {
                    String baseName = stripExtension(name);
                    String cleanBase = sanitizePath(baseName);
                    CACHED_MODELS.add(cleanBase);
                    CACHED_MODELS.add(root.namespace + ":" + cleanBase);

                    String rel = getRelativePath(root.directory, file);
                    String cleanRel = sanitizePath(rel);

                    indexResource(root.namespace, "geo/" + cleanRel, file);
                    indexResource(root.namespace, "models/" + cleanRel, file);
                    if (cleanRel.endsWith(".geo.json")) {
                        indexResource(root.namespace, "geo/" + cleanRel.substring(0, cleanRel.length() - 9) + ".json", file);
                    }
                }
            } else if (lower.endsWith(".java")) {
                String baseName = stripExtension(name);
                String cleanBase = sanitizePath(baseName);
                CACHED_MODELS.add(cleanBase);
                CACHED_MODELS.add(root.namespace + ":" + cleanBase);

                String rel = getRelativePath(root.directory, file);
                String cleanRel = sanitizePath(rel);
                indexResource(root.namespace, "models/" + cleanRel, file);
            }
        });
    }

    private static void scanTextureDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                String rel = getRelativePath(root.directory, file);
                String cleanRel = sanitizePath(rel);
                CACHED_TEXTURES.add(cleanRel);
                CACHED_TEXTURES.add(root.namespace + ":" + cleanRel);

                indexResource(root.namespace, "textures/" + cleanRel, file);
            }
        });
    }

    private static void scanAnimationDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".animation.json") || lower.endsWith(".json")) {
                if (isValidAnimationFile(file)) {
                    String rel = getRelativePath(root.directory, file);
                    String cleanRel = sanitizePath(rel);
                    CACHED_ANIMATIONS.add(cleanRel);
                    CACHED_ANIMATIONS.add(root.namespace + ":" + cleanRel);

                    indexResource(root.namespace, "animations/" + cleanRel, file);
                    if (cleanRel.endsWith(".animation.json")) {
                        indexResource(root.namespace, "animations/" + cleanRel.substring(0, cleanRel.length() - 15) + ".json", file);
                    }
                }
            }
        });
    }

    private static void scanSoundDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String rel = getRelativePath(root.directory, file);
                String relNoExt = stripExtension(rel);
                String clean = sanitizePath(relNoExt);
                CACHED_SOUNDS.add(root.namespace + ":" + clean);

                indexResource(root.namespace, "sounds/" + clean + ".ogg", file);
            }
        });
    }

    private static void scanFilesRecursive(File dir, java.util.function.Consumer<File> consumer) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanFilesRecursive(f, consumer);
            } else if (f.isFile()) {
                consumer.accept(f);
            }
        }
    }

    public static File findModelFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;

        // 1. Direct index check
        ResourceLocation directLoc = parseLocation(rawInput, "geo/");
        if (directLoc != null && RESOURCE_INDEX.containsKey(directLoc)) {
            return RESOURCE_INDEX.get(directLoc);
        }

        String key = cleanKey(rawInput);
        ResourceLocation modelLoc = ResourceLocation.tryBuild("azureframelib", "geo/" + key + ".geo.json");
        if (modelLoc != null && RESOURCE_INDEX.containsKey(modelLoc)) {
            return RESOURCE_INDEX.get(modelLoc);
        }

        // 2. Fallback filesystem search across registered roots
        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File unpackedDir = findCaseInsensitiveFile(root.directory, key);
                if (unpackedDir != null && unpackedDir.isDirectory()) {
                    File geoFile = findGeoModelInBundle(unpackedDir);
                    if (geoFile != null) return geoFile;
                    File javaFile = findFileWithExtensions(unpackedDir, ".java");
                    if (javaFile != null) return javaFile;
                }
            } else if (root.category == ResourceCategory.MODEL) {
                File file = findCaseInsensitiveFile(root.directory, key);
                if (file != null && file.isFile() && (isValidGeoModelFile(file) || file.getName().toLowerCase(Locale.ROOT).endsWith(".java"))) {
                    return file;
                }

                File geoFile = findFileWithExtensions(root.directory, key + ".geo.json");
                if (geoFile != null && isValidGeoModelFile(geoFile)) return geoFile;

                File jsonFile = findFileWithExtensions(root.directory, key + ".json");
                if (jsonFile != null && isValidGeoModelFile(jsonFile)) return jsonFile;

                File javaFile = findFileWithExtensions(root.directory, key + ".java");
                if (javaFile != null) return javaFile;
            }
        }
        return null;
    }

    public static File findJavaModelFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;
        String key = cleanKey(rawInput);
        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File unpackedDir = findCaseInsensitiveFile(root.directory, key);
                if (unpackedDir != null && unpackedDir.isDirectory()) {
                    File javaFile = findFileWithExtensions(unpackedDir, ".java");
                    if (javaFile != null) return javaFile;
                }
            } else if (root.category == ResourceCategory.MODEL) {
                File javaFile = findFileWithExtensions(root.directory, key + ".java");
                if (javaFile != null) return javaFile;
            }
        }
        return null;
    }

    public static File findAnimationFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;

        // 1. Direct index check
        ResourceLocation directLoc = parseLocation(rawInput, "animations/");
        if (directLoc != null && RESOURCE_INDEX.containsKey(directLoc)) {
            return RESOURCE_INDEX.get(directLoc);
        }

        String key = cleanKey(rawInput);
        ResourceLocation animLoc = ResourceLocation.tryBuild("azureframelib", "animations/" + key + ".animation.json");
        if (animLoc != null && RESOURCE_INDEX.containsKey(animLoc)) {
            return RESOURCE_INDEX.get(animLoc);
        }

        // 2. Fallback filesystem search across registered roots
        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File unpackedDir = findCaseInsensitiveFile(root.directory, key);
                if (unpackedDir != null && unpackedDir.isDirectory()) {
                    File animFile = findAnimationInBundle(unpackedDir);
                    if (animFile != null) return animFile;
                }
            } else if (root.category == ResourceCategory.ANIMATION) {
                File file = findCaseInsensitiveFile(root.directory, key);
                if (file != null && file.isFile() && isValidAnimationFile(file)) return file;

                File animFile = findFileWithExtensions(root.directory, key + ".animation.json");
                if (animFile != null && isValidAnimationFile(animFile)) return animFile;

                File jsonFile = findFileWithExtensions(root.directory, key + ".json");
                if (jsonFile != null && isValidAnimationFile(jsonFile)) return jsonFile;
            }
        }
        return null;
    }

    public static File findTextureFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;

        // 1. Direct index check
        ResourceLocation directLoc = parseLocation(rawInput, "textures/");
        if (directLoc != null && RESOURCE_INDEX.containsKey(directLoc)) {
            return RESOURCE_INDEX.get(directLoc);
        }

        String key = cleanKey(rawInput);
        ResourceLocation texLoc = ResourceLocation.tryBuild("azureframelib", "textures/" + key + ".png");
        if (texLoc != null && RESOURCE_INDEX.containsKey(texLoc)) {
            return RESOURCE_INDEX.get(texLoc);
        }

        // 2. Fallback search
        String withPng = key.toLowerCase(Locale.ROOT).endsWith(".png") ? key : key + ".png";
        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE || root.category == ResourceCategory.TEXTURE) {
                File found = findFileRecursiveByName(root.directory, withPng);
                if (found != null && found.isFile()) return found;
            }
        }
        return null;
    }

    public static File findSoundFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;

        // 1. Direct index check
        ResourceLocation directLoc = parseLocation(rawInput, "sounds/");
        if (directLoc != null && RESOURCE_INDEX.containsKey(directLoc)) {
            return RESOURCE_INDEX.get(directLoc);
        }

        String key = cleanKey(rawInput);
        ResourceLocation soundLoc = ResourceLocation.tryBuild("azureframelib", "sounds/" + key + ".ogg");
        if (soundLoc != null && RESOURCE_INDEX.containsKey(soundLoc)) {
            return RESOURCE_INDEX.get(soundLoc);
        }

        // 2. Fallback search
        String withOgg = key.toLowerCase(Locale.ROOT).endsWith(".ogg") ? key : key + ".ogg";
        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.SOUND || root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File found = findFileRecursiveByName(root.directory, withOgg);
                if (found != null && found.isFile()) return found;
            }
        }
        return null;
    }

    // Path returning helper methods
    public Path findModel(String name) {
        File f = findModelFile(name);
        return f != null ? f.toPath() : null;
    }

    public Path findTexture(String name) {
        File f = findTextureFile(name);
        return f != null ? f.toPath() : null;
    }

    public Path findAnimation(String name) {
        File f = findAnimationFile(name);
        return f != null ? f.toPath() : null;
    }

    public Path findSound(String name) {
        File f = findSoundFile(name);
        return f != null ? f.toPath() : null;
    }

    public static List<String> getAnimationNamesForModel(String modelOrAnimId) {
        if (modelOrAnimId == null || modelOrAnimId.trim().isEmpty()) return Collections.emptyList();
        String clean = cleanKey(modelOrAnimId);

        if (CACHED_MODEL_ANIM_KEYS.containsKey(clean)) {
            return CACHED_MODEL_ANIM_KEYS.get(clean);
        }

        List<String> keys = new ArrayList<>();
        File animFile = findAnimationFile(clean);
        if (animFile != null && animFile.exists() && animFile.isFile() && animFile.length() > 0) {
            try (FileReader reader = new FileReader(animFile)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null && json.has("animations") && json.get("animations").isJsonObject()) {
                    JsonObject animsObj = json.getAsJsonObject("animations");
                    for (String key : animsObj.keySet()) {
                        if (!keys.contains(key)) {
                            keys.add(key);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Collections.sort(keys);
        CACHED_MODEL_ANIM_KEYS.put(clean, Collections.unmodifiableList(keys));
        return keys;
    }

    public static Set<String> getDiscoveredModels() {
        return Collections.unmodifiableSet(CACHED_MODELS);
    }

    public static Set<String> getDiscoveredTextures() {
        return Collections.unmodifiableSet(CACHED_TEXTURES);
    }

    public static Set<String> getDiscoveredAnimations() {
        return Collections.unmodifiableSet(CACHED_ANIMATIONS);
    }

    public static Set<String> getDiscoveredSounds() {
        return Collections.unmodifiableSet(CACHED_SOUNDS);
    }

    // --- Helpers ---

    public static String sanitizePath(String path) {
        if (path == null) return "";
        return path.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_.-/]", "");
    }

    /**
     * Cleans an input query by stripping namespace prefixes, category folders,
     * and file extensions so it cleanly matches bundle folders or base names.
     */
    public static String cleanKey(String rawInput) {
        if (rawInput == null) return "";
        String s = rawInput.trim();
        if (s.contains(":")) {
            s = s.substring(s.indexOf(':') + 1);
        }
        s = s.replace('\\', '/').replaceAll("^/+", "");

        // Strip leading category prefixes
        if (s.startsWith("animations/")) s = s.substring(11);
        else if (s.startsWith("geo/")) s = s.substring(4);
        else if (s.startsWith("models/")) s = s.substring(7);
        else if (s.startsWith("textures/entity/")) s = s.substring(16);
        else if (s.startsWith("textures/")) s = s.substring(9);
        else if (s.startsWith("sounds/")) s = s.substring(7);

        // Strip known file extensions
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".animation.json")) {
            s = s.substring(0, s.length() - 15);
        } else if (lower.endsWith(".geo.json")) {
            s = s.substring(0, s.length() - 9);
        } else if (lower.endsWith(".json")) {
            s = s.substring(0, s.length() - 5);
        } else if (lower.endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        } else if (lower.endsWith(".ogg")) {
            s = s.substring(0, s.length() - 4);
        } else if (lower.endsWith(".java")) {
            s = s.substring(0, s.length() - 5);
        }
        return sanitizePath(s);
    }

    public static String cleanInput(String input) {
        return cleanKey(input);
    }

    private static ResourceLocation parseLocation(String input, String defaultPrefix) {
        if (input == null) return null;
        try {
            if (input.contains(":")) {
                return new ResourceLocation(input.toLowerCase(Locale.ROOT));
            }
            String p = input.startsWith(defaultPrefix) ? input : defaultPrefix + input;
            return ResourceLocation.tryBuild("azureframelib", p.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExtension(String filename) {
        int idx = filename.indexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static String getRelativePath(File base, File file) {
        try {
            Path basePath = base.toPath();
            Path filePath = file.toPath();
            return basePath.relativize(filePath).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    private static File findCaseInsensitiveFile(File parent, String relPath) {
        if (parent == null || !parent.exists() || !parent.isDirectory()) return null;
        File exact = new File(parent, relPath);
        if (exact.exists()) return exact;

        String[] parts = relPath.replace('\\', '/').split("/");
        File current = parent;
        for (String part : parts) {
            if (!current.exists() || !current.isDirectory()) return null;
            File[] children = current.listFiles();
            if (children == null) return null;
            File match = null;
            for (File child : children) {
                if (child.getName().equalsIgnoreCase(part) || sanitizePath(child.getName()).equalsIgnoreCase(sanitizePath(part))) {
                    match = child;
                    break;
                }
            }
            if (match == null) return null;
            current = match;
        }
        return current.exists() ? current : null;
    }

    private static File findFileWithExtensions(File dir, String... extensions) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) {
                String nameLower = f.getName().toLowerCase(Locale.ROOT);
                for (String ext : extensions) {
                    if (nameLower.endsWith(ext.toLowerCase(Locale.ROOT))) {
                        return f;
                    }
                }
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileWithExtensions(f, extensions);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static File findFileRecursiveByName(File dir, String targetName) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && (f.getName().equalsIgnoreCase(targetName) || sanitizePath(f.getName()).equalsIgnoreCase(sanitizePath(targetName)))) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileRecursiveByName(f, targetName);
                if (found != null) return found;
            }
        }
        return null;
    }
}
