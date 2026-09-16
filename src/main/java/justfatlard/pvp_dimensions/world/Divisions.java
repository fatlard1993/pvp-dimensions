package justfatlard.pvp_dimensions.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How an arena's walls cut it up, on a square so many blocks across, with no arena needed to ask:
 * the arena itself is built from this, and the editor's map drawn from it, so the two can't
 * disagree.
 *
 * <p>Either a grid of {@code cols} by {@code rows}, or {@code slices} pie slices meeting in the
 * middle. Positions are local, from the square's north-west corner.
 *
 * @param slices how many slices, or 0 for a grid
 */
public record Divisions(int cols, int rows, int slices) {
	/** The first slice's edge points north; the rest follow round clockwise. */
	private static final double FIRST_EDGE = -Math.PI / 2;
	private static final double TURN = Math.PI * 2;
	private static final Map<Integer, double[]> SLICE_ANGLES = new ConcurrentHashMap<>();

	public static final Divisions NONE = new Divisions(1, 1, 0);

	public static Divisions of(int parts, boolean pie) {
		if (parts <= 1) return NONE;
		if (pie && parts > 2) return new Divisions(1, 1, parts);
		int[] grid = grid(parts);
		return new Divisions(grid[0], grid[1], 0);
	}

	/** Columns and rows for so many divisions, as near square as the number allows. */
	public static int[] grid(int divisions) {
		int best = 1;
		for (int cols = 1; cols * cols <= divisions; cols++) {
			if (divisions % cols == 0) best = cols;
		}
		return new int[] {divisions / best, best};
	}

	public int parts() {
		return slices > 0 ? slices : cols * rows;
	}

	/** Whether a wall two blocks thick stands at this column. */
	public boolean wall(int width, int localX, int localZ) {
		if (parts() <= 1) return false;
		if (slices > 0) {
			double half = width / 2.0;
			double px = localX + 0.5 - half;
			double pz = localZ + 0.5 - half;
			double[] angles = sliceAngles(slices);
			for (int i = 0; i < slices; i++) {
				double dx = Math.cos(angles[2 * i]);
				double dz = Math.sin(angles[2 * i]);
				if (px * dx + pz * dz < -1) continue;
				if (Math.abs(px * dz - pz * dx) < 1) return true;
			}
			return false;
		}
		for (int i = 1; i < cols; i++) {
			int line = Math.round(i * width / (float) cols);
			if (localX == line - 1 || localX == line) return true;
		}
		for (int j = 1; j < rows; j++) {
			int line = Math.round(j * width / (float) rows);
			if (localZ == line - 1 || localZ == line) return true;
		}
		return false;
	}

	/**
	 * Which part a column is in: for slices, counted clockwise from the one whose edge points
	 * north; for a grid, along rows from the north-west corner.
	 */
	public int part(int width, int localX, int localZ) {
		if (slices > 0) {
			double half = width / 2.0;
			double angle = Math.atan2(localZ + 0.5 - half, localX + 0.5 - half) - FIRST_EDGE;
			angle = ((angle % TURN) + TURN) % TURN;
			double[] angles = sliceAngles(slices);
			int slice = 0;
			while (slice < slices - 1 && angles[2 * (slice + 1)] - FIRST_EDGE <= angle) slice++;
			return slice;
		}
		int col = 0;
		while (col < cols - 1 && localX >= Math.round((col + 1) * width / (float) cols)) col++;
		int row = 0;
		while (row < rows - 1 && localZ >= Math.round((row + 1) * width / (float) rows)) row++;
		return row * cols + col;
	}

	/** The middle of a part: the heart of a grid cell, a third of the way out along a slice. */
	public int[] center(int width, int part) {
		if (slices > 0) {
			double angle = sliceAngles(slices)[2 * part + 1];
			double radius = width * 0.3;
			return new int[] {(int) (width / 2.0 + Math.cos(angle) * radius), (int) (width / 2.0 + Math.sin(angle) * radius)};
		}
		return new int[] {(int) ((part % cols + 0.5) * width / cols), (int) ((part / cols + 0.5) * width / rows)};
	}

	/**
	 * Where each slice's edge runs, and the middle of each, round a square: spaced so every slice
	 * has the same ground, not the same angle, which on a square gives some slices a corner more
	 * than others. Edge {@code i} is at {@code [2i]}, the middle of slice {@code i} at {@code [2i + 1]}.
	 */
	private static double[] sliceAngles(int slices) {
		return SLICE_ANGLES.computeIfAbsent(slices, count -> {
			int steps = 7200;
			double[] swept = new double[steps + 1];
			for (int i = 0; i < steps; i++) {
				double angle = FIRST_EDGE + (i + 0.5) * TURN / steps;
				double reach = 1 / Math.max(Math.abs(Math.cos(angle)), Math.abs(Math.sin(angle)));
				swept[i + 1] = swept[i] + reach * reach;
			}
			double[] angles = new double[2 * count];
			int at = 0;
			for (int k = 0; k < 2 * count; k++) {
				double wanted = swept[steps] * k / (2.0 * count);
				while (at < steps && swept[at + 1] <= wanted) at++;
				angles[k] = FIRST_EDGE + at * TURN / steps;
			}
			return angles;
		});
	}
}
