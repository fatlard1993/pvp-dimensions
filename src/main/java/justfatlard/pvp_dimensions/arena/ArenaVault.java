package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Every arena, running or finished and still somebody's to come home from, and the numbers handed out. */
public final class ArenaVault extends SavedData {
	/** A finished arena's plot, waiting to be deleted the next time the server is down. */
	public record DeadPlot(ResourceKey<Level> dimension, int plot) {
		static final Codec<DeadPlot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(DeadPlot::dimension),
			Codec.INT.fieldOf("plot").forGetter(DeadPlot::plot)
		).apply(instance, DeadPlot::new));
	}

	private record Stored(List<Arena> arenas, int nextNumber, Map<String, Integer> nextPlot, List<DeadPlot> dead) {
		static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Arena.CODEC.listOf().fieldOf("arenas").forGetter(Stored::arenas),
			Codec.INT.fieldOf("next_number").forGetter(Stored::nextNumber),
			Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("next_plot").forGetter(Stored::nextPlot),
			DeadPlot.CODEC.listOf().fieldOf("dead").forGetter(Stored::dead)
		).apply(instance, Stored::new));
	}

	public static final Codec<ArenaVault> CODEC = Stored.CODEC.xmap(ArenaVault::fromStored, ArenaVault::toStored);

	private static final SavedDataType<ArenaVault> TYPE = new SavedDataType<>(
		PvpDimensions.id("arenas"), ArenaVault::new, CODEC, DataFixTypes.LEVEL);

	private final Map<String, Arena> arenas = new LinkedHashMap<>();
	private int nextNumber = 1;
	private final Map<String, Integer> nextPlot = new HashMap<>();
	private final List<DeadPlot> dead = new ArrayList<>();

	public static ArenaVault get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public Map<String, Arena> arenas() {
		return arenas;
	}

	public int takeNumber() {
		setDirty();
		return nextNumber++;
	}

	public int takePlot(ResourceKey<Level> dimension) {
		String key = dimension.identifier().toString();
		int plot = nextPlot.getOrDefault(key, 0);
		nextPlot.put(key, plot + 1);
		setDirty();
		return plot;
	}

	public void add(Arena arena) {
		arenas.put(arena.id, arena);
		setDirty();
	}

	public void remove(Arena arena) {
		arenas.remove(arena.id);
		setDirty();
	}

	public void bury(Arena arena) {
		dead.add(new DeadPlot(arena.dimension, arena.plot));
		setDirty();
	}

	/** The plots to delete, handed over once: the caller deletes them before the dimensions load. */
	public List<DeadPlot> takeDead() {
		List<DeadPlot> taken = List.copyOf(dead);
		if (!dead.isEmpty()) {
			dead.clear();
			setDirty();
		}
		return taken;
	}

	public void touch() {
		setDirty();
	}

	private static ArenaVault fromStored(Stored stored) {
		ArenaVault vault = new ArenaVault();
		for (Arena arena : stored.arenas()) vault.arenas.put(arena.id, arena);
		vault.nextNumber = stored.nextNumber();
		vault.nextPlot.putAll(stored.nextPlot());
		vault.dead.addAll(stored.dead());
		return vault;
	}

	private static Stored toStored(ArenaVault vault) {
		return new Stored(List.copyOf(vault.arenas.values()), vault.nextNumber, Map.copyOf(vault.nextPlot), List.copyOf(vault.dead));
	}
}
