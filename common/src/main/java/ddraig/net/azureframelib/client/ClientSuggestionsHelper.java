package ddraig.net.azureframelib.client;

import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper for populating autocomplete and suggestion lists for creator screens and GUIs.
 * <p>
 * Lists are built once, sorted, and kept until the asset index changes (F3+T, new bundles,
 * reload) or, for biomes and dimensions, until a different world is loaded. Adding them to a
 * caller's list checks duplicates with a hash set and adds everything in one go, so it stays
 * fast with tens of thousands of entries (also with CopyOnWriteArrayList).
 */
public class ClientSuggestionsHelper {

    /** One cached list plus what it was built from. */
    private record Cached(int version, Object source, List<String> list) {
    }

    private static volatile Cached biomes;
    private static volatile Cached dimensions;
    private static volatile Cached sounds;
    private static volatile Cached particles;
    private static volatile Cached items;
    private static volatile Cached entities;
    private static volatile Cached models;
    private static volatile Cached textures;
    private static volatile Cached animations;

    private static final Map<String, List<String>> ANIMATION_SUGGESTIONS = new ConcurrentHashMap<>();
    private static volatile int animationSuggestionsVersion = -1;

    public static void clearCache() {
        biomes = null;
        dimensions = null;
        sounds = null;
        particles = null;
        items = null;
        entities = null;
        models = null;
        textures = null;
        animations = null;
        ANIMATION_SUGGESTIONS.clear();
    }

    // ------------------------------------------------------------------
    // Add to a caller's list (older API, same behavior: no duplicates are added)
    // ------------------------------------------------------------------

    public static void addClientModels(List<String> target) {
        mergeInto(target, getClientModels());
    }

    public static void addClientTextures(List<String> target) {
        mergeInto(target, getClientTextures());
    }

    public static void addClientAnimations(List<String> target) {
        mergeInto(target, getClientAnimations());
    }

    public static void addClientBiomes(List<String> target) {
        mergeInto(target, getClientBiomes());
    }

    public static void addClientDimensions(List<String> target) {
        mergeInto(target, getClientDimensions());
    }

    public static void addClientSounds(List<String> target) {
        mergeInto(target, getClientSounds());
    }

    public static void addClientParticles(List<String> target) {
        mergeInto(target, getClientParticles());
    }

    public static void addClientItems(List<String> target) {
        mergeInto(target, getClientItems());
    }

    public static void addClientEntities(List<String> target) {
        mergeInto(target, getClientEntities());
    }

    // ------------------------------------------------------------------
    // Read-only cached lists (sorted, cannot be changed)
    // ------------------------------------------------------------------

