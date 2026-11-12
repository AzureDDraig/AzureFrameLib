package ddraig.net.azureframelib.client.theme;

import ddraig.net.azureframelib.client.UIHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Universal theme palette for mod GUIs, menus, and HUDs.
 */
public enum AzureTheme {
    VANILLA("vanilla",
            0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF000000,
            0xFF8B8B8B, 0xFF373737, 0xFFFFFFFF,
            0xFFC6C6C6, 0xFF9D9D9D,
            0xFF404040, 0xFF000000, 0xFF555555, false),

    DARK("dark",
            0xFF2D2D2D, 0xFF4A4A4A, 0xFF1A1A1A, 0xFF0C0C0C,
            0xFF1E1E1E, 0xFF121212, 0xFF3D3D3D,
            0xFF383838, 0xFF1F1F1F,
            0xFFDDDDDD, 0xFFFFFFFF, 0xFF7A7A7A, true),

    WOODEN("wooden",
            0xFF805A36, 0xFFA67C52, 0xFF54381C, 0xFF2E1C0A,
            0xFF4F341E, 0xFF2B1C0E, 0xFF6D4D2E,
            0xFF946C46, 0xFF5A3E22,
            0xFFF4E3B1, 0xFFFFFFFF, 0xFFC6B485, true),

    CYBERPUNK("cyberpunk",
            0xFF140A24, 0xFF3F007F, 0xFF0B001F, 0xFF00FFFF,
            0xFF0D021C, 0xFF05000A, 0xFF00FFCC,
            0xFF24103B, 0xFF10031E,
            0xFF00FFFF, 0xFF00FFFF, 0xFF7F00FF, true),

    MEDIEVAL("medieval",
            0xFF53585F, 0xFF7A818C, 0xFF3A3E44, 0xFF1D2024,
            0xFF34383D, 0xFF1F2225, 0xFF474C52,
            0xFF44484E, 0xFF2C2F33,
            0xFFDFD0A0, 0xFFD4AF37, 0xFF9F926B, true),

    NETHER("nether",
            0xFF1F1418, 0xFF3A242B, 0xFF0E080A, 0xFF0F090B,
            0xFF160E11, 0xFF0E080A, 0xFF2A1B20,
            0xFF301E24, 0xFF191013,
            0xFFECE5DF, 0xFFF7D154, 0xFF8A7670, true),

    END("end",
            0xFF1B1226, 0xFF352647, 0xFF0D0814, 0xFF0A0610,
            0xFF140D1D, 0xFF0A0610, 0xFF261A34,
            0xFF2F2040, 0xFF160F20,
            0xFFDEE5A8, 0xFFFF80FF, 0xFF85896E, true),

    FROST("frost",
            0xFFE1ECF4, 0xFFFFFFFF, 0xFFADC9DC, 0xFF5F8CA3,
            0xFFCBE0EE, 0xFF96B8CF, 0xFFFFFFFF,
            0xFFC2D9E8, 0xFFE3EDF5,
            0xFF1C3A50, 0xFF006699, 0xFF7D9BB0, false),

    JUNGLE("jungle",
            0xFF364B30, 0xFF5A7851, 0xFF1F2B1C, 0xFF151D13,
            0xFF293B24, 0xFF1A2617, 0xFF465E3E,
            0xFF455D3E, 0xFF2E3D2A,
            0xFFEBF7E3, 0xFFFFFFFF, 0xFF688562, true),

    AETHER("aether",
            0xFFF0F5FA, 0xFFFFFFFF, 0xFFCAD5E2, 0xFFBCA663,
            0xFFDFE7F2, 0xFFBAC8DB, 0xFFFFFFFF,
            0xFFE6EDF5, 0xFFF3F7FA,
            0xFF222647, 0xFF11153E, 0xFF8FA4BA, false);

    public final String id;
    public final int panelBg;
    public final int panelHighlight;
    public final int panelShadow;
    public final int panelBorder;
    public final int slotBg;
    public final int slotShadow;
    public final int slotHighlight;
    public final int buttonBg;
    public final int buttonDisabledBg;
    public final int textColor;
    public final int textActiveColor;
    public final int textInactiveColor;
    public final boolean isDark;

    AzureTheme(String id, int panelBg, int panelHighlight, int panelShadow, int panelBorder,
               int slotBg, int slotShadow, int slotHighlight, int buttonBg, int buttonDisabledBg,
               int textColor, int textActiveColor, int textInactiveColor, boolean isDark) {
        this.id = id;
        this.panelBg = panelBg;
        this.panelHighlight = panelHighlight;
        this.panelShadow = panelShadow;
        this.panelBorder = panelBorder;
        this.slotBg = slotBg;
        this.slotShadow = slotShadow;
        this.slotHighlight = slotHighlight;
        this.buttonBg = buttonBg;
        this.buttonDisabledBg = buttonDisabledBg;
        this.textColor = textColor;
        this.textActiveColor = textActiveColor;
        this.textInactiveColor = textInactiveColor;
        this.isDark = isDark;
    }

    public static AzureTheme getTheme(String id) {
        if (id == null) return VANILLA;
        for (AzureTheme t : values()) {
            if (t.id.equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return VANILLA;
    }

    public Component getDisplayName() {
        return Component.literal(name().charAt(0) + name().substring(1).toLowerCase());
    }

    public void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, panelBg);
        UIHelper.drawOutline(graphics, x, y, width, height, panelBorder);
    }

    public void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, slotBg);
        graphics.fill(x, y, x + 18, y + 1, slotShadow);
        graphics.fill(x, y, x + 1, y + 18, slotShadow);
        graphics.fill(x, y + 17, x + 18, y + 18, slotHighlight);
        graphics.fill(x + 17, y, x + 18, y + 18, slotHighlight);
    }
}
