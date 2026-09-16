package justfatlard.pvp_dimensions.arena;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.ServerConfig;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.ui.MainScreen;
import justfatlard.pvp_dimensions.world.ArenaGenerator;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Footprints;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Arenas from start to finish: made on a fresh plot, waited on while their ground is generated,
 * opened, fought in, and ended, with everybody sent home.
 */
public final class Arenas {
	private Arenas() {}

	/** Holds an arena's chunks loaded and ticking while it is being built or played in. */
	public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, PvpDimensions.id("arena"),
		new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

	/** How long an open arena with nobody in it keeps its chunks loaded. */
	private static final long IDLE_RELEASE_MILLIS = 2 * 60_000L;

	public enum Invite { EVERYONE, CHOSEN, NOBODY }

	/** How an arena is started: who hears of it, whether a nearby frame is lit, whether its starter goes in. */
	public record Start(Invite invite, List<UUID> chosen, boolean portal, boolean join) {
		public static Start standard() {
			return new Start(Invite.EVERYONE, List.of(), true, true);
		}
	}

	private static final Map<String, ServerBossEvent> bars = new HashMap<>();
	private static final Map<String, Set<UUID>> waiting = new HashMap<>();
	private static final Set<String> ticketed = new HashSet<>();
	private static final Map<String, Long> emptySince = new HashMap<>();
	private static final Map<String, Long> lastMobs = new HashMap<>();
	private static int ticks;

	public static void init() {
		// Touching the class registers the ticket type at init, where registries are still open.
	}

	// --- Lookups ---

	public static ArenaVault vault(MinecraftServer server) {
		return ArenaVault.get(server);
	}

	public static @Nullable Arena get(MinecraftServer server, String id) {
		return vault(server).arenas().get(id);
	}

	/** Every arena that has not ended, oldest first. */
	public static List<Arena> running(MinecraftServer server) {
		return vault(server).arenas().values().stream()
			.filter(arena -> arena.phase != Arena.Phase.ENDED)
			.sorted(Comparator.comparingLong(arena -> arena.createdAt))
			.toList();
	}

	/** The arena this player is in, by their visit, while it runs. */
	public static @Nullable Arena of(ServerPlayer player) {
		Visit visit = Visit.of(player);
		if (visit == null) return null;
		Arena arena = get(player.level().getServer(), visit.arena());
		return arena != null && arena.phase != Arena.Phase.ENDED ? arena : null;
	}

	/** The arena standing at this spot of an arena dimension. */
	public static @Nullable Arena at(ServerLevel level, double x, double z) {
		Footprint footprint = Footprints.atBlock(level.dimension(), x, z);
		return footprint == null ? null : get(level.getServer(), footprint.arena());
	}

	public static @Nullable ServerLevel level(MinecraftServer server, Arena arena) {
		return server.getLevel(arena.dimension);
	}

	// --- Server lifecycle ---

	/**
	 * Before the dimensions load: the plots of arenas that finished last time are deleted, file
	 * by file, so their chunks come back as empty void the next time anything asks.
	 */
	public static void beforeLevels(MinecraftServer server) {
		Presets.load(server.registryAccess());
		SavedTerrains.load(server);
		Path root = server.getWorldPath(LevelResource.ROOT);
		for (ArenaVault.DeadPlot dead : vault(server).takeDead()) {
			Path dimension = DimensionType.getStorageFolder(dead.dimension(), root);
			int fromX = Places.cellX(dead.plot()) >> 9;
			int fromZ = Places.cellZ(dead.plot()) >> 9;
			int toX = (Places.cellX(dead.plot()) + Places.SPACING - 1) >> 9;
			int toZ = (Places.cellZ(dead.plot()) + Places.SPACING - 1) >> 9;
			for (String folder : List.of("region", "entities", "poi")) {
				for (int x = fromX; x <= toX; x++) {
					for (int z = fromZ; z <= toZ; z++) {
						try {
							Files.deleteIfExists(dimension.resolve(folder).resolve("r." + x + "." + z + ".mca"));
						} catch (IOException e) {
							PvpDimensions.LOGGER.warn("Could not clear a finished arena's region file", e);
						}
					}
				}
			}
		}
	}

