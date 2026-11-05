package com.okak.taxi.entity;

import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The kind of vehicle that comes to pick the player up. Every variant is a separate
 * entity type so the vanilla boat/raft renderers can be reused as-is.
 */
public enum TaxiVariant {
	OAK("oak", () -> Items.OAK_BOAT, false),
	SPRUCE("spruce", () -> Items.SPRUCE_BOAT, false),
	BIRCH("birch", () -> Items.BIRCH_BOAT, false),
	JUNGLE("jungle", () -> Items.JUNGLE_BOAT, false),
	ACACIA("acacia", () -> Items.ACACIA_BOAT, false),
	CHERRY("cherry", () -> Items.CHERRY_BOAT, false),
	DARK_OAK("dark_oak", () -> Items.DARK_OAK_BOAT, false),
	PALE_OAK("pale_oak", () -> Items.PALE_OAK_BOAT, false),
	MANGROVE("mangrove", () -> Items.MANGROVE_BOAT, false),
	POPLAR("poplar", () -> Items.POPLAR_BOAT, false),
	BAMBOO("bamboo", () -> Items.BAMBOO_RAFT, true);

	private static final TaxiVariant[] VALUES = values();

	public final String id;
	public final Supplier<Item> item;
	public final boolean raft;

	TaxiVariant(String id, Supplier<Item> item, boolean raft) {
		this.id = id;
		this.item = item;
		this.raft = raft;
	}

	public String plateWord() {
		return this.raft ? "raft" : "boat";
	}

	public static TaxiVariant byIndex(int index) {
		return index >= 0 && index < VALUES.length ? VALUES[index] : OAK;
	}

	public static int count() {
		return VALUES.length;
	}
}
