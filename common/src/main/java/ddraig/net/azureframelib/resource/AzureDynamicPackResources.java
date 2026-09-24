package ddraig.net.azureframelib.resource;

import com.google.gson.GsonBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Dynamic in-game client resource pack.
 * Streams models, animations, textures, and sounds directly from all registered
 * framework config directories into Minecraft's client resource engine.
 */
public class AzureDynamicPackResources implements PackResources {
    public static final String PACK_ID = "azureframelib_dynamic";
    private static final Set<String> SUPPORTED_NAMESPACES = Set.of(
            "azureframelib",
            "custom_mobs",
            "rpg_mounts",
            "customraces"
    );

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

        // 1. Dynamic sounds.json
        if (path.equals("sounds.json")) {
            String json = generateSoundsJson(namespace);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return () -> new java.io.ByteArrayInputStream(bytes);
        }

        // 2. High-speed exact index lookup
        File indexed = AzureResourceManager.getResourceIndex().get(location);
        if (indexed != null && indexed.exists()) {
            return createIoSupplier(location, indexed);
        }

        // 3. Fallback category search
        if (path.startsWith("geo/") || path.startsWith("models/")) {
            File file = AzureResourceManager.findModelFile(path);
            if (file != null && file.exists()) {
                return createIoSupplier(location, file);
            }
        } else if (path.startsWith("animations/")) {
            File file = AzureResourceManager.findAnimationFile(path);
            if (file != null && file.exists()) {
                return createIoSupplier(location, file);
            }
        } else if (path.startsWith("textures/")) {
            File file = AzureResourceManager.findTextureFile(path);
            if (file != null && file.exists()) {
                return createIoSupplier(location, file);
            }
        } else if (path.startsWith("sounds/")) {
            File file = AzureResourceManager.findSoundFile(path);
            if (file != null && file.exists()) {
                return createIoSupplier(location, file);
            }
        }

        return null;
    }

    private IoSupplier<InputStream> createIoSupplier(ResourceLocation location, File file) {
        if (location.getPath().startsWith("animations/") && file.length() == 0) {
            byte[] fallback = "{\"format_version\":\"1.8.0\",\"animations\":{}}".getBytes(StandardCharsets.UTF_8);
            return () -> new java.io.ByteArrayInputStream(fallback);
        }
        return () -> new FileInputStream(file);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !isSupportedNamespace(namespace)) {
            return;
        }

        String prefix = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<ResourceLocation, File> entry : AzureResourceManager.getResourceIndex().entrySet()) {
            ResourceLocation loc = entry.getKey();
            if (!loc.getNamespace().equalsIgnoreCase(namespace)) {
                continue;
            }
            if (loc.getPath().startsWith(prefix)) {
                File file = entry.getValue();
                if (file != null && file.exists()) {
                    output.accept(loc, createIoSupplier(loc, file));
                }
            }
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
            Set<String> namespaces = new HashSet<>(SUPPORTED_NAMESPACES);
            namespaces.addAll(AzureResourceManager.getIndexedNamespaces());
            for (AzureResourceManager.ResourceRoot r : AzureResourceManager.getRoots()) {
                namespaces.add(r.namespace.toLowerCase(Locale.ROOT));
            }
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
        if (SUPPORTED_NAMESPACES.contains(lower)) return true;
        if (AzureResourceManager.getIndexedNamespaces().contains(lower)) return true;
        for (AzureResourceManager.ResourceRoot r : AzureResourceManager.getRoots()) {
            if (r.namespace.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }
}
