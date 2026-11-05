package com.okak.taxi.net;

import com.okak.taxi.TaxiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The player put (or removed) the destination marker on the taxi map. */
public record SetDestinationC2S(boolean clear, int x, int z) implements CustomPacketPayload {
	public static final Type<SetDestinationC2S> TYPE = new Type<>(TaxiMod.id("set_destination"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetDestinationC2S> CODEC = CustomPacketPayload.codec(SetDestinationC2S::write, SetDestinationC2S::new);

	private SetDestinationC2S(RegistryFriendlyByteBuf buf) {
		this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeBoolean(this.clear);
		buf.writeVarInt(this.x);
		buf.writeVarInt(this.z);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
