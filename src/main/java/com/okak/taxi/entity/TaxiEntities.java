package com.okak.taxi.entity;

import com.okak.taxi.TaxiMod;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class TaxiEntities {
	public static final Map<TaxiVariant, EntityType<TaxiEntity>> TYPES = new EnumMap<>(TaxiVariant.class);

	private TaxiEntities() {
	}

	public static void register() {
		for (TaxiVariant variant : TaxiVariant.values()) {
			ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, TaxiMod.id("taxi_" + variant.id));
			EntityType<TaxiEntity> type = EntityType.Builder.<TaxiEntity>of((entityType, level) -> new TaxiEntity(entityType, level, variant), MobCategory.MISC)
					.noLootTable()
					.noSummon()
					.fireImmune()
					.sized(1.375F, 0.5625F)
					.eyeHeight(0.5625F)
					.clientTrackingRange(10)
					.updateInterval(1)
					.build(key);
			TYPES.put(variant, Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type));
		}
	}
}
