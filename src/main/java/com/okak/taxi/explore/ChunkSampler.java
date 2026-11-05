package com.okak.taxi.explore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Builds a 4x4 map-colour thumbnail of a loaded chunk, shaded like a vanilla map. */
public final class ChunkSampler {
	private ChunkSampler() {
	}

	public static byte[] sample(ServerLevel level, int chunkX, int chunkZ) {
		byte[] out = new byte[ExploredData.SAMPLES];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = level.getMinY();

		for (int sz = 0; sz < 4; sz++) {
			for (int sx = 0; sx < 4; sx++) {
				int x = (chunkX << 4) + sx * 4 + 2;
				int z = (chunkZ << 4) + sz * 4 + 2;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				if (y < minY) {
					continue;
				}

				pos.set(x, y, z);
				BlockState state = level.getBlockState(pos);
				MapColor color = state.getMapColor(level, pos);
				MapColor.Brightness brightness;

				if (color == MapColor.WATER) {
					int depth = 0;
					while (depth < 10 && level.getFluidState(pos.set(x, y - depth - 1, z)).is(FluidTags.WATER)) {
						depth++;
					}
					brightness = depth < 2 ? MapColor.Brightness.HIGH
							: depth < 5 ? MapColor.Brightness.NORMAL
							: depth < 8 ? MapColor.Brightness.LOW
							: MapColor.Brightness.LOWEST;
				} else {
					int northY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 4) - 1;
					if (northY < minY || northY == y) {
						brightness = MapColor.Brightness.NORMAL;
					} else {
						brightness = y > northY ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
					}
				}

				out[sz * 4 + sx] = color.getPackedId(brightness);
			}
		}
		return out;
	}
}
