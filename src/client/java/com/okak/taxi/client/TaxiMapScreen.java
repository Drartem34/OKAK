package com.okak.taxi.client;

import com.okak.taxi.entity.TaxiVariant;
import com.okak.taxi.explore.ChunkKey;
import com.okak.taxi.net.SetDestinationC2S;
import com.okak.taxi.net.TaxiActionC2S;
import com.okak.taxi.net.TaxiStatusS2C;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The taxi app: a map of everywhere the player has been, with a status panel at the bottom.
 * Left click puts the destination marker, right click removes it, drag to pan, scroll to zoom.
 */
public class TaxiMapScreen extends Screen {
	private static final double[] ZOOMS = {0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0};
	private static final int TILE_BLOCKS = ChunkKey.TILE_SIZE * 16;
	private static final int PANEL_HEIGHT = 70;
	private static final int BUTTON_WIDTH = 124;

	private static final int COLOR_UNEXPLORED = 0xFF27313A;
	private static final int COLOR_FRAME = 0xFFC9A66B;
	private static final int COLOR_FRAME_DARK = 0xFF6B4F2A;
	private static final int COLOR_ROUTE = 0xFFE53935;
	private static final int COLOR_ROUTE_SHADOW = 0xFF7A1010;
	private static final int COLOR_TEXT = 0xFF1A1A2E;
	private static final int COLOR_TEXT_MUTED = 0xFF6A6A7E;
	private static final int COLOR_ERROR = 0xFFD32F2F;

	private static int zoomIndex = 3;

	private double centerX;
	private double centerZ;
	private boolean follow = true;
	private boolean pressing;
	private boolean dragged;
	private double pressX;
	private double pressY;
	private int frame;
	private Component localError = Component.empty();
	private int localErrorTicks;

	private Button actionButton;
	private Button centerButton;
	private Button clearButton;

	public TaxiMapScreen() {
		super(Component.translatable("taxi.map.title"));
	}

	@Override
	protected void init() {
		LocalPlayer player = this.minecraft.player;
		if (player != null && this.follow) {
			this.centerX = player.getX();
			this.centerZ = player.getZ();
		}
		if (this.minecraft.level != null) {
			ClientMapData.ensureDimension(this.minecraft.level.dimension().identifier().toString());
		}
		send(new TaxiActionC2S(TaxiActionC2S.SYNC));

		int top = this.height - PANEL_HEIGHT;
		int x = this.width - BUTTON_WIDTH - 8;
		this.actionButton = this.addRenderableWidget(Button.builder(Component.translatable("taxi.button.call"), b -> this.onAction())
				.bounds(x, top + 12, BUTTON_WIDTH, 20).build());
		int half = (BUTTON_WIDTH - 4) / 2;
		this.centerButton = this.addRenderableWidget(Button.builder(Component.translatable("taxi.button.center"), b -> this.follow = true)
				.bounds(x, top + 38, half, 20).build());
		this.clearButton = this.addRenderableWidget(Button.builder(Component.translatable("taxi.button.clear"),
						b -> send(new SetDestinationC2S(true, 0, 0)))
				.bounds(x + half + 4, top + 38, half, 20).build());
		this.updateButtons();
	}

	@Override
	public void tick() {
		this.updateButtons();
		if (this.localErrorTicks > 0) {
			this.localErrorTicks--;
		}
	}

	private void updateButtons() {
		TaxiStatusS2C status = ClientTaxiState.get();
		this.actionButton.setMessage(Component.translatable(status.active() ? "taxi.button.cancel" : "taxi.button.call"));
		this.clearButton.active = status.hasDestination() && status.state() != TaxiStatusS2C.RIDING;
		this.centerButton.active = !this.follow;
	}

	private void onAction() {
		send(new TaxiActionC2S(ClientTaxiState.get().active() ? TaxiActionC2S.CANCEL : TaxiActionC2S.CALL));
	}

