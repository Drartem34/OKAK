package com.okak.taxi.explore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.okak.taxi.TaxiMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Per-player memory of every chunk the player has been to, per dimension. Each chunk stores
 * {@link #SAMPLES} packed map-colour bytes (a 4x4 thumbnail) used to draw the taxi map.
 */
public final class ExploredData {
	public static final int SAMPLES = 16;

	private static final Codec<Packed> PACKED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG_STREAM.fieldOf("chunks").forGetter(p -> Arrays.stream(p.keys())),
			Codec.BYTE_BUFFER.fieldOf("colors").forGetter(p -> ByteBuffer.wrap(p.colors()))
	).apply(instance, (keys, colors) -> new Packed(keys.toArray(), toBytes(colors))));

	public static final Codec<ExploredData> CODEC = Codec.unboundedMap(Codec.STRING, PACKED_CODEC)
			.xmap(ExploredData::fromPacked, ExploredData::toPacked);

	public static AttachmentType<ExploredData> TYPE;

	private final Map<String, Long2ObjectOpenHashMap<byte[]>> dimensions = new HashMap<>();

	public static void register() {
		TYPE = AttachmentRegistry.create(TaxiMod.id("explored"), builder -> builder
				.persistent(CODEC)
				.copyOnDeath()
				.initializer(ExploredData::new));
	}

	public static ExploredData of(ServerPlayer player) {
		return player.getAttachedOrCreate(TYPE);
	}

	public Long2ObjectOpenHashMap<byte[]> dimension(String id) {
		return this.dimensions.computeIfAbsent(id, k -> new Long2ObjectOpenHashMap<>());
	}

	public @Nullable Long2ObjectOpenHashMap<byte[]> dimensionOrNull(String id) {
		return this.dimensions.get(id);
	}

	private record Packed(long[] keys, byte[] colors) {
	}

	private static byte[] toBytes(ByteBuffer buffer) {
		ByteBuffer copy = buffer.duplicate();
		byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}

	private static ExploredData fromPacked(Map<String, Packed> packed) {
		ExploredData data = new ExploredData();
		packed.forEach((dim, p) -> {
			Long2ObjectOpenHashMap<byte[]> map = data.dimension(dim);
			for (int i = 0; i < p.keys().length; i++) {
				byte[] colors = new byte[SAMPLES];
				int offset = i * SAMPLES;
				if (offset + SAMPLES <= p.colors().length) {
					System.arraycopy(p.colors(), offset, colors, 0, SAMPLES);
				}
				map.put(p.keys()[i], colors);
			}
		});
		return data;
	}

	private static Map<String, Packed> toPacked(ExploredData data) {
		Map<String, Packed> result = new HashMap<>();
		data.dimensions.forEach((dim, map) -> {
			long[] keys = new long[map.size()];
			byte[] colors = new byte[map.size() * SAMPLES];
			int i = 0;
			for (Long2ObjectMap.Entry<byte[]> entry : map.long2ObjectEntrySet()) {
				keys[i] = entry.getLongKey();
				System.arraycopy(entry.getValue(), 0, colors, i * SAMPLES, SAMPLES);
				i++;
			}
			result.put(dim, new Packed(keys, colors));
		});
		return result;
	}
}
