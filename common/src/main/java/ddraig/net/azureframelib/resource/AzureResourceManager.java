package ddraig.net.azureframelib.resource;

import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.resource.AssetSnapshot.Type;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central resource manager that links and shares custom resources
 * (models, textures, sounds, animations) across all framework mods.
 * Maintains an exact in-memory ResourceLocation index for instant O(1) lookups.
 * <p>
 * The index is built on a background thread and published all at once (see {@link AssetSnapshot}),
 * so readers on any thread always see a complete index. Files that haven't changed since the last
 * scan are not read again. For fast, disk-free lookups from render code use {@link AzureAssetIndex}.
 */
public class AzureResourceManager {
    private static final AzureResourceManager INSTANCE = new AzureResourceManager();

    static final Set<String> IGNORED_CONFIG_JSONS = Set.of(
            "mount.json", "mob.json", "projectile.json", "entity.json", "config.json",
            "data.json", "metadata.json", "sounds.json", "pack.mcmeta"
    );

    /** Namespaces the shared resource pack always answers for. */
    static final Set<String> SUPPORTED_NAMESPACES = Set.of(
            "azureframelib",
            "custom_mobs",
            "rpg_mounts",
            "customraces"
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
    /** Lower-case namespaces of all registered folders plus the always-supported ones. Never blocks. */
    private static volatile Set<String> managedNamespaces = SUPPORTED_NAMESPACES;

    /** Remembers what each file is (model, animation, broken ...) so unchanged files are never re-read. */
    static final AssetFileCache FILE_CACHE = new AssetFileCache();

    // The current finished index. Swapped in all at once.
    private static volatile AssetSnapshot snapshot = AssetSnapshot.EMPTY;
    private static final AtomicInteger INDEX_VERSION = new AtomicInteger();
    private static final List<Runnable> INDEX_LISTENERS = new CopyOnWriteArrayList<>();

    // Resources registered at runtime through registerDynamicResource. Kept across rescans.
    private static final Map<ResourceLocation, File> DYNAMIC_RESOURCES = new ConcurrentHashMap<>();
    private static final AtomicInteger DYNAMIC_VERSION = new AtomicInteger();
    private static volatile MergedIndex mergedIndex;

    // Files found on disk that were missing from the index (each one triggers at most one rescan).
    private static final Set<String> STALE_TRIGGERS = ConcurrentHashMap.newKeySet();

    private static volatile boolean initialized = false;

    // ---- background indexing ----
    private static final String INDEXER_THREAD_NAME = "AzureFrameLib-Indexer";
    private static final long FIRST_BUILD_WAIT_MS = 60_000L;
    private static final long LEGACY_MISS_RETRY_MS = 30_000L;
    private static final Object BUILD_LOCK = new Object();
    private static ExecutorService indexer;
    private static volatile Thread indexerThread;
    private static CompletableFuture<Void> buildInFlight;
    private static CompletableFuture<Void> buildQueued;
    private static String queuedReason;
    private static final CompletableFuture<Void> FIRST_BUILD = new CompletableFuture<>();
    private static volatile CompletableFuture<Void> resourceReloadBuild;
    private static volatile boolean firstClientReloadSeen;

    public static AzureResourceManager get() {
        return INSTANCE;
    }

    public static synchronized void init() {
        if (initialized) return;
        AzureFrameLibConfig.load();
        registerDefaultFrameworkFolders();
        initialized = true;
        CompletableFuture<Void> build = requestRebuild("startup");
        if (!AzureFrameLibConfig.get().backgroundIndexing) {
            waitFor(build, 300_000L);
        }
    }

    public static void registerFolder(ResourceCategory category, String namespace, File dir) {
        if (dir == null) return;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        for (ResourceRoot r : RESOURCE_ROOTS) {
            if (r.category == category && r.namespace.equals(namespace) && r.directory.equals(dir)) {
                return; // already registered
            }
        }
        RESOURCE_ROOTS.add(new ResourceRoot(category, namespace, dir));
        refreshManagedNamespaces();
        if (initialized) {
            requestRebuild("a new folder was registered (" + dir.getName() + ")");
        }
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
        registerFolder(ResourceCategory.MODEL, "custom_mobs", new File(cmBase, "Models"));
        registerFolder(ResourceCategory.TEXTURE, "custom_mobs", new File(cmBase, "Textures"));
        registerFolder(ResourceCategory.ANIMATION, "custom_mobs", new File(cmBase, "Animations"));

        // 3. RPG Mounts Folders
        File mountBase = new File(configDir, "RPG Mounts");
        registerFolder(ResourceCategory.UNPACKED_BUNDLE, "rpg_mounts", new File(mountBase, "Mounts/Unpacked"));
        registerFolder(ResourceCategory.SOUND, "rpg_mounts", new File(mountBase, "Mounts/Sounds"));
        registerFolder(ResourceCategory.MODEL, "rpg_mounts", new File(mountBase, "Models"));
        registerFolder(ResourceCategory.TEXTURE, "rpg_mounts", new File(mountBase, "Textures"));
        registerFolder(ResourceCategory.ANIMATION, "rpg_mounts", new File(mountBase, "Animations"));

        // 4. Custom Races Folders
        File raceBase = new File(configDir, "custom_races");
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "models"));
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "geo"));
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "models/were"));
        registerFolder(ResourceCategory.MODEL, "customraces", new File(raceBase, "models/parts"));
        registerFolder(ResourceCategory.TEXTURE, "customraces", new File(raceBase, "textures"));
        registerFolder(ResourceCategory.TEXTURE, "customraces", new File(raceBase, "textures/were"));
        registerFolder(ResourceCategory.TEXTURE, "customraces", new File(raceBase, "textures/parts"));
        registerFolder(ResourceCategory.ANIMATION, "customraces", new File(raceBase, "animations"));
        registerFolder(ResourceCategory.ANIMATION, "customraces", new File(raceBase, "animations/were"));
        registerFolder(ResourceCategory.SOUND, "customraces", new File(raceBase, "sounds"));
        registerFolder(ResourceCategory.SOUND, "customraces", new File(raceBase, "sounds/were"));
    }

    private static void refreshManagedNamespaces() {
        Set<String> set = new HashSet<>(SUPPORTED_NAMESPACES);
        for (ResourceRoot r : RESOURCE_ROOTS) {
            set.add(r.namespace.toLowerCase(Locale.ROOT));
        }
        managedNamespaces = Collections.unmodifiableSet(set);
    }

    /** Namespaces handled by AzureFrameLib. Never waits for the index. */
    static Set<String> managedNamespaces() {
        return managedNamespaces;
    }

    public static List<ResourceRoot> getRoots() {
        return Collections.unmodifiableList(RESOURCE_ROOTS);
    }

    /**
     * Every ResourceLocation AzureFrameLib can serve, mapped to its file.
     * Safe to call from any thread. Returns a read-only snapshot.
     */
    public static Map<ResourceLocation, File> getResourceIndex() {
        AssetSnapshot s = snapshot();
        if (DYNAMIC_RESOURCES.isEmpty()) return s.index;
        int dv = DYNAMIC_VERSION.get();
        MergedIndex m = mergedIndex;
        if (m != null && m.snapshot == s && m.dynamicVersion == dv) return m.map;
        Map<ResourceLocation, File> merged = new HashMap<>(s.index);
        merged.putAll(DYNAMIC_RESOURCES);
        m = new MergedIndex(s, dv, Collections.unmodifiableMap(merged));
        mergedIndex = m;
        return m.map;
    }

    private record MergedIndex(AssetSnapshot snapshot, int dynamicVersion, Map<ResourceLocation, File> map) {
    }

    public static void registerDynamicResource(ResourceLocation location, File file) {
        if (location == null || file == null || !file.exists()) return;
        File previous = DYNAMIC_RESOURCES.put(location, file);
        if (!file.equals(previous)) {
            DYNAMIC_VERSION.incrementAndGet();
            // Forget earlier "not found" answers so the new resource is found right away.
            AssetSnapshot s = snapshot;
            s.lookupCache.clear();
            s.legacyFileCache.clear();
            s.legacyMisses.clear();
        }
    }

    /** Looks up a file by its exact location: the index first, then runtime-registered resources. No disk access. */
    static File indexedFile(AssetSnapshot s, ResourceLocation location) {
        if (location == null) return null;
        File f = s.index.get(location);
        return f != null ? f : DYNAMIC_RESOURCES.get(location);
    }

    /** True if the location was registered through {@link #registerDynamicResource}. */
    static boolean isDynamicResource(ResourceLocation location) {
        return location != null && DYNAMIC_RESOURCES.containsKey(location);
    }

    /** Resources registered at runtime through {@link #registerDynamicResource}. Read-only view. */
    static Map<ResourceLocation, File> dynamicResources() {
        return Collections.unmodifiableMap(DYNAMIC_RESOURCES);
    }

    /**
     * Namespaces found in the registered folders.
     * Safe to call from any thread. Returns a snapshot.
     */
    public static Set<String> getIndexedNamespaces() {
        return snapshot().namespaces;
    }

    // ------------------------------------------------------------------
    // Rescanning
    // ------------------------------------------------------------------

    /**
     * Rescans all registered folders and waits until the new index is ready.
     * Files that haven't changed are not read again, so this is quick after the first scan.
     * Use {@link #requestReload()} to rescan without waiting.
     */
    public static void reload() {
        if (isIndexerThread()) {
            buildAndPublish("reload");
            return;
        }
        waitFor(requestRebuild("reload requested"), 300_000L);
    }

    /** Starts a rescan in the background. The returned future completes when the new index is live. */
    public static CompletableFuture<Void> requestReload() {
        return requestRebuild("reload requested");
    }

    /** Number that goes up every time the index changes (startup, F3+T, new folders or bundles, reload()). */
    public static int getIndexVersion() {
        return INDEX_VERSION.get();
    }

    /**
     * Runs the listener every time the index changes. On a client it runs on the main game thread;
     * on a dedicated server it runs on the indexing thread. A failing listener never breaks others.
     */
    public static void onIndexChanged(Runnable listener) {
        if (listener != null) INDEX_LISTENERS.add(listener);
    }

    public static void removeIndexListener(Runnable listener) {
        INDEX_LISTENERS.remove(listener);
    }

    /** True once the first scan has finished. */
    public static boolean isIndexReady() {
        return FIRST_BUILD.isDone();
    }

    /** Returns the current index, waiting for the first scan if it is still running. */
    static AssetSnapshot snapshot() {
        AssetSnapshot s = snapshot;
        if (!s.isEmpty() || FIRST_BUILD.isDone() || !initialized || isIndexerThread()) return s;
        waitFor(FIRST_BUILD, FIRST_BUILD_WAIT_MS);
        return snapshot;
    }

    /** Returns the current index without ever waiting (may be empty during the very first scan). */
    static AssetSnapshot currentSnapshot() {
        return snapshot;
    }

    /**
     * Index to use while Minecraft is (re)loading resources. If a rescan was started for this
     * resource reload, waits for it (briefly on the render thread) so new files are picked up;
     * otherwise keeps using the previous index.
     */
    static AssetSnapshot snapshotForResourceLoading() {
        AssetSnapshot s = snapshot();
        CompletableFuture<Void> f = resourceReloadBuild;
        if (f == null || f.isDone() || isIndexerThread()) return s;
        long wait = "Render thread".equals(Thread.currentThread().getName()) ? 5_000L : 60_000L;
        waitFor(f, wait);
        return snapshot;
    }

    /** Called when the client starts reloading resources (game start, F3+T, resource pack changes). */
    @ApiStatus.Internal
    public static void onClientResourceReloadStarting() {
        if (!initialized) return;
        if (!firstClientReloadSeen) {
            // The startup scan is already running or done; the first resource load just uses it.
            firstClientReloadSeen = true;
            synchronized (BUILD_LOCK) {
                resourceReloadBuild = buildInFlight != null ? buildInFlight : FIRST_BUILD;
            }
            return;
        }
        resourceReloadBuild = requestRebuild("resource reload");
    }

    /** Called on the main client thread after a resource reload has fully finished. */
    @ApiStatus.Internal
    public static void onClientResourceReloadFinished() {
        try {
            ddraig.net.azureframelib.client.GeckoLibModelLoader.onResourceReloadFinished();
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.debug("[AzureFrameLib] GeckoLib follow-up after reload failed: {}", t.toString());
        }
        if (AzureFrameLibConfig.get().logAnimationLinks) {
            try {
                List<String> lines = AzureAssetIndex.describeAnimationLinks(null);
                AzureFrameLib.LOGGER.info("[AzureFrameLib] Model -> animation links ({} models):", lines.size());
                for (String line : lines) {
                    AzureFrameLib.LOGGER.info("[AzureFrameLib]   {}", line);
                }
            } catch (Throwable t) {
                AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not list animation links: {}", t.toString());
            }
        }
    }

    static CompletableFuture<Void> requestRebuild(String reason) {
        synchronized (BUILD_LOCK) {
            if (buildInFlight != null && !buildInFlight.isDone()) {
                // A scan is running and may have passed the changed folder already: queue one more.
                if (buildQueued == null) buildQueued = new CompletableFuture<>();
                queuedReason = reason;
                return buildQueued;
            }
            CompletableFuture<Void> f = new CompletableFuture<>();
            buildInFlight = f;
            startBuild(reason, f);
            return f;
        }
    }

    private static void startBuild(String reason, CompletableFuture<Void> f) {
        try {
            indexer().execute(() -> runBuild(reason, f));
        } catch (Throwable t) {
            runBuild(reason, f);
        }
    }

    private static ExecutorService indexer() {
        synchronized (BUILD_LOCK) {
            if (indexer == null) {
                indexer = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, INDEXER_THREAD_NAME);
                    t.setDaemon(true);
                    t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                    indexerThread = t;
                    return t;
                });
            }
            return indexer;
        }
    }

    private static boolean isIndexerThread() {
        return Thread.currentThread() == indexerThread;
    }

    private static void runBuild(String reason, CompletableFuture<Void> f) {
        try {
            buildAndPublish(reason);
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Resource scan failed: {}", t.toString(), t);
        } finally {
            if (!FIRST_BUILD.isDone()) FIRST_BUILD.complete(null);
            synchronized (BUILD_LOCK) {
                CompletableFuture<Void> next = buildQueued;
                String nextReason = queuedReason;
                buildQueued = null;
                queuedReason = null;
                buildInFlight = next;
                if (next != null) startBuild(nextReason, next);
            }
            f.complete(null);
        }
    }

    private static void buildAndPublish(String reason) {
        long start = System.nanoTime();
        FILE_CACHE.takeFilesRead();
        AssetIndexBuilder.Result result;
        try {
            result = new AssetIndexBuilder(new ArrayList<>(RESOURCE_ROOTS), FILE_CACHE).build();
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Could not finish scanning the shared resource folders ({}). "
                    + "The previous list of resources will keep being used.", t.toString(), t);
            return;
        }

        AssetSnapshot s = result.snapshot;
        snapshot = s;
        mergedIndex = null;
        if (STALE_TRIGGERS.size() > 10_000) STALE_TRIGGERS.clear();
        int version = INDEX_VERSION.incrementAndGet();

        for (String warning : result.warnings) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] {}", warning);
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        int models = s.count(Type.MODEL) + s.count(Type.JAVA_MODEL);
        String skipped = s.skippedFiles > 0
                ? " (" + s.skippedFiles + " files skipped - see warnings in the log)"
                : "";
        AzureFrameLib.LOGGER.info("[AzureFrameLib] Indexed {} models, {} textures, {} animations, {} sounds{}",
                models, s.count(Type.TEXTURE), s.count(Type.ANIMATION), s.count(Type.SOUND), skipped);
        AzureFrameLib.LOGGER.info("[AzureFrameLib] Index #{} ready: {} entries in {} namespaces, {} ms, {} files read from disk (reason: {}).",
                version, s.index.size(), s.namespaces.size(), ms, FILE_CACHE.takeFilesRead(), reason);

        notifyIndexListeners();
    }

    private static void notifyIndexListeners() {
        if (INDEX_LISTENERS.isEmpty()) return;
        Runnable task = () -> {
            for (Runnable listener : INDEX_LISTENERS) {
                try {
                    listener.run();
                } catch (Throwable t) {
                    AzureFrameLib.LOGGER.warn("[AzureFrameLib] A mod's index-change listener failed: {}", t.toString());
                }
            }
        };
        boolean client = false;
        try {
            client = Platform.getEnvironment() == Env.CLIENT;
        } catch (Throwable ignored) {
        }
        if (client) {
            try {
                ddraig.net.azureframelib.client.ClientTaskHelper.runOnMainThread(task);
                return;
            } catch (Throwable ignored) {
            }
        }
        task.run();
    }

    private static void waitFor(CompletableFuture<?> future, long millis) {
        if (future == null) return;
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // File checks (results are remembered per file version)
    // ------------------------------------------------------------------

    public static boolean isValidGeoModelFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".animation.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) return false;
        if (IGNORED_CONFIG_JSONS.contains(name)) return false;
        if (!name.endsWith(".json")) return false;
        AssetFileCache.Info info = FILE_CACHE.get(file);
        return info != null && info.kind == AssetFileCache.Kind.MODEL;
    }

    public static boolean isModelFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".animation.json") || name.endsWith(".class") || name.endsWith(".jar")) return false;
        if (name.endsWith(".java")) return file.isFile() && file.length() >= 10;
        return isValidGeoModelFile(file);
    }

    public static boolean isValidAnimationFile(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".geo.json") || name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar")) return false;
        if (IGNORED_CONFIG_JSONS.contains(name)) return false;
        if (!name.endsWith(".json")) return false;
        AssetFileCache.Info info = FILE_CACHE.get(file);
        return info != null && info.kind == AssetFileCache.Kind.ANIMATION;
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

    // ------------------------------------------------------------------
    // File lookups (older API, returns files). Answers come from the index and are remembered;
    // the disk is only searched for names the index doesn't know.
    // ------------------------------------------------------------------

    public static File findModelFile(String rawInput) {
        File f = legacyFind(Type.MODEL, rawInput);
        return f != null ? f : legacyFind(Type.JAVA_MODEL, rawInput);
    }

    public static File findJavaModelFile(String rawInput) {
        return legacyFind(Type.JAVA_MODEL, rawInput);
    }

    public static File findAnimationFile(String rawInput) {
        return legacyFind(Type.ANIMATION, rawInput);
    }

    public static File findTextureFile(String rawInput) {
        return legacyFind(Type.TEXTURE, rawInput);
    }

    public static File findSoundFile(String rawInput) {
        return legacyFind(Type.SOUND, rawInput);
    }

    private static File legacyFind(Type type, String rawInput) {
        if (rawInput == null) return null;
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).endsWith(".mcmeta")) return null;

        AssetSnapshot s = snapshot();
        Map<String, Optional<File>> cache = s.legacyFileCache(type);
        Optional<File> cached = cache.get(trimmed);
        if (cached != null) return cached.orElse(null);

        Map<String, Long> misses = s.legacyMisses(type);
        long now = System.currentTimeMillis();
        Long missedAt = misses.get(trimmed);
        if (missedAt != null && now - missedAt < LEGACY_MISS_RETRY_MS) return null;

        File found = null;
        boolean fromIndex = false;
        try {
            found = findInIndex(s, type, trimmed);
            fromIndex = found != null;
            if (found == null) found = findOnDisk(type, trimmed);
        } catch (Throwable ignored) {
        }

        if (found != null) {
            cache.put(trimmed, Optional.of(found));
            misses.remove(trimmed);
            if (!fromIndex) noteFileMissingFromIndex(s, found);
        } else {
            misses.put(trimmed, now);
        }
        return found;
    }

    private static File findInIndex(AssetSnapshot s, Type type, String trimmed) {
        // 1. Exact ResourceLocation
        int colon = namespaceColon(trimmed);
        if (colon > 0) {
            ResourceLocation exact = ResourceLocation.tryParse(trimmed.toLowerCase(Locale.ROOT).replace('\\', '/'));
            File f = indexedFile(s, exact);
            if (f != null && isType(s, type, f)) return f;
        }

        // 2. Normalized name lookup ("dragon", "geo/dragon.geo.json", "custom_mobs:models/dragon" ...)
        ResourceLocation canon = AzureAssetIndex.resolve(s, type, trimmed);
        if (canon != null) {
            File f = indexedFile(s, canon);
            if (f != null) return f;
        }

        // 3. Older prefix/suffix combinations (map lookups only)
        String[] prefixes = legacyPrefixes(type);
        String[] suffixes = legacySuffixes(type);
        if (colon > 0) {
            String ns = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
            String path = trimmed.substring(colon + 1).toLowerCase(Locale.ROOT);
            for (String pfx : prefixes) {
                for (String sfx : suffixes) {
                    File f = indexedFile(s, ResourceLocation.tryBuild(ns, sanitizePath(pfx + path + sfx)));
                    if (f != null && isType(s, type, f)) return f;
                }
            }
        }
        String clean = cleanKey(trimmed);
        String baseName = sanitizePath(stripExtension(new File(trimmed).getName()));
        for (String ns : s.namespaces) {
            for (String testKey : new String[]{clean, baseName, trimmed}) {
                for (String pfx : prefixes) {
                    for (String sfx : suffixes) {
                        File f = indexedFile(s, ResourceLocation.tryBuild(ns, sanitizePath(pfx + testKey + sfx)));
                        if (f != null && isType(s, type, f)) return f;
                    }
                }
            }
        }
        return null;
    }

    private static String[] legacyPrefixes(Type type) {
        return switch (type) {
            case MODEL, JAVA_MODEL -> new String[]{"", "geo/", "models/", "geo/were/", "models/were/"};
            case ANIMATION -> new String[]{"", "animations/", "animations/were/"};
            case TEXTURE -> new String[]{"", "textures/", "textures/dynamic/", "textures/were/", "textures/parts/", "textures/entity/"};
            case SOUND -> new String[]{"", "sounds/", "sounds/were/"};
        };
    }

    private static String[] legacySuffixes(Type type) {
        return switch (type) {
            case MODEL -> new String[]{"", ".geo.json", ".json"};
            case JAVA_MODEL -> new String[]{"", ".java"};
            case ANIMATION -> new String[]{"", ".animation.json", ".json"};
            case TEXTURE -> new String[]{"", ".png"};
            case SOUND -> new String[]{"", ".ogg"};
        };
    }

    /** True if the file is (or, for files outside the index, looks like) an asset of this type. */
    private static boolean isType(AssetSnapshot s, Type type, File f) {
        if (s.files(type).contains(f)) return true;
        if (s.primaryLocation.containsKey(f)) return false; // indexed as a different type
        return matchesTypeOnDisk(type, f);
    }

    private static boolean matchesTypeOnDisk(Type type, File f) {
        if (f == null) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        return switch (type) {
            case TEXTURE -> name.endsWith(".png") && f.isFile();
            case SOUND -> name.endsWith(".ogg") && f.isFile();
            case JAVA_MODEL -> name.endsWith(".java") && f.isFile();
            case MODEL -> isValidGeoModelFile(f);
            case ANIMATION -> isValidAnimationFile(f);
        };
    }

    /** Last resort: search the registered folders on disk (for files added since the last scan). */
    private static File findOnDisk(Type type, String trimmed) {
        File direct = new File(trimmed);
        if (direct.isFile() && matchesTypeOnDisk(type, direct)) {
            return direct;
        }

        String clean = cleanKey(trimmed);
        String baseName = sanitizePath(stripExtension(direct.getName()));
        String[] suffixes = legacySuffixes(type);
        String[] recursiveSuffixes = switch (type) {
            case MODEL -> new String[]{".geo.json", ".json"};
            case JAVA_MODEL -> new String[]{".java"};
            case ANIMATION -> new String[]{".animation.json", ".json"};
            case TEXTURE -> new String[]{".png"};
            case SOUND -> new String[]{".ogg"};
        };

        for (ResourceRoot root : RESOURCE_ROOTS) {
            if (!root.directory.isDirectory()) continue;

            if (root.category == ResourceCategory.UNPACKED_BUNDLE
                    && (type == Type.MODEL || type == Type.JAVA_MODEL || type == Type.ANIMATION)) {
                for (String testName : new String[]{baseName, clean}) {
                    File unpackedDir = findCaseInsensitiveFile(root.directory, testName);
                    if (unpackedDir != null && unpackedDir.isDirectory()) {
                        File f = switch (type) {
                            case MODEL -> findGeoModelInBundle(unpackedDir);
                            case JAVA_MODEL -> findFileWithExtensions(unpackedDir, ".java");
                            default -> findAnimationInBundle(unpackedDir);
                        };
                        if (f != null) return f;
                    }
                }
            }

            for (String testPath : new String[]{clean, baseName, trimmed}) {
                for (String sfx : suffixes) {
                    File subFile = findCaseInsensitiveFile(root.directory, testPath + sfx);
                    if (subFile != null && subFile.isFile() && matchesTypeOnDisk(type, subFile)) {
                        return subFile;
                    }
                }
            }

            for (String sfx : recursiveSuffixes) {
                File found = findFileRecursiveByName(root.directory, baseName + sfx);
                if (found != null && found.isFile() && matchesTypeOnDisk(type, found)) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A file was found on disk that the index doesn't know about: rescan once so it gets indexed. */
    private static void noteFileMissingFromIndex(AssetSnapshot s, File f) {
        if (!initialized || s.primaryLocation.containsKey(f)) return;
        String key = f.getAbsolutePath() + "|" + f.lastModified();
        if (STALE_TRIGGERS.add(key)) {
            requestRebuild("found a file that wasn't indexed yet (" + f.getName() + ")");
        }
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

    /**
     * Animation names (sorted) in the animation file matching the given model or animation name.
     * Results are remembered until the index changes. Returns an unmodifiable list.
     */
    public static List<String> getAnimationNamesForModel(String modelOrAnimId) {
        if (modelOrAnimId == null || modelOrAnimId.trim().isEmpty()) return Collections.emptyList();
        String key = modelOrAnimId.trim();
        AssetSnapshot s = snapshot();
        List<String> cached = s.animationNameCache.get(key);
        if (cached != null) return cached;

        List<String> names = List.of();
        try {
            ResourceLocation anim = AzureAssetIndex.resolve(s, Type.ANIMATION, key);
            if (anim == null) {
                ResourceLocation model = AzureAssetIndex.resolve(s, Type.MODEL, key);
                if (model != null) anim = s.modelToAnimation.get(model);
            }
            File animFile = anim != null ? indexedFile(s, anim) : null;
            if (animFile == null) {
                String clean = cleanKey(key);
                animFile = findAnimationFile(clean);
                if (animFile == null && key.contains(":")) animFile = findAnimationFile(key);
                if (animFile == null) animFile = findAnimationFile(clean + ".animation.json");
            }
            if (animFile != null) {
                AssetFileCache.Info info = s.fileInfo.get(animFile);
                if (info == null) info = FILE_CACHE.get(animFile);
                if (info != null && info.kind == AssetFileCache.Kind.ANIMATION) names = info.animationNames;
            }
        } catch (Throwable ignored) {
        }
        if (s.animationNameCache.size() > 50_000) s.animationNameCache.clear();
        s.animationNameCache.put(key, names);
        return names;
    }

    /** Model names found in the registered folders. Safe to call from any thread. Returns a snapshot. */
    public static Set<String> getDiscoveredModels() {
        return snapshot().discoveredModels;
    }

    /** Texture names found in the registered folders. Safe to call from any thread. Returns a snapshot. */
    public static Set<String> getDiscoveredTextures() {
        return snapshot().discoveredTextures;
    }

    /** Animation names found in the registered folders. Safe to call from any thread. Returns a snapshot. */
    public static Set<String> getDiscoveredAnimations() {
        return snapshot().discoveredAnimations;
    }

    /** Sound names found in the registered folders. Safe to call from any thread. Returns a snapshot. */
    public static Set<String> getDiscoveredSounds() {
        return snapshot().discoveredSounds;
    }

    // --- Helpers ---

    public static String sanitizePath(String path) {
        if (path == null) return "";
        return path.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_.\\-/]", "");
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
        else if (s.startsWith("textures/dynamic/")) s = s.substring(17);
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
        } else if (lower.endsWith("_png")) {
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

    @SuppressWarnings("unused")
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

    public static String stripExtension(String filename) {
        if (filename == null) return "";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".animation.json")) return filename.substring(0, filename.length() - 15);
        if (lower.endsWith(".geo.json")) return filename.substring(0, filename.length() - 9);
        if (lower.endsWith("_png")) return filename.substring(0, filename.length() - 4);
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    /**
     * Position of the namespace colon in "namespace:path", or -1. Windows drive letters
     * ("C:\...") are not treated as namespaces.
     */
    static int namespaceColon(String input) {
        if (input == null) return -1;
        int colon = input.indexOf(':');
        if (colon <= 0) return -1;
        if (colon == 1 && input.length() > 2 && (input.charAt(2) == '\\' || input.charAt(2) == '/')) return -1;
        return colon;
    }

    private static File findCaseInsensitiveFile(File parent, String relPath) {
        if (parent == null || !parent.exists() || !parent.isDirectory() || relPath == null) return null;
        File exact = new File(parent, relPath);
        if (exact.exists()) return exact;

        String[] parts = relPath.replace('\\', '/').split("/");
        File current = parent;
        for (String part : parts) {
            if (part.isEmpty()) continue;
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
                    String cleanExt = ext.replace('\\', '/');
                    if (cleanExt.contains("/")) {
                        cleanExt = cleanExt.substring(cleanExt.lastIndexOf('/') + 1);
                    }
                    if (nameLower.equalsIgnoreCase(cleanExt.toLowerCase(Locale.ROOT)) || nameLower.endsWith(cleanExt.toLowerCase(Locale.ROOT))) {
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
        if (dir == null || !dir.exists() || !dir.isDirectory() || targetName == null) return null;
        String cleanTarget = targetName.replace('\\', '/');
        String pureTarget = cleanTarget.contains("/") ? cleanTarget.substring(cleanTarget.lastIndexOf('/') + 1) : cleanTarget;

        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && (f.getName().equalsIgnoreCase(pureTarget) || sanitizePath(f.getName()).equalsIgnoreCase(sanitizePath(pureTarget)))) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileRecursiveByName(f, pureTarget);
                if (found != null) return found;
            }
        }
        return null;
    }
}
