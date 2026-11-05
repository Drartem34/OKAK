package com.okak.taxi.service;

import net.minecraft.util.RandomSource;

/**
 * Taxi class by driver rating: more stars = faster driver and a fancier glow colour.
 * 1★ "Turtle" (red, slow) ... 5★ "Premium" (gold, fast).
 */
public final class TaxiClass {
	private static final int[] COLORS = {0xFF4040, 0xFF9A3C, 0x4FC3F7, 0x66FF66, 0xFFD700};
	private static final double[] SPEED = {0.6, 0.8, 1.0, 1.25, 1.5};

	private TaxiClass() {
	}

	/** Random rating x10 (10..50): 5★ 40%, 4★ 30%, 3★ 15%, 2★ 10%, 1★ 5%. */
	public static int randomRating10(RandomSource random) {
		int roll = random.nextInt(100);
		int stars = roll < 40 ? 5 : roll < 70 ? 4 : roll < 85 ? 3 : roll < 95 ? 2 : 1;
		return switch (stars) {
			case 5 -> 46 + random.nextInt(5);
			case 4 -> 36 + random.nextInt(10);
			case 3 -> 26 + random.nextInt(10);
			case 2 -> 16 + random.nextInt(10);
			default -> 10 + random.nextInt(6);
		};
	}

	public static int stars(int rating10) {
		return Math.max(1, Math.min(5, (rating10 + 4) / 10));
	}

	/** RGB colour of the glow / particles / UI accents. */
	public static int color(int rating10) {
		return COLORS[stars(rating10) - 1];
	}

	public static double speedMultiplier(int rating10) {
		return SPEED[stars(rating10) - 1];
	}

	public static String nameKey(int rating10) {
		return "taxi.class." + stars(rating10);
	}
}
