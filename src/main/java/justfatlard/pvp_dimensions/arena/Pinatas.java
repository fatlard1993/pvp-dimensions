package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Pinatas in the fight, when Pinata is installed: some at the start, more every so often, or a
 * new one each time one is broken, each filled from the next of the preset's loot lists so the
 * loot can climb as the game goes on. How many stand at once, where they go and whether they
 * wander is the preset's.
 *
 * <p>Everything that touches Pinata's own classes is in {@link PinataHook}, loaded only when it
 * is installed.
 */
public final class Pinatas {
	private Pinatas() {}

	/** How long a wandering pinata stays put before it moves. */
	private static final long ROAM_MILLIS = 45_000L;

	private static boolean on(Arena arena) {
		return Fields.PINATA_INSTALLED && arena.preset.pinata != Preset.PinataMode.OFF && !arena.preset.pinataLoot.isEmpty();
	}

	public static void begin(MinecraftServer server, Arena arena) {
		if (!on(arena)) return;
		ServerLevel level = Arenas.level(server, arena);
		if (level == null) return;
		spawn(server, level, arena, arena.preset.pinataCount);
		long now = System.currentTimeMillis();
		arena.nextPinataAt = arena.preset.pinata == Preset.PinataMode.EVERY ? now + arena.preset.pinataMinutes * 60_000L : 0;
		arena.pinataMovesAt = now + ROAM_MILLIS;
	}

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (!on(arena)) return;
		int before = arena.pinatas.size();
		arena.pinatas.removeIf(pos -> level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && !PinataHook.standing(level, pos));
		boolean broken = arena.pinatas.size() < before;
		int wanted = arena.preset.pinataCount;

		switch (arena.preset.pinata) {
			case EVERY -> {
				if (arena.nextPinataAt > 0 && now >= arena.nextPinataAt) {
					int more = arena.preset.pinataRegardless ? wanted : wanted - arena.pinatas.size();
					if (more > 0) spawn(server, level, arena, more);
					arena.nextPinataAt = now + arena.preset.pinataMinutes * 60_000L;
				}
			}
			case AFTER_BREAK -> {
				if (broken && arena.nextPinataAt == 0) {
					arena.nextPinataAt = now + arena.preset.pinataMinutes * 60_000L;
					Arenas.tellInside(server, arena, "A pinata is broken; the next comes in " + Fields.duration(arena.preset.pinataMinutes));
				}
				if (arena.pinatas.size() < wanted && arena.nextPinataAt > 0 && now >= arena.nextPinataAt) {
					arena.nextPinataAt = 0;
					spawn(server, level, arena, wanted - arena.pinatas.size());
				}
			}
			default -> { }
		}

		if (arena.preset.pinataPlace == Preset.PinataPlace.ROAMING && now >= arena.pinataMovesAt && !arena.pinatas.isEmpty()) {
			arena.pinataMovesAt = now + ROAM_MILLIS;
			List<BlockPos> moved = new ArrayList<>();
			for (BlockPos pos : arena.pinatas) {
				Vec3 spot = Spawns.random(level, arena);
				BlockPos to = BlockPos.containing(spot);
				if (PinataHook.move(level, pos, to)) moved.add(to);
				else moved.add(pos);
			}
			arena.pinatas.clear();
			arena.pinatas.addAll(moved);
			Arenas.tellInside(server, arena, "The pinata has moved!");
		}
	}

	/** This many more, each from the next loot list; in the middle, the first there and the rest round it. */
	private static void spawn(MinecraftServer server, ServerLevel level, Arena arena, int count) {
		List<Preset.PinataLoot> loot = arena.preset.pinataLoot;
		Footprint footprint = arena.footprint();
		boolean middle = arena.preset.pinataPlace == Preset.PinataPlace.CENTER;
		List<String> placed = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Preset.PinataLoot next = loot.get(Math.min(arena.pinataIndex, loot.size() - 1));
			int round = arena.pinatas.size();
			double angle = Math.PI * 2 * round / Math.max(1, arena.preset.pinataCount);
			int x = footprint.minX() + footprint.width() / 2 + (round == 0 ? 0 : (int) Math.round(Math.cos(angle) * 5));
			int z = footprint.minZ() + footprint.width() / 2 + (round == 0 ? 0 : (int) Math.round(Math.sin(angle) * 5));
			Vec3 spot = middle ? Spawns.near(level, arena, x, z, 2) : Spawns.random(level, arena);
			BlockPos pos = BlockPos.containing(spot);
			if (arena.pinatas.contains(pos)) pos = pos.east(2);
			if (!PinataHook.place(level, pos, next)) continue;
			arena.pinataIndex++;
			arena.pinatas.add(pos);
			placed.add(next.hits + " hits" + (middle ? "" : " at " + pos.getX() + ", " + pos.getZ()));
		}
		if (placed.isEmpty()) return;
		String where = middle ? " in the middle" : "";
		Arenas.tellInside(server, arena, (placed.size() == 1 ? "A pinata is up" : placed.size() + " pinatas are up") + where + "! "
			+ String.join("; ", placed) + (placed.size() == 1 ? " to break it" : ""));
	}

	/** Whatever pinatas are still standing when the arena ends come down with it, unbroken. */
	public static void clear(MinecraftServer server, Arena arena) {
		if (!Fields.PINATA_INSTALLED || arena.pinatas.isEmpty()) return;
		ServerLevel level = Arenas.level(server, arena);
		if (level != null) {
			for (BlockPos pos : arena.pinatas) PinataHook.remove(level, pos);
		}
		arena.pinatas.clear();
	}
}
