package ddraig.net.azureframelib.compat.geckolib;

import com.google.common.collect.MapMaker;
import ddraig.net.azureframelib.resource.AzureAssetIndex;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets mods ask for an animation by its short name ("idle") when the file names it in full
 * ("animation.dragon.idle"). Answers are remembered per loaded animation file.
 * Only used when GeckoLib is installed.
 */
public final class ShortAnimationNames {

    private static final Map<BakedAnimations, Map<String, Optional<Animation>>> CACHE = new MapMaker().weakKeys().makeMap();

    private ShortAnimationNames() {
    }

    public static Animation find(BakedAnimations baked, String requested) {
        if (baked == null || requested == null || baked.animations() == null) return null;
        Map<String, Optional<Animation>> perFile = CACHE.computeIfAbsent(baked, b -> new ConcurrentHashMap<>());
        Optional<Animation> hit = perFile.get(requested);
        if (hit == null) {
            String match = AzureAssetIndex.matchAnimationName(baked.animations().keySet(), requested);
            hit = Optional.ofNullable(match != null ? baked.animations().get(match) : null);
            if (perFile.size() < 512) perFile.put(requested, hit);
        }
        return hit.orElse(null);
    }
}
