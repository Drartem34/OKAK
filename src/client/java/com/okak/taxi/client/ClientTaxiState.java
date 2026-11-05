package com.okak.taxi.client;

import com.okak.taxi.net.TaxiStatusS2C;
import net.minecraft.network.chat.Component;

/** Latest taxi status received from the server. */
public final class ClientTaxiState {
	private static TaxiStatusS2C status = TaxiStatusS2C.empty();

	private ClientTaxiState() {
	}

	public static TaxiStatusS2C get() {
		return status;
	}

	public static void update(TaxiStatusS2C newStatus) {
		status = newStatus;
	}

	public static void reset() {
		status = TaxiStatusS2C.empty();
	}

	public static Component time(int ticks) {
		int seconds = Math.max(1, (int) Math.ceil(ticks / 20.0));
		if (seconds < 60) {
			return Component.translatable("taxi.time.seconds", seconds);
		}
		return Component.translatable("taxi.time.minutes", seconds / 60, seconds % 60);
	}

	public static Component driverName(int driver) {
		return Component.translatable("taxi.driver." + driver);
	}

	public static String rating(int rating10) {
		return (rating10 / 10) + "." + (rating10 % 10);
	}
}
