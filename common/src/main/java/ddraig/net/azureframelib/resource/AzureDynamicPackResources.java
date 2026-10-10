package ddraig.net.azureframelib.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.config.AzureFrameLibConfig;
import ddraig.net.azureframelib.model.ModelHitboxHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Dynamic in-game client resource pack.
 * Streams models, animations, textures, and sounds directly from all registered
 * framework config directories into Minecraft's client resource engine.
 * <p>
 * All lookups come from AzureFrameLib's in-memory index; files are only opened when
 * Minecraft actually reads them.
 */
public class AzureDynamicPackResources implements PackResources {
    public static final String PACK_ID = "azureframelib_dynamic";
    private static final Set<String> SUPPORTED_NAMESPACES = AzureResourceManager.SUPPORTED_NAMESPACES;
    private static final Gson GSON = new Gson();
    private static final byte[] EMPTY_ANIMATIONS = "{\"format_version\":\"1.8.0\",\"animations\":{}}".getBytes(StandardCharsets.UTF_8);

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }

        String namespace = location.getNamespace();
        if (!isSupportedNamespace(namespace)) {
            return null;
        }

        String path = location.getPath();

        // 0. Metadata (.mcmeta) requests: NEVER return a .png or other binary/model file for metadata requests.
        //    Only real .mcmeta files found during the scan are served.
        if (path.endsWith(".mcmeta")) {
            File metaFile = AzureResourceManager.indexedFile(AzureResourceManager.snapshotForResourceLoading(), location);
            if (metaFile != null && metaFile.getName().toLowerCase(Locale.ROOT).endsWith(".mcmeta")) {
                return () -> new FileInputStream(metaFile);
            }
            return null;
        }

        // 1. Dynamic sounds.json
        if (path.equals("sounds.json")) {
            String json = generateSoundsJson(namespace);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return () -> new java.io.ByteArrayInputStream(bytes);
        }

        // 2. High-speed exact index lookup (memory only)
        AssetSnapshot snapshot = AzureResourceManager.snapshotForResourceLoading();
        File indexed = AzureResourceManager.indexedFile(snapshot, location);
        if (indexed != null) {
            IoSupplier<InputStream> supplier = createIoSupplier(snapshot, location, indexed);
            if (supplier != null) return supplier;
        }

        // 3. Fallback name search by category (memory only)
        AssetSnapshot.Type category = null;
        if (path.startsWith("geo/") || path.startsWith("models/")) category = AssetSnapshot.Type.MODEL;
        else if (path.startsWith("animations/")) category = AssetSnapshot.Type.ANIMATION;
        else if (path.startsWith("textures/")) category = AssetSnapshot.Type.TEXTURE;
        else if (path.startsWith("sounds/")) category = AssetSnapshot.Type.SOUND;
        if (category != null) {
            ResourceLocation canon = AzureAssetIndex.resolve(snapshot, category, location.toString());
            File file = canon != null ? AzureResourceManager.indexedFile(snapshot, canon) : null;
            if (file != null) {
                return createIoSupplier(snapshot, location, file);
            }
        }

        return null;
    }

    /**
     * Builds the stream for one file, or null if the file doesn't fit the requested path
     * (for example a model file asked for under a .png name).
     */
    @Nullable
    private IoSupplier<InputStream> createIoSupplier(AssetSnapshot snapshot, ResourceLocation location, File file) {
        String path = location.getPath();
        String nameLower = file.getName().toLowerCase(Locale.ROOT);
        boolean binaryPath = path.endsWith(".png") || path.endsWith(".ogg") || path.endsWith(".java") || path.endsWith(".mcmeta");

        if (snapshot.files(AssetSnapshot.Type.MODEL).contains(file)) {
            return binaryPath ? null : modelSupplier(snapshot, location, file);
        }
        if (snapshot.files(AssetSnapshot.Type.ANIMATION).contains(file)) {
            return binaryPath ? null : animationSupplier(snapshot, file);
        }
        if (nameLower.endsWith(".png")) {
            return path.endsWith(".png") || (path.startsWith("textures/") && !path.endsWith(".json")) ? rawSupplier(file) : null;
        }
        if (nameLower.endsWith(".ogg")) {
            return path.endsWith(".ogg") || (path.startsWith("sounds/") && !path.endsWith(".json")) ? rawSupplier(file) : null;
        }
        if (nameLower.endsWith(".java")) {
            return path.endsWith(".java") ? rawSupplier(file) : null;
        }
        if (nameLower.endsWith(".mcmeta")) {
            return path.endsWith(".mcmeta") ? rawSupplier(file) : null;
        }
        if (nameLower.endsWith(".json") && !binaryPath && AzureResourceManager.isDynamicResource(location)) {
            return rawSupplier(file);
        }
        return null;
    }

    private static IoSupplier<InputStream> rawSupplier(File file) {
        return () -> new FileInputStream(file);
    }

    /**
     * GeckoLib loads every model in one batch, and one file it can't read stops ALL GeckoLib models
     * from loading. So models are checked again when opened (only re-read if the file changed), and
     * anything unusable is replaced by a tiny empty model instead.
     */
    private static IoSupplier<InputStream> modelSupplier(AssetSnapshot snapshot, ResourceLocation location, File file) {
        return () -> {
            try {
                AssetFileCache.Info info = AzureResourceManager.FILE_CACHE.get(file);
                if (info == null || info.kind != AssetFileCache.Kind.MODEL) {
                    AzureFrameLib.LOGGER.warn("[AzureFrameLib] Model file \"{}\" changed and can no longer be used{}. An empty model is used instead until it is fixed.",
                            file.getName(), info != null && info.problem != null ? " (" + info.problem + ")" : "");
                    return new ByteArrayInputStream(AzureResourceManager.getEmptyGeoModelFallbackBytes(location));
                }
                boolean strip = info.hasHitboxCubes && AzureFrameLibConfig.get().hideHitboxBones;
                if (!strip && !info.needsCleanup) {
                    return new FileInputStream(file);
                }
                JsonObject root = readLenient(file);
                if (strip) ModelHitboxHelper.stripHitboxCubes(root);
                return new ByteArrayInputStream(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not read model file \"{}\" ({}). An empty model is used instead.", file.getName(), t.toString());
                return new ByteArrayInputStream(AzureResourceManager.getEmptyGeoModelFallbackBytes(location));
            }
        };
    }

    private static IoSupplier<InputStream> animationSupplier(AssetSnapshot snapshot, File file) {
        return () -> {
            try {
                AssetFileCache.Info info = AzureResourceManager.FILE_CACHE.get(file);
                if (info == null || info.kind != AssetFileCache.Kind.ANIMATION) {
                    AzureFrameLib.LOGGER.warn("[AzureFrameLib] Animation file \"{}\" changed and can no longer be used{}. It is skipped until it is fixed.",
                            file.getName(), info != null && info.problem != null ? " (" + info.problem + ")" : "");
                    return new ByteArrayInputStream(EMPTY_ANIMATIONS);
                }
                if (!info.needsCleanup) {
                    return new FileInputStream(file);
                }
                return new ByteArrayInputStream(GSON.toJson(readLenient(file)).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not read animation file \"{}\" ({}). It is skipped.", file.getName(), t.toString());
                return new ByteArrayInputStream(EMPTY_ANIMATIONS);
            }
        };
    }

    private static JsonObject readLenient(File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        JsonReader reader = new JsonReader(new StringReader(content));
        reader.setLenient(true);
        JsonElement el = JsonParser.parseReader(reader);
        if (!el.isJsonObject()) throw new IOException("not a JSON object");
        return el.getAsJsonObject();
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !isSupportedNamespace(namespace)) {
            return;
        }

        String prefix = path.endsWith("/") ? path : path + "/";
        AssetSnapshot snapshot = AzureResourceManager.snapshotForResourceLoading();
        Set<String> listed = new HashSet<>();
        NavigableMap<String, File> byPath = snapshot.byNamespace.get(namespace.toLowerCase(Locale.ROOT));
        if (byPath != null) {
            for (Map.Entry<String, File> entry : byPath.subMap(prefix, true, prefix + '\uffff', false).entrySet()) {
                ResourceLocation loc = ResourceLocation.tryBuild(namespace, entry.getKey());
                if (loc == null) continue;
                IoSupplier<InputStream> supplier = createIoSupplier(snapshot, loc, entry.getValue());
                if (supplier != null) {
                    listed.add(entry.getKey());
                    output.accept(loc, supplier);
                }
            }
        }
        // Resources registered at runtime
        for (Map.Entry<ResourceLocation, File> entry : AzureResourceManager.dynamicResources().entrySet()) {
            ResourceLocation loc = entry.getKey();
            if (!loc.getNamespace().equalsIgnoreCase(namespace) || !loc.getPath().startsWith(prefix) || listed.contains(loc.getPath())) {
                continue;
            }
            IoSupplier<InputStream> supplier = createIoSupplier(snapshot, loc, entry.getValue());
            if (supplier != null) output.accept(loc, supplier);
        }
    }

    private String generateSoundsJson(String targetNamespace) {
        Map<String, Object> rootJson = new HashMap<>();

        for (AzureResourceManager.ResourceRoot root : AzureResourceManager.getRoots()) {
            if (!root.directory.exists() || !root.directory.isDirectory()) continue;

            if (root.category == AzureResourceManager.ResourceCategory.SOUND) {
                scanOggDirectory(root.directory, "", root.namespace, rootJson);
            } else if (root.category == AzureResourceManager.ResourceCategory.UNPACKED_BUNDLE) {
                File[] folders = root.directory.listFiles();
                if (folders == null) continue;
                for (File folder : folders) {
                    if (folder.isDirectory()) {
                        String id = folder.getName();
                        scanOggDirectory(folder, "", root.namespace + ".unpacked." + AzureResourceManager.sanitizePath(id), rootJson);
                    }
                }
            }
        }

        return new GsonBuilder().setPrettyPrinting().create().toJson(rootJson);
    }

    private void scanOggDirectory(File dir, String relativePath, String eventPrefix, Map<String, Object> rootJson) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String nextRel = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
                scanOggDirectory(f, nextRel, eventPrefix, rootJson);
            } else if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String nameNoExt = f.getName().substring(0, f.getName().length() - 4);
                String soundPath = relativePath.isEmpty() ? nameNoExt : relativePath + "/" + nameNoExt;
                String cleanPath = AzureResourceManager.sanitizePath(soundPath);

                String eventSuffix = cleanPath.replace('/', '.');
                String eventKey = eventPrefix.isEmpty() ? eventSuffix : eventPrefix + "." + eventSuffix;

                Map<String, Object> entry = new HashMap<>();
                entry.put("category", "neutral");

                List<Object> soundList = new ArrayList<>();
                Map<String, Object> soundObj = new HashMap<>();
                soundObj.put("name", "azureframelib:" + cleanPath);
                soundObj.put("stream", true);
                soundList.add(soundObj);

                entry.put("sounds", soundList);
                rootJson.put(eventKey, entry);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            // Never waits for the index: Minecraft calls this on the main thread while starting a reload.
            Set<String> namespaces = new HashSet<>(SUPPORTED_NAMESPACES);
            namespaces.addAll(AzureResourceManager.managedNamespaces());
            return Collections.unmodifiableSet(namespaces);
        }
        return Collections.emptySet();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        if (serializer.getMetadataSectionName().equals("pack")) {
            PackMetadataSection section = new PackMetadataSection(
                    Component.literal("AzureFrameLib Dynamic Resources"),
                    15 // 1.20.1 Client Resource Pack format is 15
            );
            return (T) section;
        }
        return null;
    }

    @Override
    public String packId() {
        return PACK_ID;
    }

    @Override
    public void close() {
    }

    private boolean isSupportedNamespace(String namespace) {
        if (namespace == null) return false;
        String lower = namespace.toLowerCase(Locale.ROOT);
        return SUPPORTED_NAMESPACES.contains(lower) || AzureResourceManager.managedNamespaces().contains(lower);
    }
}
