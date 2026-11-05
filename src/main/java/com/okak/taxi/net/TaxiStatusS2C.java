package com.okak.taxi.net;

import com.okak.taxi.TaxiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Everything the taxi map needs to show about the current order / planned trip. */
public record TaxiStatusS2C(
		int state,
		boolean hasDestination,
		int destX,
		int destZ,
		int taxiX,
		int taxiZ,
		int pickupX,
		int pickupZ,
		int etaTicks,
		int distance,
		int driver,
		int rating10,
		String plate,
		int variant,
		int[] route
) implements CustomPacketPayload {
	public static final int NONE = 0;
	public static final int APPROACHING = 1;
	public static final int WAITING = 2;
	public static final int RIDING = 3;
	public static final int FINISHED = 4;

	public static final Type<TaxiStatusS2C> TYPE = new Type<>(TaxiMod.id("taxi_status"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TaxiStatusS2C> CODEC = CustomPacketPayload.codec(TaxiStatusS2C::write, TaxiStatusS2C::new);

	public static TaxiStatusS2C empty() {
		return new TaxiStatusS2C(NONE, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 50, "", 0, new int[0]);
	}

	private TaxiStatusS2C(RegistryFriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readUtf(32), buf.readVarInt(), buf.readVarIntArray());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.state);
		buf.writeBoolean(this.hasDestination);
		buf.writeVarInt(this.destX);
		buf.writeVarInt(this.destZ);
		buf.writeVarInt(this.taxiX);
		buf.writeVarInt(this.taxiZ);
		buf.writeVarInt(this.pickupX);
		buf.writeVarInt(this.pickupZ);
		buf.writeVarInt(this.etaTicks);
		buf.writeVarInt(this.distance);
		buf.writeVarInt(this.driver);
		buf.writeVarInt(this.rating10);
		buf.writeUtf(this.plate, 32);
		buf.writeVarInt(this.variant);
		buf.writeVarIntArray(this.route);
	}

	public boolean active() {
		return this.state == APPROACHING || this.state == WAITING || this.state == RIDING;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
