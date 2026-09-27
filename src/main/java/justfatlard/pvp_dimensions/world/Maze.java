package justfatlard.pvp_dimensions.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A maze laid over flat ground: which columns of an arena are wall, worked out once when the arena
 * is made and read by every worldgen thread after.
 *
 * <p>Carved as a graph of cells by a depth-first walk from the middle, which leaves exactly one way
 * between any two cells; then some dead ends are knocked through into a neighbour, as many as
 * {@code loops} asks, so a chase has more than one way round. Square cells sit in a grid; round
 * ones in rings about a room in the middle, more of them to each ring the further out it is, so a
 * path stays about the same width all the way round.
 *
 * @param minX   the arena's west edge
 * @param minZ   the arena's north edge
 * @param width  the arena's width, blocks
 * @param walls  one bit a column, row by row from the north west: set for wall
 * @param baseY  the ground's surface, where walls stand on
 * @param height how many blocks a wall stands above it
 * @param roof   laid over the whole arena just above the walls, or null for open sky
 */
public record Maze(int minX, int minZ, int width, BitSet walls, BlockState block, int baseY, int height, @Nullable BlockState roof) {
	public enum Layout { SQUARE, ROUND }

	public boolean wall(int x, int z) {
		int lx = x - minX;
		int lz = z - minZ;
		if (lx < 0 || lz < 0 || lx >= width || lz >= width) return false;
		return walls.get(lz * width + lx);
	}

	/** The top of a wall. */
	public int topY() {
		return baseY + height;
	}

	/** Where the roof goes, if there is one. */
	public int roofY() {
		return baseY + height + 1;
	}

	/** The walls of a maze this wide, one bit a column. */
	public static BitSet carve(Layout layout, int width, int path, int wall, int loops, long seed) {
		Random random = new Random(seed);
		BitSet walls = layout == Layout.ROUND ? round(width, path, wall, loops, random) : square(width, path, wall, loops, random);
		sealPockets(walls, width);
		return walls;
	}

	/**
	 * Fill any open ground that can't be walked to from the middle. A curved wall a block thick drawn
	 * in square blocks can pinch a corner shut; nobody should be set down somewhere sealed in.
	 */
	private static void sealPockets(BitSet walls, int width) {
		int centre = (width / 2) * width + width / 2;
		if (walls.get(centre)) return;
		BitSet reached = new BitSet(width * width);
		Deque<Integer> frontier = new ArrayDeque<>();
		reached.set(centre);
		frontier.add(centre);
		while (!frontier.isEmpty()) {
			int at = frontier.poll();
			int x = at % width;
			int z = at / width;
			if (x > 0) reach(walls, reached, frontier, at - 1);
			if (x + 1 < width) reach(walls, reached, frontier, at + 1);
			if (z > 0) reach(walls, reached, frontier, at - width);
			if (z + 1 < width) reach(walls, reached, frontier, at + width);
		}
		for (int at = 0; at < width * width; at++) if (!reached.get(at)) walls.set(at);
	}

	private static void reach(BitSet walls, BitSet reached, Deque<Integer> frontier, int at) {
		if (walls.get(at) || reached.get(at)) return;
		reached.set(at);
		frontier.add(at);
	}

	// --- The walk ---

	/** Cells joined by passages; the walk and the loops work on this, whatever shape the cells are. */
	private static final class Graph {
		final List<List<Integer>> neighbours = new ArrayList<>();
		final Set<Long> open = new HashSet<>();

		int add() {
			neighbours.add(new ArrayList<>());
			return neighbours.size() - 1;
		}

		void link(int a, int b) {
			if (a == b || neighbours.get(a).contains(b)) return;
			neighbours.get(a).add(b);
			neighbours.get(b).add(a);
		}

		void open(int a, int b) {
			open.add(key(a, b));
		}

		boolean isOpen(int a, int b) {
			return open.contains(key(a, b));
		}

		private static long key(int a, int b) {
			return ((long) Math.min(a, b) << 32) | Math.max(a, b);
		}

