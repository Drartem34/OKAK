package com.okak.taxi.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.okak.taxi.TaxiMod;
import com.okak.taxi.explore.ChunkKey;
import com.okak.taxi.explore.ExploredData;
import com.okak.taxi.net.ChunkDataS2C;
import com.okak.taxi.net.RequestTilesC2S;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * Client-side cache of the player's explored chunks. Map data is streamed from the server in
 * tiles of 32x32 chunks and painted into one 128x128 texture per tile (4 pixels per chunk).
 */
public final class ClientMapData {
	public static final int TILE_PIXELS = ChunkKey.TILE_SIZE * 4;
	/** Explored, but nothing with a map colour on top (e.g. glass). */
	private static final int UNKNOWN_COLOR = 0xFF8E8E8E;

	private static String dimension = "";
	private static final Long2ObjectOpenHashMap<byte[]> CHUNKS = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet LOADED_TILES = new LongOpenHashSet();
	private static final LongOpenHashSet REQUESTED_TILES = new LongOpenHashSet();
	private static final LongOpenHashSet DIRTY_TILES = new LongOpenHashSet();
	private static final Long2ObjectOpenHashMap<Tile> TEXTURES = new Long2ObjectOpenHashMap<>();

	private record Tile(Identifier id, DynamicTexture texture) {
	}

	private ClientMapData() {
	}

	public static void accept(ChunkDataS2C payload) {
		if (!payload.dimension().equals(dimension)) {
			reset(payload.dimension());
		}
		long[] keys = payload.keys();
		byte[] colors = payload.colors();
		for (int i = 0; i < keys.length; i++) {
			byte[] c = new byte[ExploredData.SAMPLES];
			int offset = i * ExploredData.SAMPLES;
			if (offset + ExploredData.SAMPLES <= colors.length) {
				System.arraycopy(colors, offset, c, 0, ExploredData.SAMPLES);
			}
			CHUNKS.put(keys[i], c);
			DIRTY_TILES.add(ChunkKey.tileOf(keys[i]));
		}
		for (long tile : payload.tiles()) {
			LOADED_TILES.add(tile);
			REQUESTED_TILES.remove(tile);
			DIRTY_TILES.add(tile);
		}
	}

	/** Makes sure the cache belongs to the dimension the player is in. */
	public static void ensureDimension(String dim) {
		if (!dim.equals(dimension)) {
			reset(dim);
		}
	}

	public static boolean isExplored(double blockX, double blockZ) {
		return CHUNKS.containsKey(ChunkKey.ofBlock(blockX, blockZ));
	}

	public static boolean hasAnyData() {
		return !CHUNKS.isEmpty();
	}

	/** Asks the server for the given tiles (nearest first) that we don't have yet. */
	public static void request(LongArrayList wantedTiles) {
		if (!ClientPlayNetworking.canSend(RequestTilesC2S.TYPE)) {
			return;
		}
		LongArrayList toSend = new LongArrayList();
		for (int i = 0; i < wantedTiles.size() && toSend.size() < RequestTilesC2S.MAX_TILES; i++) {
			long tile = wantedTiles.getLong(i);
			if (!LOADED_TILES.contains(tile) && !REQUESTED_TILES.contains(tile)) {
				toSend.add(tile);
				REQUESTED_TILES.add(tile);
			}
		}
		if (!toSend.isEmpty()) {
			ClientPlayNetworking.send(new RequestTilesC2S(toSend.toLongArray()));
		}
	}

	/** Forget which tiles were requested so that reopening the map refreshes everything. */
	public static void invalidateRequests() {
		LOADED_TILES.clear();
		REQUESTED_TILES.clear();
	}

	/** Returns the texture of a tile, or null if nothing in it is explored. Must be called on the render thread. */
	public static @Nullable Identifier tileTexture(long tile) {
		Tile existing = TEXTURES.get(tile);
		if (existing != null && !DIRTY_TILES.contains(tile)) {
			return existing.id();
		}
		if (existing == null && !DIRTY_TILES.contains(tile)) {
			return null;
		}
		DIRTY_TILES.remove(tile);

		int baseX = ChunkKey.x(tile) << ChunkKey.TILE_SHIFT;
		int baseZ = ChunkKey.z(tile) << ChunkKey.TILE_SHIFT;
		boolean any = false;
		for (int dx = 0; dx < ChunkKey.TILE_SIZE && !any; dx++) {
			for (int dz = 0; dz < ChunkKey.TILE_SIZE; dz++) {
				if (CHUNKS.containsKey(ChunkKey.of(baseX + dx, baseZ + dz))) {
					any = true;
					break;
				}
			}
		}
		if (!any) {
			if (existing != null) {
				release(tile, existing);
			}
			return null;
		}

		Tile target = existing;
		if (target == null) {
			Identifier id = TaxiMod.id(String.format(Locale.ROOT, "map_tile/%d_%d", ChunkKey.x(tile), ChunkKey.z(tile)).replace('-', 'n'));
			DynamicTexture texture = new DynamicTexture(() -> "Taxi map tile " + id, TILE_PIXELS, TILE_PIXELS, true);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			target = new Tile(id, texture);
			TEXTURES.put(tile, target);
		}

		NativeImage pixels = target.texture().getPixels();
		for (int dx = 0; dx < ChunkKey.TILE_SIZE; dx++) {
			for (int dz = 0; dz < ChunkKey.TILE_SIZE; dz++) {
				byte[] colors = CHUNKS.get(ChunkKey.of(baseX + dx, baseZ + dz));
				for (int s = 0; s < ExploredData.SAMPLES; s++) {
					int px = dx * 4 + (s & 3);
					int pz = dz * 4 + (s >> 2);
					int argb;
					if (colors == null) {
						argb = 0;
					} else {
						int packed = colors[s] & 0xFF;
						argb = (packed >> 2) == 0 ? UNKNOWN_COLOR : MapColor.getColorFromPackedId(packed);
					}
					pixels.setPixel(px, pz, argb);
				}
			}
		}
		target.texture().upload();
		return target.id();
	}

	public static void clear() {
		reset("");
	}

	private static void reset(String dim) {
		dimension = dim;
		CHUNKS.clear();
		LOADED_TILES.clear();
		REQUESTED_TILES.clear();
		DIRTY_TILES.clear();
		for (Long2ObjectMap.Entry<Tile> entry : TEXTURES.long2ObjectEntrySet()) {
			Minecraft.getInstance().getTextureManager().release(entry.getValue().id());
		}
		TEXTURES.clear();
	}

	private static void release(long tile, Tile existing) {
		Minecraft.getInstance().getTextureManager().release(existing.id());
		TEXTURES.remove(tile);
	}
}
