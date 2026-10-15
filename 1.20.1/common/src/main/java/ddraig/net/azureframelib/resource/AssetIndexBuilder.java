package ddraig.net.azureframelib.resource;

import ddraig.net.azureframelib.resource.AssetSnapshot.Type;
import ddraig.net.azureframelib.resource.AzureResourceManager.ResourceCategory;
import ddraig.net.azureframelib.resource.AzureResourceManager.ResourceRoot;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a new {@link AssetSnapshot} from the registered folders. Runs on the indexing
 * thread. Each folder is walked once, and file contents come from {@link AssetFileCache}
 * so unchanged files are not re-read.
 */
final class AssetIndexBuilder {

    static final class Result {
        final AssetSnapshot snapshot;
        final List<String> warnings;

        Result(AssetSnapshot snapshot, List<String> warnings) {
            this.snapshot = snapshot;
            this.warnings = warnings;
        }
    }

    private static final int MAX_DEPTH = 32;

    private final List<ResourceRoot> roots;
    private final AssetFileCache cache;

    private final Map<ResourceLocation, File> index = new HashMap<>(1 << 16);
    private final Set<String> namespaces = new LinkedHashSet<>();
    private final Set<String> dModels = new HashSet<>();
    private final Set<String> dTextures = new HashSet<>();
    private final Set<String> dAnimations = new HashSet<>();
    private final Set<String> dSounds = new HashSet<>();
    private final EnumMap<Type, Map<String, ResourceLocation>> keys = new EnumMap<>(Type.class);
    private final EnumMap<Type, Set<File>> files = new EnumMap<>(Type.class);
    private final Map<File, ResourceLocation> primary = new HashMap<>();
    private final Map<File, AssetFileCache.Info> infos = new HashMap<>();
    private final Map<ResourceLocation, File> canonicalOwner = new HashMap<>();
    private final Map<ResourceLocation, ResourceLocation> modelToAnim = new HashMap<>();
    private final List<String[]> pendingModelLinks = new ArrayList<>(); // {canonical, ns, relNoExt, base}
    private final Set<String> mcmetaFiles = new HashSet<>();
    private final Set<File> skipped = new HashSet<>();
    private final Set<String> nameWarnedThisBuild = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();

    AssetIndexBuilder(List<ResourceRoot> roots, AssetFileCache cache) {
        this.roots = roots;
        this.cache = cache;
        for (Type t : Type.values()) {
            keys.put(t, new HashMap<>());
            files.put(t, new LinkedHashSet<>());
        }
    }

    Result build() {
        namespaces.add("azureframelib");
        for (ResourceRoot root : roots) {
            if (root.directory == null || !root.directory.isDirectory()) continue;
            String ns = root.namespace.toLowerCase(Locale.ROOT);
            namespaces.add(ns);
            switch (root.category) {
                case UNPACKED_BUNDLE -> scanBundles(root, ns);
                case MODEL -> scanModels(root, ns);
                case TEXTURE -> scanTextures(root, ns);
                case ANIMATION -> scanAnimations(root, ns);
                case SOUND -> scanSounds(root, ns);
            }
        }
        linkModelRootAnimations();
        return new Result(freeze(), warnings);
    }

    // ------------------------------------------------------------------
    // Folder types
    // ------------------------------------------------------------------

