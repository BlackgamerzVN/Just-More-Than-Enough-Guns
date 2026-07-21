package com.blackgamerz.jmteg.util;

import com.google.gson.Gson;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Shared JSON config-file plumbing (resolve-config-dir / write-defaults / read) so individual
 * config managers ({@code GunConfigManager}, {@code MobAiScannerConfig},
 * {@code RecruitLoadoutConfigManager}) only need to supply their own POJO shape and
 * defaults/fallback logic instead of each re-implementing directory creation, UTF-8
 * stream handling, and IOException logging.
 */
public final class JsonConfigIO {

    private JsonConfigIO() {}

    /**
     * Resolves {@code config/<subpath>/} under the FML config directory, creating it if it
     * doesn't exist yet. Failure to create the directory is logged (not thrown) — callers will
     * simply fail to read/write the file that lives inside it.
     */
    public static File resolveConfigDir(String subpath, Logger logger) {
        File cfgDir = FMLPaths.CONFIGDIR.get().toFile();
        File modDir = new File(cfgDir, subpath);
        if (!modDir.exists() && !modDir.mkdirs()) {
            logger.warn("Failed to create config directory {}", modDir.getAbsolutePath());
        }
        return modDir;
    }

    /**
     * Writes {@code data} as pretty-printed JSON to {@code file} (UTF-8), logging a warning
     * instead of throwing on failure.
     */
    public static void writeJson(Gson gson, File file, Object data, Logger logger) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            gson.toJson(data, w);
        } catch (IOException ex) {
            logger.warn("Failed to write config to {}", file.getAbsolutePath(), ex);
        }
    }

    /**
     * Reads and deserializes {@code file} as JSON of {@code type} (UTF-8), returning
     * {@code null} (and logging a warning) if the file is missing, unreadable, or malformed.
     */
    public static <T> T readJson(Gson gson, File file, Type type, Logger logger) {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(r, type);
        } catch (IOException ex) {
            logger.warn("Failed to read config file {}", file.getAbsolutePath(), ex);
            return null;
        }
    }
}