		/** Every cell reached once, from {@code start}, by a random walk that backs up at dead ends. */
		void walk(int start, Random random) {
			boolean[] seen = new boolean[neighbours.size()];
			Deque<Integer> trail = new ArrayDeque<>();
			seen[start] = true;
			trail.push(start);
			while (!trail.isEmpty()) {
				int here = trail.peek();
				List<Integer> fresh = new ArrayList<>();
				for (int next : neighbours.get(here)) if (!seen[next]) fresh.add(next);
				if (fresh.isEmpty()) {
					trail.pop();
					continue;
				}
				int next = fresh.get(random.nextInt(fresh.size()));
				open(here, next);
				seen[next] = true;
				trail.push(next);
			}
		}

		/** Knock this share of the dead ends, in percent, through into a neighbour they don't open onto. */
		void loop(int percent, Random random) {
			if (percent <= 0) return;
			for (int cell = 0; cell < neighbours.size(); cell++) {
				List<Integer> closed = new ArrayList<>();
				int openings = 0;
				for (int next : neighbours.get(cell)) {
					if (isOpen(cell, next)) openings++;
					else closed.add(next);
				}
				if (openings == 1 && !closed.isEmpty() && random.nextInt(100) < percent) {
					open(cell, closed.get(random.nextInt(closed.size())));
				}
			}
		}
	}

	// --- Square ---

	private static BitSet square(int width, int path, int wall, int loops, Random random) {
		int cell = path + wall;
		// An odd number across, so the middle cell's path is the middle of the arena.
		int cols = Math.max(1, (width - wall) / cell);
		if (cols % 2 == 0) cols--;
		int margin = (width - (cols * cell + wall)) / 2;
		Graph graph = new Graph();
		for (int i = 0; i < cols * cols; i++) graph.add();
		for (int cz = 0; cz < cols; cz++) {
			for (int cx = 0; cx < cols; cx++) {
				if (cx + 1 < cols) graph.link(cz * cols + cx, cz * cols + cx + 1);
				if (cz + 1 < cols) graph.link(cz * cols + cx, (cz + 1) * cols + cx);
			}
		}
		int middle = cols / 2;
		graph.walk(middle * cols + middle, random);
		graph.loop(loops, random);
		// A room in the middle, a few cells across, for whatever the game puts there.
		int room = cols >= 7 ? 1 : 0;
		for (int cz = middle - room; cz <= middle + room; cz++) {
			for (int cx = middle - room; cx < middle + room; cx++) graph.open(cz * cols + cx, cz * cols + cx + 1);
		}
		for (int cx = middle - room; cx <= middle + room; cx++) {
			for (int cz = middle - room; cz < middle + room; cz++) graph.open(cz * cols + cx, (cz + 1) * cols + cx);
		}

		BitSet walls = new BitSet(width * width);
		int span = cols * cell + wall;
		for (int z = 0; z < width; z++) {
			for (int x = 0; x < width; x++) {
				int lx = x - margin;
				int lz = z - margin;
				boolean solid;
				if (lx < 0 || lz < 0 || lx >= span || lz >= span) {
					solid = true;
				} else {
					int cx = lx / cell;
					int cz = lz / cell;
					boolean westWall = lx % cell < wall;
					boolean northWall = lz % cell < wall;
					boolean inRoom = room > 0 && Math.abs(cx - middle) <= room && Math.abs(cz - middle) <= room;
					if (cx >= cols || cz >= cols) solid = true;
					else if (inRoom && (!westWall || cx > middle - room) && (!northWall || cz > middle - room)) solid = false;
					else if (westWall && northWall) solid = true;
					else if (westWall) solid = cx == 0 || !graph.isOpen(cz * cols + cx - 1, cz * cols + cx);
					else if (northWall) solid = cz == 0 || !graph.isOpen((cz - 1) * cols + cx, cz * cols + cx);
					else solid = false;
				}
				if (solid) walls.set(z * width + x);
			}
		}
		return walls;
	}

	// --- Round ---

