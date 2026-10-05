package ddraig.net.azureframelib.client;

import com.mojang.blaze3d.platform.NativeImage;
import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.resource.AzureResourceManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic disk texture loader.
 * Loads and registers .png textures from config folders directly into Minecraft's TextureManager.
 */
public class DynamicTextureHelper {
    public static final ResourceLocation DEFAULT_TEXTURE = new ResourceLocation("minecraft", "textures/entity/cow/cow.png");
    private static final Map<String, ResourceLocation> CACHED_TEXTURES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> FAILED_TEXTURES = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> WARNED_TEXTURES = ConcurrentHashMap.newKeySet();

    private static final byte[] PNG_HEADER = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public static boolean isValidPngHeader(byte[] header) {
        if (header == null || header.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (header[i] != PNG_HEADER[i]) return false;
        }
        return true;
    }

    public static boolean isValidPngFile(File file) {
        if (file == null || !file.exists() || !file.isFile() || file.length() < 8) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[8];
            int read = fis.read(header);
            return read == 8 && isValidPngHeader(header);
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearCache() {
        CACHED_TEXTURES.clear();
        FAILED_TEXTURES.clear();
        WARNED_TEXTURES.clear();
    }

    public static ResourceLocation getTexture(String rawPath) {
        return getTexture(rawPath, DEFAULT_TEXTURE);
    }

    public static ResourceLocation getTexture(String rawPath, ResourceLocation fallback) {
        ResourceLocation fallbackLoc = fallback != null ? fallback : MissingTextureAtlasSprite.getLocation();
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return fallbackLoc;
        }

        String path = rawPath.trim();
        String cleanKey = AzureResourceManager.sanitizePath(path);

        // If previously verified as failed/corrupt, return fallback immediately without disk checks or log spam
        if (FAILED_TEXTURES.contains(cleanKey)) {
            return fallbackLoc;
        }

        // 1. Direct namespaced ResourceLocation in vanilla assets (must end with .png)
        if (path.contains(":") && !path.startsWith("file:") && path.toLowerCase(Locale.ROOT).endsWith(".png")) {
            try {
                ResourceLocation loc = new ResourceLocation(path);
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getResourceManager() != null) {
                    var resOpt = mc.getResourceManager().getResource(loc);
                    if (resOpt.isPresent()) {
                        try (InputStream is = resOpt.get().open()) {
                            byte[] header = new byte[8];
                            if (is.read(header) == 8 && isValidPngHeader(header)) {
                                CACHED_TEXTURES.put(cleanKey, loc);
                                return loc;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (CACHED_TEXTURES.containsKey(cleanKey)) {
            return CACHED_TEXTURES.get(cleanKey);
        }

        // 2. Search across all registered config folders
        File file = AzureResourceManager.findTextureFile(path);
        if (file != null && file.exists() && file.isFile()) {
            if (!isValidPngFile(file)) {
                if (WARNED_TEXTURES.add(file.getAbsolutePath())) {
                    AzureFrameLib.LOGGER.warn("[AzureFrameLib] Texture file '{}' is corrupt or not a valid PNG (length: {} bytes). Using fallback.", file.getAbsolutePath(), file.length());
                }
                FAILED_TEXTURES.add(cleanKey);
                CACHED_TEXTURES.put(cleanKey, fallbackLoc);
                return fallbackLoc;
            }

            try (InputStream is = new FileInputStream(file)) {
                NativeImage nativeImage = NativeImage.read(is);
                DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

                // Build clean ID for the texture, ensuring it explicitly ends with .png
                String baseName = AzureResourceManager.stripExtension(file.getName());
                if (baseName.toLowerCase(Locale.ROOT).endsWith("_png")) {
                    baseName = baseName.substring(0, baseName.length() - 4);
                }
                String cleanBase = AzureResourceManager.sanitizePath(baseName).replace('.', '_');
                String regPath = "textures/dynamic/" + cleanBase + ".png";
                ResourceLocation loc = new ResourceLocation(AzureFrameLib.MOD_ID, regPath.toLowerCase(Locale.ROOT));

                // Also create legacy alias for backwards compatibility with any saved configs using "_png"
                ResourceLocation legacyLoc = new ResourceLocation(AzureFrameLib.MOD_ID, ("textures/dynamic/" + cleanBase + "_png").toLowerCase(Locale.ROOT));

                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getTextureManager() != null) {
                    mc.getTextureManager().register(loc, dynamicTexture);
                    mc.getTextureManager().register(legacyLoc, dynamicTexture);

                    CACHED_TEXTURES.put(cleanKey, loc);
                    CACHED_TEXTURES.put(cleanBase, loc);
                    CACHED_TEXTURES.put(loc.toString(), loc);
                    CACHED_TEXTURES.put(legacyLoc.toString(), loc);

                    // Also index into AzureResourceManager so pack resources can serve it if queried
                    AzureResourceManager.registerDynamicResource(loc, file);
                    AzureResourceManager.registerDynamicResource(legacyLoc, file);

                    return loc;
                }
            } catch (Throwable t) {
                if (WARNED_TEXTURES.add(file.getAbsolutePath())) {
                    AzureFrameLib.LOGGER.warn("[AzureFrameLib] Failed to load dynamic disk texture from '{}': {}", file.getAbsolutePath(), t.getMessage());
                }
                FAILED_TEXTURES.add(cleanKey);
                CACHED_TEXTURES.put(cleanKey, fallbackLoc);
                return fallbackLoc;
            }
        }

        FAILED_TEXTURES.add(cleanKey);
        CACHED_TEXTURES.put(cleanKey, fallbackLoc);
        return fallbackLoc;
    }
}
