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
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
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
 */
public class AzureDynamicPackResources implements PackResources {
    public static final String PACK_ID = "azureframelib_dynamic";
    private static final Set<String> SUPPORTED_NAMESPACES = AzureResourceManager.SUPPORTED_NAMESPACES;
    private static final Gson GSON = new Gson();
    private static final byte[] EMPTY_ANIMATIONS = "{\"format_version\":\"1.8.0\",\"animations\":{}}".getBytes(StandardCharsets.UTF_8);

    private final PackLocationInfo locationInfo;

    public AzureDynamicPackResources() {
        this(new PackLocationInfo(
                PACK_ID,
                Component.literal("AzureFrameLib Dynamic Resources"),
                PackSource.BUILT_IN,
                Optional.empty()
        ));
    }

    public AzureDynamicPackResources(PackLocationInfo locationInfo) {
        this.locationInfo = locationInfo;
    }

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

        if (path.endsWith(".mcmeta")) {
            File metaFile = AzureResourceManager.indexedFile(AzureResourceManager.snapshotForResourceLoading(), location);
            if (metaFile != null && metaFile.getName().toLowerCase(Locale.ROOT).endsWith(".mcmeta")) {
                return () -> new FileInputStream(metaFile);
            }
            return null;
        }

        if (path.equals("sounds.json")) {
            AssetSnapshot snapshot = AzureResourceManager.snapshotForResourceLoading();
            byte[] bytes = snapshot.soundsJsonBytes;
            return () -> new ByteArrayInputStream(bytes);
        }

        AssetSnapshot snapshot = AzureResourceManager.snapshotForResourceLoading();
        File indexed = AzureResourceManager.indexedFile(snapshot, location);
        if (indexed != null) {
            IoSupplier<InputStream> supplier = createIoSupplier(snapshot, location, indexed);
            if (supplier != null) return supplier;
        }

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
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(true);
        JsonElement el = JsonParser.parseReader(reader);
        return el.getAsJsonObject();
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !isSupportedNamespace(namespace)) {
            return;
        }

        // Only list sound files (.ogg) so SoundManager can validate sounds.json entries.
        // Never list geo/, models/, animations/, or textures/ here:
        // - GeckoLibCache.reload() calls listResources("geo"/"models"/"animations") and would
        //   eagerly parse and bake thousands of unused models and animations into ~1.75 GB of RAM.
        //   Instead, models and animations are baked lazily on demand via GeckoLibModelLoader & GeoModelMixin.
        // - Vanilla ModelManager and TextureAtlas stitchers call listResources("models"/"textures")
        //   and must not scan entity models or entity textures.
        String cleanPath = path == null ? "" : path;
        while (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);
        if (!cleanPath.equals("sounds") && !cleanPath.startsWith("sounds/")) {
            return;
        }

        String prefix = cleanPath.endsWith("/") ? cleanPath : cleanPath + "/";
        AssetSnapshot snapshot = AzureResourceManager.snapshotForResourceLoading();
        Set<String> listed = new HashSet<>();
        NavigableMap<String, File> byPath = snapshot.soundsByNamespace.get(namespace.toLowerCase(Locale.ROOT));
        if (byPath != null) {
            for (Map.Entry<String, File> entry : byPath.subMap(prefix, true, prefix + '\uffff', false).entrySet()) {
                ResourceLocation loc = ResourceLocation.tryBuild(namespace, entry.getKey());
                if (loc == null) continue;
                listed.add(entry.getKey());
                output.accept(loc, rawSupplier(entry.getValue()));
            }
        }
        for (Map.Entry<ResourceLocation, File> entry : AzureResourceManager.dynamicResources().entrySet()) {
            ResourceLocation loc = entry.getKey();
            if (!loc.getNamespace().equalsIgnoreCase(namespace)
                    || !loc.getPath().startsWith(prefix)
                    || !loc.getPath().endsWith(".ogg")
                    || listed.contains(loc.getPath())) {
                continue;
            }
            output.accept(loc, rawSupplier(entry.getValue()));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            Set<String> namespaces = new HashSet<>(SUPPORTED_NAMESPACES);
            namespaces.addAll(AzureResourceManager.managedNamespaces());
            return Collections.unmodifiableSet(namespaces);
        }
        return Collections.emptySet();
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        if (serializer.getMetadataSectionName().equals("pack")) {
            PackMetadataSection section = new PackMetadataSection(
                    Component.literal("AzureFrameLib Dynamic Resources"),
                    34,
                    Optional.empty()
            );
            return (T) section;
        }
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return this.locationInfo;
    }

    @Override
    public String packId() {
        return this.locationInfo.id();
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
