package ddraig.net.azureframelib.client;

import com.mojang.blaze3d.platform.NativeImage;
import ddraig.net.azureframelib.AzureFrameLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal custom icon scanner and dynamic silhouette / greyscale generator.
 */
public class CustomIconHelper {
    private static final Map<String, ResourceLocation> ICONS = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> SILHOUETTES = new ConcurrentHashMap<>();

    public static void clearCache() {
        ICONS.clear();
        SILHOUETTES.clear();
    }

    public static void registerIconFromDisk(String name, File file) {
        if (file == null || !file.exists() || !file.isFile()) return;
        try (InputStream is = new FileInputStream(file)) {
            NativeImage img = NativeImage.read(is);
            registerImage(name, img);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to load icon: " + file.getName(), e);
        }
    }

    public static void registerIconFromBytes(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            NativeImage img = NativeImage.read(bis);
            registerImage(name, img);
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to register icon bytes: " + name, e);
        }
    }

    private static void registerImage(String name, NativeImage image) {
        String clean = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        DynamicTexture normalTexture = new DynamicTexture(image);
        ResourceLocation normalLoc = new ResourceLocation(AzureFrameLib.MOD_ID, "dynamic/icons/" + clean);

        // Generate greyscale silhouette
        int w = image.getWidth();
        int h = image.getHeight();
        NativeImage silhouette = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int a = (abgr >> 24) & 0xFF;
                if (a > 0) {
                    int b = (abgr >> 16) & 0xFF;
                    int g = (abgr >> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    gray = Math.max(0, Math.min(255, gray / 2 + 30));
                    int newColor = (a << 24) | (gray << 16) | (gray << 8) | gray;
                    silhouette.setPixelRGBA(x, y, newColor);
                } else {
                    silhouette.setPixelRGBA(x, y, 0);
                }
            }
        }
        DynamicTexture silTexture = new DynamicTexture(silhouette);
        ResourceLocation silLoc = new ResourceLocation(AzureFrameLib.MOD_ID, "dynamic/icons/" + clean + "_silhouette");

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                mc.getTextureManager().register(normalLoc, normalTexture);
                mc.getTextureManager().register(silLoc, silTexture);
            });
        }

        ICONS.put(clean, normalLoc);
        SILHOUETTES.put(clean, silLoc);
    }

    public static ResourceLocation getIcon(String name, ResourceLocation fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        String clean = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        return ICONS.getOrDefault(clean, fallback);
    }

    public static ResourceLocation getSilhouette(String name, ResourceLocation fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        String clean = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        return SILHOUETTES.getOrDefault(clean, fallback);
    }
}
