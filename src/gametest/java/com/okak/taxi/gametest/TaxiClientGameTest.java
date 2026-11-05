package com.okak.taxi.gametest;

import com.okak.taxi.client.ClientMapData;
import com.okak.taxi.client.ClientTaxiState;
import com.okak.taxi.client.TaxiMapScreen;
import com.okak.taxi.entity.TaxiEntity;
import com.okak.taxi.explore.ExplorationTracker;
import com.okak.taxi.explore.ExploredData;
import com.okak.taxi.net.TaxiStatusS2C;
import com.okak.taxi.service.TaxiService;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * End-to-end test: explore a strip of land, open the taxi map, put a marker, call a taxi,
 * get in and ride to the marker. Screenshots of every step end up in run/clientGameTest/screenshots.
 */
public class TaxiClientGameTest implements FabricClientGameTest {
	private static final int STEPS = 14;
	private static final int STEP_BLOCKS = 32;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> {
					settings.setSeed("okak-taxi");
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					settings.setAllowCommands(true);
				})
				.create()) {
			TestServerContext server = singleplayer.getServer();
			singleplayer.getConnection().waitForChunksRender();
			server.runCommand("time set noon");
			server.runCommand("weather clear");

			int[] start = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				return new int[]{p.getBlockX(), p.getBlockZ()};
			});

			// 1. Explore a wiggly strip of land to the east.
			int[][] path = new int[STEPS + 1][];
			for (int i = 0; i <= STEPS; i++) {
				int x = start[0] + i * STEP_BLOCKS;
				int z = start[1] + (int) Math.round(Math.sin(i / 2.0) * 64);
				path[i] = new int[]{x, z};
				server.runCommand(String.format(Locale.ROOT, "tp @p %d 160 %d", x, z));
				context.waitTicks(25);
			}
			int exploredChunks = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				return ExploredData.of(p).dimension(ExplorationTracker.dimensionId(p.level())).size();
			});
			check(exploredChunks > 100, "expected the strip to be explored, got " + exploredChunks + " chunks");

			// Back to the start, standing on the ground.
			server.runCommand(String.format(Locale.ROOT, "tp @p %d 160 %d", start[0], start[1]));
			context.waitTicks(10);
			server.runCommand("execute as @p at @s positioned over motion_blocking run tp @s ~ ~ ~");
			singleplayer.getConnection().waitForChunksRender();

			// 2. Open the map.
			context.setScreen(TaxiMapScreen::new);
			context.waitFor(mc -> ClientMapData.hasAnyData(), 200);
			context.waitTicks(30);
			context.takeScreenshot("01-taxi-map");

			// 3. Clicking somewhere unexplored must be rejected.
			int[] far = {start[0] - 3000, start[1] - 3000};
			server.runOnServer(s -> TaxiService.setDestination(player(s), false, far[0], far[1]));
			context.waitTicks(10);
			check(!ClientTaxiState.get().hasDestination(), "unexplored destination must be rejected");

			// 4. Put the marker at the far end of the explored strip.
			int[] dest = path[STEPS];
			server.runOnServer(s -> TaxiService.setDestination(player(s), false, dest[0], dest[1]));
			context.waitFor(mc -> ClientTaxiState.get().hasDestination() && ClientTaxiState.get().route().length > 0, 100);
			context.waitTicks(10);
			context.takeScreenshot("02-destination");

			// 5. Call the taxi.
			server.runOnServer(s -> TaxiService.call(player(s)));
			context.waitFor(mc -> ClientTaxiState.get().state() == TaxiStatusS2C.APPROACHING, 100);
			context.waitTicks(40);
			context.takeScreenshot("03-approaching");
			context.setScreen(() -> null);

			context.waitFor(mc -> ClientTaxiState.get().state() == TaxiStatusS2C.WAITING, 20 * 180);
			context.waitTicks(20);
			// Look at the taxi for a picture.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				TaxiEntity taxi = findTaxi(p);
				check(taxi != null, "taxi entity should be spawned when it arrives");
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), String.format(Locale.ROOT,
						"tp %s ~ ~ ~ facing %.2f %.2f %.2f", p.getScoreboardName(), taxi.getX(), taxi.getY() + 0.4, taxi.getZ()));
			});
			context.waitTicks(10);
			context.takeScreenshot("04-taxi-arrived");

			// 6. Get in - the trip starts automatically since a marker is set.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				TaxiEntity taxi = findTaxi(p);
				check(taxi != null && TaxiService.onInteract(taxi, p), "boarding should succeed");
				check(p.getVehicle() == taxi, "player should sit in the taxi");
			});
			context.waitFor(mc -> ClientTaxiState.get().state() == TaxiStatusS2C.RIDING, 100);
			context.waitTicks(60);
			context.takeScreenshot("05-riding");
			context.setScreen(TaxiMapScreen::new);
			context.waitTicks(20);
			context.takeScreenshot("06-riding-map");
			context.setScreen(() -> null);

			// 7. Arrive.
			context.waitFor(mc -> ClientTaxiState.get().state() != TaxiStatusS2C.RIDING, 20 * 240);
			context.waitTicks(5);
			double distance = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				check(!p.isPassenger(), "player should be dropped off");
				return Math.hypot(p.getX() - (dest[0] + 0.5), p.getZ() - (dest[1] + 0.5));
			});
			check(distance < 6, "player should arrive at the marker, but is " + distance + " blocks away");
			context.waitTicks(10);
			context.takeScreenshot("07-arrived");

			context.waitFor(mc -> !ClientTaxiState.get().active(), 200);
			context.waitTicks(60);
			boolean taxiGone = server.computeOnServer(s -> findTaxi(player(s)) == null);
			check(taxiGone, "taxi should drive away after the trip");
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static TaxiEntity findTaxi(ServerPlayer player) {
		List<TaxiEntity> taxis = player.level().getEntitiesOfClass(TaxiEntity.class, player.getBoundingBox().inflate(96));
		return taxis.isEmpty() ? null : taxis.getFirst();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
