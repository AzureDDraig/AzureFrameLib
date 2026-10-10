package ddraig.net.azureframelib.config;

import ddraig.net.azureframelib.AzureFrameLib;
import ddraig.net.azureframelib.util.ConfigHelper;
import dev.architectury.platform.Platform;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AzureFrameLib's own settings, stored in {@code config/AzureFrameLib/azureframelib.json}.
 * Missing settings are filled in with defaults and written back the first time the game starts.
 */
public final class AzureFrameLibConfig {

    /** Plain-language help text written into the config file. Not read by the code. */
    public String _help = "hitboxBoneNames: model bones with these names are treated as size-only boxes and are never drawn. "
            + "hideHitboxBones: set to false to draw them anyway (for debugging). "
            + "backgroundIndexing: scan config folders on a background thread at startup. "
            + "addShortAnimationNames: lets mods ask for 'idle' when the file calls it 'animation.dragon.idle'. "
            + "fixAnimationBones: makes sure GeckoLib knows the bones of AzureFrameLib models so their animations play. "
            + "logAnimationLinks: after each resource reload, list which animation file is linked to each model.";

    public List<String> hitboxBoneNames = new ArrayList<>(List.of("hitbox", "hit_box", "collision", "collider", "bounds"));
    public boolean hideHitboxBones = true;
    public boolean backgroundIndexing = true;
    public boolean addShortAnimationNames = true;
    public boolean fixAnimationBones = true;
    public boolean logAnimationLinks = false;

    private static volatile AzureFrameLibConfig instance = new AzureFrameLibConfig();
    private static volatile Set<String> hitboxNameSet = buildNameSet(instance.hitboxBoneNames);

    public static AzureFrameLibConfig get() {
        return instance;
    }

    /** Loads (or creates) the config file. Safe to call more than once. */
    public static synchronized void load() {
        try {
            File file = new File(Platform.getConfigFolder().toFile(), "AzureFrameLib/azureframelib.json");
            AzureFrameLibConfig loaded = ConfigHelper.loadConfig(file, AzureFrameLibConfig.class, AzureFrameLibConfig::new);
            if (loaded.hitboxBoneNames == null) {
                loaded.hitboxBoneNames = new ArrayList<>();
            }
            instance = loaded;
            hitboxNameSet = buildNameSet(loaded.hitboxBoneNames);
            // Write back so newly added settings appear in older config files.
            ConfigHelper.saveConfig(file, loaded);
        } catch (Throwable t) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not read azureframelib.json, using default settings: {}", t.toString());
        }
    }

    /** True when the bone name is on the "size-only box" list (ignores case, spaces and dashes). */
    public static boolean isHitboxBoneName(String boneName) {
        if (boneName == null || boneName.isEmpty()) return false;
        return hitboxNameSet.contains(normalizeBoneName(boneName));
    }

    public static Set<String> getHitboxBoneNames() {
        return hitboxNameSet;
    }

    private static String normalizeBoneName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static Set<String> buildNameSet(List<String> names) {
        Set<String> set = new LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isBlank()) set.add(normalizeBoneName(n));
            }
        }
        return Collections.unmodifiableSet(set);
    }
}
