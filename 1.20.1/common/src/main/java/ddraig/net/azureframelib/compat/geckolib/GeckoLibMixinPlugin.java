package ddraig.net.azureframelib.compat.geckolib;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Only applies AzureFrameLib's GeckoLib fixes when GeckoLib is installed.
 */
public class GeckoLibMixinPlugin implements IMixinConfigPlugin {

    private static Boolean present;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isGeckoLibPresent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    static synchronized boolean isGeckoLibPresent() {
        if (present != null) return present;
        Boolean result = null;
        // Forge
        try {
            Class<?> lml = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
            Object list = lml.getMethod("get").invoke(null);
            if (list != null) {
                result = lml.getMethod("getModFileById", String.class).invoke(list, "geckolib") != null;
            }
        } catch (Throwable ignored) {
        }
        // Fabric
        if (result == null) {
            try {
                Class<?> fl = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object instance = fl.getMethod("getInstance").invoke(null);
                result = (Boolean) fl.getMethod("isModLoaded", String.class).invoke(instance, "geckolib");
            } catch (Throwable ignored) {
            }
        }
        // Last resort: look for the class file
        if (result == null) {
            try {
                ClassLoader cl = GeckoLibMixinPlugin.class.getClassLoader();
                result = cl != null && cl.getResource("software/bernie/geckolib/model/GeoModel.class") != null;
            } catch (Throwable ignored) {
                result = false;
            }
        }
        present = result;
        return result;
    }
}
