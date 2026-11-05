package com.okak.taxi.net;

import com.okak.taxi.TaxiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The client asks for explored-chunk data of some map tiles (32x32 chunks each). */
public record RequestTilesC2S(long[] tiles) implements CustomPacketPayload {
	public static final int MAX_TILES = 16;
	public static final Type<RequestTilesC2S> TYPE = new Type<>(TaxiMod.id("request_tiles"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RequestTilesC2S> CODEC = CustomPacketPayload.codec(RequestTilesC2S::write, RequestTilesC2S::new);

	private RequestTilesC2S(RegistryFriendlyByteBuf buf) {
		this(buf.readLongArray());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeLongArray(this.tiles);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
