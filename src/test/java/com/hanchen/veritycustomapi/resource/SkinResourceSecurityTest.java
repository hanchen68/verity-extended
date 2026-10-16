package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.config.VoiceUiPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SkinResourceSecurityTest {
    private static final List<String> VARIANTS = List.of("crazy", "crazy_talking", "evil", "evil_talking",
            "happy", "happy_sleep", "happy_talking", "hurt", "neutral", "neutral_talking", "noface",
            "serious_1", "serious_2", "serious_3", "serious_talking", "smiling_evil");

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("verity-resource-security-");
        try {
            expectValid(create(root.resolve("valid.zip"), null, null, png(64, 64)));
            expectInvalid(create(root.resolve("dotdot.zip"), "../neutral.png", null, png(64, 64)));
            expectInvalid(create(root.resolve("backslash.zip"), "..\\neutral.png", null, png(64, 64)));
            expectInvalid(create(root.resolve("absolute.zip"), "/neutral.png", null, png(64, 64)));
            expectInvalid(create(root.resolve("drive.zip"), "C:/neutral.png", null, png(64, 64)));
            expectInvalid(create(root.resolve("duplicate.zip"), null, "nested/neutral.png", png(64, 64)));
            expectInvalid(create(root.resolve("unknown.zip"), null, "unknown.png", png(64, 64)));
            expectInvalid(create(root.resolve("fake-png.zip"), null, null, new byte[24]));
            expectInvalid(create(root.resolve("bad-size.zip"), null, null, png(8, 64)));
            verifyPairingFallbackPolicy();
            verifyAndroidVoiceUiPolicy();
            System.out.println("Resource ZIP security tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static Path create(Path target, String neutralName, String extraName, byte[] content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String variant : VARIANTS) {
                String name = "neutral".equals(variant) && neutralName != null ? neutralName : variant + ".png";
                write(output, name, content);
            }
            if (extraName != null) write(output, extraName, content);
        }
        return target;
    }

    private static void write(ZipOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static byte[] png(int width, int height) {
        byte[] value = new byte[24];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, value, 0, signature.length);
        putInt(value, 16, width);
        putInt(value, 20, height);
        return value;
    }

    private static void putInt(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    private static void expectValid(Path archive) throws Exception {
        SkinResourceManager.validate(archive);
    }

    private static void expectInvalid(Path archive) throws Exception {
        try {
            SkinResourceManager.validate(archive);
            throw new AssertionError("Expected invalid archive: " + archive.getFileName());
        } catch (ResourceException expected) {
        }
    }

    private static void verifyPairingFallbackPolicy() {
        ResourceCacheIndex.Entry privateSkin = entry("skin", "private-skin", true, false, false);
        ResourceCacheIndex.Entry publicSkin = entry("skin", "public-skin", true, true, false);
        ResourceCacheIndex.Entry privateVoice = entry("voice", "private-voice", true, false, false);
        require(PairingResourceFallback.isPrivate(privateSkin), "private resource not detected");
        require(!PairingResourceFallback.isPrivate(publicSkin), "public resource treated as private");
        require(PairingResourceFallback.isSelectedSkin("account", "account:private-skin", privateSkin), "selected skin not detected");
        require(PairingResourceFallback.isSelectedVoice("private-voice", privateVoice), "selected voice not detected");
    }

    private static void verifyAndroidVoiceUiPolicy() {
        require(!VoiceUiPolicy.offlineEntriesEnabled(true), "Android offline entries enabled");
        require("api".equals(VoiceUiPolicy.recognitionMode(true, "local")), "Android STT mode not normalized");
        require("online".equals(VoiceUiPolicy.ttsMode(true, "local")), "Android TTS mode not normalized");
        require("local".equals(VoiceUiPolicy.recognitionMode(false, "local")), "desktop STT mode changed");
    }

    private static ResourceCacheIndex.Entry entry(String type, String id, boolean owner, boolean publicResource, boolean platform) {
        return new ResourceCacheIndex.Entry(type, id, id, id, "", 0, 0, owner, publicResource, platform);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path value : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(value);
        }
    }
}
