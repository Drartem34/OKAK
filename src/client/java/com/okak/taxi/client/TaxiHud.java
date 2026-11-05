package com.okak.taxi.client;

import com.okak.taxi.net.TaxiStatusS2C;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Small status card in the top right corner while a taxi order is active. */
public final class TaxiHud {
	private TaxiHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		TaxiStatusS2C status = ClientTaxiState.get();
		if (!status.active() || mc.screen != null) {
			return;
		}

		Component title = switch (status.state()) {
			case TaxiStatusS2C.APPROACHING -> Component.translatable("taxi.hud.approaching", ClientTaxiState.time(status.etaTicks()));
			case TaxiStatusS2C.WAITING -> Component.translatable("taxi.hud.waiting");
			default -> Component.translatable("taxi.hud.riding", ClientTaxiState.time(status.etaTicks()));
		};
		Component subtitle = Component.translatable("taxi.hud.driver", ClientTaxiState.driverName(status.driver()),
				ClientTaxiState.rating(status.rating10()), status.plate());

		Font font = mc.font;
		int width = Math.max(font.width(title), font.width(subtitle)) + 12;
		int x = graphics.guiWidth() - width - 6;
		int y = 6;
		graphics.fill(x, y, x + width, y + 26, 0xE0FFFFFF);
		graphics.fill(x, y, x + 3, y + 26, 0xFF000000 | com.okak.taxi.service.TaxiClass.color(status.rating10()));
		graphics.text(font, title, x + 7, y + 4, 0xFF1A1A2E, false);
		graphics.text(font, subtitle, x + 7, y + 15, 0xFF5A5A6E, false);
	}
}
