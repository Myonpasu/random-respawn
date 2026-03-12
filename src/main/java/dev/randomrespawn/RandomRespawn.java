package dev.randomrespawn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData.RespawnData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RandomRespawn implements ModInitializer {
	public static final String MOD_ID = "random-respawn";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final AtomicBoolean generatingSpawn = new AtomicBoolean(false);

	// Holds a fully chunk-loaded spawn that hasn't been set as the world spawn yet.
	// Set on the first death so that all players respawn together at the new location.
	// New players join at the old spawn location.
	private static final AtomicReference<BlockPos> pendingSpawn = new AtomicReference<>(null);

	// Remembers which chunks we force-loaded so we can unforce them when a new spawn location is chosen.
	private static final List<ChunkPos> forcedSpawnChunks = new ArrayList<>();

	private static final int GENERATION_DELAY_SECONDS = 10;

	private static RandomRespawnConfig CONFIG;

	@Override
	public void onInitialize() {
		LOGGER.info("[Random Respawn]: Mod loaded.");

		CONFIG = RandomRespawnConfig.load();

		// Pre-generate a pending spawn on server start so it's ready for the first death.
		ServerLifecycleEvents.SERVER_STARTED.register(RandomRespawn::generateSpawn);

		// When the first player dies, swap in the pending spawn.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer player)) return;

			BlockPos spawn = pendingSpawn.getAndSet(null);
			if (spawn == null) return;

			MinecraftServer server = player.level().getServer();

			ServerLevel overworld = server.overworld();
			GlobalPos newGlobalSpawn = GlobalPos.of(overworld.dimension(), spawn);
			overworld.setRespawnData(new RespawnData(newGlobalSpawn, 0, 0));

			LOGGER.info("[Random Respawn]: Pending spawn applied at {}, {}, {} on first death.",
					spawn.getX(), spawn.getY(), spawn.getZ());
		});

		// When the last dead player respawns, pre-generate the next pending spawn.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {

			if (alive) return; // Ignore dimension change respawns

			MinecraftServer server = newPlayer.level().getServer();

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!player.isAlive()) return;
			}

			if (!generatingSpawn.compareAndSet(false, true)) return;

			LOGGER.info("[Random Respawn]: All players alive. Pre-generation scheduled in {} seconds.",
					GENERATION_DELAY_SECONDS);

			CompletableFuture.delayedExecutor(GENERATION_DELAY_SECONDS, TimeUnit.SECONDS)
					.execute(() -> generateSpawn(server));

		});

	}

	private static void generateSpawn(MinecraftServer server) {

		LOGGER.info("[Random Respawn]: Starting spawn generation.");

		ServerLevel overworld = server.overworld();

		findSafeSpawnAsync(overworld, 0)
				.thenCompose(spawn -> preloadSpawnChunksAsync(overworld, spawn)
						.thenApply(ignored -> spawn))
				.thenAccept(spawn -> server.execute(() -> {
                    applySpawnChunkTickets(overworld, spawn);
                    pendingSpawn.set(spawn);
                    generatingSpawn.set(false);
                    LOGGER.info("[Random Respawn]: Spawn pre-generated and pending at {}, {}, {}.",
							spawn.getX(), spawn.getY(), spawn.getZ());
                }))
				.exceptionally(e -> {
					LOGGER.error("[Random Respawn]: Spawn generation failed.", e);
					generatingSpawn.set(false);
					return null;
				});

	}

	// Recursively attempts to find a safe spawn using non-blocking chunk futures.
	private static CompletableFuture<BlockPos> findSafeSpawnAsync(ServerLevel level, int attempt) {

		if (attempt >= 1000) {
			LOGGER.warn("[Random Respawn]: Failed to find a safe random spawn, defaulting to world origin.");
			int centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CONFIG.centerX, CONFIG.centerZ);
			return CompletableFuture.completedFuture(new BlockPos(CONFIG.centerX, centerY, CONFIG.centerZ));
		}

		double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
		double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * CONFIG.radius;

		int chunkX = (CONFIG.centerX + (int) (Math.cos(angle) * distance)) >> 4;
		int chunkZ = (CONFIG.centerZ + (int) (Math.sin(angle) * distance)) >> 4;

		return level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
				.thenCompose(either -> {
					ChunkAccess chunk = either.orElse(null);

					if (chunk != null) {
						for (int i = 0; i < 8; i++) {
							int x = (chunkX << 4) + ThreadLocalRandom.current().nextInt(16);
							int z = (chunkZ << 4) + ThreadLocalRandom.current().nextInt(16);
							int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
							BlockPos pos = new BlockPos(x, y - 1, z);

							if (isSafe(level, pos)) {
								return CompletableFuture.completedFuture(pos.above());
							}
						}
					}

					return findSafeSpawnAsync(level, attempt + 1);
				});
	}

	// Check if a location is safe for a player.
	private static boolean isSafe(ServerLevel world, BlockPos pos) {

		BlockState ground = world.getBlockState(pos);
		BlockState feet = world.getBlockState(pos.above());
		BlockState head = world.getBlockState(pos.above(2));

		if (ground.isAir() || !ground.getFluidState().isEmpty()) return false;
		if (!feet.isAir() || !head.isAir()) return false;

		return true;
	}

	// Request all surrounding chunks non-blocking and return a future that resolves once all of them are fully loaded.
	private static CompletableFuture<Void> preloadSpawnChunksAsync(ServerLevel level, BlockPos pos) {

		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;

		List<CompletableFuture<?>> futures = new ArrayList<>();

		for (int dx = -CONFIG.preloadRadius; dx <= CONFIG.preloadRadius; dx++) {
			for (int dz = -CONFIG.preloadRadius; dz <= CONFIG.preloadRadius; dz++) {
				futures.add(level.getChunkSource().getChunkFuture(
						chunkX + dx, chunkZ + dz, ChunkStatus.FULL, true
				));
			}
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}

	// Apply force-load tickets to the preloaded spawn chunks.
	// Must be called on the main thread as setChunkForced mutates world state.
	private static void applySpawnChunkTickets(ServerLevel level, BlockPos pos) {

		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;

		// Release tickets on the previous spawn's chunks so they can unload.
		for (ChunkPos old : forcedSpawnChunks) {
			level.setChunkForced(old.x, old.z, false);
		}
		forcedSpawnChunks.clear();

		for (int dx = -CONFIG.preloadRadius; dx <= CONFIG.preloadRadius; dx++) {
			for (int dz = -CONFIG.preloadRadius; dz <= CONFIG.preloadRadius; dz++) {
				int cx = chunkX + dx;
				int cz = chunkZ + dz;
				level.setChunkForced(cx, cz, true);
				forcedSpawnChunks.add(new ChunkPos(cx, cz));
			}
		}
	}

}
