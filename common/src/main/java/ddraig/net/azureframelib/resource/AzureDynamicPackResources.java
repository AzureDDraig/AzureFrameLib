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

        // 2. Geometry models (.geo.json or .json)
        if (path.startsWith("geo/") || path.startsWith("models/")) {
            String modelKey = path.substring(path.indexOf('/') + 1);
            File file = AzureResourceManager.findModelFile(modelKey);
            if (file != null && file.exists()) {
                return () -> new FileInputStream(file);
            }
        }

        // 3. Animations (.animation.json or .json)
        if (path.startsWith("animations/")) {
            String animKey = path.substring(11);
            File file = AzureResourceManager.findAnimationFile(animKey);
            if (file != null && file.exists()) {
                return () -> new FileInputStream(file);
            }
        }

        // 4. Textures (.png)
        if (path.startsWith("textures/") && path.endsWith(".png")) {
            String texKey = path.substring(9);
            File file = AzureResourceManager.findTextureFile(texKey);
            if (file != null && file.exists()) {
                return () -> new FileInputStream(file);
            }
        }

        // 5. Sounds (.ogg)
        if (path.startsWith("sounds/") && path.endsWith(".ogg")) {
            String soundKey = path.substring(7);
            File file = AzureResourceManager.findSoundFile(soundKey);
            if (file != null && file.exists()) {
                return () -> new FileInputStream(file);
            }
        }

        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !isSupportedNamespace(namespace)) {
            return;
        }

        for (AzureResourceManager.ResourceRoot root : AzureResourceManager.getRoots()) {
            if (!root.directory.exists() || !root.directory.isDirectory()) continue;

            if (path.equals("geo") || path.equals("models")) {
                listModels(root, namespace, output);
            } else if (path.equals("animations")) {
                listAnimations(root, namespace, output);
            } else if (path.equals("textures")) {
                listTextures(root, namespace, output);
            } else if (path.equals("sounds")) {
                listSounds(root, namespace, output);
            }
        }
    }

    private void listModels(AzureResourceManager.ResourceRoot root, String namespace, ResourceOutput output) {
        if (root.category == AzureResourceManager.ResourceCategory.UNPACKED_BUNDLE) {
            File[] folders = root.directory.listFiles();
            if (folders == null) return;
            for (File folder : folders) {
                if (folder.isDirectory()) {
                    File geoFile = findFileEndingWith(folder, ".geo.json");
                    if (geoFile != null) {
                        acceptResource(output, namespace, "geo/" + AzureResourceManager.sanitizePath(folder.getName()) + ".geo.json", geoFile);
                    }
                }
            }
        } else if (root.category == AzureResourceManager.ResourceCategory.MODEL) {
            scanFilesRecursive(root.directory, file -> {
                String nameLower = file.getName().toLowerCase(Locale.ROOT);
                if (nameLower.endsWith(".geo.json")) {
                    acceptResource(output, namespace, "geo/" + file.getName(), file);
                } else if (nameLower.endsWith(".json")) {
                    acceptResource(output, namespace, "models/" + file.getName(), file);
                }
            });
        }
    }

    private void listAnimations(AzureResourceManager.ResourceRoot root, String namespace, ResourceOutput output) {
        if (root.category == AzureResourceManager.ResourceCategory.UNPACKED_BUNDLE) {
            File[] folders = root.directory.listFiles();
            if (folders == null) return;
            for (File folder : folders) {
                if (folder.isDirectory()) {
                    File animFile = findFileEndingWith(folder, ".animation.json");
                    if (animFile != null) {
                        acceptResource(output, namespace, "animations/" + AzureResourceManager.sanitizePath(folder.getName()) + ".animation.json", animFile);
                    }
                }
            }
        } else if (root.category == AzureResourceManager.ResourceCategory.ANIMATION) {
            scanFilesRecursive(root.directory, file -> {
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".animation.json")) {
                    acceptResource(output, namespace, "animations/" + file.getName(), file);
                }
            });
        }
    }

    private void listTextures(AzureResourceManager.ResourceRoot root, String namespace, ResourceOutput output) {
        scanFilesRecursive(root.directory, file -> {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                String rel = getRelativePath(root.directory, file);
                acceptResource(output, namespace, "textures/" + AzureResourceManager.sanitizePath(rel), file);
            }
        });
    }

    private void listSounds(AzureResourceManager.ResourceRoot root, String namespace, ResourceOutput output) {
        scanFilesRecursive(root.directory, file -> {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String rel = getRelativePath(root.directory, file);
                acceptResource(output, namespace, "sounds/" + AzureResourceManager.sanitizePath(rel), file);
            }
        });
    }

    private void acceptResource(ResourceOutput output, String namespace, String path, File file) {
        try {
            ResourceLocation loc = ResourceLocation.tryBuild(namespace, path.toLowerCase(Locale.ROOT));
            if (loc != null) {
                output.accept(loc, IoSupplier.create(file.toPath()));
            }
        } catch (Exception ignored) {}
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
            for (AzureResourceManager.ResourceRoot r : AzureResourceManager.getRoots()) {
                namespaces.add(r.namespace);
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
        if (SUPPORTED_NAMESPACES.contains(namespace)) return true;
        for (AzureResourceManager.ResourceRoot r : AzureResourceManager.getRoots()) {
            if (r.namespace.equalsIgnoreCase(namespace)) return true;
        }
        return false;
    }

    private static String getRelativePath(File base, File file) {
        try {
            return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    private static File findFileEndingWith(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileEndingWith(f, suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void scanFilesRecursive(File dir, java.util.function.Consumer<File> consumer) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanFilesRecursive(f, consumer);
            } else if (f.isFile()) {
                consumer.accept(f);
            }
        }
    }
}
