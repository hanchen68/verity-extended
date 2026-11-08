/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Locale;
import java.util.Objects;

public class LibraryUtils {
    private static final String NATIVE_PATH_PROP = "sherpa_onnx.native.path";
    private static final String LIB_NAME = "sherpa-onnx-jni";
    private static boolean debug = false;
    private static String detectedOS;

    public static void enableDebug() {
        debug = true;
    }

    public static void disableDebug() {
        debug = false;
    }

    public static void load() {
        if (LibraryUtils.loadFromSherpaOnnxNativePath()) {
            return;
        }
        if (!LibraryUtils.isAndroid()) {
            try {
                if (LibraryUtils.loadFromResourceInJar()) {
                    return;
                }
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
        System.loadLibrary(LIB_NAME);
    }

    private static boolean loadFromSherpaOnnxNativePath() {
        String string = System.mapLibraryName(LIB_NAME);
        String string2 = System.getProperty(NATIVE_PATH_PROP);
        if (string2 != null) {
            File file = new File(string2);
            File file2 = new File(file, string);
            if (file.isDirectory() && file2.exists()) {
                if (debug) {
                    System.out.printf("Loading from: %s\n", file2.getAbsolutePath());
                }
                System.load(file2.getAbsolutePath());
                return true;
            }
        }
        if (debug) {
            System.out.println("nativePath is null");
        }
        return false;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static boolean loadFromResourceInJar() throws IOException {
        String string = System.mapLibraryName(LIB_NAME);
        String string2 = "sherpa-onnx/native/" + LibraryUtils.getOsArch() + '/' + string;
        Path path = null;
        try {
            Object object;
            if (!LibraryUtils.resourceExists(string2)) {
                if (debug) {
                    System.out.printf("%s does not exist\n", string2);
                }
                boolean bl = false;
                return bl;
            }
            path = Files.createTempDirectory("sherpa-onnx-java", new FileAttribute[0]);
            if (Objects.equals(detectedOS, "osx")) {
                object = "sherpa-onnx/native/" + LibraryUtils.getOsArch() + '/' + "libonnxruntime.1.24.4.dylib";
                if (!LibraryUtils.resourceExists((String)object)) {
                    if (debug) {
                        System.out.printf("%s does not exist\n", object);
                    }
                    boolean bl = false;
                    return bl;
                }
                File file = path.resolve("libonnxruntime.1.24.4.dylib").toFile();
                LibraryUtils.extractResource((String)object, file);
                System.load(file.getAbsolutePath());
            } else {
                object = System.mapLibraryName("onnxruntime");
                String string3 = "sherpa-onnx/native/" + LibraryUtils.getOsArch() + '/' + (String)object;
                if (!LibraryUtils.resourceExists(string3)) {
                    if (debug) {
                        System.out.printf("%s does not exist\n", string3);
                    }
                    boolean bl = false;
                    return bl;
                }
                File file = path.resolve((String)object).toFile();
                LibraryUtils.extractResource(string3, file);
                System.load(file.getAbsolutePath());
            }
            object = path.resolve(string).toFile();
            LibraryUtils.extractResource(string2, (File)object);
            System.load(((File)object).getAbsolutePath());
        }
        finally {
            if (path != null) {
                LibraryUtils.cleanUpTempDir(path.toFile());
            }
        }
        return true;
    }

    private static String getOsArch() {
        String string;
        String string2 = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        if (string2.contains("mac") || string2.contains("darwin")) {
            detectedOS = "osx";
        } else if (string2.contains("win")) {
            detectedOS = "win";
        } else if (string2.contains("nux")) {
            detectedOS = "linux";
        } else {
            throw new IllegalStateException("Unsupported os:" + string2);
        }
        String string3 = System.getProperty("os.arch", "generic").toLowerCase(Locale.ENGLISH);
        if (string3.startsWith("amd64") || string3.startsWith("x86_64")) {
            string = "x64";
        } else if (string3.startsWith("x86")) {
            string = "x86";
        } else if (string3.startsWith("aarch64") || string3.startsWith("arm64")) {
            string = "aarch64";
        } else if (string3.startsWith("arm")) {
            string = "arm";
        } else {
            throw new IllegalStateException("Unsupported arch:" + string3);
        }
        return detectedOS + '-' + string;
    }

    private static void extractResource(String string, File file) {
        if (debug) {
            System.out.printf("Copying from resource path %s to %s\n", string, file.toPath());
        }
        try (InputStream inputStream = LibraryUtils.class.getClassLoader().getResourceAsStream(string);){
            if (inputStream == null) {
                throw new RuntimeException("Resource not found: " + string);
            }
            Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException iOException) {
            throw new RuntimeException("Failed to extract resource " + string + " to " + file.getAbsolutePath(), iOException);
        }
    }

    private static boolean resourceExists(String string) {
        return LibraryUtils.class.getClassLoader().getResource(string) != null;
    }

    private static void cleanUpTempDir(File file) {
        if (!file.exists()) {
            return;
        }
        File[] fileArray = file.listFiles();
        if (fileArray != null) {
            for (File file2 : fileArray) {
                file2.deleteOnExit();
            }
        }
        file.deleteOnExit();
    }

    static boolean isAndroid() {
        String string = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String string2 = System.getProperty("java.specification.vendor", "");
        return string.contains("dalvik") || string.contains("art") || string2.equals("The Android Project");
    }
}

