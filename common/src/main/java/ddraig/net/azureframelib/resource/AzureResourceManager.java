package ddraig.net.azureframelib.resource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import ddraig.net.azureframelib.AzureFrameLib;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central resource manager that links and shares custom resources
 * (models, textures, sounds, animations) across all framework mods.
 */
public class AzureResourceManager {
    private static final Gson GSON = new Gson();

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

    private static boolean initialized = false;

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

    public static synchronized void reload() {
        CACHED_MODELS.clear();
        CACHED_TEXTURES.clear();
        CACHED_ANIMATIONS.clear();
        CACHED_SOUNDS.clear();
        CACHED_MODEL_ANIM_KEYS.clear();

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (!root.directory.exists() || !root.directory.isDirectory()) continue;

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

        AzureFrameLib.LOGGER.info("[AzureFrameLib] Scanned shared resources: {} models, {} textures, {} animations, {} sounds",
                CACHED_MODELS.size(), CACHED_TEXTURES.size(), CACHED_ANIMATIONS.size(), CACHED_SOUNDS.size());
    }

    private static void scanBundleDirectory(ResourceRoot root) {
        File[] folders = root.directory.listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (folder.isDirectory()) {
                String id = folder.getName();
                CACHED_MODELS.add(id);
                CACHED_MODELS.add(root.namespace + ":" + id);

                // Scan animations inside unpacked bundle
                scanBundleAnimsRecursive(folder, id);

                // Scan sounds inside unpacked bundle
                scanBundleSoundsRecursive(folder, "", root.namespace + ":unpacked/" + sanitizePath(id));
            }
        }
    }

    private static void scanBundleAnimsRecursive(File dir, String modelId) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanBundleAnimsRecursive(f, modelId);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".animation.json")) {
                CACHED_ANIMATIONS.add(modelId);
                CACHED_ANIMATIONS.add(f.getName());
            }
        }
    }

    private static void scanBundleSoundsRecursive(File dir, String relativePath, String soundPrefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String nextRel = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
                scanBundleSoundsRecursive(f, nextRel, soundPrefix);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String nameNoExt = f.getName().substring(0, f.getName().length() - 4);
                String soundPath = relativePath.isEmpty() ? nameNoExt : relativePath + "/" + nameNoExt;
                String cleanPath = sanitizePath(soundPath);
                CACHED_SOUNDS.add(soundPrefix + "/" + cleanPath);
            }
        }
    }

    private static void scanModelDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            String name = file.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".geo.json") || lower.endsWith(".json") || lower.endsWith(".java")) {
                String baseName = stripExtension(name);
                CACHED_MODELS.add(baseName);
                CACHED_MODELS.add(root.namespace + ":" + baseName);
            }
        });
    }

    private static void scanTextureDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                String rel = getRelativePath(root.directory, file);
                CACHED_TEXTURES.add(rel);
                CACHED_TEXTURES.add(root.namespace + ":" + rel);
            }
        });
    }

    private static void scanAnimationDirectory(ResourceRoot root) {
        scanFilesRecursive(root.directory, file -> {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".animation.json") || lower.endsWith(".json")) {
                String rel = getRelativePath(root.directory, file);
                CACHED_ANIMATIONS.add(rel);
                CACHED_ANIMATIONS.add(root.namespace + ":" + rel);
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
        String input = cleanInput(rawInput);

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File unpackedDir = findCaseInsensitiveFile(root.directory, input);
                if (unpackedDir != null && unpackedDir.isDirectory()) {
                    File geoFile = findFileWithExtensions(unpackedDir, ".geo.json", ".json", ".java");
                    if (geoFile != null) return geoFile;
                }
            } else if (root.category == ResourceCategory.MODEL) {
                File file = findCaseInsensitiveFile(root.directory, input);
                if (file != null && file.isFile()) return file;

                File geoFile = findFileWithExtensions(root.directory, input + ".geo.json", input + ".json");
                if (geoFile != null) return geoFile;
            }
        }
        return null;
    }

    public static File findAnimationFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;
        String input = cleanInput(rawInput);

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File unpackedDir = findCaseInsensitiveFile(root.directory, input);
                if (unpackedDir != null && unpackedDir.isDirectory()) {
                    File animFile = findFileWithExtensions(unpackedDir, ".animation.json", ".json");
                    if (animFile != null) return animFile;
                }
            } else if (root.category == ResourceCategory.ANIMATION) {
                File file = findCaseInsensitiveFile(root.directory, input);
                if (file != null && file.isFile()) return file;

                File animFile = findFileWithExtensions(root.directory, input + ".animation.json", input + ".json");
                if (animFile != null) return animFile;
            }
        }
        return null;
    }

    public static File findTextureFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;
        String input = cleanInput(rawInput);
        if (!input.toLowerCase(Locale.ROOT).endsWith(".png")) {
            input = input + ".png";
        }

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.UNPACKED_BUNDLE) {
                // Check direct in unpacked bundle directories
                File direct = findFileRecursiveByName(root.directory, input);
                if (direct != null && direct.isFile()) return direct;
            } else if (root.category == ResourceCategory.TEXTURE) {
                File file = findCaseInsensitiveFile(root.directory, input);
                if (file != null && file.isFile()) return file;

                File recursive = findFileRecursiveByName(root.directory, input);
                if (recursive != null && recursive.isFile()) return recursive;
            }
        }
        return null;
    }

    public static File findSoundFile(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) return null;
        String input = cleanInput(rawInput);
        if (!input.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            input = input + ".ogg";
        }

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (root.category == ResourceCategory.SOUND || root.category == ResourceCategory.UNPACKED_BUNDLE) {
                File found = findFileRecursiveByName(root.directory, input);
                if (found != null && found.isFile()) return found;
            }
        }
        return null;
    }

    public static List<String> getAnimationNamesForModel(String modelOrAnimId) {
        if (modelOrAnimId == null || modelOrAnimId.trim().isEmpty()) return Collections.emptyList();
        String clean = cleanInput(modelOrAnimId);

        if (CACHED_MODEL_ANIM_KEYS.containsKey(clean)) {
            return CACHED_MODEL_ANIM_KEYS.get(clean);
        }

        List<String> keys = new ArrayList<>();
        File animFile = findAnimationFile(clean);
        if (animFile != null && animFile.exists() && animFile.isFile()) {
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

    private static String cleanInput(String input) {
        String s = input.trim();
        if (s.contains(":")) {
            s = s.substring(s.indexOf(':') + 1);
        }
        return s.replace('\\', '/').replaceAll("^/+", "");
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
