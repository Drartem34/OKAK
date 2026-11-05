package com.okak.taxi.route;

import java.util.List;

/** An immutable 2D polyline (block X/Z coordinates) parameterised by distance travelled. */
public final class Route {
	private final double[] xs;
	private final double[] zs;
	private final double[] cumulative;

	public Route(List<double[]> points) {
		if (points.isEmpty()) {
			throw new IllegalArgumentException("Route needs at least one point");
		}
		int n = points.size();
		this.xs = new double[n];
		this.zs = new double[n];
		this.cumulative = new double[n];
		for (int i = 0; i < n; i++) {
			this.xs[i] = points.get(i)[0];
			this.zs[i] = points.get(i)[1];
			if (i > 0) {
				this.cumulative[i] = this.cumulative[i - 1] + Math.hypot(this.xs[i] - this.xs[i - 1], this.zs[i] - this.zs[i - 1]);
			}
		}
	}

	public double length() {
		return this.cumulative[this.cumulative.length - 1];
	}

	public double endX() {
		return this.xs[this.xs.length - 1];
	}

	public double endZ() {
		return this.zs[this.zs.length - 1];
	}

	/** Returns the {x, z} position after travelling {@code distance} blocks along the route. */
	public double[] at(double distance) {
		int n = this.xs.length;
		if (n == 1 || distance <= 0) {
			return new double[]{this.xs[0], this.zs[0]};
		}
		if (distance >= this.length()) {
			return new double[]{this.xs[n - 1], this.zs[n - 1]};
		}
		int lo = 0;
		int hi = n - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (this.cumulative[mid] <= distance) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double segment = this.cumulative[hi] - this.cumulative[lo];
		double t = segment <= 1.0E-9 ? 0.0 : (distance - this.cumulative[lo]) / segment;
		return new double[]{this.xs[lo] + (this.xs[hi] - this.xs[lo]) * t, this.zs[lo] + (this.zs[hi] - this.zs[lo]) * t};
	}

	/** Remaining polyline from {@code fromDistance} to the end, flattened as x0,z0,x1,z1... and capped in size. */
	public int[] remainingPoints(double fromDistance, int maxPoints) {
		int n = this.xs.length;
		int first = 0;
		while (first < n && this.cumulative[first] <= fromDistance) {
			first++;
		}
		int count = n - first + 1;
		int step = Math.max(1, (int) Math.ceil(count / (double) maxPoints));
		int outCount = 1 + (count - 1 + step - 1) / step + 1;
		int[] out = new int[outCount * 2];
		int o = 0;
		double[] start = this.at(fromDistance);
		out[o++] = (int) Math.round(start[0]);
		out[o++] = (int) Math.round(start[1]);
		for (int i = first; i < n; i += step) {
			out[o++] = (int) Math.round(this.xs[i]);
			out[o++] = (int) Math.round(this.zs[i]);
		}
		out[o++] = (int) Math.round(this.xs[n - 1]);
		out[o++] = (int) Math.round(this.zs[n - 1]);
		return java.util.Arrays.copyOf(out, o);
	}
}
