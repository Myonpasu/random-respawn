package dev.randomrespawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RandomRespawnConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("randomrespawn.json");

    private static final int DEFAULT_RADIUS = 10000;
    private static final int DEFAULT_PRELOAD_RADIUS = 1;

    public int centerX = 0;
    public int centerZ = 0;
    public int radius = DEFAULT_RADIUS;
    public int preloadRadius = DEFAULT_PRELOAD_RADIUS;

    public static RandomRespawnConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                RandomRespawnConfig config = GSON.fromJson(Files.readString(CONFIG_PATH), RandomRespawnConfig.class);
                config.applyDefaults();
                Files.writeString(CONFIG_PATH, GSON.toJson(config));
                return config;
            } catch (IOException e) {
                RandomRespawn.LOGGER.error("[Random Respawn]: Failed to read config file, using defaults.", e);
            }
        }

        RandomRespawnConfig config = new RandomRespawnConfig();

        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(config));
        } catch (IOException e) {
            RandomRespawn.LOGGER.error("[Random Respawn]: Failed to write config file.", e);
        }

        return config;
    }

    private void applyDefaults() {
        if (radius <= 0) radius = DEFAULT_RADIUS;
        if (preloadRadius < 0) preloadRadius = DEFAULT_PRELOAD_RADIUS;
    }

}
