package ddraig.net.azureframelib.db;

import ddraig.net.azureframelib.AzureFrameLib;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal SQLite Database Helper for Minecraft mods.
 * Incorporates high-performance PRAGMA defaults (WAL mode, normal synchronous,
 * memory temp store, 5s busy timeout) and dynamic driver fallback loading.
 */
public class SQLiteHelper {

    private static Driver sqliteDriver;
    private static boolean driverLoaded = false;
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AzureFrameLib-DB-Worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Ensures the SQLite JDBC driver is available on the classpath or dynamically loads it.
     */
    public static synchronized void ensureDriverLoaded(Path libFolder) {
        if (driverLoaded) return;

        try {
            Class<?> jdbcClass;
            try {
                jdbcClass = Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                jdbcClass = Class.forName("com.rpgwarehouse.storage.sqlite.JDBC");
            }
            sqliteDriver = (Driver) jdbcClass.getDeclaredConstructor().newInstance();
            driverLoaded = true;
            AzureFrameLib.LOGGER.info("[AzureFrameLib] SQLite JDBC driver found on classpath.");
        } catch (Exception ex) {
            if (libFolder != null) {
                try {
                    File libDir = libFolder.toFile();
                    if (!libDir.exists()) libDir.mkdirs();

                    File jarFile = new File(libDir, "sqlite-jdbc-3.43.0.0.jar");
                    if (jarFile.exists()) {
                        URL jarUrl = jarFile.toURI().toURL();
                        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, SQLiteHelper.class.getClassLoader());
                        Class<?> jdbcClass = Class.forName("org.sqlite.JDBC", true, classLoader);
                        sqliteDriver = (Driver) jdbcClass.getDeclaredConstructor().newInstance();
                        driverLoaded = true;
                        AzureFrameLib.LOGGER.info("[AzureFrameLib] Dynamically loaded SQLite JDBC driver from: " + jarFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed to dynamically load SQLite driver from lib directory:", e);
                }
            }
        }
    }

    /**
     * Opens an SQLite connection to the target database file and applies performance optimizations.
     */
    public static Connection openConnection(File dbFile) throws SQLException {
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Connection conn;
        if (sqliteDriver != null) {
            conn = sqliteDriver.connect(url, new Properties());
        } else {
            conn = DriverManager.getConnection(url);
        }

        applyOptimizedPragmas(conn);
        return conn;
    }

    /**
     * Applies performance-optimized PRAGMAs:
     * - WAL journal mode (prevents lock contention during concurrent reads/writes)
     * - NORMAL synchronous (safe and significantly faster than FULL)
     * - MEMORY temp store
     * - 5000ms busy timeout (avoids database locked exceptions under load)
     */
    public static void applyOptimizedPragmas(Connection conn) {
        if (conn == null) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA temp_store=MEMORY;");
            stmt.execute("PRAGMA busy_timeout=5000;");
        } catch (SQLException e) {
            AzureFrameLib.LOGGER.warn("[AzureFrameLib] Could not apply SQLite PRAGMAs: " + e.getMessage());
        }
    }

    /**
     * Submits an asynchronous task to the database background executor.
     */
    public static void executeAsync(Runnable task) {
        if (task != null && !DB_EXECUTOR.isShutdown()) {
            DB_EXECUTOR.submit(task);
        }
    }

    public static ExecutorService getAsyncExecutor() {
        return DB_EXECUTOR;
    }
}
