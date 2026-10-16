package com.hanchen.veritycustomapi.stt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@OnlyIn(Dist.CLIENT)
public class ModelDownloader {
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private static final String MODEL_DIR = "config/vosk-model-small-cn";

    public static boolean isModelExists() {
        return Files.exists(Paths.get(MODEL_DIR, "am", "final.mdl"));
    }

    public static void downloadAndExtract(ProgressCallback callback) throws IOException {
        Path modelPath = Paths.get(MODEL_DIR);
        if (!Files.exists(modelPath)) {
            Files.createDirectories(modelPath);
        }

        String tempZip = modelPath.resolve("model.zip").toString();
        downloadFile(MODEL_URL, tempZip, callback);
        extractZip(tempZip, modelPath.toString());
        Files.deleteIfExists(Paths.get(tempZip));
    }

    private static void downloadFile(String fileURL, String savePath, ProgressCallback callback) throws IOException {
        URL url = new URL(fileURL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int fileSize = conn.getContentLength();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(savePath)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if (callback != null && fileSize > 0) {
                        callback.onProgress((int) (totalRead * 100 / fileSize));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void extractZip(String zipPath, String destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = Paths.get(destDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
