package justfatlard.pvp_dimensions.arena;

import java.util.ArrayDeque;
import java.util.Deque;
import justfatlard.pvp_dimensions.PvpDimensions;

/**
 * Block work too big for one tick, done a slice at a time: walls coming down, a waiting room
 * taken apart, a saved terrain pasted in. Each tick gets a few milliseconds of it and no more, so
 * an arena changing shape never stalls the server for everyone else.
 */
public final class Jobs {
	private Jobs() {}

	/** One slice of a job. True once there is nothing left to do. */
	@FunctionalInterface
	public interface Job {
		boolean step();
	}

	private static final long BUDGET_NANOS = 8_000_000L;
	private static final Deque<Job> queue = new ArrayDeque<>();

	public static void add(Job job) {
		queue.add(job);
	}

	public static boolean busy() {
		return !queue.isEmpty();
	}

	public static void tick() {
		long until = System.nanoTime() + BUDGET_NANOS;
		while (!queue.isEmpty() && System.nanoTime() < until) {
			Job job = queue.peek();
			boolean done;
			try {
				done = job.step();
			} catch (RuntimeException e) {
				PvpDimensions.LOGGER.error("An arena job failed and was dropped", e);
				done = true;
			}
			if (done) queue.poll();
		}
	}

	public static void clear() {
		queue.clear();
	}
}
