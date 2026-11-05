package com.okak.taxi.net;

import com.okak.taxi.explore.ExplorationTracker;
import com.okak.taxi.service.TaxiService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class TaxiNetworking {
	private TaxiNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(RequestTilesC2S.TYPE, RequestTilesC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetDestinationC2S.TYPE, SetDestinationC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TaxiActionC2S.TYPE, TaxiActionC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ChunkDataS2C.TYPE, ChunkDataS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TaxiStatusS2C.TYPE, TaxiStatusS2C.CODEC);
	}

	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(RequestTilesC2S.TYPE, (payload, context) -> {
			long[] tiles = payload.tiles();
			int count = Math.min(tiles.length, RequestTilesC2S.MAX_TILES);
			for (int i = 0; i < count; i++) {
				ExplorationTracker.sendTile(context.player(), tiles[i]);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SetDestinationC2S.TYPE, (payload, context) ->
				TaxiService.setDestination(context.player(), payload.clear(), payload.x(), payload.z()));

		ServerPlayNetworking.registerGlobalReceiver(TaxiActionC2S.TYPE, (payload, context) -> {
			switch (payload.action()) {
				case TaxiActionC2S.CALL -> TaxiService.call(context.player());
				case TaxiActionC2S.CANCEL -> TaxiService.cancelByPlayer(context.player());
				case TaxiActionC2S.SYNC -> TaxiService.sendStatus(context.player());
				default -> {
				}
			}
		});
	}
}
