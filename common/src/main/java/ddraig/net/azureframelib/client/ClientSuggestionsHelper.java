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
