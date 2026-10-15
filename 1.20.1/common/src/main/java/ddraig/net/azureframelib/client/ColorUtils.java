package ddraig.net.azureframelib.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for extracting dominant and average colors from textures.
 */
public class ColorUtils {
    private static final Map<ResourceLocation, Integer> DOMINANT_COLOR_CACHE = new ConcurrentHashMap<>();

    public static void clearCache() {
        DOMINANT_COLOR_CACHE.clear();
    }

    public static int getDominantColor(ResourceLocation textureLoc) {
        if (textureLoc == null) return 0xFFFFFF;

        if (DOMINANT_COLOR_CACHE.containsKey(textureLoc)) {
            return DOMINANT_COLOR_CACHE.get(textureLoc);
        }

        int extracted = extractDominantColor(textureLoc);
        DOMINANT_COLOR_CACHE.put(textureLoc, extracted);
        return extracted;
    }

    private static int extractDominantColor(ResourceLocation textureLoc) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return 0xFFFFFF;

            // 1. Try checking dynamic texture from TextureManager
            AbstractTexture tex = mc.getTextureManager().getTexture(textureLoc);
            if (tex instanceof DynamicTexture dynamicTex && dynamicTex.getPixels() != null) {
                return calculateAverageColor(dynamicTex.getPixels());
            }

            // 2. Try reading from ResourceManager
            if (mc.getResourceManager() != null) {
                Optional<Resource> res = mc.getResourceManager().getResource(textureLoc);
                if (res.isPresent()) {
                    try (InputStream is = res.get().open()) {
                        NativeImage image = NativeImage.read(is);
                        int color = calculateAverageColor(image);
                        image.close();
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return 0xFFFFFF;
    }

    private static int calculateAverageColor(NativeImage img) {
        long totalR = 0, totalG = 0, totalB = 0;
        int count = 0;

        int width = img.getWidth();
        int height = img.getHeight();

        // Sample pixels with a stride to maintain performance
        int stepX = Math.max(1, width / 32);
        int stepY = Math.max(1, height / 32);

        for (int x = 0; x < width; x += stepX) {
            for (int y = 0; y < height; y += stepY) {
                int pixel = img.getPixelRGBA(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a > 32) { // ignore transparent pixels
                    int r = pixel & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;

                    totalR += r;
                    totalG += g;
                    totalB += b;
                    count++;
                }
            }
        }

        if (count == 0) return 0xFFFFFF;

        int avgR = (int) (totalR / count);
        int avgG = (int) (totalG / count);
        int avgB = (int) (totalB / count);

        return ((avgR & 0xFF) << 16) | ((avgG & 0xFF) << 8) | (avgB & 0xFF);
    }
}
