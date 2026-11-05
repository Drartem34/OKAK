package com.okak.taxi.route;

import com.okak.taxi.explore.ChunkKey;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Path finding on the grid of explored chunks. A taxi is only allowed to drive through
 * chunks the player has already visited.
 */
public final class RouteFinder {
	private static final int MAX_EXPANSIONS = 400_000;
	private static final int[][] DIRECTIONS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private RouteFinder() {
	}

	/** The plan for a taxi driving towards the pickup point. */
	public record Approach(Route route) {
	}

	/** Shortest route through explored chunks from (sx, sz) to (ex, ez), or null if impossible. */
	public static @Nullable Route findRoute(LongSet explored, double sx, double sz, double ex, double ez) {
		long start = ChunkKey.ofBlock(sx, sz);
		long goal = ChunkKey.ofBlock(ex, ez);
		Passable passable = key -> key == start || key == goal || explored.contains(key);

		List<Long> chunks = aStar(passable, start, goal);
		if (chunks == null) {
			return null;
		}
		return smooth(chunks, passable, sx, sz, ex, ez);
	}

	/**
	 * Picks a random explored spot some distance away from the pickup point (where the driver
	 * "comes from") and builds the route from there to the pickup point.
	 */
	public static Approach planApproach(LongSet explored, double pickupX, double pickupZ, RandomSource random) {
		long pickup = ChunkKey.ofBlock(pickupX, pickupZ);
		Passable passable = key -> key == pickup || explored.contains(key);
		int maxDepth = 36;

		Long2IntOpenHashMap depth = new Long2IntOpenHashMap();
		Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		depth.put(pickup, 0);
		queue.enqueue(pickup);
		int reached = 0;

		while (!queue.isEmpty()) {
			long current = queue.dequeueLong();
			int d = depth.get(current);
			reached = Math.max(reached, d);
			if (d >= maxDepth) {
				continue;
			}
			for (long next : neighbours(current, passable)) {
				if (!depth.containsKey(next)) {
					depth.put(next, d + 1);
					parent.put(next, current);
					queue.enqueue(next);
				}
			}
		}

		int target = Math.min(reached, 10 + random.nextInt(20));
		List<double[]> points = new ArrayList<>();
		if (target <= 0) {
			// Nothing explored around: the driver appears just around the corner.
			double angle = random.nextDouble() * Math.PI * 2;
			points.add(new double[]{pickupX + Math.cos(angle) * 12, pickupZ + Math.sin(angle) * 12});
			points.add(new double[]{pickupX, pickupZ});
			return new Approach(new Route(points));
		}

		LongArrayList candidates = new LongArrayList();
		for (Long2IntOpenHashMap.Entry entry : depth.long2IntEntrySet()) {
			if (entry.getIntValue() == target) {
				candidates.add(entry.getLongKey());
			}
		}
		long origin = candidates.getLong(random.nextInt(candidates.size()));

		List<Long> chunks = new ArrayList<>();
		long cursor = origin;
		chunks.add(cursor);
		while (cursor != pickup) {
			cursor = parent.get(cursor);
			chunks.add(cursor);
		}

		double ox = (ChunkKey.x(origin) << 4) + 8;
		double oz = (ChunkKey.z(origin) << 4) + 8;
		return new Approach(smooth(chunks, passable, ox, oz, pickupX, pickupZ));
	}

	@FunctionalInterface
	private interface Passable {
		boolean test(long key);
	}

	private static long[] neighbours(long key, Passable passable) {
		int x = ChunkKey.x(key);
		int z = ChunkKey.z(key);
		long[] result = new long[8];
		int count = 0;
		for (int[] dir : DIRECTIONS) {
			long next = ChunkKey.of(x + dir[0], z + dir[1]);
			if (!passable.test(next)) {
				continue;
			}
			if (dir[0] != 0 && dir[1] != 0) {
				// Don't cut corners through unexplored land.
				if (!passable.test(ChunkKey.of(x + dir[0], z)) || !passable.test(ChunkKey.of(x, z + dir[1]))) {
					continue;
				}
			}
			result[count++] = next;
		}
		return java.util.Arrays.copyOf(result, count);
	}

	private record Node(long key, double f) {
	}