	private static void send(CustomPacketPayload payload) {
		if (ClientPlayNetworking.canSend(payload.type())) {
			ClientPlayNetworking.send(payload);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// The map covers the whole screen; no blur/dim needed.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		LocalPlayer player = this.minecraft.player;
		if (player != null && this.follow) {
			this.centerX = Mth.lerp(a, player.xo, player.getX());
			this.centerZ = Mth.lerp(a, player.zo, player.getZ());
		}
		this.frame++;

		int mapBottom = this.height - PANEL_HEIGHT;
		this.extractMap(graphics, mapBottom, mouseX, mouseY, player);
		this.extractPanel(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	private double zoom() {
		return ZOOMS[zoomIndex];
	}

	private int screenX(double blockX) {
		return (int) Math.round(this.width / 2.0 + (blockX - this.centerX) * this.zoom());
	}

	private int screenY(double blockZ) {
		return (int) Math.round((this.height - PANEL_HEIGHT) / 2.0 + (blockZ - this.centerZ) * this.zoom());
	}

	private double blockX(double screenX) {
		return this.centerX + (screenX - this.width / 2.0) / this.zoom();
	}

	private double blockZ(double screenY) {
		return this.centerZ + (screenY - (this.height - PANEL_HEIGHT) / 2.0) / this.zoom();
	}

	private void extractMap(GuiGraphicsExtractor graphics, int mapBottom, int mouseX, int mouseY, LocalPlayer player) {
		TaxiStatusS2C status = ClientTaxiState.get();
		graphics.fill(0, 0, this.width, mapBottom, COLOR_UNEXPLORED);
		graphics.enableScissor(0, 0, this.width, mapBottom);

		int tx0 = Math.floorDiv(Mth.floor(this.blockX(0)), TILE_BLOCKS);
		int tx1 = Math.floorDiv(Mth.floor(this.blockX(this.width)), TILE_BLOCKS);
		int tz0 = Math.floorDiv(Mth.floor(this.blockZ(0)), TILE_BLOCKS);
		int tz1 = Math.floorDiv(Mth.floor(this.blockZ(mapBottom)), TILE_BLOCKS);
		int centerTileX = Math.floorDiv(Mth.floor(this.centerX), TILE_BLOCKS);
		int centerTileZ = Math.floorDiv(Mth.floor(this.centerZ), TILE_BLOCKS);
		LongArrayList wanted = new LongArrayList();

		for (int tx = tx0; tx <= tx1; tx++) {
			for (int tz = tz0; tz <= tz1; tz++) {
				long tile = ChunkKey.of(tx, tz);
				wanted.add(tile);
				Identifier texture = ClientMapData.tileTexture(tile);
				if (texture == null) {
					continue;
				}
				int x0 = this.screenX((double) tx * TILE_BLOCKS);
				int x1 = this.screenX((double) (tx + 1) * TILE_BLOCKS);
				int y0 = this.screenY((double) tz * TILE_BLOCKS);
				int y1 = this.screenY((double) (tz + 1) * TILE_BLOCKS);
				int size = ClientMapData.TILE_PIXELS;
				graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x0, y0, 0.0F, 0.0F, x1 - x0, y1 - y0, size, size, size, size);
			}
		}

		if (this.frame % 4 == 0) {
			wanted.sort((a, b) -> Integer.compare(tileDistance(a, centerTileX, centerTileZ), tileDistance(b, centerTileX, centerTileZ)));
			ClientMapData.request(wanted);
		}

		this.extractRoute(graphics, status.route());

		if (status.state() == TaxiStatusS2C.APPROACHING || status.state() == TaxiStatusS2C.WAITING) {
			int px = this.screenX(status.pickupX());
			int py = this.screenY(status.pickupZ());
			graphics.fill(px - 4, py - 4, px + 4, py + 4, 0xFFFFFFFF);
			graphics.fill(px - 3, py - 3, px + 3, py + 3, 0xFF2E7D32);
		}

		if (status.hasDestination()) {
			this.extractCross(graphics, this.screenX(status.destX() + 0.5), this.screenY(status.destZ() + 0.5));
		}

		if (status.active() && status.state() != TaxiStatusS2C.RIDING) {
			int tx = this.screenX(status.taxiX() + 0.5);
			int ty = this.screenY(status.taxiZ() + 0.5);
			graphics.fill(tx - 10, ty - 10, tx + 10, ty + 10, 0xFF1A1A2E);
			graphics.fill(tx - 9, ty - 9, tx + 9, ty + 9, 0xFFFFC107);
			graphics.item(new ItemStack(TaxiVariant.byIndex(status.variant()).item.get()), tx - 8, ty - 8);
		}

		if (player != null) {
			this.extractPlayer(graphics, player, status);
		}

		graphics.disableScissor();

		// Paper frame like a vanilla map.
		graphics.fill(0, 0, this.width, 3, COLOR_FRAME);
		graphics.fill(0, mapBottom - 3, this.width, mapBottom, COLOR_FRAME);
		graphics.fill(0, 0, 3, mapBottom, COLOR_FRAME);
		graphics.fill(this.width - 3, 0, this.width, mapBottom, COLOR_FRAME);
		graphics.outline(3, 3, this.width - 6, mapBottom - 6, COLOR_FRAME_DARK);

		if (!ClientMapData.hasAnyData()) {
			graphics.centeredText(this.font, Component.translatable("taxi.map.empty"), this.width / 2, mapBottom / 2 - 4, 0xFFFFFFFF);
		}

		Component zoomText = Component.translatable("taxi.map.zoom", (int) Math.round(16 * this.zoom()));
		graphics.text(this.font, zoomText, 8, 8, 0xFFFFFFFF, true);

		if (mouseY < mapBottom && mouseY > 3) {
			int bx = Mth.floor(this.blockX(mouseX));
			int bz = Mth.floor(this.blockZ(mouseY));
			boolean explored = ClientMapData.isExplored(bx, bz);
			Component hover = Component.translatable(explored ? "taxi.map.hover_explored" : "taxi.map.hover_unexplored", bx, bz);
			graphics.text(this.font, hover, 8, mapBottom - 14, explored ? 0xFFFFFFFF : 0xFFFF8A80, true);
		}
	}

	private static int tileDistance(long tile, int cx, int cz) {
		return Math.max(Math.abs(ChunkKey.x(tile) - cx), Math.abs(ChunkKey.z(tile) - cz));
	}

	private void extractRoute(GuiGraphicsExtractor graphics, int[] route) {
		if (route.length < 4) {
			return;
		}
		int mapBottom = this.height - PANEL_HEIGHT;
		for (int pass = 0; pass < 2; pass++) {
			int half = pass == 0 ? 2 : 1;
			int color = pass == 0 ? COLOR_ROUTE_SHADOW : COLOR_ROUTE;
			for (int i = 0; i + 3 < route.length; i += 2) {
				double x0 = this.width / 2.0 + (route[i] + 0.5 - this.centerX) * this.zoom();
				double y0 = mapBottom / 2.0 + (route[i + 1] + 0.5 - this.centerZ) * this.zoom();
				double x1 = this.width / 2.0 + (route[i + 2] + 0.5 - this.centerX) * this.zoom();
				double y1 = mapBottom / 2.0 + (route[i + 3] + 0.5 - this.centerZ) * this.zoom();
				if (Math.max(x0, x1) < -4 || Math.min(x0, x1) > this.width + 4 || Math.max(y0, y1) < -4 || Math.min(y0, y1) > mapBottom + 4) {
					continue;
				}
				double length = Math.hypot(x1 - x0, y1 - y0);
				int steps = Math.min(2000, Math.max(1, (int) Math.ceil(length)));
				for (int s = 0; s <= steps; s++) {
					double t = s / (double) steps;
					int x = (int) Math.round(x0 + (x1 - x0) * t);
					int y = (int) Math.round(y0 + (y1 - y0) * t);
					graphics.fill(x - half, y - half, x + half, y + half, color);
				}
			}
		}
	}

	private void extractCross(GuiGraphicsExtractor graphics, int x, int y) {
		for (int pass = 0; pass < 2; pass++) {
			int half = pass == 0 ? 2 : 1;
			int color = pass == 0 ? 0xFF4A0000 : 0xFFFF1744;
			for (int i = -5; i <= 5; i++) {
				graphics.fill(x + i - half, y + i - half, x + i + half, y + i + half, color);
				graphics.fill(x + i - half, y - i - half, x + i + half, y - i + half, color);
			}
		}
	}

	private void extractPlayer(GuiGraphicsExtractor graphics, LocalPlayer player, TaxiStatusS2C status) {
		int px = this.screenX(player.getX());
		int py = this.screenY(player.getZ());
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double dirX = -Mth.sin(yaw);
		double dirZ = Mth.cos(yaw);
		for (int i = 3; i <= 8; i++) {
			int x = (int) Math.round(px + dirX * i);
			int y = (int) Math.round(py + dirZ * i);
			graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xFF1A1A2E);
		}
		if (status.state() == TaxiStatusS2C.RIDING) {
			graphics.fill(px - 7, py - 7, px + 7, py + 7, 0xFF1A1A2E);
			graphics.fill(px - 6, py - 6, px + 6, py + 6, 0xFFFFC107);
		}
		graphics.fill(px - 4, py - 4, px + 4, py + 4, 0xFF1A1A2E);
		graphics.fill(px - 3, py - 3, px + 3, py + 3, 0xFFFFFFFF);
	}

	private void extractPanel(GuiGraphicsExtractor graphics) {
		TaxiStatusS2C status = ClientTaxiState.get();
		int top = this.height - PANEL_HEIGHT;
		graphics.fill(0, top, this.width, this.height, 0xFFF7F7F9);
		graphics.fill(0, top, this.width, top + 1, 0xFFD0D0D8);

		int x = 12;
		int textRight = this.width - BUTTON_WIDTH - 20;
		Component title;
		Component line2 = null;
		Component line3 = null;
		boolean showDriver = false;

		switch (status.state()) {
			case TaxiStatusS2C.APPROACHING -> {
				title = Component.translatable("taxi.panel.approaching", ClientTaxiState.time(status.etaTicks()));
				showDriver = true;
			}
			case TaxiStatusS2C.WAITING -> {
				title = Component.translatable("taxi.panel.waiting");
				showDriver = true;
				line3 = Component.translatable(status.hasDestination() ? "taxi.panel.board" : "taxi.panel.board_no_dest");
			}
			case TaxiStatusS2C.RIDING -> {
				title = Component.translatable("taxi.panel.riding", ClientTaxiState.time(status.etaTicks()));
				showDriver = true;
			}
			default -> {
				if (status.hasDestination() && status.distance() > 0) {
					title = Component.translatable("taxi.panel.trip", status.distance(), ClientTaxiState.time(status.etaTicks()));
					line2 = Component.translatable("taxi.panel.call_hint");
				} else {
					title = Component.translatable("taxi.panel.pick_destination");
					line2 = Component.translatable("taxi.panel.explored_only");
				}
			}
		}

		this.extractScaledText(graphics, title, x, top + 8, textRight - x, COLOR_TEXT);

		if (showDriver) {
			TaxiVariant variant = TaxiVariant.byIndex(status.variant());
			Component driver = Component.translatable("taxi.panel.driver", ClientTaxiState.driverName(status.driver()), ClientTaxiState.rating(status.rating10()));
			int driverY = top + 28;
			this.extractScaledText(graphics, driver, x, driverY, textRight - x - 40, COLOR_TEXT);
			int plateX = x + (int) (this.font.width(driver) * 1.25F) + 10;
			if (plateX + 70 < textRight - 40) {
				this.extractPlate(graphics, status.plate(), plateX, driverY);
			}
			graphics.text(this.font, Component.translatable("taxi.vehicle." + variant.id), x, top + 46, COLOR_TEXT_MUTED, false);
			if (line3 != null) {
				graphics.text(this.font, line3, x, top + 57, COLOR_TEXT_MUTED, false);
			}

			// Big picture of the vehicle, like in a real taxi app.
			int iconX = textRight - 36;
			if (iconX > plateX + 70 || iconX > x + 160) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(iconX, top + 22);
				graphics.pose().scale(2.0F, 2.0F);
				graphics.item(new ItemStack(variant.item.get()), 0, 0);
				graphics.pose().popMatrix();
			}
		} else if (line2 != null) {
			graphics.text(this.font, line2, x, top + 32, COLOR_TEXT_MUTED, false);
			graphics.text(this.font, Component.translatable("taxi.panel.controls"), x, top + 46, COLOR_TEXT_MUTED, false);
		}

		if (this.localErrorTicks > 0) {
			graphics.text(this.font, this.localError, x, top + 57, COLOR_ERROR, false);
		}
	}

