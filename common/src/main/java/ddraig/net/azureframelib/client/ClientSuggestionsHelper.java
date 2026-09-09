package ddraig.net.azureframelib.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Helper for populating autocomplete and suggestion lists for creator screens and GUIs.
 */
public class ClientSuggestionsHelper {

    private static final List<String> CACHED_BIOMES = new CopyOnWriteArrayList<>();
    private static final List<String> CACHED_DIMENSIONS = new CopyOnWriteArrayList<>();
    private static final List<String> CACHED_SOUNDS = new CopyOnWriteArrayList<>();
    private static final List<String> CACHED_PARTICLES = new CopyOnWriteArrayList<>();
    private static final List<String> CACHED_ITEMS = new CopyOnWriteArrayList<>();
    private static final List<String> CACHED_ENTITIES = new CopyOnWriteArrayList<>();

    public static void clearCache() {
        CACHED_BIOMES.clear();
        CACHED_DIMENSIONS.clear();
        CACHED_SOUNDS.clear();
        CACHED_PARTICLES.clear();
        CACHED_ITEMS.clear();
        CACHED_ENTITIES.clear();
    }

    public static void addClientBiomes(List<String> target) {
        if (CACHED_BIOMES.isEmpty()) {
            populateBiomes();
        }
        mergeInto(target, CACHED_BIOMES);
    }

    public static void addClientDimensions(List<String> target) {
        if (CACHED_DIMENSIONS.isEmpty()) {
            populateDimensions();
        }
        mergeInto(target, CACHED_DIMENSIONS);
    }

    public static void addClientSounds(List<String> target) {
        if (CACHED_SOUNDS.isEmpty()) {
            populateSounds();
        }
        mergeInto(target, CACHED_SOUNDS);
    }

    public static void addClientParticles(List<String> target) {
        if (CACHED_PARTICLES.isEmpty()) {
            populateParticles();
        }
        mergeInto(target, CACHED_PARTICLES);
    }

    public static void addClientItems(List<String> target) {
        if (CACHED_ITEMS.isEmpty()) {
            populateItems();
        }
        mergeInto(target, CACHED_ITEMS);
    }

    public static void addClientEntities(List<String> target) {
        if (CACHED_ENTITIES.isEmpty()) {
            populateEntities();
        }
        mergeInto(target, CACHED_ENTITIES);
    }

    private static void populateBiomes() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                var regOpt = mc.level.registryAccess().registry(Registries.BIOME);
                if (regOpt.isPresent()) {
                    for (ResourceLocation loc : regOpt.get().keySet()) {
                        CACHED_BIOMES.add(loc.toString());
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void populateDimensions() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                var regOpt = mc.level.registryAccess().registry(Registries.DIMENSION_TYPE);
                if (regOpt.isPresent()) {
                    for (ResourceLocation loc : regOpt.get().keySet()) {
                        CACHED_DIMENSIONS.add(loc.toString());
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void populateSounds() {
        try {
            for (ResourceLocation loc : BuiltInRegistries.SOUND_EVENT.keySet()) {
                CACHED_SOUNDS.add(loc.toString());
            }
        } catch (Throwable ignored) {}

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getSoundManager() != null) {
                Collection<ResourceLocation> available = mc.getSoundManager().getAvailableSounds();
                if (available != null) {
                    for (ResourceLocation loc : available) {
                        String str = loc.toString();
                        if (!CACHED_SOUNDS.contains(str)) {
                            CACHED_SOUNDS.add(str);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            for (String soundId : ddraig.net.azureframelib.resource.AzureResourceManager.getDiscoveredSounds()) {
                if (!CACHED_SOUNDS.contains(soundId)) {
                    CACHED_SOUNDS.add(soundId);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void addClientAnimationSuggestions(String cleanPath, List<String> results, com.google.gson.Gson gson) {
        try {
            if (Minecraft.getInstance() != null) {
                ResourceLocation rl = cleanPath.contains(":")
                        ? new ResourceLocation(cleanPath)
                        : new ResourceLocation("customraces", "animations/" + cleanPath);
                var res = Minecraft.getInstance().getResourceManager().getResource(rl);
                if (res.isPresent()) {
                    try (java.io.InputStreamReader isr = new java.io.InputStreamReader(res.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                        com.google.gson.JsonObject json = gson.fromJson(isr, com.google.gson.JsonObject.class);
                        if (json != null && json.has("animations") && json.get("animations").isJsonObject()) {
                            com.google.gson.JsonObject animsObj = json.getAsJsonObject("animations");
                            for (String key : animsObj.keySet()) {
                                if (!results.contains(key)) {
                                    results.add(key);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            List<String> discovered = ddraig.net.azureframelib.resource.AzureResourceManager.getAnimationNamesForModel(cleanPath);
            for (String key : discovered) {
                if (!results.contains(key)) {
                    results.add(key);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void populateParticles() {
        try {
            for (ResourceLocation loc : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
                CACHED_PARTICLES.add(loc.toString());
            }
        } catch (Throwable ignored) {}
    }

    private static void populateItems() {
        try {
            for (ResourceLocation loc : BuiltInRegistries.ITEM.keySet()) {
                CACHED_ITEMS.add(loc.toString());
            }
        } catch (Throwable ignored) {}
    }

    private static void populateEntities() {
        try {
            for (ResourceLocation loc : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                CACHED_ENTITIES.add(loc.toString());
            }
        } catch (Throwable ignored) {}
    }

    private static void mergeInto(List<String> target, List<String> source) {
        if (target == null) return;
        for (String s : source) {
            if (!target.contains(s)) {
                target.add(s);
            }
        }
    }
}
