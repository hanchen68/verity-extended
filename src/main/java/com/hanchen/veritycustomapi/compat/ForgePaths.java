package com.hanchen.veritycustomapi.compat;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ForgePaths {
    private ForgePaths() {
    }

    public static Path config(String first, String... more) {
        return FMLPaths.CONFIGDIR.get().resolve(Paths.get(first, more)).normalize();
    }

    public static Path game(String first, String... more) {
        return FMLPaths.GAMEDIR.get().resolve(Paths.get(first, more)).normalize();
    }

    public static void migrateRelative(Path legacy, Path target) throws IOException {
        if (Files.exists(target) || !Files.exists(legacy)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.move(legacy, target);
    }
}
