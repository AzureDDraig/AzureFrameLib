package ddraig.net.azureframelib.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import ddraig.net.azureframelib.model.HitboxSize;
import ddraig.net.azureframelib.model.ModelHitboxHelper;
import ddraig.net.azureframelib.resource.AssetSnapshot.Type;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Fast lookups of AzureFrameLib's models, animations, textures and sounds.
 * <p>
 * Every method here answers from the in-memory index and never touches the disk, so they are
 * safe to call every frame from the render thread, and from any other thread. Answers (including
 * "not found") are remembered until the index changes.
 * <p>
 * All common ways of naming an asset work and are not case-sensitive, for example:
 * {@code "dragon"}, {@code "Dragon.geo.json"}, {@code "models/dragon.geo.json"},
 * {@code "geo/dragon.geo.json"} or {@code "custom_mobs:models/dragon.geo.json"}
 * (and the same forms with {@code .animation.json} or {@code .png}). File names with capitals or
 * spaces are found by their original name and by their Minecraft-safe name
 * ({@code "Anime Girl.png"} → {@code "anime_girl.png"}).
 */
public final class AzureAssetIndex {

    private AzureAssetIndex() {
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    /** The GeckoLib model ({@code <ns>:geo/<name>.geo.json}) matching the name or path. */
    public static Optional<ResourceLocation> findModel(String nameOrPath) {
        return Optional.ofNullable(resolve(AzureResourceManager.snapshot(), Type.MODEL, nameOrPath));
    }

    /** The animation file ({@code <ns>:animations/<name>.animation.json}) matching the name or path. */
    public static Optional<ResourceLocation> findAnimation(String nameOrPath) {
        return Optional.ofNullable(resolve(AzureResourceManager.snapshot(), Type.ANIMATION, nameOrPath));
    }

    /** The texture ({@code <ns>:textures/.../<name>.png}) matching the name or path. */
    public static Optional<ResourceLocation> findTexture(String nameOrPath) {
        return Optional.ofNullable(resolve(AzureResourceManager.snapshot(), Type.TEXTURE, nameOrPath));
    }

    /** The sound file ({@code <ns>:sounds/<name>.ogg}) matching the name or path. */
    public static Optional<ResourceLocation> findSound(String nameOrPath) {
        return Optional.ofNullable(resolve(AzureResourceManager.snapshot(), Type.SOUND, nameOrPath));
    }

    /** The Java model file ({@code <ns>:models/<name>.java}) matching the name or path. */
    public static Optional<ResourceLocation> findJavaModel(String nameOrPath) {
        return Optional.ofNullable(resolve(AzureResourceManager.snapshot(), Type.JAVA_MODEL, nameOrPath));
    }

    public static boolean hasModel(String nameOrPath) {
        return findModel(nameOrPath).isPresent();
    }

    public static boolean hasAnimation(String nameOrPath) {
        return findAnimation(nameOrPath).isPresent();
    }

    public static boolean hasTexture(String nameOrPath) {
        return findTexture(nameOrPath).isPresent();
    }

    public static boolean hasSound(String nameOrPath) {
        return findSound(nameOrPath).isPresent();
    }

    /**
     * The file on disk behind a location returned by the find methods (or any location
     * AzureFrameLib serves), for mods that need to read the raw file. No disk access.
     */
    public static Optional<File> getSourceFile(ResourceLocation location) {
        if (location == null) return Optional.empty();
        AssetSnapshot s = AzureResourceManager.snapshot();
        File f = AzureResourceManager.indexedFile(s, location);
        if (f == null) {
            // Allow non-standard forms like "custom_mobs:dragon".
            Type type = guessType(location.getPath());
            if (type != null) {
                ResourceLocation canon = resolve(s, type, location.toString());
                if (canon != null) f = AzureResourceManager.indexedFile(s, canon);
            }
        }
        return Optional.ofNullable(f);
    }

    // ------------------------------------------------------------------
    // Animations
    // ------------------------------------------------------------------

    /** The animation file linked to a model (same bundle folder, or same name). */
    public static Optional<ResourceLocation> findAnimationForModel(String modelNameOrPath) {
        AssetSnapshot s = AzureResourceManager.snapshot();
        ResourceLocation model = resolve(s, Type.MODEL, modelNameOrPath);
        ResourceLocation anim = model != null ? s.modelToAnimation.get(model) : null;
        if (anim == null) anim = resolve(s, Type.ANIMATION, modelNameOrPath);
        return Optional.ofNullable(anim);
    }

    /** Names of the animations inside an animation file (sorted). Empty if unknown. No disk access. */
    public static List<String> getAnimationNames(ResourceLocation animationFile) {
        if (animationFile == null) return List.of();
        AssetSnapshot s = AzureResourceManager.snapshot();
        File f = AzureResourceManager.indexedFile(s, animationFile);
        if (f == null || !s.files(Type.ANIMATION).contains(f)) {
            ResourceLocation canon = resolve(s, Type.ANIMATION, animationFile.toString());
            f = canon != null ? AzureResourceManager.indexedFile(s, canon) : f;
        }
        return namesOf(s, f);
    }

    /** Names of the animations for a model or animation name/path (sorted). Empty if unknown. No disk access. */
    public static List<String> getAnimationNames(String modelOrAnimation) {
        AssetSnapshot s = AzureResourceManager.snapshot();
        ResourceLocation anim = resolve(s, Type.ANIMATION, modelOrAnimation);
        if (anim == null) {
            ResourceLocation model = resolve(s, Type.MODEL, modelOrAnimation);
            if (model != null) anim = s.modelToAnimation.get(model);
        }
        return anim != null ? namesOf(s, AzureResourceManager.indexedFile(s, anim)) : List.of();
    }

    /**
     * Finds the full animation name in a file for a short name, e.g. {@code "idle"} →
     * {@code "animation.dragon.idle"}. Tries an exact match, then ignoring case, then the last part
     * of the name after the final dot, then names ending in {@code ".<name>"}.
     */
    public static Optional<String> resolveAnimationName(ResourceLocation animationFile, String requested) {
        return Optional.ofNullable(matchAnimationName(getAnimationNames(animationFile), requested));
    }

    /** Same matching rules as {@link #resolveAnimationName}, over any list of names. */
    public static String matchAnimationName(Collection<String> names, String requested) {
        if (names == null || names.isEmpty() || requested == null || requested.isEmpty()) return null;
        if (names.contains(requested)) return requested;
        for (String n : names) {
            if (n.equalsIgnoreCase(requested)) return n;
        }
        String reqLast = lastSegment(requested);
        for (String n : names) {
            if (lastSegment(n).equalsIgnoreCase(reqLast)) return n;
        }
        String suffix = "." + reqLast.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).endsWith(suffix)) return n;
        }
        return null;
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
    }

    private static List<String> namesOf(AssetSnapshot s, File f) {
        if (f == null) return List.of();
        AssetFileCache.Info info = s.fileInfo.get(f);
        if (info == null) info = AzureResourceManager.FILE_CACHE.peek(f);
        return info != null && info.kind == AssetFileCache.Kind.ANIMATION ? info.animationNames : List.of();
    }

    /** Model → linked animation file, for every model that has one. Read-only snapshot. */
    public static Map<ResourceLocation, ResourceLocation> getAnimationLinks() {
        return AzureResourceManager.snapshot().modelToAnimation;
    }

    /**
     * One plain line per model: the model, its linked animation file and the animation names in it.
     * Used by the {@code /azureframelib animations} command and the optional reload log.
     */
    public static List<String> describeAnimationLinks(String filter) {
        AssetSnapshot s = AzureResourceManager.snapshot();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        TreeMap<String, String> lines = new TreeMap<>();
        for (File model : s.files(Type.MODEL)) {
            ResourceLocation rl = s.primaryLocation.get(model);
            if (rl == null) continue;
            String id = rl.toString();
            if (f != null && !f.isEmpty() && !id.contains(f)) continue;
            ResourceLocation anim = s.modelToAnimation.get(rl);
            String line;
            if (anim == null) {
                line = id + " -> no animation file linked";
            } else {
                List<String> names = namesOf(s, AzureResourceManager.indexedFile(s, anim));
                line = id + " -> " + anim + " " + (names.isEmpty() ? "(no animations inside)" : names.toString());
            }
            lines.put(id, line);
        }
        return new ArrayList<>(lines.values());
    }

    // ------------------------------------------------------------------
    // Hitboxes
    // ------------------------------------------------------------------

    /**
     * Size of the model's hitbox bone in blocks (model units / 16), if the model has one.
     * Works for AzureFrameLib models and for models inside mod jars. Remembered until the index changes.
     */
    public static Optional<HitboxSize> getModelHitbox(ResourceLocation model) {
        if (model == null) return Optional.empty();
        AssetSnapshot s = AzureResourceManager.snapshot();
        Optional<HitboxSize> cached = s.hitboxCache.get(model);
        if (cached != null) return cached;

        Optional<HitboxSize> result = Optional.empty();
        try {
            File f = AzureResourceManager.indexedFile(s, model);
            if (f == null || !s.files(Type.MODEL).contains(f)) {
                ResourceLocation canon = resolve(s, Type.MODEL, model.toString());
                if (canon != null) f = AzureResourceManager.indexedFile(s, canon);
            }
            AssetFileCache.Info info = f != null ? s.fileInfo.get(f) : null;
            if (info != null) {
                result = Optional.ofNullable(info.hitbox);
            } else {
                result = Optional.ofNullable(measureFromJar(model));
            }
        } catch (Throwable ignored) {
        }
        if (s.hitboxCache.size() > 50_000) s.hitboxCache.clear();
        s.hitboxCache.put(model, result);
        return result;
    }

    private static HitboxSize measureFromJar(ResourceLocation model) {
        String text = null;
        String path = "assets/" + model.getNamespace() + "/" + model.getPath();
        try (InputStream in = AzureAssetIndex.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
        if (text == null && isClient()) {
            text = ddraig.net.azureframelib.client.ClientTaskHelper.readResourceText(model).orElse(null);
        }
        if (text == null) return null;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(true);
        JsonElement el = JsonParser.parseReader(reader);
        return el.isJsonObject() ? ModelHitboxHelper.measureHitbox(el.getAsJsonObject()) : null;
    }

    private static boolean isClient() {
        try {
            return Platform.getEnvironment() == Env.CLIENT;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Index state
    // ------------------------------------------------------------------

    /** Goes up every time the index changes (startup, F3+T / resource reload, new folders or bundles). */
    public static int getIndexVersion() {
        return AzureResourceManager.getIndexVersion();
    }

    /** Runs the listener every time the index changes (on the main client thread when on a client). */
    public static void onIndexChanged(Runnable listener) {
        AzureResourceManager.onIndexChanged(listener);
    }

    public static void removeIndexListener(Runnable listener) {
        AzureResourceManager.removeIndexListener(listener);
    }

    /** True once the first scan has finished. */
    public static boolean isIndexReady() {
        return AzureResourceManager.isIndexReady();
    }

    /** Rescans the folders in the background; the future completes when the new index is live. */
    public static CompletableFuture<Void> requestReload() {
        return AzureResourceManager.requestReload();
    }

    /** True for namespaces AzureFrameLib serves (custom_mobs, rpg_mounts, customraces, azureframelib ...). Never waits. */
    public static boolean isManagedNamespace(String namespace) {
        return namespace != null && AzureResourceManager.managedNamespaces().contains(namespace.toLowerCase(Locale.ROOT));
    }

    /** Asset counts of the current index: models, textures, animations, sounds, entries. */
    public static int[] getCounts() {
        AssetSnapshot s = AzureResourceManager.snapshot();
        return new int[]{s.count(Type.MODEL) + s.count(Type.JAVA_MODEL), s.count(Type.TEXTURE),
                s.count(Type.ANIMATION), s.count(Type.SOUND), s.index.size()};
    }

    // ------------------------------------------------------------------
    // Name resolution (memory only)
    // ------------------------------------------------------------------

    static ResourceLocation resolve(AssetSnapshot s, Type type, String input) {
        if (input == null) return null;
        Map<String, Optional<ResourceLocation>> cache = s.lookupCache(type);
        Optional<ResourceLocation> hit = cache.get(input);
        if (hit != null) return hit.orElse(null);
        ResourceLocation result = null;
        try {
            result = resolveUncached(s, type, input.trim());
        } catch (Throwable ignored) {
        }
        cache.put(input, Optional.ofNullable(result));
        return result;
    }

    private static ResourceLocation resolveUncached(AssetSnapshot s, Type type, String raw) {
        if (raw.isEmpty()) return null;
        String norm = raw.replace('\\', '/');
        String ns = null;
        String path = norm;
        int colon = AzureResourceManager.namespaceColon(norm);
        if (colon > 0) {
            ns = AzureResourceManager.sanitizePath(norm.substring(0, colon)).replace("/", "");
            path = norm.substring(colon + 1);
        }

        // 1. Exact location (e.g. "custom_mobs:geo/dragon.geo.json" or a mirrored "azureframelib:..." path)
        if (ns != null && !ns.isEmpty()) {
            ResourceLocation exact = ResourceLocation.tryBuild(ns, AzureResourceManager.sanitizePath(path));
            if (exact != null) {
                File f = AzureResourceManager.indexedFile(s, exact);
                if (f != null) {
                    if (s.files(type).contains(f)) {
                        ResourceLocation primary = s.primaryLocation.get(f);
                        return primary != null ? primary : exact;
                    }
                    if (AzureResourceManager.isDynamicResource(exact) && extensionMatches(type, f.getName())) {
                        return exact;
                    }
                }
            }
        }

        // 2. A full file path on disk (no disk access: compared by path only)
        if (colon < 0 && (norm.startsWith("/") || (norm.length() > 2 && norm.charAt(1) == ':'))) {
            ResourceLocation byFile = s.primaryLocation.get(new File(raw));
            if (byFile == null) byFile = s.primaryLocation.get(new File(raw).getAbsoluteFile());
            if (byFile != null && s.files(type).contains(AzureResourceManager.indexedFile(s, byFile))) return byFile;
        }

        // 3. Name / relative path keys
        String rel = path.toLowerCase(Locale.ROOT);
        while (rel.startsWith("/")) rel = rel.substring(1);
        rel = stripCategoryPrefix(type, rel);
        rel = stripTypeExtension(type, rel);
        rel = AzureResourceManager.sanitizePath(rel);
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return null;
        String base = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;

        Map<String, ResourceLocation> keys = s.keys(type);
        ResourceLocation found = null;
        if (ns != null && !ns.isEmpty()) {
            found = keys.get(ns + ":" + rel);
            if (found == null) found = keys.get(ns + ":" + base);
        }
        if (found == null) found = keys.get(rel);
        if (found == null && type == Type.TEXTURE) {
            // "entity/<folder>/x" and "dynamic/x" forms
            String alt = rel.startsWith("entity/") ? rel.substring(7) : rel.startsWith("dynamic/") ? rel.substring(8) : null;
            if (alt != null) {
                if (ns != null && !ns.isEmpty()) found = keys.get(ns + ":" + alt);
                if (found == null) found = keys.get(alt);
            }
        }
        if (found == null) found = keys.get(base);
        if (found == null && rel.indexOf('-') >= 0) found = keys.get(rel.replace("-", ""));
        return found;
    }

    private static String stripCategoryPrefix(Type type, String p) {
        String[] prefixes = switch (type) {
            case MODEL -> new String[]{"geo/", "models/", "model/"};
            case JAVA_MODEL -> new String[]{"models/", "model/"};
            case ANIMATION -> new String[]{"animations/", "animation/"};
            case TEXTURE -> new String[]{"textures/", "texture/"};
            case SOUND -> new String[]{"sounds/", "sound/"};
        };
        for (String pfx : prefixes) {
            if (p.startsWith(pfx)) return p.substring(pfx.length());
        }
        return p;
    }

    private static String stripTypeExtension(Type type, String p) {
        String[] exts = switch (type) {
            case MODEL -> new String[]{".geo.json", ".json"};
            case JAVA_MODEL -> new String[]{".java"};
            case ANIMATION -> new String[]{".animation.json", ".json"};
            case TEXTURE -> new String[]{".png", "_png"};
            case SOUND -> new String[]{".ogg"};
        };
        for (String ext : exts) {
            if (p.endsWith(ext) && p.length() > ext.length()) return p.substring(0, p.length() - ext.length());
        }
        return p;
    }

    private static boolean extensionMatches(Type type, String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return switch (type) {
            case MODEL -> n.endsWith(".json") && !n.endsWith(".animation.json");
            case JAVA_MODEL -> n.endsWith(".java");
            case ANIMATION -> n.endsWith(".json") && !n.endsWith(".geo.json");
            case TEXTURE -> n.endsWith(".png");
            case SOUND -> n.endsWith(".ogg");
        };
    }

    private static Type guessType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png") || p.startsWith("textures/")) return Type.TEXTURE;
        if (p.endsWith(".ogg") || p.startsWith("sounds/")) return Type.SOUND;
        if (p.endsWith(".animation.json") || p.startsWith("animations/")) return Type.ANIMATION;
        if (p.endsWith(".java")) return Type.JAVA_MODEL;
        if (p.endsWith(".json") || p.startsWith("geo/") || p.startsWith("models/")) return Type.MODEL;
        return null;
    }
}
