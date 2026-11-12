package ddraig.net.azureframelib.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared handling for "Hitbox" bones: bones that only exist to describe a mob's size
 * (named hitbox, hit_box, collision, collider or bounds by default; see azureframelib.json).
 * <p>
 * AzureFrameLib removes the cubes of these bones before models are handed to GeckoLib,
 * so they are never drawn, and can report their size for collision boxes.
 */
public final class ModelHitboxHelper {

    private ModelHitboxHelper() {
    }

    /**
     * Size of the model's hitbox bone in blocks, if the model has one.
     * Works with names, paths or ResourceLocations of AzureFrameLib models, and with models
     * bundled inside mod jars. Results are cached until the asset index changes.
     * Safe to call on both client and server.
     */
    public static Optional<HitboxSize> getModelHitbox(ResourceLocation model) {
        return AzureAssetIndex.getModelHitbox(model);
    }

    public static boolean isHitboxBone(String boneName) {
        return AzureFrameLibConfig.isHitboxBoneName(boneName);
    }

    // ------------------------------------------------------------------
    // JSON (Bedrock / GeckoLib geometry) helpers
    // ------------------------------------------------------------------

    /** Measures the hitbox bone(s) of a parsed model file. Returns null when there are none. */
    public static HitboxSize measureHitbox(JsonObject root) {
        if (root == null) return null;
        for (JsonArray bones : collectBoneArrays(root)) {
            Set<String> hitboxBones = findHitboxBoneNames(bones);
            if (hitboxBones.isEmpty()) continue;

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            boolean any = false;
            for (JsonElement el : bones) {
                if (!el.isJsonObject()) continue;
                JsonObject bone = el.getAsJsonObject();
                if (!hitboxBones.contains(boneName(bone))) continue;
                JsonElement cubesEl = bone.get("cubes");
                if (cubesEl == null || !cubesEl.isJsonArray()) continue;
                for (JsonElement cubeEl : cubesEl.getAsJsonArray()) {
                    if (!cubeEl.isJsonObject()) continue;
                    JsonObject cube = cubeEl.getAsJsonObject();
                    double[] origin = readVec3(cube.get("origin"));
                    double[] size = readVec3(cube.get("size"));
                    if (origin == null || size == null) continue;
                    double x1 = Math.min(origin[0], origin[0] + size[0]), x2 = Math.max(origin[0], origin[0] + size[0]);
                    double y1 = Math.min(origin[1], origin[1] + size[1]), y2 = Math.max(origin[1], origin[1] + size[1]);
                    double z1 = Math.min(origin[2], origin[2] + size[2]), z2 = Math.max(origin[2], origin[2] + size[2]);
                    minX = Math.min(minX, x1); maxX = Math.max(maxX, x2);
                    minY = Math.min(minY, y1); maxY = Math.max(maxY, y2);
                    minZ = Math.min(minZ, z1); maxZ = Math.max(maxZ, z2);
                    any = true;
                }
            }
            if (any) {
                return new HitboxSize((float) ((maxX - minX) / 16.0), (float) ((maxY - minY) / 16.0), (float) ((maxZ - minZ) / 16.0));
            }
        }
        return null;
    }

    /** True when the model has at least one hitbox bone with cubes in it. */
    public static boolean hasHitboxCubes(JsonObject root) {
        if (root == null) return false;
        for (JsonArray bones : collectBoneArrays(root)) {
            Set<String> hitboxBones = findHitboxBoneNames(bones);
            if (hitboxBones.isEmpty()) continue;
            for (JsonElement el : bones) {
                if (!el.isJsonObject()) continue;
                JsonObject bone = el.getAsJsonObject();
                if (hitboxBones.contains(boneName(bone)) && bone.has("cubes")) return true;
            }
        }
        return false;
    }

    /**
     * Removes the cubes from every hitbox bone (and bones inside it) so they are never drawn.
     * The bones themselves stay, so animations that mention them keep working.
     *
     * @return true if anything was removed
     */
    public static boolean stripHitboxCubes(JsonObject root) {
        if (root == null) return false;
        boolean changed = false;
        for (JsonArray bones : collectBoneArrays(root)) {
            Set<String> hitboxBones = findHitboxBoneNames(bones);
            if (hitboxBones.isEmpty()) continue;
            for (JsonElement el : bones) {
                if (!el.isJsonObject()) continue;
                JsonObject bone = el.getAsJsonObject();
                if (!hitboxBones.contains(boneName(bone))) continue;
                if (bone.remove("cubes") != null) changed = true;
                if (bone.remove("poly_mesh") != null) changed = true;
            }
        }
        return changed;
    }

