package com.okak.taxi;

import com.okak.taxi.entity.TaxiEntities;
import com.okak.taxi.explore.ExplorationTracker;
import com.okak.taxi.explore.ExploredData;
import com.okak.taxi.net.TaxiNetworking;
import com.okak.taxi.service.TaxiService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaxiMod implements ModInitializer {
	public static final String MOD_ID = "taxi";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TaxiEntities.register();
		ExploredData.register();
		TaxiNetworking.registerPayloads();
		TaxiNetworking.registerServerReceivers();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ExplorationTracker.tick(server);
			TaxiService.tick(server);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			TaxiService.onDisconnect(handler.getPlayer());
			ExplorationTracker.forget(handler.getPlayer().getUUID());
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
				TaxiService.onDimensionChange(player));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TaxiService.clear();
			ExplorationTracker.clear();
		});

		LOGGER.info("OKAK Taxi is ready to drive!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
