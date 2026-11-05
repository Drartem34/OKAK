package com.okak.taxi.explore;

import com.okak.taxi.net.ChunkDataS2C;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Remembers where every player has been. Chunks within {@link #RADIUS} of a player count as
 * explored; only explored chunks can be used as a taxi destination or route.
 */
public final class ExplorationTracker {
	public static final int RADIUS = 2;
	private static final int REFRESH_INTERVAL = 100;

	private static final Map<UUID, Long> LAST_CHUNK = new HashMap<>();
	private static final Map<UUID, String> LAST_DIMENSION = new HashMap<>();

	private ExplorationTracker() {
	}

	public static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}

	/** The taxi can't work under a bedrock roof (the Nether) - there's no sky to navigate by. */
	public static boolean isSupported(Level level) {
		return !level.dimensionType().hasCeiling();
	}

	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 5 != 0) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator()) {
				continue;
			}
			ServerLevel level = player.level();
			if (!isSupported(level)) {
				continue;
			}

			String dim = dimensionId(level);
			int cx = player.getBlockX() >> 4;
			int cz = player.getBlockZ() >> 4;
			long key = ChunkKey.of(cx, cz);
			UUID uuid = player.getUUID();
			Long previous = LAST_CHUNK.get(uuid);
			boolean sameChunk = previous != null && previous == key && dim.equals(LAST_DIMENSION.get(uuid));
			if (sameChunk && tick % REFRESH_INTERVAL != 0) {
				continue;
			}

			LAST_CHUNK.put(uuid, key);
			LAST_DIMENSION.put(uuid, dim);
			markAround(player, level, cx, cz);
		}
	}

	/** Marks the chunks around the given chunk as explored and pushes changes to the client. */
	public static void markAround(ServerPlayer player, ServerLevel level, int cx, int cz) {
		String dim = dimensionId(level);
		Long2ObjectOpenHashMap<byte[]> explored = ExploredData.of(player).dimension(dim);
		int size = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);
		long[] changedKeys = new long[size];
		byte[] changedColors = new byte[size * ExploredData.SAMPLES];
		int changed = 0;

		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				int x = cx + dx;
				int z = cz + dz;
				if (!level.hasChunk(x, z)) {
					continue;
				}
				long key = ChunkKey.of(x, z);
				byte[] colors = ChunkSampler.sample(level, x, z);
				byte[] old = explored.get(key);
				if (old == null || !Arrays.equals(old, colors)) {
					explored.put(key, colors);
					changedKeys[changed] = key;
					System.arraycopy(colors, 0, changedColors, changed * ExploredData.SAMPLES, ExploredData.SAMPLES);
					changed++;
				}
			}
		}

		if (changed > 0 && ServerPlayNetworking.canSend(player, ChunkDataS2C.TYPE)) {
			ServerPlayNetworking.send(player, new ChunkDataS2C(
					dim,
					Arrays.copyOf(changedKeys, changed),
					Arrays.copyOf(changedColors, changed * ExploredData.SAMPLES),
					new long[0]));
		}
	}

	/** Sends every explored chunk inside the requested map tile to the player. */
	public static void sendTile(ServerPlayer player, long tileKey) {
		String dim = dimensionId(player.level());
		Long2ObjectOpenHashMap<byte[]> explored = ExploredData.of(player).dimensionOrNull(dim);
		int baseX = ChunkKey.x(tileKey) << ChunkKey.TILE_SHIFT;
		int baseZ = ChunkKey.z(tileKey) << ChunkKey.TILE_SHIFT;
		long[] keys = new long[ChunkKey.TILE_SIZE * ChunkKey.TILE_SIZE];
		byte[] colors = new byte[keys.length * ExploredData.SAMPLES];
		int count = 0;

		if (explored != null && !explored.isEmpty()) {
			for (int dx = 0; dx < ChunkKey.TILE_SIZE; dx++) {
				for (int dz = 0; dz < ChunkKey.TILE_SIZE; dz++) {
					long key = ChunkKey.of(baseX + dx, baseZ + dz);
					byte[] c = explored.get(key);
					if (c != null) {
						keys[count] = key;
						System.arraycopy(c, 0, colors, count * ExploredData.SAMPLES, ExploredData.SAMPLES);
						count++;
					}
				}
			}
		}

		ServerPlayNetworking.send(player, new ChunkDataS2C(
				dim,
				Arrays.copyOf(keys, count),
				Arrays.copyOf(colors, count * ExploredData.SAMPLES),
				new long[]{tileKey}));
	}

	public static void forget(UUID uuid) {
		LAST_CHUNK.remove(uuid);
		LAST_DIMENSION.remove(uuid);
	}

	public static void clear() {
		LAST_CHUNK.clear();
		LAST_DIMENSION.clear();
	}
}
