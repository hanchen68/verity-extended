package com.hanchen.veritycustomapi.resource;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class MmdArchiveEncodingTest {
    private MmdArchiveEncodingTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            MmdResourceManager.validateArchive(Path.of(args[0]));
            System.out.println("Real MMD archive encoding validation passed");
            return;
        }
        Path root = Files.createTempDirectory("verity-mmd-encoding-");
        Path archive = root.resolve("legacy-names.zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive), Charset.forName("GB18030"))) {
                write(output, "motion.vmd", new byte[]{1, 2, 3, 4});
                write(output, "极乐净土（MARiA位）.mp3", new byte[]{5, 6, 7, 8});
            }
            boolean defaultRejected = false;
            try (ZipFile ignored = new ZipFile(archive.toFile())) {
            } catch (java.util.zip.ZipException expected) {
                defaultRejected = expected.getMessage() != null && expected.getMessage().contains("bad entry name");
            }
            require(defaultRejected, "test ZIP did not reproduce legacy filename failure");
            MmdResourceManager.validateArchive(archive);
            System.out.println("MMD legacy ZIP filename encoding test passed");
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(root);
        }
    }

    private static void write(ZipOutputStream output, String name, byte[] content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
