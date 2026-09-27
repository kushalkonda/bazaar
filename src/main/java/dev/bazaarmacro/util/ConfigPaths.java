package dev.bazaarmacro.util;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * The one definition of where this mod keeps everything on disk:
 * {@code <minecraft>/config/bazaarmacro/}. Previously spelled out independently in five separate
 * classes, which meant "where does my config live" had five sources of truth that only happened to
 * agree.
 */
public final class ConfigPaths {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("bazaarmacro");

    private ConfigPaths() {
    }

    /** {@code <minecraft>/config/bazaarmacro} - not created here; writers call {@code Files.createDirectories} when they actually save. */
    public static Path dir() {
        return DIR;
    }

    /** Where {@link ErrorReporter} writes crash reports. */
    public static Path crashLogDir() {
        return DIR.resolve("crash-logs");
    }
}
