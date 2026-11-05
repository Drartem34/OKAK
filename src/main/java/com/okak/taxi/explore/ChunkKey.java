package com.okak.taxi.explore;

/** Packs chunk / tile coordinates into a single long. */
public final class ChunkKey {
	/** A tile is a square of TILE_SIZE x TILE_SIZE chunks, used to stream map data to the client. */
	public static final int TILE_SHIFT = 5;
	public static final int TILE_SIZE = 1 << TILE_SHIFT;

	private ChunkKey() {
	}

	public static long of(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int x(long key) {
		return (int) (key >> 32);
	}

	public static int z(long key) {
		return (int) key;
	}

	public static long ofBlock(double blockX, double blockZ) {
		return of((int) Math.floor(blockX) >> 4, (int) Math.floor(blockZ) >> 4);
	}

	public static long tileOf(long chunkKey) {
		return of(x(chunkKey) >> TILE_SHIFT, z(chunkKey) >> TILE_SHIFT);
	}
}
