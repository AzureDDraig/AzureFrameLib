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

    public static void clearCache() {
        CACHED_TEXTURES.clear();
    }

    public static ResourceLocation getTexture(String rawPath) {
        return getTexture(rawPath, DEFAULT_TEXTURE);
    }

    public static ResourceLocation getTexture(String rawPath, ResourceLocation fallback) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return fallback != null ? fallback : MissingTextureAtlasSprite.getLocation();
        }

        String path = rawPath.trim();

        // 1. Direct namespaced ResourceLocation in vanilla assets
        if (path.contains(":") && !path.startsWith("file:")) {
            try {
                ResourceLocation loc = new ResourceLocation(path);
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getResourceManager() != null && mc.getResourceManager().getResource(loc).isPresent()) {
                    return loc;
                }
            } catch (Exception ignored) {}
        }

        String cleanKey = AzureResourceManager.sanitizePath(path);
        if (CACHED_TEXTURES.containsKey(cleanKey)) {
            return CACHED_TEXTURES.get(cleanKey);
        }

        // 2. Search across all registered config folders
        File file = AzureResourceManager.findTextureFile(path);
        if (file != null && file.exists() && file.isFile()) {
            try (InputStream is = new FileInputStream(file)) {
                NativeImage nativeImage = NativeImage.read(is);
                DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

                String regPath = "textures/dynamic/" + cleanKey.replace('.', '_');
                ResourceLocation loc = new ResourceLocation(AzureFrameLib.MOD_ID, regPath.toLowerCase(Locale.ROOT));

                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getTextureManager() != null) {
                    AbstractTexture old = mc.getTextureManager().getTexture(loc);
                    if (old != null) {
                        try {
                            old.close();
                        } catch (Exception ignored) {}
                    }
                    mc.getTextureManager().register(loc, dynamicTexture);
                    CACHED_TEXTURES.put(cleanKey, loc);
                    return loc;
                }
            } catch (Throwable t) {
                AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to load dynamic disk texture from " + file.getAbsolutePath(), t);
            }
        }

        return fallback != null ? fallback : MissingTextureAtlasSprite.getLocation();
    }
}