    private void scanModels(ResourceRoot root, String ns) {
        for (File f : walk(root.directory)) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                addJavaModelFromRoot(root, ns, f);
                continue;
            }
            if (!lower.endsWith(".json") || AzureResourceManager.IGNORED_CONFIG_JSONS.contains(lower)) continue;
            AssetFileCache.Info info = cache.get(f);
            if (info == null) continue;
            String rel = relPath(root.directory, f);
            switch (info.kind) {
                case MODEL -> addModelFromRoot(ns, f, info, rel);
                case BROKEN -> problem(f, "Model file \"" + rel + "\" can't be used: " + info.problem + ".");
                case OTHER_JSON -> problem(f, "Model file \"" + rel + "\" has no \"minecraft:geometry\" section, so it can't be used as a model.");
                default -> { /* animation files in a models folder are ignored, as before */ }
            }
        }
    }

    private void addModelFromRoot(String ns, File f, AssetFileCache.Info info, String rel) {
        String cleanRel = AzureResourceManager.sanitizePath(rel);
        String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
        String relNoExt = stripModelExt(cleanRel);

        ResourceLocation canon = registerAsset(Type.MODEL, ns, "geo/" + relNoExt + ".geo.json", f, true);
        if (canon == null) return;
        checkName(f, rel, cleanRel);
        infos.put(f, info);

        dModels.add(base);
        dModels.add(ns + ":" + base);
        dModels.add(cleanRel);
        dModels.add(ns + ":" + cleanRel);

        index(ns, "geo/" + cleanRel, f);
        index(ns, "models/" + cleanRel, f);
        index(ns, "geo/" + base + ".geo.json", f);
        index(ns, "geo/" + base + ".json", f);
        index(ns, "geo/" + base, f);
        index(ns, "models/" + base + ".geo.json", f);
        index(ns, "models/" + base + ".json", f);
        index(ns, "models/" + base, f);
        index(ns, base + ".geo.json", f);
        index(ns, base + ".json", f);
        index(ns, "geo/" + relNoExt + ".json", f);
        index(ns, "models/" + relNoExt + ".json", f);
        index(ns, "geo/" + relNoExt, f);
        index(ns, "models/" + relNoExt, f);

        key(Type.MODEL, ns, relNoExt, canon);
        key(Type.MODEL, ns, base, canon);
        pendingModelLinks.add(new String[]{canon.toString(), ns, relNoExt, base});
    }

    private void addJavaModelFromRoot(ResourceRoot root, String ns, File f) {
        String rel = relPath(root.directory, f);
        String cleanRel = AzureResourceManager.sanitizePath(rel);
        String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
        String relNoExt = cleanRel.endsWith(".java") ? cleanRel.substring(0, cleanRel.length() - 5) : cleanRel;

        ResourceLocation canon = registerAsset(Type.JAVA_MODEL, ns, "models/" + relNoExt + ".java", f, true);
        if (canon == null) return;
        checkName(f, rel, cleanRel);

        dModels.add(base);
        dModels.add(ns + ":" + base);
        dModels.add(cleanRel);
        dModels.add(ns + ":" + cleanRel);

        index(ns, "models/" + cleanRel, f);
        index(ns, "models/" + base + ".java", f);
        index(ns, base + ".java", f);

        key(Type.JAVA_MODEL, ns, relNoExt, canon);
        key(Type.JAVA_MODEL, ns, base, canon);
    }

    private void scanTextures(ResourceRoot root, String ns) {
        for (File f : walk(root.directory)) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            String rel = relPath(root.directory, f);
            String cleanRel = AzureResourceManager.sanitizePath(rel);
            String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
            String relNoExt = cleanRel.endsWith(".png") ? cleanRel.substring(0, cleanRel.length() - 4) : cleanRel;

            ResourceLocation canon = registerAsset(Type.TEXTURE, ns, "textures/" + relNoExt + ".png", f, true);
            if (canon == null) continue;
            checkName(f, rel, cleanRel);

            dTextures.add(cleanRel);
            dTextures.add(ns + ":" + cleanRel);
            dTextures.add(base);
            dTextures.add(ns + ":" + base);

            index(ns, "textures/" + cleanRel, f);
            index(ns, "textures/" + base + ".png", f);
            index(ns, "textures/" + base, f);
            index(ns, base + ".png", f);

            key(Type.TEXTURE, ns, relNoExt, canon);
            key(Type.TEXTURE, ns, base, canon);
        }
    }

    private void scanAnimations(ResourceRoot root, String ns) {
        for (File f : walk(root.directory)) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".json") || AzureResourceManager.IGNORED_CONFIG_JSONS.contains(lower)) continue;
            AssetFileCache.Info info = cache.get(f);
            if (info == null) continue;
            String rel = relPath(root.directory, f);
            switch (info.kind) {
                case ANIMATION -> addAnimationFromRoot(ns, f, info, rel);
                case BROKEN -> problem(f, "Animation file \"" + rel + "\" can't be used: " + info.problem + ".");
                case OTHER_JSON -> problem(f, "Animation file \"" + rel + "\" has no \"animations\" section, so it can't be used.");
                default -> { /* model files in an animations folder are ignored, as before */ }
            }
        }
    }

    private void addAnimationFromRoot(String ns, File f, AssetFileCache.Info info, String rel) {
        String cleanRel = AzureResourceManager.sanitizePath(rel);
        String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
        String relNoExt = stripAnimExt(cleanRel);

        ResourceLocation canon = registerAsset(Type.ANIMATION, ns, "animations/" + relNoExt + ".animation.json", f, true);
        if (canon == null) return;
        checkName(f, rel, cleanRel);
        infos.put(f, info);

        dAnimations.add(cleanRel);
        dAnimations.add(ns + ":" + cleanRel);
        dAnimations.add(base);
        dAnimations.add(ns + ":" + base);

        index(ns, "animations/" + cleanRel, f);
        index(ns, "animations/" + base + ".animation.json", f);
        index(ns, "animations/" + base + ".json", f);
        index(ns, "animations/" + base, f);
        index(ns, base + ".animation.json", f);
        index(ns, "animations/" + relNoExt + ".json", f);
        index(ns, "animations/" + relNoExt, f);

        key(Type.ANIMATION, ns, relNoExt, canon);
        key(Type.ANIMATION, ns, base, canon);
    }

    private void scanSounds(ResourceRoot root, String ns) {
        for (File f : walk(root.directory)) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) continue;
            String rel = relPath(root.directory, f);
            String clean = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(rel));
            String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));

            ResourceLocation canon = registerAsset(Type.SOUND, ns, "sounds/" + clean + ".ogg", f, true);
            if (canon == null) continue;
            checkName(f, rel, AzureResourceManager.sanitizePath(rel));

            dSounds.add(ns + ":" + clean);
            dSounds.add(ns + ":" + base);
            dSounds.add(clean);
            dSounds.add(base);

            index(ns, "sounds/" + clean + ".ogg", f);
            index(ns, "sounds/" + clean, f);
            index(ns, "sounds/" + base + ".ogg", f);
            index(ns, "sounds/" + base, f);
            index(ns, base + ".ogg", f);

            key(Type.SOUND, ns, clean, canon);
            key(Type.SOUND, ns, base, canon);
        }
    }

    // ------------------------------------------------------------------
    // Unpacked bundles: one folder per mob/mount with model, animation, textures and sounds
    // ------------------------------------------------------------------

    private void scanBundles(ResourceRoot root, String ns) {
        for (File folder : listSorted(root.directory)) {
            if (!folder.isDirectory()) continue;
            try {
                scanBundle(ns, folder);
            } catch (Throwable t) {
                warnings.add("Could not read the folder \"" + folder.getName() + "\": " + t);
            }
        }
    }

    private void scanBundle(String ns, File folder) {
        String rawId = folder.getName();
        String cleanId = AzureResourceManager.sanitizePath(rawId);
        if (cleanId.isEmpty()) return;
        checkName(folder, rawId, cleanId);

        List<File> all = walk(folder);
        File[] topLevel = folder.listFiles();
        List<File> top = topLevel == null ? List.of() : sorted(topLevel);

        // ---- classify model / animation files ----
        File primaryGeo = null;
        AssetFileCache.Info primaryGeoInfo = null;
        List<File> extraGeo = new ArrayList<>();
        File primaryAnim = null;
        AssetFileCache.Info primaryAnimInfo = null;
        List<File> extraAnim = new ArrayList<>();

        for (File f : all) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".geo.json")) {
                AssetFileCache.Info info = cache.get(f);
                if (info == null) continue;
                if (info.kind == AssetFileCache.Kind.MODEL) {
                    if (primaryGeo == null) {
                        primaryGeo = f;
                        primaryGeoInfo = info;
                    } else {
                        extraGeo.add(f);
                    }
                } else if (info.kind == AssetFileCache.Kind.BROKEN) {
                    problem(f, "Model file \"" + rawId + "/" + relPath(folder, f) + "\" can't be used: " + info.problem + ".");
                } else if (info.kind == AssetFileCache.Kind.OTHER_JSON) {
                    problem(f, "Model file \"" + rawId + "/" + relPath(folder, f) + "\" has no \"minecraft:geometry\" section, so it can't be used as a model.");
                }
            } else if (lower.endsWith(".animation.json")) {
                AssetFileCache.Info info = cache.get(f);
                if (info == null) continue;
                if (info.kind == AssetFileCache.Kind.ANIMATION) {
                    if (primaryAnim == null) {
                        primaryAnim = f;
                        primaryAnimInfo = info;
                    } else {
                        extraAnim.add(f);
                    }
                } else if (info.kind == AssetFileCache.Kind.BROKEN) {
                    problem(f, "Animation file \"" + rawId + "/" + relPath(folder, f) + "\" can't be used: " + info.problem + ".");
                } else if (info.kind == AssetFileCache.Kind.OTHER_JSON) {
                    problem(f, "Animation file \"" + rawId + "/" + relPath(folder, f) + "\" has no \"animations\" section, so it can't be used.");
                }
            }
        }
        // Plain ".json" files at the top of the folder can also be the model / animation.
        if (primaryGeo == null || primaryAnim == null) {
            for (File f : top) {
                if (!f.isFile()) continue;
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".json") || lower.endsWith(".geo.json") || lower.endsWith(".animation.json")
                        || AzureResourceManager.IGNORED_CONFIG_JSONS.contains(lower)) continue;
                AssetFileCache.Info info = cache.get(f);
                if (info == null) continue;
                if (primaryGeo == null && info.kind == AssetFileCache.Kind.MODEL) {
                    primaryGeo = f;
                    primaryGeoInfo = info;
                } else if (primaryAnim == null && info.kind == AssetFileCache.Kind.ANIMATION) {
                    primaryAnim = f;
                    primaryAnimInfo = info;
                }
            }
        }

        // ---- model ----
        ResourceLocation modelCanon = null;
        String cleanName = null;
        Map<String, ResourceLocation> extraModelCanon = new HashMap<>();
        if (primaryGeo != null) {
            cleanName = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(primaryGeo.getName()));
            modelCanon = registerAsset(Type.MODEL, ns, "geo/" + cleanId + ".geo.json", primaryGeo, true);
            if (modelCanon != null) {
                String relIn = relPath(folder, primaryGeo);
                checkName(primaryGeo, relIn, AzureResourceManager.sanitizePath(relIn));
                infos.put(primaryGeo, primaryGeoInfo);
                dModels.add(cleanId);
                dModels.add(ns + ":" + cleanId);
                indexBundleModelAliases(ns, cleanId, primaryGeo);
                key(Type.MODEL, ns, cleanId, modelCanon);
                if (!cleanName.equals(cleanId) && !cleanName.isEmpty()) {
                    indexBundleModelAliases(ns, cleanName, primaryGeo);
                    dModels.add(cleanName);
                    dModels.add(ns + ":" + cleanName);
                    key(Type.MODEL, ns, cleanName, modelCanon);
                    key(Type.MODEL, ns, cleanId + "/" + cleanName, modelCanon);
                }
            }
            for (File f : extraGeo) {
                String name = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
                if (name.isEmpty() || name.equals(cleanId) || name.equals(cleanName)) continue;
                ResourceLocation canon = registerAsset(Type.MODEL, ns, "geo/" + name + ".geo.json", f, false);
                if (canon == null) continue;
                infos.put(f, cache.get(f));
                dModels.add(name);
                dModels.add(ns + ":" + name);
                indexBundleModelAliases(ns, name, f);
                key(Type.MODEL, ns, name, canon);
                key(Type.MODEL, ns, cleanId + "/" + name, canon);
                extraModelCanon.put(name, canon);
            }
        } else {
            File javaFile = null;
            for (File f : all) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".java")) {
                    javaFile = f;
                    break;
                }
            }
            if (javaFile != null) {
                ResourceLocation canon = registerAsset(Type.JAVA_MODEL, ns, "models/" + cleanId + ".java", javaFile, true);
                if (canon != null) {
                    dModels.add(cleanId);
                    dModels.add(ns + ":" + cleanId);
                    index(ns, "models/" + cleanId + ".java", javaFile);
                    index(ns, cleanId + ".java", javaFile);
                    key(Type.JAVA_MODEL, ns, cleanId, canon);
                }
            }
        }

        // ---- animation ----
        ResourceLocation animCanon = null;
        Map<String, ResourceLocation> extraAnimCanon = new HashMap<>();
        if (primaryAnim != null) {
            animCanon = registerAsset(Type.ANIMATION, ns, "animations/" + cleanId + ".animation.json", primaryAnim, true);
            if (animCanon != null) {
                String relIn = relPath(folder, primaryAnim);
                checkName(primaryAnim, relIn, AzureResourceManager.sanitizePath(relIn));
                infos.put(primaryAnim, primaryAnimInfo);
                dAnimations.add(cleanId);
                dAnimations.add(ns + ":" + cleanId);
                indexBundleAnimationAliases(ns, cleanId, cleanId, primaryAnim);
                key(Type.ANIMATION, ns, cleanId, animCanon);

                String animName = animationBaseName(primaryAnim);
                if (animName != null && !animName.equals(cleanId)) {
                    indexBundleAnimationAliases(ns, cleanId, animName, primaryAnim);
                    dAnimations.add(animName);
                    dAnimations.add(ns + ":" + animName);
                    key(Type.ANIMATION, ns, animName, animCanon);
                    key(Type.ANIMATION, ns, cleanId + "/" + animName, animCanon);
                }
            }
            for (File f : extraAnim) {
                String name = animationBaseName(f);
                if (name == null || name.isEmpty() || name.equals(cleanId)) continue;
                ResourceLocation canon = registerAsset(Type.ANIMATION, ns, "animations/" + name + ".animation.json", f, false);
                if (canon == null) continue;
                infos.put(f, cache.get(f));
                dAnimations.add(name);
                dAnimations.add(ns + ":" + name);
                indexBundleAnimationAliases(ns, cleanId, name, f);
                key(Type.ANIMATION, ns, name, canon);
                key(Type.ANIMATION, ns, cleanId + "/" + name, canon);
                extraAnimCanon.put(name, canon);
            }
        }

        // ---- link models and animations in this folder ----
        if (modelCanon != null && animCanon != null) {
            modelToAnim.putIfAbsent(modelCanon, animCanon);
        }
        for (Map.Entry<String, ResourceLocation> e : extraModelCanon.entrySet()) {
            ResourceLocation a = extraAnimCanon.getOrDefault(e.getKey(), animCanon);
            if (a != null) modelToAnim.putIfAbsent(e.getValue(), a);
        }

        // ---- textures ----
        ResourceLocation firstTexture = null;
        ResourceLocation namedTexture = null;
        boolean anyPng = false;
        for (File f : all) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            anyPng = true;
            String rel = relPath(folder, f);
            String cleanRel = AzureResourceManager.sanitizePath(rel);
            String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));
            String relNoExt = cleanRel.endsWith(".png") ? cleanRel.substring(0, cleanRel.length() - 4) : cleanRel;

            ResourceLocation canon = registerAsset(Type.TEXTURE, ns, "textures/" + cleanId + "/" + cleanRel, f, false);
            if (canon == null) continue;
            checkName(f, rel, cleanRel);

            dTextures.add(cleanRel);
            dTextures.add(ns + ":" + cleanRel);
            dTextures.add(base);
            dTextures.add(ns + ":" + base);

            index(ns, "textures/entity/" + cleanId + "/" + cleanRel, f);
            index(ns, "textures/" + cleanRel, f);
            index(ns, "textures/" + base + ".png", f);
            index(ns, "textures/" + base, f);
            index(ns, base + ".png", f);

            key(Type.TEXTURE, ns, cleanId + "/" + relNoExt, canon);
            key(Type.TEXTURE, ns, "entity/" + cleanId + "/" + relNoExt, canon);
            key(Type.TEXTURE, ns, relNoExt, canon);
            key(Type.TEXTURE, ns, base, canon);

            if (firstTexture == null) firstTexture = canon;
            if (namedTexture == null && (base.equals(cleanId) || base.equals(cleanName))) namedTexture = canon;
        }
        ResourceLocation mainTexture = namedTexture != null ? namedTexture : firstTexture;
        if (mainTexture != null) {
            key(Type.TEXTURE, ns, cleanId, mainTexture);
        }
        if (primaryGeo != null && !anyPng && cache.shouldWarn(folder, "notexture")) {
            warnings.add("Model folder \"" + rawId + "\" has a model but no texture (.png) file, so it will show the missing-texture pattern.");
        }

        // ---- sounds ----
        for (File f : all) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) continue;
            String rel = relPath(folder, f);
            String cleanRel = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(rel));
            String base = AzureResourceManager.sanitizePath(AzureResourceManager.stripExtension(f.getName()));

            ResourceLocation canon = registerAsset(Type.SOUND, ns, "sounds/" + cleanId + "/" + cleanRel + ".ogg", f, false);
            if (canon == null) continue;
            checkName(f, rel, AzureResourceManager.sanitizePath(rel));

            dSounds.add(ns + ":unpacked/" + cleanId + "/" + cleanRel);
            dSounds.add(ns + ":" + base);

            index(ns, "sounds/" + cleanRel + ".ogg", f);
            index(ns, "sounds/" + base + ".ogg", f);
            index(ns, "sounds/" + base, f);
            index(ns, base + ".ogg", f);

            key(Type.SOUND, ns, cleanId + "/" + cleanRel, canon);
            key(Type.SOUND, ns, cleanRel, canon);
            key(Type.SOUND, ns, base, canon);
        }
    }

    private void indexBundleModelAliases(String ns, String id, File f) {
        index(ns, "geo/" + id + ".geo.json", f);
        index(ns, "geo/" + id + ".json", f);
        index(ns, "geo/" + id, f);
        index(ns, "models/" + id + ".geo.json", f);
        index(ns, "models/" + id + ".json", f);
        index(ns, "models/" + id, f);
        index(ns, id + ".geo.json", f);
        index(ns, id + ".json", f);
    }

    private void indexBundleAnimationAliases(String ns, String folderId, String id, File f) {
        index(ns, "animations/" + id + ".animation.json", f);
        index(ns, "animations/" + id + ".json", f);
        index(ns, "animations/" + id, f);
        index(ns, id + ".animation.json", f);
        // Some mods ask for "animations/<folder>/<file>.animation.json"
        index(ns, "animations/" + folderId + "/" + id + ".animation.json", f);
    }

    private static String animationBaseName(File f) {
        String name = f.getName();
        int idx = name.toLowerCase(Locale.ROOT).indexOf(".animation.json");
        String raw = idx > 0 ? name.substring(0, idx) : AzureResourceManager.stripExtension(name);
        String clean = AzureResourceManager.sanitizePath(raw);
        return clean.isEmpty() ? null : clean;
    }

    // ------------------------------------------------------------------
    // Linking, freezing
    // ------------------------------------------------------------------

    private void linkModelRootAnimations() {
        Map<String, ResourceLocation> animKeys = keys.get(Type.ANIMATION);
        for (String[] p : pendingModelLinks) {
            ResourceLocation model = ResourceLocation.tryParse(p[0]);
            if (model == null || modelToAnim.containsKey(model)) continue;
            String ns = p[1], relNoExt = p[2], base = p[3];
            ResourceLocation anim = animKeys.get(ns + ":" + relNoExt);
            if (anim == null) anim = animKeys.get(ns + ":" + base);
            if (anim == null) anim = animKeys.get(relNoExt);
            if (anim == null) anim = animKeys.get(base);
            if (anim != null) modelToAnim.put(model, anim);
        }
    }

    private AssetSnapshot freeze() {
        Map<String, NavigableMap<String, File>> byNs = new HashMap<>();
        for (Map.Entry<ResourceLocation, File> e : index.entrySet()) {
            byNs.computeIfAbsent(e.getKey().getNamespace(), k -> new TreeMap<>()).put(e.getKey().getPath(), e.getValue());
        }
        Map<String, NavigableMap<String, File>> byNsFrozen = new HashMap<>();
        byNs.forEach((k, v) -> byNsFrozen.put(k, Collections.unmodifiableNavigableMap(v)));

        EnumMap<Type, Map<String, ResourceLocation>> keysFrozen = new EnumMap<>(Type.class);
        keys.forEach((t, m) -> keysFrozen.put(t, Collections.unmodifiableMap(m)));
        EnumMap<Type, Set<File>> filesFrozen = new EnumMap<>(Type.class);
        files.forEach((t, s) -> filesFrozen.put(t, Collections.unmodifiableSet(s)));

        return new AssetSnapshot(
                Collections.unmodifiableMap(index),
                Collections.unmodifiableMap(byNsFrozen),
                Collections.unmodifiableSet(namespaces),
                Set.copyOf(dModels), Set.copyOf(dTextures), Set.copyOf(dAnimations), Set.copyOf(dSounds),
                Collections.unmodifiableMap(keysFrozen),
                Collections.unmodifiableMap(primary),
                Collections.unmodifiableMap(infos),
                Collections.unmodifiableMap(filesFrozen),
                Collections.unmodifiableMap(modelToAnim),
                skipped.size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Registers an asset's main ResourceLocation. If a different file already uses that
     * location, the first one is kept and (optionally) a warning names both files.
     */
    private ResourceLocation registerAsset(Type type, String ns, String path, File f, boolean warnOnClash) {
        ResourceLocation rl = ResourceLocation.tryBuild(ns, path.toLowerCase(Locale.ROOT));
        if (rl == null) {
            problem(f, "\"" + f.getName() + "\" could not be turned into a valid Minecraft name.");
            return null;
        }
        File owner = canonicalOwner.putIfAbsent(rl, f);
        if (owner != null && !owner.equals(f)) {
            if (warnOnClash) {
                skipped.add(f);
                if (cache.shouldWarn(f, "clash")) {
                    warnings.add("Two files would load under the same name \"" + rl + "\": \"" + owner.getPath()
                            + "\" and \"" + f.getPath() + "\". Only the first one will be used.");
                }
            }
            return null;
        }
        index(ns, path, f);
        primary.putIfAbsent(f, rl);
        files.get(type).add(f);
        return rl;
    }

    private void index(String ns, String path, File f) {
        String lp = path.toLowerCase(Locale.ROOT);
        put(ns, lp, f);
        if (!"azureframelib".equals(ns)) put("azureframelib", lp, f);
        if (lp.indexOf('-') >= 0) {
            // Older AzureFrameLib versions removed '-' from names; keep those names working.
            String legacy = lp.replace("-", "");
            put(ns, legacy, f);
            if (!"azureframelib".equals(ns)) put("azureframelib", legacy, f);
        }
    }

    private void put(String ns, String path, File f) {
        ResourceLocation rl = ResourceLocation.tryBuild(ns, path);
        if (rl == null) return;
        if (index.putIfAbsent(rl, f) == null && path.endsWith(".png")) {
            String meta = f.getAbsolutePath() + ".mcmeta";
            if (mcmetaFiles.contains(meta.toLowerCase(Locale.ROOT))) {
                ResourceLocation metaRl = ResourceLocation.tryBuild(ns, path + ".mcmeta");
                if (metaRl != null) index.putIfAbsent(metaRl, new File(meta));
            }
        }
    }

    private void key(Type type, String ns, String keyPath, ResourceLocation target) {
        if (keyPath == null || keyPath.isEmpty() || target == null) return;
        Map<String, ResourceLocation> m = keys.get(type);
        m.putIfAbsent(keyPath, target);
        m.putIfAbsent(ns + ":" + keyPath, target);
        if (keyPath.indexOf('-') >= 0) {
            String legacy = keyPath.replace("-", "");
            m.putIfAbsent(legacy, target);
            m.putIfAbsent(ns + ":" + legacy, target);
        }
    }

    /** Warns once when a file or folder name had to be changed to be a valid Minecraft name. */
    private void checkName(File f, String original, String normalized) {
        if (original == null || original.equals(normalized)) return;
        if (!nameWarnedThisBuild.add(f.getAbsolutePath())) return;
        if (!cache.shouldWarn(f, "name")) return;
        warnings.add("\"" + original + "\" contains characters Minecraft doesn't allow. It will be loaded as \"" + normalized + "\".");
    }

    private void problem(File f, String message) {
        skipped.add(f);
        if (cache.shouldWarn(f, "problem")) {
            warnings.add(message);
        }
    }

    /** All files under a folder: files of each folder first, then its sub-folders (sorted by name). */
    private List<File> walk(File dir) {
        List<File> out = new ArrayList<>();
        walk(dir, out, 0);
        return out;
    }

    private void walk(File dir, List<File> out, int depth) {
        if (depth > MAX_DEPTH) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        List<File> sorted = sorted(children);
        List<File> dirs = new ArrayList<>();
        for (File c : sorted) {
            if (c.isDirectory()) {
                dirs.add(c);
            } else {
                String lower = c.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mcmeta")) {
                    mcmetaFiles.add(c.getAbsolutePath().toLowerCase(Locale.ROOT));
                } else {
                    out.add(c);
                }
            }
        }
        for (File d : dirs) walk(d, out, depth + 1);
    }

    private static List<File> listSorted(File dir) {
        File[] children = dir.listFiles();
        return children == null ? List.of() : sorted(children);
    }

    private static List<File> sorted(File[] arr) {
        File[] copy = Arrays.copyOf(arr, arr.length);
        Arrays.sort(copy, (a, b) -> {
            int c = String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
            return c != 0 ? c : a.getName().compareTo(b.getName());
        });
        return Arrays.asList(copy);
    }

    private static String relPath(File base, File file) {
        try {
            return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    static String stripModelExt(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".geo.json")) return s.substring(0, s.length() - 9);
        if (lower.endsWith(".json")) return s.substring(0, s.length() - 5);
        return s;
    }

    static String stripAnimExt(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".animation.json")) return s.substring(0, s.length() - 15);
        if (lower.endsWith(".json")) return s.substring(0, s.length() - 5);
        return s;
    }

    @SuppressWarnings("unused")
    private static boolean isCategory(ResourceRoot r, ResourceCategory c) {
        return r.category == c;
    }
}
