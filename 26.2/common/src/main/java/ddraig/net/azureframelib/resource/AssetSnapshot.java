package ddraig.net.azureframelib.resource;

import net.minecraft.resources.Identifier;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One finished, read-only copy of the asset index. A new snapshot is built in the
 * background and swapped in all at once, so readers on any thread always see a
 * complete, consistent index and never a half-built one.
 * <p>
 * Everything here is in memory: nothing in this class touches the disk.
 */
public final class AssetSnapshot {

    /** Asset types with their own lookup tables. */
    public enum Type { MODEL, JAVA_MODEL, ANIMATION, TEXTURE, SOUND }

    static final AssetSnapshot EMPTY = new AssetSnapshot();

    /** Every Identifier (including aliases) the dynamic pack can serve, mapped to its file. */
    final Map<Identifier, File> index;
    /** The same entries grouped by namespace and sorted by path, for fast folder listing. */
    final Map<String, NavigableMap<String, File>> byNamespace;
    final Set<String> namespaces;

    final Set<String> discoveredModels;
    final Set<String> discoveredTextures;
    final Set<String> discoveredAnimations;
    final Set<String> discoveredSounds;

    /** Normalized lookup key ("dragon", "custom_mobs:dragon", "were/dragon", ...) to the asset's main Identifier. */
    final Map<Type, Map<String, Identifier>> keys;
    /** Main Identifier of each asset file. */
    final Map<File, Identifier> primaryLocation;
    /** Checked file info for model and animation files. */
    final Map<File, AssetFileCache.Info> fileInfo;
    /** Files of each type. */
    final Map<Type, Set<File>> filesByType;
    /** Model main Identifier to the animation file linked with it. */
    final Map<Identifier, Identifier> modelToAnimation;

    final int skippedFiles;
    final long builtAtMillis;

    // Per-snapshot lookup caches (including "not found"). They disappear with the snapshot.
    final Map<Type, Map<String, Optional<Identifier>>> lookupCache = new ConcurrentHashMap<>();
    final Map<Type, Map<String, Optional<File>>> legacyFileCache = new ConcurrentHashMap<>();
    final Map<String, List<String>> animationNameCache = new ConcurrentHashMap<>();
    final Map<Identifier, Optional<ddraig.net.azureframelib.model.HitboxSize>> hitboxCache = new ConcurrentHashMap<>();
    /** Old-style file searches that found nothing (also on disk): input to the time of the miss. */
    final Map<Type, Map<String, Long>> legacyMisses = new ConcurrentHashMap<>();

    private AssetSnapshot() {
        this.index = Collections.emptyMap();
        this.byNamespace = Collections.emptyMap();
        this.namespaces = Set.of();
        this.discoveredModels = Set.of();
        this.discoveredTextures = Set.of();
        this.discoveredAnimations = Set.of();
        this.discoveredSounds = Set.of();
        this.keys = Collections.emptyMap();
        this.primaryLocation = Collections.emptyMap();
        this.fileInfo = Collections.emptyMap();
        this.filesByType = Collections.emptyMap();
        this.modelToAnimation = Collections.emptyMap();
        this.skippedFiles = 0;
        this.builtAtMillis = 0L;
    }

    AssetSnapshot(Map<Identifier, File> index,
                  Map<String, NavigableMap<String, File>> byNamespace,
                  Set<String> namespaces,
                  Set<String> discoveredModels, Set<String> discoveredTextures,
                  Set<String> discoveredAnimations, Set<String> discoveredSounds,
                  Map<Type, Map<String, Identifier>> keys,
                  Map<File, Identifier> primaryLocation,
                  Map<File, AssetFileCache.Info> fileInfo,
                  Map<Type, Set<File>> filesByType,
                  Map<Identifier, Identifier> modelToAnimation,
                  int skippedFiles) {
        this.index = index;
        this.byNamespace = byNamespace;
        this.namespaces = namespaces;
        this.discoveredModels = discoveredModels;
        this.discoveredTextures = discoveredTextures;
        this.discoveredAnimations = discoveredAnimations;
        this.discoveredSounds = discoveredSounds;
        this.keys = keys;
        this.primaryLocation = primaryLocation;
        this.fileInfo = fileInfo;
        this.filesByType = filesByType;
        this.modelToAnimation = modelToAnimation;
        this.skippedFiles = skippedFiles;
        this.builtAtMillis = System.currentTimeMillis();
    }

    boolean isEmpty() {
        return this == EMPTY;
    }

    Set<File> files(Type type) {
        Set<File> s = filesByType.get(type);
        return s != null ? s : Set.of();
    }

    Map<String, Identifier> keys(Type type) {
        Map<String, Identifier> m = keys.get(type);
        return m != null ? m : Map.of();
    }

    int count(Type type) {
        return files(type).size();
    }

    // ---------------- bounded caches ----------------

    private static final int MAX_CACHE = 50_000;

    Map<String, Optional<Identifier>> lookupCache(Type type) {
        Map<String, Optional<Identifier>> m = lookupCache.computeIfAbsent(type, t -> new ConcurrentHashMap<>());
        if (m.size() > MAX_CACHE) m.clear();
        return m;
    }

    Map<String, Optional<File>> legacyFileCache(Type type) {
        Map<String, Optional<File>> m = legacyFileCache.computeIfAbsent(type, t -> new ConcurrentHashMap<>());
        if (m.size() > MAX_CACHE) m.clear();
        return m;
    }

    Map<String, Long> legacyMisses(Type type) {
        Map<String, Long> m = legacyMisses.computeIfAbsent(type, t -> new ConcurrentHashMap<>());
        if (m.size() > MAX_CACHE) m.clear();
        return m;
    }
}
