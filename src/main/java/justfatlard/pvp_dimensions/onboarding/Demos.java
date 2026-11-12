package justfatlard.pvp_dimensions.onboarding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.NoticeApi;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.ServerConfig;
import justfatlard.pvp_dimensions.api.DemoHook;
import justfatlard.pvp_dimensions.api.DemoRun;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Places;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.ui.ItemSessions;
import justfatlard.pvp_dimensions.ui.Menus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.jspecify.annotations.Nullable;

/**
 * Onboarding: a short demo for each mod that ships one, played alone on a copy of its own.
 *
 * <p>A demo is played as an arena: a plot of ground made for one player, their own things put
 * aside on the way in and handed back on the way out, home remembered. Going from one demo to
 * another keeps the home they first came from, so a whole evening of demos ends where it began.
 *
 * <p>Each player is asked once about every demo they have not been asked about. On a first visit
 * that is all of them, together; after an update that adds a mod, it is the new one. Nothing is
 * asked when the server turns onboarding off, and the list is always one command away.
 */
public final class Demos {
	private Demos() {}

	private static final String KIND = PvpDimensions.MOD_ID + ":demos";
	/** Long enough after joining to land below the server's own welcome rather than inside it. */
	private static final long ASK_AFTER_MILLIS = 5_000L;
	/** A player who logs out inside a demo has this long to come back to it. */
	private static final long AWAY_MILLIS = 5 * 60_000L;

	private static List<Demo> demos = List.of();
	/** Demos being made, by the player they are for. */
	private static final Map<UUID, String> making = new HashMap<>();
	/** Players about to be asked, and when. */
	private static final Map<UUID, Long> asking = new HashMap<>();
	/** Players between two demos, whose trip out of the first is not the end of their onboarding. */
	private static final Set<UUID> hopping = new HashSet<>();
	private static final Map<String, Long> awaySince = new HashMap<>();

	public static void register() {
		Progress.init();
		PandoricalApi.notices().onChoice(KIND, (player, id, choice) -> {
			switch (choice) {
				case "play" -> {
					Demo next = next(player, null);
					if (next != null) play(player, next.id());
				}
				case "list" -> Menus.demos(player);
				default -> { }
			}
		});
	}

	public static void load(MinecraftServer server) {
		demos = DemoFiles.read(server);
		if (!demos.isEmpty()) PvpDimensions.LOGGER.info("Demos for {}", demos.stream().map(Demo::name).toList());
	}

	public static List<Demo> all() {
		return demos;
	}

	public static @Nullable Demo get(String id) {
		for (Demo demo : demos) if (demo.id().equals(id)) return demo;
		return null;
	}

	public static @Nullable Demo of(Arena arena) {
		return arena.demo.isEmpty() ? null : get(arena.demo);
	}

	/** The first demo not yet played after this one, in list order, coming round to the start; null when all are played. */
	public static @Nullable Demo next(ServerPlayer player, @Nullable String after) {
		Progress progress = Progress.of(player);
		int start = 0;
		for (int i = 0; i < demos.size(); i++) if (demos.get(i).id().equals(after)) start = i + 1;
		for (int i = 0; i < demos.size(); i++) {
			Demo demo = demos.get((start + i) % demos.size());
			if (!demo.id().equals(after) && !progress.played(demo)) return demo;
		}
		return null;
	}

	// --- Playing ---

