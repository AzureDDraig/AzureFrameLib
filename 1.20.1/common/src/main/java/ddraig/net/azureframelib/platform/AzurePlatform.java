package ddraig.net.azureframelib.platform;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the config folder and runtime environment (Client vs Server) across
 * Architectury, Forge, NeoForge, and Fabric without requiring Architectury at
 * runtime on standalone Forge builds.
 */
public final class AzurePlatform {

    private static volatile Path cachedConfigFolder;
    private static volatile Boolean cachedIsClient;

    private AzurePlatform() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Path getConfigFolder() {
        Path path = cachedConfigFolder;
        if (path != null) return path;

        // 1. Try Architectury Platform if present
        try {
            Class<?> archPlatform = Class.forName("dev.architectury.platform.Platform");
            Object result = archPlatform.getMethod("getConfigFolder").invoke(null);
            if (result instanceof Path p) {
                cachedConfigFolder = p;
                return p;
            }
        } catch (Throwable ignored) {
        }

        // 2. Try Forge FMLPaths
        try {
            Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Object configDirEnum = Enum.valueOf((Class<Enum>) fmlPaths.asSubclass(Enum.class), "CONFIGDIR");
            Object result = fmlPaths.getMethod("get").invoke(configDirEnum);
            if (result instanceof Path p) {
                cachedConfigFolder = p;
                return p;
            }
        } catch (Throwable ignored) {
        }

        // 3. Try NeoForge FMLPaths
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object configDirEnum = Enum.valueOf((Class<Enum>) fmlPaths.asSubclass(Enum.class), "CONFIGDIR");
            Object result = fmlPaths.getMethod("get").invoke(configDirEnum);
            if (result instanceof Path p) {
                cachedConfigFolder = p;
                return p;
            }
        } catch (Throwable ignored) {
        }

        // 4. Try FabricLoader
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            Object result = fabricLoader.getMethod("getConfigDir").invoke(instance);
            if (result instanceof Path p) {
                cachedConfigFolder = p;
                return p;
            }
        } catch (Throwable ignored) {
        }

        path = Paths.get("config");
        cachedConfigFolder = path;
        return path;
    }

    public static boolean isClient() {
        Boolean client = cachedIsClient;
        if (client != null) return client;

        // 1. Try Architectury Platform if present
        try {
            Class<?> archPlatform = Class.forName("dev.architectury.platform.Platform");
            Object env = archPlatform.getMethod("getEnvironment").invoke(null);
            if (env != null) {
                boolean res = "CLIENT".equals(env.toString());
                cachedIsClient = res;
                return res;
            }
        } catch (Throwable ignored) {
        }

        // 2. Try Forge FMLEnvironment
        try {
            Class<?> fmlEnv = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist = fmlEnv.getField("dist").get(null);
            if (dist != null) {
                boolean res = "CLIENT".equals(dist.toString());
                cachedIsClient = res;
                return res;
            }
        } catch (Throwable ignored) {
        }

        // 3. Try NeoForge FMLEnvironment
        try {
            Class<?> fmlEnv = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = fmlEnv.getField("dist").get(null);
            if (dist != null) {
                boolean res = "CLIENT".equals(dist.toString());
                cachedIsClient = res;
                return res;
            }
        } catch (Throwable ignored) {
        }

        // 4. Try FabricLoader
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            Object envType = fabricLoader.getMethod("getEnvironmentType").invoke(instance);
            if (envType != null) {
                boolean res = "CLIENT".equals(envType.toString());
                cachedIsClient = res;
                return res;
            }
        } catch (Throwable ignored) {
        }

        cachedIsClient = false;
        return false;
    }
}