	/** Once the dimensions exist: every running arena is put back as it was. */
	public static void afterLevels(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (Arena arena : List.copyOf(vault(server).arenas().values())) {
			if (arena.phase == Arena.Phase.ENDED) continue;
			Footprints.put(arena.footprint());
			Portals.index(arena);
			Teams.create(server, arena);
			if (arena.phase == Arena.Phase.GENERATING) hold(server, arena);
			if (arena.endsAt > 0 && now >= arena.endsAt) end(server, arena, "ran its time while the server was down");
		}
	}

	public static void stopping(MinecraftServer server) {
		for (ServerBossEvent bar : bars.values()) bar.removeAllPlayers();
		bars.clear();
		waiting.clear();
		ticketed.clear();
		emptySince.clear();
		Jobs.clear();
		Portals.clear();
	}

	// --- Starting ---

	/**
	 * A new arena from a preset, on a fresh plot. Its ground generates over the next moments;
	 * invitations go out and people are let in once it is ready.
	 *
	 * @param starter the player starting it, or null from the console
	 * @return the arena, or null with the reason already said to {@code complain}
	 */
	public static @Nullable Arena start(MinecraftServer server, @Nullable ServerPlayer starter, String presetId, Preset source,
			Start options, java.util.function.Consumer<String> complain) {
		List<Arena> open = running(server);
		if (open.size() >= ServerConfig.maxArenas()) {
			complain.accept("There are already " + open.size() + " arenas running, the most this server allows");
			return null;
		}
		if (starter != null && Access.of(starter) == Access.Tier.USER) {
			long mine = open.stream().filter(arena -> arena.host.equals(starter.getUUID())).count();
			if (mine >= ServerConfig.userArenas()) {
				complain.accept("You already have an arena running; end it first");
				return null;
			}
		}
		Preset preset = Presets.copy(source);
		if (preset.source == Preset.Source.SAVED && SavedTerrains.meta(preset.saved) == null) {
			complain.accept("The saved terrain \"" + preset.saved + "\" is gone");
			return null;
		}
		// Whoever starts one goes in, so pays like anyone: no arena made for a host who can't.
		String shortOf = starter != null && options.join() && !preset.entryFee.isEmpty() ? Fees.shortOf(starter, preset.entryFee) : null;
		if (shortOf != null) {
			complain.accept("It costs " + preset.entryFee.describe(6) + " to join; you're short " + shortOf);
			return null;
		}

		ServerLevel level = server.getLevel(Terrains.dimensionOf(preset));
		if (level == null || !(level.getChunkSource().getGenerator() instanceof ArenaGenerator generator)) {
			complain.accept("The arena dimensions are missing from this world");
			return null;
		}

		ArenaVault vault = vault(server);
		// A plot that is all sea, for a preset that did not ask for sea, is passed over for the
		// next: a few tries, keeping the best, since the next plot is only ever a few numbers on.
		int plot = vault.takePlot(level.dimension());
		Survey survey = survey(level, generator, preset, plot);
		for (int tries = 1; tries < 4 && survey.land() < 0.6; tries++) {
			int next = vault.takePlot(level.dimension());
			Survey another = survey(level, generator, preset, next);
			if (another.land() > survey.land()) {
				plot = next;
				survey = another;
			}
		}
		int number = vault.takeNumber();

		UUID host = starter != null ? starter.getUUID() : new UUID(0, 0);
		String hostName = starter != null ? starter.getGameProfile().name() : "Server";
		Arena arena = new Arena("a" + number, number, presetId, preset, host, hostName, level.dimension(), plot,
			survey.minChunkX, survey.minChunkZ, survey.chunks, survey.surfaceY, survey.highestY, survey.wallTop, survey.lobbyY,
			System.currentTimeMillis());
		arena.openToAll = options.invite() == Invite.EVERYONE;
		arena.invited.addAll(options.chosen());
		vault.add(arena);

		Footprints.put(arena.footprint());
		hold(server, arena);

		if (starter != null && options.portal()) {
			int lit = Portals.lightNear(starter.level(), starter.position(), arena);
			if (lit > 0) Say.to(starter, "The frame beside you is lit and leads into " + arena.title());
			else Say.to(starter, "No empty obsidian frame nearby to light; invitations are the way in");
		}
		if (starter != null && options.join()) waiting.computeIfAbsent(arena.id, id -> new HashSet<>()).add(starter.getUUID());
		for (UUID chosen : options.chosen()) {
			if (!chosen.equals(host)) invite(server, arena, chosen, starter);
		}
		PvpDimensions.LOGGER.info("{} started {} ({} chunks, {})", hostName, arena.title(), arena.chunks, level.dimension().identifier());
		MainScreen.refreshAll(server);
		return arena;
	}

