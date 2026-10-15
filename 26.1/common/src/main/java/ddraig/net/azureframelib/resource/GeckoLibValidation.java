package ddraig.net.azureframelib.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Method;

/**
 * Optional extra check that a model file can actually be read by GeckoLib.
 * <p>
 * GeckoLib loads every model in one batch, and a single file it can't read stops ALL
 * GeckoLib models and animations from loading (for every mod). Catching those files
 * here keeps them out of the shared resource pack. Uses reflection so AzureFrameLib
 * still works when GeckoLib isn't installed.
 */
final class GeckoLibValidation {

    private static volatile boolean initialized;
    private static Object geoGson;
    private static Method fromJsonElement;
    private static Class<?> modelClass;

    private GeckoLibValidation() {
    }

    /** Returns a plain-language problem, or null if the model looks loadable (or GeckoLib is absent). */
    static String checkModel(JsonObject root) {
        if (!init()) return null;
        try {
            Object model = fromJsonElement.invoke(geoGson, root, modelClass);
            if (model == null) return "GeckoLib could not read it";
            return null;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            if (msg.length() > 160) msg = msg.substring(0, 160) + "...";
            return "GeckoLib could not read it (" + msg + ")";
        }
    }

    private static boolean init() {
        if (initialized) return fromJsonElement != null;
        synchronized (GeckoLibValidation.class) {
            if (initialized) return fromJsonElement != null;
            try {
                Class<?> jsonUtil = Class.forName("software.bernie.geckolib.util.JsonUtil");
                geoGson = jsonUtil.getField("GEO_GSON").get(null);
                modelClass = Class.forName("software.bernie.geckolib.loading.json.raw.Model");
                fromJsonElement = geoGson.getClass().getMethod("fromJson", JsonElement.class, Class.class);
            } catch (Throwable t) {
                fromJsonElement = null;
            }
            initialized = true;
            return fromJsonElement != null;
        }
    }
}
