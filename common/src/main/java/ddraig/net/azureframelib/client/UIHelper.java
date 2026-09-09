package ddraig.net.azureframelib.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared GUI rendering utilities.
 */
public class UIHelper {

    public static void drawOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    public static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height, int bgColor, int borderColor) {
        graphics.fill(x, y, x + width, y + height, bgColor);
        drawOutline(graphics, x, y, width, height, borderColor);
    }

    public static String truncate(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        int dotsWidth = font.width("...");
        if (maxWidth <= dotsWidth) return "...";
        return font.plainSubstrByWidth(text, maxWidth - dotsWidth) + "...";
    }
}
