package ddraig.net.azureframelib.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import ddraig.net.azureframelib.model.HitboxSize;
import ddraig.net.azureframelib.model.ModelHitboxHelper;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers what kind of file each asset is (model, animation, broken, ...) keyed by
 * path + last-modified time + size, so unchanged files are never re-read on reload.
 * Thread-safe.
 */
public final class AssetFileCache {

    public enum Kind {
        /** GeckoLib / Bedrock geometry file. */
        MODEL,
        /** GeckoLib / Bedrock animation file. */
        ANIMATION,
        /** Valid JSON, but neither a model nor an animation (configs, etc.). */
        OTHER_JSON,
        /** Could not be read or is not valid JSON. */
        BROKEN,
        /** Not a JSON file (png, ogg, java ...). Never read. */
        NOT_JSON
    }

    /** Immutable result of checking one file. */
    public static final class Info {
        public final long lastModified;
        public final long length;
        public final Kind kind;
        /** Animation names inside an animation file (sorted, immutable). Empty for other kinds. */
        public final List<String> animationNames;
        /** Size of the model's hitbox bone, or null. */
        public final HitboxSize hitbox;
        /** True when the model has hitbox cubes that must be removed before rendering. */
        public final boolean hasHitboxCubes;
        /** Plain-language reason when the file can't be used, otherwise null. */
        public final String problem;
        /**
         * True when the file only reads with relaxed rules (a byte-order mark, comments, trailing
         * commas ...). GeckoLib reads files strictly, so these are served in cleaned-up form.
         */
        public final boolean needsCleanup;

        Info(long lastModified, long length, Kind kind, List<String> animationNames, HitboxSize hitbox,
             boolean hasHitboxCubes, String problem) {
            this(lastModified, length, kind, animationNames, hitbox, hasHitboxCubes, problem, false);
        }

        Info(long lastModified, long length, Kind kind, List<String> animationNames, HitboxSize hitbox,
             boolean hasHitboxCubes, String problem, boolean needsCleanup) {
            this.lastModified = lastModified;
            this.length = length;
            this.kind = kind;
            this.animationNames = animationNames;
            this.hitbox = hitbox;
            this.hasHitboxCubes = hasHitboxCubes;
            this.problem = problem;
            this.needsCleanup = needsCleanup;
        }
    }

    private final Map<String, Info> cache = new ConcurrentHashMap<>();
    /** "path|lastModified" keys of files we have already warned about. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final AtomicInteger filesRead = new AtomicInteger();

    /** Returns the cached check for a file, reading it only if it is new or changed. Null if the file is gone. */
    public Info get(File file) {
        if (file == null) return null;
        long lastModified = file.lastModified();
        long length = file.length();
        if (lastModified == 0L && !file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath();
        Info cached = cache.get(key);
        if (cached != null && cached.lastModified == lastModified && cached.length == length) {
            return cached;
        }
        Info fresh = check(file, lastModified, length);
        cache.put(key, fresh);
        return fresh;
    }

    /** Returns the cached check without touching the disk, or null if never checked. */
    public Info peek(File file) {
        return file == null ? null : cache.get(file.getAbsolutePath());
    }

    /** True the first time this (file, version) pair is reported; false afterwards. */
    public boolean shouldWarn(File file, String category) {
        if (file == null) return true;
        return warned.add(category + "|" + file.getAbsolutePath() + "|" + file.lastModified());
    }

    /** Number of files actually read from disk since the last call. */
    public int takeFilesRead() {
        return filesRead.getAndSet(0);
    }

    public void clear() {
        cache.clear();
        warned.clear();
    }

    private Info check(File file, long lastModified, long length) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".json")) {
            return new Info(lastModified, length, Kind.NOT_JSON, List.of(), null, false, null);
        }
        if (length == 0) {
            return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false, "the file is empty");
        }

        filesRead.incrementAndGet();
        JsonObject root;
        boolean needsCleanup = false;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
                needsCleanup = true;
            }
            JsonElement parsed;
            try {
                // GeckoLib reads files strictly, so try that first.
                JsonReader strict = new JsonReader(new StringReader(content));
                strict.setLenient(false);
                parsed = STRICT_ADAPTER.read(strict);
            } catch (Exception strictFail) {
                JsonReader reader = new JsonReader(new StringReader(content));
                reader.setLenient(true);
                parsed = JsonParser.parseReader(reader);
                needsCleanup = true;
            }
            if (parsed == null || !parsed.isJsonObject()) {
                return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false, "it isn't a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false,
                    "it isn't valid JSON (" + cleanJsonMessage(t) + ")");
        }

        // Model?
        JsonElement geometry = root.get("minecraft:geometry");
        boolean legacyGeometry = false;
        if (geometry == null) {
            for (String k : root.keySet()) {
                if (k.startsWith("geometry.")) {
                    legacyGeometry = true;
                    break;
                }
            }
        }
        if (geometry != null || legacyGeometry) {
            String structureProblem = legacyGeometry ? null : checkGeometryStructure(geometry);
            if (structureProblem == null) {
                structureProblem = GeckoLibValidation.checkModel(root);
            }
            if (structureProblem != null) {
                return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false, structureProblem);
            }
            HitboxSize hitbox = ModelHitboxHelper.measureHitbox(root);
            boolean hasCubes = ModelHitboxHelper.hasHitboxCubes(root);
            return new Info(lastModified, length, Kind.MODEL, List.of(), hitbox, hasCubes, null, needsCleanup);
        }

        // Animation?
        JsonElement animations = root.get("animations");
        if (animations != null) {
            if (!animations.isJsonObject()) {
                return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false,
                        "its \"animations\" section isn't a list of named animations");
            }
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : animations.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    return new Info(lastModified, length, Kind.BROKEN, List.of(), null, false,
                            "animation \"" + e.getKey() + "\" is not written correctly");
                }
                names.add(e.getKey());
            }
            Collections.sort(names);
            return new Info(lastModified, length, Kind.ANIMATION, List.copyOf(names), null, false, null, needsCleanup);
        }

        return new Info(lastModified, length, Kind.OTHER_JSON, List.of(), null, false, null);
    }

    private static final com.google.gson.TypeAdapter<JsonElement> STRICT_ADAPTER =
            new com.google.gson.Gson().getAdapter(JsonElement.class);

    private static String checkGeometryStructure(JsonElement geometry) {
        if (!geometry.isJsonArray()) {
            return "its \"minecraft:geometry\" section is not written correctly";
        }
        JsonArray arr = geometry.getAsJsonArray();
        if (arr.isEmpty() || !arr.get(0).isJsonObject()) {
            return "its \"minecraft:geometry\" section is empty";
        }
        JsonElement bones = arr.get(0).getAsJsonObject().get("bones");
        if (bones != null) {
            if (!bones.isJsonArray()) return "its \"bones\" list is not written correctly";
            for (JsonElement b : bones.getAsJsonArray()) {
                if (!b.isJsonObject()) return "one of its bones is not written correctly";
                JsonElement n = b.getAsJsonObject().get("name");
                if (n == null || !n.isJsonPrimitive()) return "one of its bones has no name";
            }
        }
        return null;
    }

    private static String cleanJsonMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        msg = msg.replace("Use JsonReader.setLenient(true) to accept malformed JSON ", "");
        int see = msg.indexOf("\nSee ");
        if (see > 0) msg = msg.substring(0, see);
        see = msg.indexOf(" See https://");
        if (see > 0) msg = msg.substring(0, see);
        return msg.trim();
    }
}