	/** @param land how much of it is dry ground, 0 to 1; 1 wherever that is not a question */
	private record Survey(int minChunkX, int minChunkZ, int chunks, int surfaceY, int highestY, int wallTop, int lobbyY, double land) {}

	/**
	 * Where on its plot an arena goes, and how high its ground stands, worked out from the noise
	 * before a single chunk exists. For ground that grows, spots all across the plot are tried
	 * and the one with the most land and the gentlest hills is kept.
	 */
	private static Survey survey(ServerLevel level, ArenaGenerator generator, Preset preset, int plot) {
		boolean saved = preset.source == Preset.Source.SAVED;
		SavedTerrains.Meta meta = saved ? SavedTerrains.meta(preset.saved) : null;
		int chunks = meta != null ? meta.chunks() : Math.max(2, Math.min(Fields.MAX_SIZE, preset.size));
		int width = chunks * 16;
		int logicalTop = level.getMinY() + level.dimensionType().logicalHeight() - 1;
		int centerX = Places.centerX(plot);
		int centerZ = Places.centerZ(plot);
		int surfaceY;
		int highestY;
		double landShare = 1;

		if (meta != null) {
			surfaceY = meta.surfaceY();
			highestY = meta.highestY();
		} else if (preset.shape == Preset.Shape.FLAT) {
			surfaceY = ArenaGenerator.FLAT_TOP;
			highestY = surfaceY;
		} else if (preset.roofed()) {
			surfaceY = 64;
			highestY = logicalTop;
		} else {
			RandomState random = generator.randomState(level.getChunkSource().randomState());
			boolean wantsWater = preset.biome.contains("ocean") || preset.biome.contains("river");
			double bestScore = Double.NEGATIVE_INFINITY;
			int bestLand = 0;
			int reach = Places.SPACING / 2 - width / 2 - 64;
			int step = Math.max(64, reach / 3);
			int samples = 4;
			int bestX = centerX;
			int bestZ = centerZ;
			int bestSurface = generator.getSeaLevel();
			int bestHighest = generator.getSeaLevel();
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					int x = centerX + dx * step;
					int z = centerZ + dz * step;
					List<Integer> floors = new ArrayList<>();
					int land = 0;
					int highest = level.getMinY();
					for (int i = 0; i < samples; i++) {
						for (int j = 0; j < samples; j++) {
							int sx = x - width / 2 + (int) ((i + 0.5) * width / samples);
							int sz = z - width / 2 + (int) ((j + 0.5) * width / samples);
							int floor = generator.getBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
							int top = generator.getBaseHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, level, random);
							floors.add(floor);
							highest = Math.max(highest, top);
							boolean ground = preset.world == Preset.World.END ? floor > level.getMinY() + 1 : top - floor <= 1;
							if (ground) land++;
						}
					}
					double mean = floors.stream().mapToInt(Integer::intValue).average().orElse(0);
					double spread = Math.sqrt(floors.stream().mapToDouble(f -> (f - mean) * (f - mean)).average().orElse(0));
					int all = samples * samples;
					double score = (wantsWater ? all - land : land) * 6 - spread - Math.abs(dx) - Math.abs(dz);
					if (score > bestScore) {
						bestScore = score;
						bestLand = wantsWater ? all : land;
						bestX = x;
						bestZ = z;
						floors.sort(Integer::compare);
						bestSurface = preset.world == Preset.World.END
							? floors.stream().filter(f -> f > level.getMinY() + 1).findFirst().orElse(64)
							: floors.get(floors.size() / 2);
						if (preset.world == Preset.World.END) {
							List<Integer> islands = floors.stream().filter(f -> f > level.getMinY() + 1).toList();
							if (!islands.isEmpty()) bestSurface = islands.get(islands.size() / 2);
						}
						bestHighest = highest;
					}
				}
			}
			centerX = bestX;
			centerZ = bestZ;
			landShare = bestLand / (double) (samples * samples);
			surfaceY = Math.max(level.getMinY() + 1, bestSurface);
			highestY = Math.max(surfaceY, bestHighest);
		}

		int wallTop;
		if (meta != null) wallTop = meta.wallTop();
		else if (preset.bedrock == Preset.Bedrock.SHELL) wallTop = Math.min(logicalTop, highestY + 40);
		else wallTop = logicalTop;

		int lobbyY = preset.roofed() && !saved
			? 100
			: Math.max(highestY + 12, Math.min(wallTop - Lobby.HEIGHT - 3, highestY + 20));
		lobbyY = Math.min(lobbyY, wallTop - Lobby.HEIGHT - 3);

		int minChunkX = (centerX >> 4) - chunks / 2;
		int minChunkZ = (centerZ >> 4) - chunks / 2;
		return new Survey(minChunkX, minChunkZ, chunks, surfaceY, highestY, wallTop, lobbyY, landShare);
	}

	/** Load the arena's chunks, and keep them loaded. */
	private static void hold(MinecraftServer server, Arena arena) {
		if (!ticketed.add(arena.id)) return;
		ServerLevel level = level(server, arena);
		if (level == null) return;
		level.getChunkSource().addTicketWithRadius(TICKET, centerChunk(arena), (arena.chunks + 1) / 2);
	}

	private static void release(MinecraftServer server, Arena arena) {
		if (!ticketed.remove(arena.id)) return;
		ServerLevel level = level(server, arena);
		if (level == null) return;
		level.getChunkSource().removeTicketWithRadius(TICKET, centerChunk(arena), (arena.chunks + 1) / 2);
	}

	private static ChunkPos centerChunk(Arena arena) {
		return new ChunkPos(arena.minChunkX + arena.chunks / 2, arena.minChunkZ + arena.chunks / 2);
	}

	private static boolean generated(ServerLevel level, Arena arena) {
		for (int x = arena.minChunkX; x < arena.minChunkX + arena.chunks; x++) {
			for (int z = arena.minChunkZ; z < arena.minChunkZ + arena.chunks; z++) {
				if (level.getChunkSource().getChunkNow(x, z) == null) return false;
			}
		}
		return true;
	}

	/** Its ground is all there: pasted if saved, then the waiting room or straight into the fight. */
	private static void ready(MinecraftServer server, Arena arena) {
		ServerLevel level = level(server, arena);
		if (level == null) return;
		arena.phase = Arena.Phase.LOBBY;
		Teams.create(server, arena);
		Runnable open = () -> {
			if (arena.phase == Arena.Phase.ENDED) return;
			if (arena.preset.exitPortal) Portals.buildExits(level, arena);
			if (arena.preset.waitingRoom) {
				Lobby.build(level, arena);
				arena.lobbyEndsAt = System.currentTimeMillis() + ServerConfig.lobbyMinutes() * 60_000L;
			}
			vault(server).touch();
			announce(server, arena);
			MainScreen.refreshAll(server);
			Set<UUID> queued = waiting.remove(arena.id);
			if (queued != null) {
				for (UUID id : queued) {
					ServerPlayer player = server.getPlayerList().getPlayer(id);
					if (player != null) Travel.join(player, arena);
				}
			}
			if (!arena.preset.waitingRoom) goLive(server, arena);
		};
		if (arena.preset.source == Preset.Source.SAVED) SavedTerrains.paste(level, arena.footprint(), arena.preset.saved, open);
		else open.run();
	}

	/** The fight begins: the waiting room comes down, everyone is set down on the ground and handed their kit. */
	public static void goLive(MinecraftServer server, Arena arena) {
		if (arena.phase != Arena.Phase.LOBBY) return;
		ServerLevel level = level(server, arena);
		if (level == null) return;
		long now = System.currentTimeMillis();
		arena.phase = Arena.Phase.LIVE;
		arena.liveAt = now;
		arena.endsAt = arena.preset.lifeMinutes > 0 ? now + arena.preset.lifeMinutes * 60_000L : 0;
		if (arena.lobbyStanding) Lobby.remove(level, arena);
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || member.watching) continue;
			if (arena.preset.teamsOn() && member.team < 0) Teams.put(player, arena, member, Teams.smallest(arena));
		}
		TeamBases.build(level, arena);
		Goals.begin(server, level, arena);
		Waves.begin(arena, now);
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || member.watching) continue;
			Travel.setDown(player, arena, member);
		}
		Pinatas.begin(server, arena);
		vault(server).touch();
		tellInside(server, arena, arena.preset.lifeMinutes > 0
			? "Fight! " + arena.title() + " lasts " + Fields.duration(arena.preset.lifeMinutes)
			: "Fight!");
	}

	// --- Invitations ---

	public static void invite(MinecraftServer server, Arena arena, UUID who, @Nullable ServerPlayer from) {
		arena.invited.add(who);
		vault(server).touch();
		ServerPlayer player = server.getPlayerList().getPlayer(who);
		if (player == null || !arena.open()) return;
		player.sendSystemMessage(inviteLine(arena, from != null ? from.getGameProfile().name() : arena.hostName));
	}

	private static MutableComponent inviteLine(Arena arena, String from) {
		return Say.line(from + " invites you to " + arena.title() + " ")
			.append(Say.button("Join", "/pvp join " + arena.id));
	}

	/** Ready: whoever was invited hears it, with a button to go. */
	private static void announce(MinecraftServer server, Arena arena) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean waitingToGo = waiting.getOrDefault(arena.id, Set.of()).contains(player.getUUID());
			if (waitingToGo || Visit.of(player) != null) continue;
			if (arena.openToAll || arena.invited.contains(player.getUUID())) player.sendSystemMessage(inviteLine(arena, arena.hostName));
		}
	}

	/** Someone asked in before the ground was ready: taken in the moment it is. */
	public static void queue(Arena arena, ServerPlayer player) {
		waiting.computeIfAbsent(arena.id, id -> new HashSet<>()).add(player.getUUID());
	}

	// --- Every tick ---

	public static void tick(MinecraftServer server) {
		Jobs.tick();
		Curtain.tick();
		if (++ticks % 10 != 0) return;
		long now = System.currentTimeMillis();
		for (Arena arena : List.copyOf(vault(server).arenas().values())) {
			if (arena.phase == Arena.Phase.ENDED) continue;
			ServerLevel level = level(server, arena);
			if (level == null) continue;
			switch (arena.phase) {
				case GENERATING -> {
					if (generated(level, arena)) ready(server, arena);
				}
				case LOBBY -> lobbyTick(server, level, arena, now);
				case LIVE -> liveTick(server, level, arena, now);
				default -> { }
			}
			if (arena.phase == Arena.Phase.LOBBY || arena.phase == Arena.Phase.LIVE) Weather.tick(server, level, arena, now);
			if (arena.phase != Arena.Phase.ENDED) {
				idle(server, arena, now);
				if (ticks % 20 == 0) {
					bar(server, arena, now);
					justfatlard.pvp_dimensions.ui.GameHud.tick(server, arena);
				}
			}
		}
		if (ticks % 20 == 0) Travel.sweep(server);
		Landing.tick(server);
		if (ticks % 1200 == 0) vault(server).touch();
	}

	private static void lobbyTick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (!arena.lobbyStanding) return;
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || player.level() != level) continue;
			if (!Lobby.inside(arena, player.position())) {
				Vec3 back = Lobby.arrival(arena);
				player.teleportTo(level, back.x, back.y, back.z, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
				continue;
			}
			int standing = Lobby.standingOn(level, arena, player.position());
			if (standing >= 0 && standing != member.team) {
				Teams.put(player, arena, member, standing);
				Say.to(player, "You're on " + TeamColors.of(standing).name());
			}
			if (ticks % 40 == 0) Say.bar(player, lobbyLine(arena, player.getUUID()));
		}
		if (arena.lobbyEndsAt > 0 && now >= arena.lobbyEndsAt) {
			if (arena.inside().size() >= 2) goLive(server, arena);
			else end(server, arena, "nobody came to the waiting room");
		}
	}

	private static String lobbyLine(Arena arena, UUID viewer) {
		StringBuilder line = new StringBuilder();
		if (arena.preset.teamsOn()) {
			for (int team = 0; team < arena.preset.teams; team++) {
				if (team > 0) line.append("  ");
				line.append(TeamColors.of(team).name()).append(" ").append(arena.teamSize(team));
			}
			line.append("  |  Stand on a colour to join it");
		} else {
			line.append(arena.inside().size()).append(" waiting");
		}
		if (viewer.equals(arena.host)) line.append("  |  /pvp begin to start");
		return line.toString();
	}

	private static void liveTick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || player.level() != level || member.watching) continue;
			Borders.enforce(level, arena, player);
		}
		if (!arena.wallsDown && arena.terrain().divided() && arena.preset.wallsFall > 0
				&& now >= arena.liveAt + arena.preset.wallsFall * 60_000L) {
			wallsFall(server, level, arena);
		}
		Pinatas.tick(server, level, arena, now);
		ModeChanges.tick(server, arena, now);
		if (ticks % 20 == 0) Moments.tick(server, arena, now);
		Mobs.tick(server, level, arena, now, lastMobs);
		Waves.tick(server, level, arena, now, ticks);
		if (ticks % 40 == 0) Mobs.turnOnPlayers(server, level, arena);
		Goals.tick(server, level, arena, now);
		if (arena.phase != Arena.Phase.LIVE) return;
		if (arena.endsAt > 0 && now >= arena.endsAt && arena.closesAt == 0) {
			Goals.timeUp(server, arena);
			end(server, arena, "time is up");
			return;
		}
		if (ticks % 40 == 0) {
			for (Arena.Member member : arena.inside()) {
				String status = Goals.status(arena, member.id);
				ServerPlayer player = server.getPlayerList().getPlayer(member.id);
				// The drawn scoreboard says it already, and better.
				if (status != null && player != null && !justfatlard.pvp_dimensions.ui.GameHud.showing(player)) Say.bar(player, status);
			}
		}
		if (arena.preset.endWhenOneSideLeft && now - arena.liveAt > 15_000L && arena.closesAt == 0) {
			Set<String> sides = new HashSet<>();
			Arena.Member last = null;
			for (Arena.Member member : arena.fighting()) {
				if (server.getPlayerList().getPlayer(member.id) == null) continue;
				sides.add(arena.preset.teamsOn() ? "team" + member.team : member.id.toString());
				last = member;
			}
			if (sides.isEmpty()) end(server, arena, "everyone left");
			else if (sides.size() == 1 && arena.members.size() > 1) {
				if (arena.preset.teamsOn()) Goals.winTeam(server, arena, last.team, "the last side standing");
				else Goals.winPlayer(server, arena, last, "the last one standing");
			}
		}
	}

	/** Nobody inside for a while: its chunks are let go until somebody comes back. */
	private static void idle(MinecraftServer server, Arena arena, long now) {
		boolean occupied = arena.inside().stream().anyMatch(member -> server.getPlayerList().getPlayer(member.id) != null);
		if (arena.phase == Arena.Phase.GENERATING || occupied || Jobs.busy()) {
			emptySince.remove(arena.id);
			hold(server, arena);
			return;
		}
		long since = emptySince.computeIfAbsent(arena.id, id -> now);
		if (now - since > IDLE_RELEASE_MILLIS) release(server, arena);
	}

	public static void wake(MinecraftServer server, Arena arena) {
		emptySince.remove(arena.id);
		hold(server, arena);
	}

	/** The division walls come down, from the top like a curtain; the outer walls stay. */
	private static void wallsFall(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.wallsDown = true;
		vault(server).touch();
		tellInside(server, arena, "The walls are coming down!");
		Curtain.drop(level, arena);
	}

	/** The nearest column that is not wall, looking straight out from this one. */
	static int @Nullable [] besideWall(Footprint footprint, Terrain terrain, int x, int z) {
		int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
		for (int distance = 1; distance <= 3; distance++) {
			for (int[] direction : directions) {
				int sx = x + direction[0] * distance;
				int sz = z + direction[1] * distance;
				if (footprint.containsBlock(sx, sz) && terrain.wallAt(footprint, sx, sz) == null) return new int[] {sx, sz};
			}
		}
		return null;
	}

	// --- The bar ---

	private static void bar(MinecraftServer server, Arena arena, long now) {
		ServerBossEvent bar = bars.computeIfAbsent(arena.id, id -> new ServerBossEvent(UUID.nameUUIDFromBytes(("pvp-dimensions:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
			Component.literal(arena.title()), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10));
		String words;
		float progress = 1;
		switch (arena.phase) {
			case LOBBY -> words = arena.title() + ": waiting room";
			case LIVE -> {
				if (arena.endsAt > 0) {
					long left = Math.max(0, arena.endsAt - now);
					words = arena.title() + ": " + clock(left) + " left";
					progress = (float) left / Math.max(1, arena.endsAt - arena.liveAt);
				} else {
					words = arena.title();
				}
			}
			default -> words = arena.title() + ": getting ready";
		}
		bar.setName(Component.literal(words));
		bar.setProgress(Math.max(0, Math.min(1, progress)));
		Set<UUID> inside = new HashSet<>();
		for (Arena.Member member : arena.inside()) inside.add(member.id);
		for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
			if (!inside.contains(player.getUUID()) || player.isRemoved()) bar.removePlayer(player);
		}
		for (UUID id : inside) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && !bar.getPlayers().contains(player)) bar.addPlayer(player);
		}
	}

	public static void unbar(Arena arena, ServerPlayer player) {
		ServerBossEvent bar = bars.get(arena.id);
		if (bar != null) bar.removePlayer(player);
	}

	public static String clock(long millis) {
		long seconds = millis / 1000;
		if (seconds >= 3600) return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	// --- Ending ---

	/** Over: everyone inside is sent home, winners with their prize, and the frames go dark. */
	public static void end(MinecraftServer server, Arena arena, String why) {
		if (arena.phase == Arena.Phase.ENDED) return;
		String scores = scores(arena);
		tellInside(server, arena, arena.title() + " is over: " + why + "." + (scores.isEmpty() ? "" : " " + scores));
		arena.phase = Arena.Phase.ENDED;
		waiting.remove(arena.id);
		Fees.settle(arena);

		for (Arena.Member member : List.copyOf(arena.members.values())) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			// Somebody on the death screen has no body to send home or hand a pack to: their visit
			// ends when they respawn. Anyone away ends theirs when they next log in.
			if (player != null && member.inside && Visit.of(player) != null && !player.isDeadOrDying()) {
				Travel.goHome(player, arena, Travel.Why.ENDED);
			}
		}

		ServerBossEvent bar = bars.remove(arena.id);
		if (bar != null) bar.removeAllPlayers();
		Pinatas.clear(server, arena);
		ServerLevel ended = level(server, arena);
		if (ended != null && justfatlard.pvp_dimensions.integration.ChestUtils.INSTALLED) {
			for (BlockPos chest : arena.teamChests.keySet()) justfatlard.pvp_dimensions.integration.ChestUtils.strip(ended, chest);
			if (arena.preset.activeGoal() == Preset.Goal.CTF) {
				for (BlockPos chest : arena.bases) justfatlard.pvp_dimensions.integration.ChestUtils.strip(ended, chest);
			}
		}
		Goals.forget(arena);
		Weather.forget(arena);
		Traps.forget(arena);
		Waves.forget(arena);
		lastMobs.remove(arena.id);
		Portals.darken(server, arena);
		Teams.remove(server, arena);
		Gateways.petsHome(server, arena);
		release(server, arena);
		Footprints.remove(arena.id);
		emptySince.remove(arena.id);
		ArenaVault vault = vault(server);
		vault.bury(arena);
		forgetIfSettled(server, arena);
		vault.touch();
		MainScreen.refreshAll(server);
		PvpDimensions.LOGGER.info("{} ended: {}", arena.title(), why);
	}

	/** A finished arena is kept only while somebody still has a visit in it. */
	public static void forgetIfSettled(MinecraftServer server, Arena arena) {
		if (arena.phase != Arena.Phase.ENDED) return;
		boolean someoneAway = arena.members.values().stream().anyMatch(member -> member.inside);
		if (!someoneAway) vault(server).remove(arena);
	}

	/**
	 * The top five by kills where players fight; by the hunted mobs taken down on a hunt; and
	 * nothing for a fight against mobs that keeps no count.
	 */
	public static String scores(Arena arena) {
		boolean pvp = arena.preset.pvp;
		if (!pvp && arena.preset.activeGoal() != Preset.Goal.MOBS) return "";
		String what = pvp ? "kills" : Goals.mobWords(arena.preset) + " down";
		java.util.function.ToIntFunction<Arena.Member> count = pvp ? member -> member.kills : member -> member.mobKills;
		List<Arena.Member> ranked = new ArrayList<>(arena.members.values());
		ranked.removeIf(member -> count.applyAsInt(member) == 0);
		if (ranked.isEmpty()) return "No " + what + ".";
		ranked.sort(Comparator.comparingInt(count).reversed());
		StringBuilder line = new StringBuilder(what.substring(0, 1).toUpperCase() + what.substring(1) + ": ");
		for (int i = 0; i < Math.min(5, ranked.size()); i++) {
			if (i > 0) line.append(", ");
			line.append(ranked.get(i).name).append(" ").append(count.applyAsInt(ranked.get(i)));
		}
		return line.toString();
	}

	public static void tellInside(MinecraftServer server, Arena arena, String words) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null) Say.to(player, words);
		}
	}

	public static void tellInside(MinecraftServer server, Arena arena, Component words) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null) player.sendSystemMessage(words);
		}
	}

	/** "Skirmish #3, 4 inside, 12:30 left" for a list. */
	public static Component summary(Arena arena) {
		long now = System.currentTimeMillis();
		String state = switch (arena.phase) {
			case GENERATING -> "getting ready";
			case LOBBY -> "waiting room";
			case LIVE -> arena.endsAt > 0 ? clock(Math.max(0, arena.endsAt - now)) + " left" : "for good";
			case ENDED -> "over";
		};
		return Component.literal(arena.title()).withStyle(ChatFormatting.WHITE)
			.append(Component.literal(" (" + arena.id + "), " + arena.inside().size() + " inside, " + state + ", host " + arena.hostName)
				.withStyle(ChatFormatting.GRAY));
	}
}