	/** A fresh copy of this demo for this player, taken in the moment it is ready: from home, or straight from the demo they are in. */
	public static void play(ServerPlayer player, String id) {
		MinecraftServer server = player.level().getServer();
		Demo demo = get(id);
		if (demo == null) {
			Say.to(player, "There is no demo called " + id);
			return;
		}
		Arena current = Arenas.of(player);
		if (current != null && current.demo.isEmpty()) {
			Say.to(player, "Leave " + current.title() + " first: /pvp leave");
			return;
		}
		if (current != null && current.demo.equals(id)) {
			Say.to(player, "You're in the " + demo.title() + " already");
			return;
		}
		if (ItemSessions.editing(player)) {
			Say.to(player, "Finish editing items first: /pvp done");
			return;
		}
		if (justfatlard.pvp_dimensions.integration.SpawnLock.held(player)) {
			Say.to(player, "Not while you're held at the spawn");
			return;
		}
		if (current == null && Places.heldElsewhere(player.level())) {
			Say.to(player, "Not from here: leave this world first");
			return;
		}
		String already = making.remove(player.getUUID());
		Arena stale = already == null ? null : Arenas.get(server, already);
		if (stale != null) Arenas.end(server, stale, "another demo was picked");
		if (Arenas.demos(server).size() >= ServerConfig.demoArenas()) {
			Say.to(player, "Every demo space is in use; try again in a minute");
			return;
		}
		Arena arena = Arenas.start(server, player, demo.presetId(), demo.preset(),
			new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, false), message -> Say.to(player, message), demo.id());
		if (arena == null) return;
		making.put(player.getUUID(), arena.id);
		Say.bar(player, "Making the " + demo.title());
	}

	/** Set down in a demo: counted as stepped into, told what it is, and its hook told. */
	public static void arrived(ServerPlayer player, Arena arena) {
		Demo demo = of(arena);
		if (demo == null) return;
		Progress.enter(player, demo.id());
		player.sendSystemMessage(Say.line(demo.title()).withStyle(net.minecraft.ChatFormatting.BOLD));
		for (String line : demo.about()) Say.to(player, line);
		Say.to(player, Say.line((demo.finishes() ? "Finish it to tick it off. " : "")
			+ "Your own things are kept safe until you leave. ")
			.append(Say.button("Other demos", "/pvp demos")).append(" ").append(Say.button("Leave", "/pvp leave")));
		call(demo, player, arena, DemoHook::arrived);
	}

	/**
	 * Out of a demo, by whatever way. A win there finishes it. Unless they are only on their way
	 * to another demo, the next one not yet played is offered.
	 */
	public static void left(ServerPlayer player, Arena arena, boolean won) {
		Demo demo = of(arena);
		if (demo == null) return;
		if (won) finish(player, demo);
		call(demo, player, arena, DemoHook::left);
		if (hopping.contains(player.getUUID())) return;
		Demo next = next(player, demo.id());
		if (next == null) return;
		player.sendSystemMessage(Say.line("Next: " + next.name() + " ")
			.append(Say.button("Play", "/pvp demo " + next.id())).append(" ").append(Say.button("All demos", "/pvp demos")));
	}

	static void finish(ServerPlayer player, Demo demo) {
		if (Progress.of(player).finished().contains(demo.id())) return;
		Progress.finish(player, demo.id());
		if (demo.finishes()) Say.to(player, demo.title() + " done");
	}

	/** Every second: demos that are ready taken to, empty ones closed, hooks ticked, and the asking done. */
	public static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, String> entry : List.copyOf(making.entrySet())) {
			Arena arena = Arenas.get(server, entry.getValue());
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (arena == null || arena.phase == Arena.Phase.ENDED) {
				making.remove(entry.getKey());
			} else if (player == null) {
				making.remove(entry.getKey());
				Arenas.end(server, arena, "its player left");
			} else if (arena.phase == Arena.Phase.LIVE) {
				making.remove(entry.getKey());
				takeIn(server, player, arena);
			}
		}

		Set<String> waited = new HashSet<>(making.values());
		for (Arena arena : Arenas.demos(server)) {
			if (arena.phase != Arena.Phase.LIVE || waited.contains(arena.id)) continue;
			List<Arena.Member> inside = arena.inside();
			if (inside.isEmpty()) {
				Arenas.end(server, arena, "nobody is left in it");
				continue;
			}
			boolean anyHere = false;
			Demo demo = of(arena);
			for (Arena.Member member : inside) {
				ServerPlayer player = server.getPlayerList().getPlayer(member.id);
				if (player == null) continue;
				anyHere = true;
				if (demo != null) call(demo, player, arena, DemoHook::tick);
			}
			if (anyHere) awaySince.remove(arena.id);
			else if (now - awaySince.computeIfAbsent(arena.id, id -> now) > AWAY_MILLIS) Arenas.end(server, arena, "its player was away too long");
		}
		awaySince.keySet().removeIf(id -> {
			Arena arena = Arenas.get(server, id);
			return arena == null || arena.phase == Arena.Phase.ENDED;
		});

		for (Map.Entry<UUID, Long> entry : List.copyOf(asking.entrySet())) {
			if (now < entry.getValue()) continue;
			asking.remove(entry.getKey());
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) ask(player);
		}
	}

	/**
	 * Into a demo that is ready. From another demo, out of that one first, keeping the home they
	 * came from; the trip home that leaving makes is kept back for if the way in is refused.
	 */
	private static void takeIn(MinecraftServer server, ServerPlayer player, Arena arena) {
		Visit visit = Visit.of(player);
		if (visit == null) {
			Travel.join(player, arena);
			return;
		}
		Arena from = Arenas.get(server, visit.arena());
		if (from == null || from.demo.isEmpty()) {
			Say.to(player, "You're in an arena now; the demo is closed");
			Arenas.end(server, arena, "its player is in an arena");
			return;
		}
		TeleportTransition home;
		hopping.add(player.getUUID());
		try {
			home = Travel.leave(player, from, Travel.Why.MOVED);
		} finally {
			hopping.remove(player.getUUID());
		}
		TeleportTransition in = Travel.enter(player, arena, visit.home());
		if (in != null) player.teleport(in);
		else if (home != null) player.teleport(home);
		Arenas.end(server, from, "moved on to another demo");
	}

	// --- Asking ---

	/** Logging in: asked about any demo not yet asked about, a moment after arriving. */
	public static void loggedIn(ServerPlayer player) {
		if (!ServerConfig.onboarding() || demos.isEmpty()) return;
		asking.put(player.getUUID(), System.currentTimeMillis() + ASK_AFTER_MILLIS);
	}

	public static void forget(UUID player) {
		asking.remove(player);
		hopping.remove(player);
	}

	private static void ask(ServerPlayer player) {
		if (!ServerConfig.onboarding()) return;
		if (Visit.of(player) != null || justfatlard.pvp_dimensions.integration.SpawnLock.held(player)
				|| Places.heldElsewhere(player.level())) {
			asking.put(player.getUUID(), System.currentTimeMillis() + 60_000L);
			return;
		}
		Progress progress = Progress.of(player);
		Set<String> fresh = new LinkedHashSet<>();
		for (Demo demo : demos) if (!progress.offered().contains(demo.id()) && !progress.played(demo)) fresh.add(demo.id());
		if (fresh.isEmpty()) return;
		Progress.offer(player, fresh);

		String asked;
		if (progress.offered().isEmpty() && progress.entered().isEmpty()) {
			asked = fresh.size() == 1 ? "New here? There's a short demo to play: " + get(fresh.iterator().next()).name()
				: "New here? Each mod here has a short demo to play: " + fresh.size() + " to try, in any order";
		} else {
			List<String> names = new ArrayList<>();
			for (String id : fresh) names.add(get(id).name());
			asked = (names.size() == 1 ? "New demo: " : "New demos: ") + String.join(", ", names);
		}
		if (!PandoricalApi.hasCapability(player, Capabilities.SCREENS)) {
			Component line = Say.line(asked + " ").append(Say.button("Play", "/pvp demo " + fresh.iterator().next()))
				.append(" ").append(Say.button("All demos", "/pvp demos"));
			player.sendSystemMessage(line);
			return;
		}
		PandoricalApi.notices().offer(player, new NoticeApi.Notice("demos", KIND, "minecraft:map", asked,
			List.of(new NoticeApi.Choice("play", "minecraft:oak_door", "Play"),
				new NoticeApi.Choice("list", "minecraft:book", "All demos"),
				new NoticeApi.Choice("later", "minecraft:clock", "Later")),
			0));
	}

	// --- Hooks ---

	private static void call(Demo demo, ServerPlayer player, Arena arena, java.util.function.BiConsumer<DemoHook, DemoRun> what) {
		DemoHook hook = demo.hook();
		if (hook == null) return;
		ServerLevel level = Arenas.level(player.level().getServer(), arena);
		if (level == null) return;
		try {
			what.accept(hook, new Run(player, level, arena, demo));
		} catch (Exception e) {
			PvpDimensions.LOGGER.error("{}'s demo hook failed", demo.id(), e);
		}
	}

	private record Run(ServerPlayer player, ServerLevel level, Arena arena, Demo demo) implements DemoRun {
		@Override
		public BlockPos at(int east, int y, int south) {
			return new BlockPos(arena.footprint().minX() + east, y, arena.footprint().minZ() + south);
		}

		@Override
		public int width() {
			return arena.footprint().width();
		}

		@Override
		public void finish() {
			Demos.finish(player, demo);
		}

		@Override
		public boolean finished() {
			return Progress.of(player).finished().contains(demo.id());
		}
	}
}
