package com.okak.taxi.net;

import com.okak.taxi.TaxiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A button press on the taxi map. */
public record TaxiActionC2S(int action) implements CustomPacketPayload {
	public static final int CALL = 0;
	public static final int CANCEL = 1;
	public static final int SYNC = 2;

	public static final Type<TaxiActionC2S> TYPE = new Type<>(TaxiMod.id("taxi_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TaxiActionC2S> CODEC = CustomPacketPayload.codec(TaxiActionC2S::write, TaxiActionC2S::new);

	private TaxiActionC2S(RegistryFriendlyByteBuf buf) {
		this(buf.readVarInt());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.action);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
