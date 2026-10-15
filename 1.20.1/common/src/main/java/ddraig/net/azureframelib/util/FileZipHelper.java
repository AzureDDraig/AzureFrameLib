package ddraig.net.azureframelib.util;

import ddraig.net.azureframelib.AzureFrameLib;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Utility for zipping and unzipping custom template packages.
 */
public class FileZipHelper {

    public static boolean unpackZip(File zipFile, File destDir) {
        if (!zipFile.exists() || !zipFile.isFile()) return false;
        if (!destDir.exists()) destDir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File targetFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    targetFile.mkdirs();
                } else {
                    File parent = targetFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(targetFile))) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            bos.write(buffer, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed unpacking zip: " + zipFile.getName(), e);
            return false;
        }
    }

    public static boolean packDirectory(File srcDir, File zipFile) {
        if (!srcDir.exists() || !srcDir.isDirectory()) return false;
        if (zipFile.getParentFile() != null && !zipFile.getParentFile().exists()) {
            zipFile.getParentFile().mkdirs();
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            packDirRecursive(srcDir, srcDir, zos);
            return true;
        } catch (Exception e) {
            AzureFrameLib.LOGGER.error("[AzureFrameLib] Failed packing zip: " + zipFile.getName(), e);
            return false;
        }
    }

    private static void packDirRecursive(File rootDir, File currentDir, ZipOutputStream zos) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                packDirRecursive(rootDir, f, zos);
            } else {
                String relativePath = rootDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(relativePath);
                zos.putNextEntry(entry);
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public static void deleteRecursively(File dir) {
        if (!dir.exists()) return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        dir.delete();
    }
}
