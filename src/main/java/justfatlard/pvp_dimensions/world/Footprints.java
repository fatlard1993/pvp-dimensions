package justfatlard.pvp_dimensions.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The arenas worldgen builds ground for. A chunk outside every footprint generates empty, which
 * is what leaves each arena floating alone in its dimension.
 *
 * <p>A footprint goes in before any of its chunks is asked for and comes out when the arena ends,
 * so a chunk is only ever generated as the arena it belongs to or as nothing.
 */
public final class Footprints {
	private Footprints() {}

	private static volatile Map<ResourceKey<Level>, List<Footprint>> byDimension = Map.of();

	public static @Nullable Footprint at(@Nullable ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		if (dimension == null) return null;
		List<Footprint> here = byDimension.get(dimension);
		if (here == null) return null;
		for (Footprint footprint : here) {
			if (footprint.containsChunk(chunkX, chunkZ)) return footprint;
		}
		return null;
	}

	public static @Nullable Footprint atBlock(@Nullable ResourceKey<Level> dimension, double x, double z) {
		return at(dimension, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
	}

	public static synchronized void put(Footprint footprint) {
		Map<ResourceKey<Level>, List<Footprint>> next = copy();
		next.computeIfAbsent(footprint.dimension(), key -> new ArrayList<>()).removeIf(f -> f.arena().equals(footprint.arena()));
		next.get(footprint.dimension()).add(footprint);
		byDimension = freeze(next);
	}

	public static synchronized void remove(String arena) {
		Map<ResourceKey<Level>, List<Footprint>> next = copy();
		for (List<Footprint> list : next.values()) list.removeIf(f -> f.arena().equals(arena));
		byDimension = freeze(next);
	}

	public static synchronized void clear() {
		byDimension = Map.of();
	}

	private static Map<ResourceKey<Level>, List<Footprint>> copy() {
		Map<ResourceKey<Level>, List<Footprint>> next = new HashMap<>();
		byDimension.forEach((key, list) -> next.put(key, new ArrayList<>(list)));
		return next;
	}

	private static Map<ResourceKey<Level>, List<Footprint>> freeze(Map<ResourceKey<Level>, List<Footprint>> map) {
		Map<ResourceKey<Level>, List<Footprint>> frozen = new HashMap<>();
		map.forEach((key, list) -> frozen.put(key, List.copyOf(list)));
		return Map.copyOf(frozen);
	}
}
