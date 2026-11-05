package com.okak.taxi.net;

import com.okak.taxi.TaxiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Explored chunks (with their 4x4 colour thumbnails) for the taxi map. {@code tiles} lists map
 * tiles that are now fully known by the client (empty for live exploration updates).
 */
public record ChunkDataS2C(String dimension, long[] keys, byte[] colors, long[] tiles) implements CustomPacketPayload {
	public static final Type<ChunkDataS2C> TYPE = new Type<>(TaxiMod.id("chunk_data"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChunkDataS2C> CODEC = CustomPacketPayload.codec(ChunkDataS2C::write, ChunkDataS2C::new);

	private ChunkDataS2C(RegistryFriendlyByteBuf buf) {
		this(buf.readUtf(), buf.readLongArray(), buf.readByteArray(), buf.readLongArray());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(this.dimension);
		buf.writeLongArray(this.keys);
		buf.writeByteArray(this.colors);
		buf.writeLongArray(this.tiles);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