	private static @Nullable List<Long> aStar(Passable passable, long start, long goal) {
		if (start == goal) {
			return List.of(start, goal);
		}
		int gx = ChunkKey.x(goal);
		int gz = ChunkKey.z(goal);

		Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
		g.defaultReturnValue(Double.MAX_VALUE);
		Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
		LongOpenHashSet closed = new LongOpenHashSet();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		g.put(start, 0.0);
		open.add(new Node(start, heuristic(start, gx, gz)));
		int expansions = 0;

		while (!open.isEmpty()) {
			Node node = open.poll();
			long current = node.key();
			if (current == goal) {
				List<Long> path = new ArrayList<>();
				long cursor = goal;
				path.add(cursor);
				while (cursor != start) {
					cursor = parent.get(cursor);
					path.add(cursor);
				}
				Collections.reverse(path);
				return path;
			}
			if (!closed.add(current)) {
				continue;
			}
			if (++expansions > MAX_EXPANSIONS) {
				return null;
			}

			double base = g.get(current);
			int cx = ChunkKey.x(current);
			int cz = ChunkKey.z(current);
			for (long next : neighbours(current, passable)) {
				if (closed.contains(next)) {
					continue;
				}
				boolean diagonal = ChunkKey.x(next) != cx && ChunkKey.z(next) != cz;
				double cost = base + (diagonal ? Math.sqrt(2) : 1.0);
				if (cost < g.get(next)) {
					g.put(next, cost);
					parent.put(next, current);
					open.add(new Node(next, cost + heuristic(next, gx, gz)));
				}
			}
		}
		return null;
	}

	private static double heuristic(long key, int gx, int gz) {
		int dx = Math.abs(ChunkKey.x(key) - gx);
		int dz = Math.abs(ChunkKey.z(key) - gz);
		return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
	}

	/** Turns a chain of chunks into a pleasant curvy line: string pulling + Chaikin smoothing. */
	private static Route smooth(List<Long> chunks, Passable passable, double sx, double sz, double ex, double ez) {
		List<double[]> points = new ArrayList<>(chunks.size());
		for (long key : chunks) {
			points.add(new double[]{(ChunkKey.x(key) << 4) + 8, (ChunkKey.z(key) << 4) + 8});
		}
		points.set(0, new double[]{sx, sz});
		if (points.size() == 1) {
			points.add(new double[]{ex, ez});
		} else {
			points.set(points.size() - 1, new double[]{ex, ez});
		}

		List<double[]> pulled = new ArrayList<>();
		int i = 0;
		pulled.add(points.get(0));
		while (i < points.size() - 1) {
			int best = i + 1;
			int limit = Math.min(points.size() - 1, i + 48);
			for (int j = limit; j > i + 1; j--) {
				if (lineOfSight(points.get(i), points.get(j), passable)) {
					best = j;
					break;
				}
			}
			pulled.add(points.get(best));
			i = best;
		}

		List<double[]> result = pulled;
		for (int iteration = 0; iteration < 3; iteration++) {
			result = chaikin(result);
		}
		return new Route(result);
	}

	private static boolean lineOfSight(double[] a, double[] b, Passable passable) {
		double dx = b[0] - a[0];
		double dz = b[1] - a[1];
		double length = Math.hypot(dx, dz);
		int steps = Math.max(1, (int) Math.ceil(length / 2.0));
		for (int s = 0; s <= steps; s++) {
			double t = s / (double) steps;
			if (!passable.test(ChunkKey.ofBlock(a[0] + dx * t, a[1] + dz * t))) {
				return false;
			}
		}
		return true;
	}

	private static List<double[]> chaikin(List<double[]> points) {
		if (points.size() < 3) {
			return points;
		}
		List<double[]> out = new ArrayList<>(points.size() * 2);
		out.add(points.get(0));
		for (int i = 0; i < points.size() - 1; i++) {
			double[] p = points.get(i);
			double[] q = points.get(i + 1);
			if (i > 0) {
				out.add(new double[]{p[0] * 0.75 + q[0] * 0.25, p[1] * 0.75 + q[1] * 0.25});
			}
			if (i < points.size() - 2) {
				out.add(new double[]{p[0] * 0.25 + q[0] * 0.75, p[1] * 0.25 + q[1] * 0.75});
			}
		}
		out.add(points.get(points.size() - 1));
		return out;
	}
}