	private static BitSet round(int width, int path, int wall, int loops, Random random) {
		// A curved path one block wide pinches shut wherever it bends across a corner.
		path = Math.max(2, path);
		int cell = path + wall;
		double centre = width / 2.0;
		double room = Math.max(path + 1, cell);
		int rings = Math.max(1, (int) ((centre - wall - room) / cell));
		int[] counts = new int[rings];
		int[] first = new int[rings];
		Graph graph = new Graph();
		int middle = graph.add();
		for (int ring = 0; ring < rings; ring++) {
			// Counted at the ring's inside edge, its narrowest, so no cell there is narrower than a path and its wall.
			double inside = room + ring * cell;
			counts[ring] = Math.max(3, (int) (2 * Math.PI * inside / cell));
			first[ring] = graph.neighbours.size();
			for (int j = 0; j < counts[ring]; j++) graph.add();
			for (int j = 0; j < counts[ring]; j++) graph.link(first[ring] + j, first[ring] + (j + 1) % counts[ring]);
			for (int j = 0; j < counts[ring]; j++) {
				if (ring == 0) {
					graph.link(middle, first[0] + j);
					continue;
				}
				// Inward to each cell of the ring inside that it meets along a path and its wall's width,
				// or failing that the one it meets most: a doorway needs room to stand in.
				double from = j / (double) counts[ring];
				double to = (j + 1) / (double) counts[ring];
				int inner = counts[ring - 1];
				double edge = 2 * Math.PI * (room + ring * cell);
				int widest = -1;
				double most = 0;
				boolean any = false;
				for (int k = (int) Math.floor(from * inner); k < Math.ceil(to * inner) && k < inner; k++) {
					double meets = (Math.min(to, (k + 1) / (double) inner) - Math.max(from, k / (double) inner)) * edge;
					if (meets > most) {
						most = meets;
						widest = k;
					}
					if (meets >= cell) {
						graph.link(first[ring - 1] + k, first[ring] + j);
						any = true;
					}
				}
				if (!any && widest >= 0) graph.link(first[ring - 1] + widest, first[ring] + j);
			}
		}
		graph.walk(middle, random);
		graph.loop(loops, random);

		BitSet walls = new BitSet(width * width);
		double outer = room + rings * cell + wall;
		for (int z = 0; z < width; z++) {
			for (int x = 0; x < width; x++) {
				double dx = x + 0.5 - centre;
				double dz = z + 0.5 - centre;
				double r = Math.hypot(dx, dz);
				boolean solid;
				if (r < room) {
					solid = false;
				} else if (r >= outer - wall) {
					solid = true;
				} else {
					double turn = (Math.atan2(dz, dx) + 2 * Math.PI) % (2 * Math.PI) / (2 * Math.PI);
					int ring = Math.min(rings - 1, (int) ((r - room) / cell));
					double into = r - room - ring * cell;
					int n = counts[ring];
					int j = Math.min(n - 1, (int) (turn * n));
					if (into < wall) {
						solid = !throughInner(graph, counts, first, middle, ring, j, turn, room + ring * cell, path);
					} else {
						double along = (turn * n - j) / n * 2 * Math.PI * r;
						solid = along < wall && !graph.isOpen(first[ring] + (j - 1 + n) % n, first[ring] + j);
					}
				}
				if (solid) walls.set(z * width + x);
			}
		}
		return walls;
	}

	/**
	 * Whether this spot of the wall inside a ring is the doorway through it: only a path's width of
	 * it, at the middle of where the two cells it joins meet.
	 */
	private static boolean throughInner(Graph graph, int[] counts, int[] first, int middle, int ring, int j, double turn, double radius, int path) {
		int n = counts[ring];
		double from = j / (double) n;
		double to = (j + 1) / (double) n;
		int inner;
		double meetFrom = from;
		double meetTo = to;
		if (ring == 0) {
			inner = middle;
		} else {
			int m = counts[ring - 1];
			int k = Math.min(m - 1, (int) (turn * m));
			inner = first[ring - 1] + k;
			meetFrom = Math.max(from, k / (double) m);
			meetTo = Math.min(to, (k + 1) / (double) m);
		}
		if (!graph.isOpen(inner, first[ring] + j)) return false;
		double meet = (meetFrom + meetTo) / 2;
		return Math.abs(turn - meet) * 2 * Math.PI * radius <= path / 2.0;
	}
}