    public static List<String> getClientModels() {
        int v = AzureResourceManager.getIndexVersion();
        Cached c = models;
        if (c != null && c.version == v) return c.list;
        Set<String> set = new HashSet<>();
        set.add("customraces:models/were/default_werewolf.geo.json");
        set.add("customraces:geo/default_werewolf.geo.json");
        set.add("default_werewolf");
        try {
            set.addAll(AzureResourceManager.getDiscoveredModels());
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        models = new Cached(v, null, list);
        return list;
    }

    public static List<String> getClientTextures() {
        int v = AzureResourceManager.getIndexVersion();
        Cached c = textures;
        if (c != null && c.version == v) return c.list;
        Set<String> set = new HashSet<>();
        set.add("skin");
        set.add("player");
        set.add("customraces:textures/were/default_werewolf.png");
        set.add("default_werewolf.png");
        try {
            set.addAll(AzureResourceManager.getDiscoveredTextures());
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        textures = new Cached(v, null, list);
        return list;
    }

    public static List<String> getClientAnimations() {
        int v = AzureResourceManager.getIndexVersion();
        Cached c = animations;
        if (c != null && c.version == v) return c.list;
        Set<String> set = new HashSet<>();
        set.add("customraces:animations/were/default_werewolf.animation.json");
        set.add("default_werewolf.animation.json");
        try {
            set.addAll(AzureResourceManager.getDiscoveredAnimations());
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        animations = new Cached(v, null, list);
        return list;
    }

    public static List<String> getClientSounds() {
        int v = AzureResourceManager.getIndexVersion();
        Cached c = sounds;
        if (c != null && c.version == v) return c.list;
        Set<String> set = new HashSet<>();
        try {
            for (ResourceLocation loc : BuiltInRegistries.SOUND_EVENT.keySet()) {
                set.add(loc.toString());
            }
        } catch (Throwable ignored) {
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getSoundManager() != null) {
                Collection<ResourceLocation> available = mc.getSoundManager().getAvailableSounds();
                if (available != null) {
                    for (ResourceLocation loc : available) {
                        set.add(loc.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            set.addAll(AzureResourceManager.getDiscoveredSounds());
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        sounds = new Cached(v, null, list);
        return list;
    }

    public static List<String> getClientBiomes() {
        Object registries = currentRegistries();
        Cached c = biomes;
        if (c != null && c.source == registries) return c.list;
        Set<String> set = new HashSet<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                var regOpt = mc.level.registryAccess().registry(Registries.BIOME);
                if (regOpt.isPresent()) {
                    for (ResourceLocation loc : regOpt.get().keySet()) {
                        set.add(loc.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        biomes = new Cached(0, registries, list);
        return list;
    }

    public static List<String> getClientDimensions() {
        Object registries = currentRegistries();
        Cached c = dimensions;
        if (c != null && c.source == registries) return c.list;
        Set<String> set = new HashSet<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                var regOpt = mc.level.registryAccess().registry(Registries.DIMENSION_TYPE);
                if (regOpt.isPresent()) {
                    for (ResourceLocation loc : regOpt.get().keySet()) {
                        set.add(loc.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        dimensions = new Cached(0, registries, list);
        return list;
    }

    public static List<String> getClientParticles() {
        Cached c = particles;
        if (c != null) return c.list;
        Set<String> set = new HashSet<>();
        try {
            for (ResourceLocation loc : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
                set.add(loc.toString());
            }
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        if (!list.isEmpty()) particles = new Cached(0, null, list);
        return list;
    }

    public static List<String> getClientItems() {
        Cached c = items;
        if (c != null) return c.list;
        Set<String> set = new HashSet<>();
        try {
            for (ResourceLocation loc : BuiltInRegistries.ITEM.keySet()) {
                set.add(loc.toString());
            }
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        if (!list.isEmpty()) items = new Cached(0, null, list);
        return list;
    }

    public static List<String> getClientEntities() {
        Cached c = entities;
        if (c != null) return c.list;
        Set<String> set = new HashSet<>();
        try {
            for (ResourceLocation loc : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                set.add(loc.toString());
            }
        } catch (Throwable ignored) {
        }
        List<String> list = sorted(set);
        if (!list.isEmpty()) entities = new Cached(0, null, list);
        return list;
    }

    /**
     * Adds the animation names found for a model or animation file to {@code results}
     * (no duplicates). Names come from AzureFrameLib's index first; files only found in other
     * resource packs or mod jars are read once and remembered until the index changes.
     */
    public static void addClientAnimationSuggestions(String cleanPath, List<String> results, com.google.gson.Gson gson) {
        if (cleanPath == null || results == null) return;
        try {
            mergeInto(results, getAnimationSuggestions(cleanPath, gson));
        } catch (Throwable ignored) {
        }
    }

    private static List<String> getAnimationSuggestions(String cleanPath, com.google.gson.Gson gson) {
        int v = AzureResourceManager.getIndexVersion();
        if (animationSuggestionsVersion != v) {
            ANIMATION_SUGGESTIONS.clear();
            animationSuggestionsVersion = v;
        }
        List<String> cached = ANIMATION_SUGGESTIONS.get(cleanPath);
        if (cached != null) return cached;

        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            names.addAll(AzureAssetIndex.getAnimationNames(cleanPath));
            if (names.isEmpty()) {
                names.addAll(AzureResourceManager.getAnimationNamesForModel(cleanPath));
            }
        } catch (Throwable ignored) {
        }

        if (names.isEmpty()) {
            // Not one of AzureFrameLib's files: read it from the loaded resources (mod jars, packs).
            try {
                if (Minecraft.getInstance() != null) {
                    ResourceLocation rl = cleanPath.contains(":")
                            ? new ResourceLocation(cleanPath)
                            : new ResourceLocation("customraces", "animations/" + cleanPath);
                    var res = Minecraft.getInstance().getResourceManager().getResource(rl);
                    if (res.isPresent()) {
                        try (java.io.InputStreamReader isr = new java.io.InputStreamReader(res.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                            com.google.gson.Gson g = gson != null ? gson : new com.google.gson.Gson();
                            com.google.gson.JsonObject json = g.fromJson(isr, com.google.gson.JsonObject.class);
                            if (json != null && json.has("animations") && json.get("animations").isJsonObject()) {
                                names.addAll(json.getAsJsonObject("animations").keySet());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        List<String> list = List.copyOf(names);
        if (ANIMATION_SUGGESTIONS.size() > 5_000) ANIMATION_SUGGESTIONS.clear();
        ANIMATION_SUGGESTIONS.put(cleanPath, list);
        return list;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Object currentRegistries() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null ? mc.level.registryAccess() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> sorted(Collection<String> values) {
        List<String> list = new ArrayList<>(values);
        list.removeIf(Objects::isNull);
        list.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        return Collections.unmodifiableList(list);
    }

    /**
     * Adds entries the target doesn't have yet. Duplicates are checked with a hash set, and new
     * entries are added with a single addAll, so a CopyOnWriteArrayList is only copied once.
     */
    private static void mergeInto(List<String> target, List<String> source) {
        if (target == null || source == null || source.isEmpty()) return;
        try {
            Set<String> existing = new HashSet<>(target);
            List<String> toAdd = new ArrayList<>();
            for (String s : source) {
                if (s != null && existing.add(s)) {
                    toAdd.add(s);
                }
            }
            if (!toAdd.isEmpty()) {
                target.addAll(toAdd);
            }
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.debug("[AzureFrameLib] Could not add suggestions: {}", t.toString());
        }
    }
}