    /** Names of hitbox bones plus every bone nested inside one of them. */
    private static Set<String> findHitboxBoneNames(JsonArray bones) {
        Map<String, String> parentOf = new HashMap<>();
        Set<String> direct = new HashSet<>();
        for (JsonElement el : bones) {
            if (!el.isJsonObject()) continue;
            JsonObject bone = el.getAsJsonObject();
            String name = boneName(bone);
            if (name == null) continue;
            JsonElement parent = bone.get("parent");
            if (parent != null && parent.isJsonPrimitive()) parentOf.put(name, parent.getAsString());
            if (AzureFrameLibConfig.isHitboxBoneName(name)) direct.add(name);
        }
        if (direct.isEmpty()) return direct;

        Set<String> result = new HashSet<>(direct);
        for (String name : parentOf.keySet()) {
            String p = parentOf.get(name);
            int guard = 0;
            while (p != null && guard++ < 128) {
                if (direct.contains(p)) {
                    result.add(name);
                    break;
                }
                p = parentOf.get(p);
            }
        }
        return result;
    }

    private static List<JsonArray> collectBoneArrays(JsonObject root) {
        List<JsonArray> out = new ArrayList<>();
        JsonElement geo = root.get("minecraft:geometry");
        if (geo != null && geo.isJsonArray()) {
            for (JsonElement g : geo.getAsJsonArray()) {
                if (g.isJsonObject()) addBones(g.getAsJsonObject(), out);
            }
        }
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                addBones(e.getValue().getAsJsonObject(), out);
            }
        }
        return out;
    }

    private static void addBones(JsonObject geometry, List<JsonArray> out) {
        JsonElement bones = geometry.get("bones");
        if (bones != null && bones.isJsonArray()) out.add(bones.getAsJsonArray());
    }

    private static String boneName(JsonObject bone) {
        JsonElement n = bone.get("name");
        return n != null && n.isJsonPrimitive() ? n.getAsString() : null;
    }

    private static double[] readVec3(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 3) return null;
        try {
            return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Baked GeckoLib model helper (for mods that bake models themselves)
    // ------------------------------------------------------------------

    /**
     * Hides hitbox bones in an already-baked GeckoLib model by removing their cubes.
     * Models loaded through AzureFrameLib are already handled; this is for mods that bake
     * their own. Does nothing (and never throws) if GeckoLib isn't present.
     *
     * @return number of bones that were emptied
     */
    public static int hideHitboxBones(Object bakedGeoModel) {
        if (bakedGeoModel == null || !AzureFrameLibConfig.get().hideHitboxBones) return 0;
        try {
            Method topLevel = bakedGeoModel.getClass().getMethod("topLevelBones");
            Object bones = topLevel.invoke(bakedGeoModel);
            if (!(bones instanceof List<?> list)) return 0;
            int count = 0;
            for (Object bone : list) count += hideRecursive(bone, false);
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hideRecursive(Object bone, boolean insideHitbox) throws Exception {
        if (bone == null) return 0;
        Class<?> c = bone.getClass();
        String name = (String) c.getMethod("getName").invoke(bone);
        boolean isHitbox = insideHitbox || AzureFrameLibConfig.isHitboxBoneName(name);
        int count = 0;
        if (isHitbox) {
            Object cubes = c.getMethod("getCubes").invoke(bone);
            if (cubes instanceof List<?> cubeList && !cubeList.isEmpty()) {
                try {
                    cubeList.clear();
                } catch (UnsupportedOperationException e) {
                    c.getMethod("setHidden", boolean.class).invoke(bone, true);
                }
                count++;
            }
        }
        Object children = c.getMethod("getChildBones").invoke(bone);
        if (children instanceof List<?> childList) {
            for (Object child : childList) count += hideRecursive(child, isHitbox);
        }
        return count;
    }
}