	private void extractScaledText(GuiGraphicsExtractor graphics, Component text, int x, int y, int maxWidth, int color) {
		float scale = this.font.width(text) * 1.25F <= maxWidth ? 1.25F : 1.0F;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(this.font, text, 0, 0, color, false);
		graphics.pose().popMatrix();
	}

	private void extractPlate(GuiGraphicsExtractor graphics, String plate, int x, int y) {
		int space = plate.lastIndexOf(' ');
		String word = space > 0 ? plate.substring(0, space) : plate;
		String region = space > 0 ? plate.substring(space + 1) : "";
		int wordWidth = this.font.width(word) + 10;
		int regionWidth = this.font.width(region) + 8;
		int h = 13;
		graphics.fill(x, y - 2, x + wordWidth + regionWidth + 1, y + h - 2, 0xFF1A1A2E);
		graphics.fill(x + 1, y - 1, x + wordWidth + regionWidth, y + h - 3, 0xFFFFFFFF);
		graphics.fill(x + wordWidth, y - 1, x + wordWidth + 1, y + h - 3, 0xFF1A1A2E);
		graphics.text(this.font, word, x + 5, y + 1, COLOR_TEXT, false);
		graphics.text(this.font, region, x + wordWidth + 4, y + 1, COLOR_TEXT, false);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.y() < this.height - PANEL_HEIGHT) {
			if (event.button() == 0) {
				this.pressing = true;
				this.dragged = false;
				this.pressX = event.x();
				this.pressY = event.y();
				return true;
			}
			if (event.button() == 1) {
				send(new SetDestinationC2S(true, 0, 0));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.pressing && event.button() == 0) {
			if (!this.dragged && Math.hypot(event.x() - this.pressX, event.y() - this.pressY) > 3) {
				this.dragged = true;
			}
			if (this.dragged) {
				this.follow = false;
				this.centerX -= dx / this.zoom();
				this.centerZ -= dy / this.zoom();
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.pressing && event.button() == 0) {
			this.pressing = false;
			if (!this.dragged) {
				this.clickMap(event.x(), event.y());
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (y < this.height - PANEL_HEIGHT && scrollY != 0) {
			int next = Mth.clamp(zoomIndex + (scrollY > 0 ? 1 : -1), 0, ZOOMS.length - 1);
			if (next != zoomIndex) {
				double bx = this.blockX(x);
				double bz = this.blockZ(y);
				zoomIndex = next;
				if (!this.follow) {
					this.centerX = bx - (x - this.width / 2.0) / this.zoom();
					this.centerZ = bz - (y - (this.height - PANEL_HEIGHT) / 2.0) / this.zoom();
				}
			}
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (TaxiModClient.openMapKey.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void clickMap(double mouseX, double mouseY) {
		double bx = this.blockX(mouseX);
		double bz = this.blockZ(mouseY);
		if (!ClientMapData.isExplored(bx, bz)) {
			this.localError = Component.translatable("taxi.msg.unexplored");
			this.localErrorTicks = 60;
			return;
		}
		send(new SetDestinationC2S(false, Mth.floor(bx), Mth.floor(bz)));
	}
}
