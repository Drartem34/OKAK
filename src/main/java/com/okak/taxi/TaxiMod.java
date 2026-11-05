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
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.okak.taxi.entity.TaxiEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

		// The boat sits low: grass, flowers or snow often cover it. Clicking right next to a
		// waiting (glowing) taxi counts as clicking the taxi itself.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || player.isPassenger() || player.isSecondaryUseActive()) {
				return InteractionResult.PASS;
			}
			Vec3 location = hit.getLocation();
			boolean taxiNearby = level.getEntitiesOfClass(TaxiEntity.class, new AABB(location, location).inflate(2.5), TaxiEntity::isCurrentlyGlowing).size() > 0;
			if (!taxiNearby) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide()) {
				return InteractionResult.SUCCESS;
			}
			return player instanceof ServerPlayer serverPlayer && TaxiService.tryBoardNear(serverPlayer, location)
					? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

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
