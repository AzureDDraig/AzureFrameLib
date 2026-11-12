package ddraig.net.azureframelib.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ddraig.net.azureframelib.AzureFrameLib;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Universal JSON configuration loader and saver.
 */
public class ConfigHelper {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> T loadConfig(File file, Class<T> configClass, Supplier<T> defaultSupplier) {
        if (file.exists() && file.isFile()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                T loaded = GSON.fromJson(reader, configClass);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to read config file " + file.getName() + ", using defaults.", e);
            }
        }

        T defaultConfig = defaultSupplier.get();
        saveConfig(file, defaultConfig);
        return defaultConfig;
    }

    public static boolean saveConfig(File file, Object configObject) {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(configObject, writer);
            return true;
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to save config file: " + file.getName(), e);
            return false;
        }
    }
}
