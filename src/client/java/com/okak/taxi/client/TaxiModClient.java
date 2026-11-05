package com.okak.taxi.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.okak.taxi.TaxiMod;
import com.okak.taxi.entity.TaxiEntities;
import com.okak.taxi.entity.TaxiVariant;
import com.okak.taxi.net.ChunkDataS2C;
import com.okak.taxi.net.TaxiStatusS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.RaftRenderer;

public class TaxiModClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(TaxiMod.id("taxi"));
	public static KeyMapping openMapKey;

	@Override
	public void onInitializeClient() {
		for (TaxiVariant variant : TaxiVariant.values()) {
			ModelLayerLocation layer = modelLayer(variant);
			if (variant.raft) {
				EntityRendererRegistry.register(TaxiEntities.TYPES.get(variant), context -> new RaftRenderer(context, layer));
			} else {
				EntityRendererRegistry.register(TaxiEntities.TYPES.get(variant), context -> new BoatRenderer(context, layer));
			}
		}

		openMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.taxi.open_map", InputConstants.KEY_Y, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(ChunkDataS2C.TYPE, (payload, context) -> ClientMapData.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(TaxiStatusS2C.TYPE, (payload, context) -> ClientTaxiState.update(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientMapData.clear();
			ClientTaxiState.reset();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMapKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new TaxiMapScreen());
				}
			}
		});

		HudElementRegistry.addLast(TaxiMod.id("taxi_status"), TaxiHud::extract);
	}

	private static ModelLayerLocation modelLayer(TaxiVariant variant) {
		return switch (variant) {
			case OAK -> ModelLayers.OAK_BOAT;
			case SPRUCE -> ModelLayers.SPRUCE_BOAT;
			case BIRCH -> ModelLayers.BIRCH_BOAT;
			case JUNGLE -> ModelLayers.JUNGLE_BOAT;
			case ACACIA -> ModelLayers.ACACIA_BOAT;
			case CHERRY -> ModelLayers.CHERRY_BOAT;
			case DARK_OAK -> ModelLayers.DARK_OAK_BOAT;
			case PALE_OAK -> ModelLayers.PALE_OAK_BOAT;
			case MANGROVE -> ModelLayers.MANGROVE_BOAT;
			case POPLAR -> ModelLayers.POPLAR_BOAT;
			case BAMBOO -> ModelLayers.BAMBOO_RAFT;
		};
	}
}
