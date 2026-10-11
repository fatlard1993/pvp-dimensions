package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Guess Who, between two teams: each has the same gallery of sixteen suspects at its own end of
 * the ground, and a suspect of its own that the other team is trying to find. On your turn, ask the
 * other team one yes-or-no question out loud and they answer it, truthfully; flip down the suspects
 * the answer rules out with the levers in front of them; and ring your bell to end the turn. When
 * you think you know, right-click your suspect instead: right wins it, wrong loses it.
 *
 * <p>The questions are the players' own. Nothing here hears them, so nothing here can answer them
 * or check an answer; what the game keeps is whose turn it is, who each team's suspect is, and
 * which faces each team has flipped down. Each team flips in its own gallery, which is all its own
 * thinking: the other team's is across the ground and none of its business.
 *
 * <p>The suspects are villagers from every biome at every trade, and a few farm animals, some of
 * them young. Enough of them share a feature - a biome, a trade, being a baby, being a villager at
 * all - that a good question halves the gallery, which is the game.
 *
 * <p>A gallery's first corner is {@link Arena#bases}, one per team. The turn, the suspects and the
 * flips are not saved: a game carried over a restart starts over with new suspects.
 */
public final class GuessWho {
	private GuessWho() {}

	public static final String TAG = "pvp-dimensions.suspect";
	/** Columns and rows of suspects in a gallery. */
	private static final int COLUMNS = 4;
	private static final int ROWS = 4;
	/** Blocks between neighbouring suspects across a row, and between rows. */
	private static final int ACROSS = 3;
	private static final int DOWN = 4;
	/** How far in front of its suspect a lever stands. */
	private static final int LEVER = 2;
	private static final long TURN_MILLIS = 120_000L;
	/** How long a first right-click on a suspect waits for the second that accuses them. */
	private static final long ACCUSE_MILLIS = 5_000L;
	/** A baby's age, held: young for longer than any game. */
	private static final int YOUNG = -1_000_000;
	private static final int BEHIND = 6;

	/** A suspect: what they are called, how they are described, and how to make them. */
	private record Suspect(String name, String what, EntityType<? extends Mob> type, boolean baby, Consumer<Mob> dress) {}

	private static Consumer<Mob> villager(ResourceKey<VillagerType> biome, ResourceKey<VillagerProfession> trade) {
		return mob -> {
			if (!(mob instanceof Villager villager)) return;
			var registries = mob.level().registryAccess();
			villager.setVillagerData(villager.getVillagerData()
				.withType(registries.lookupOrThrow(Registries.VILLAGER_TYPE).getOrThrow(biome))
				.withProfession(registries.lookupOrThrow(Registries.VILLAGER_PROFESSION).getOrThrow(trade))
				.withLevel(2));
			villager.setVillagerDataFinalized(true);
		};
	}

	private static Consumer<Mob> wool(DyeColor colour) {
		return mob -> {
			if (mob instanceof Sheep sheep) sheep.setColor(colour);
		};
	}

	private static final Consumer<Mob> PLAIN = mob -> { };

	public static final int SUSPECT_COUNT = COLUMNS * ROWS;

	private static final List<Suspect> SUSPECTS = List.of(
		new Suspect("Ada", "a plains farmer", EntityTypes.VILLAGER, false, villager(VillagerType.PLAINS, VillagerProfession.FARMER)),
		new Suspect("Basil", "a desert librarian", EntityTypes.VILLAGER, false, villager(VillagerType.DESERT, VillagerProfession.LIBRARIAN)),
		new Suspect("Cleo", "a snowy cleric", EntityTypes.VILLAGER, false, villager(VillagerType.SNOW, VillagerProfession.CLERIC)),
		new Suspect("Dorian", "a taiga butcher", EntityTypes.VILLAGER, false, villager(VillagerType.TAIGA, VillagerProfession.BUTCHER)),
		new Suspect("Esme", "a jungle fisherman", EntityTypes.VILLAGER, false, villager(VillagerType.JUNGLE, VillagerProfession.FISHERMAN)),
		new Suspect("Felix", "a savanna armorer", EntityTypes.VILLAGER, false, villager(VillagerType.SAVANNA, VillagerProfession.ARMORER)),
		new Suspect("Gus", "a swamp cartographer", EntityTypes.VILLAGER, false, villager(VillagerType.SWAMP, VillagerProfession.CARTOGRAPHER)),
		new Suspect("Hazel", "a plains child", EntityTypes.VILLAGER, true, villager(VillagerType.PLAINS, VillagerProfession.NONE)),
		new Suspect("Ivo", "a desert child", EntityTypes.VILLAGER, true, villager(VillagerType.DESERT, VillagerProfession.NONE)),
		new Suspect("Juno", "a snowy shepherd", EntityTypes.VILLAGER, false, villager(VillagerType.SNOW, VillagerProfession.SHEPHERD)),
		new Suspect("Kip", "a white sheep", EntityTypes.SHEEP, false, wool(DyeColor.WHITE)),
		new Suspect("Lark", "a black lamb", EntityTypes.SHEEP, true, wool(DyeColor.BLACK)),
		new Suspect("Moss", "a pig", EntityTypes.PIG, false, PLAIN),
		new Suspect("Nell", "a piglet", EntityTypes.PIG, true, PLAIN),
		new Suspect("Otto", "a cow", EntityTypes.COW, false, PLAIN),
		new Suspect("Pip", "a chick", EntityTypes.CHICKEN, true, PLAIN));

	private static final Random DRAW = new Random();

	private static final class Game {
		/** Each team's suspect, the one the other team is after, by place in the gallery. */
		final int[] secret = new int[2];
		/** Each team's gallery: which suspects it has flipped down. */
		final boolean[][] down = new boolean[2][SUSPECT_COUNT];
		/** The mobs standing in each gallery, by place. */
		final Mob[][] standing = new Mob[2][SUSPECT_COUNT];
		final Turns turn;
		/** A suspect somebody has right-clicked once, waiting for the second click that accuses. */
		final Map<UUID, Integer> accusing = new HashMap<>();
		final Map<UUID, Long> accusedAt = new HashMap<>();

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The galleries ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		for (int team = 0; team < 2; team++) arena.bases.add(gallerySpot(level, arena, team));
		Game game = new Game(DRAW.nextInt(2));
		games.put(arena.id, game);
		setUp(server, level, arena, game);
		turnTo(server, arena, game, game.turn.side);
	}

	/** Both galleries built and stood full, and each team told its suspect. */
	private static void setUp(MinecraftServer server, ServerLevel level, Arena arena, Game game) {
		clearSuspects(level, arena);
		for (int team = 0; team < 2; team++) {
			build(level, arena.bases.get(team), team);
			for (int place = 0; place < SUSPECT_COUNT; place++) game.standing[team][place] = stand(level, arena.bases.get(team), team, place);
			game.secret[team] = DRAW.nextInt(SUSPECT_COUNT);
		}
		for (Arena.Member member : arena.inside()) tellSecret(server, arena, game, member);
	}

	private static void tellSecret(MinecraftServer server, Arena arena, Game game, Arena.Member member) {
		ServerPlayer player = server.getPlayerList().getPlayer(member.id);
		if (player == null || member.team < 0 || member.team > 1) return;
		Suspect mine = SUSPECTS.get(game.secret[member.team]);
		Say.to(player, "Your team's suspect is " + mine.name() + ", " + mine.what() + ". " + TeamColors.of(1 - member.team).name()
			+ " is trying to find them: answer their questions truthfully, out loud.");
	}

	/** Each team's end of the ground, on its own spoke from the middle, as far out as leaves room behind. */
	private static BlockPos gallerySpot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		int width = footprint.width();
		int deep = ROWS * DOWN;
		double radius = Math.max(deep, Math.min(width * Preset.GOAL_RING, width / 2.0 - deep / 2.0 - BEHIND));
		double angle = arena.preset.ringAngle(team);
		int x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) Math.floor(footprint.centerZ() + Math.sin(angle) * radius);
		int y = Builds.ground(level, arena, x, z);
		return new BlockPos(x - COLUMNS * ACROSS / 2, y, z - deep / 2);
	}

	private static BlockPos spot(BlockPos corner, int place) {
		return corner.offset(1 + (place % COLUMNS) * ACROSS, 0, 1 + (place / COLUMNS) * DOWN);
	}

	private static BlockPos leverAt(BlockPos corner, int place) {
		return spot(corner, place).offset(0, 0, LEVER);
	}

	private static BlockPos bellAt(BlockPos corner) {
		return corner.offset(COLUMNS * ACROSS / 2, 0, -2);
	}

	/** The gallery floor in the team's colour, a pen of wool under each suspect, a lever before each, and the bell. */
	private static void build(ServerLevel level, BlockPos corner, int team) {
		int wide = COLUMNS * ACROSS;
		int deep = ROWS * DOWN;
		Builds.yard(level, corner.getX() + wide / 2, corner.getY(), corner.getZ() + deep / 2, Math.max(wide, deep) / 2 + 3, 4);
		BlockState floor = Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
		for (int x = -1; x <= wide; x++) {
			for (int z = -3; z <= deep; z++) level.setBlock(corner.offset(x, -1, z), floor, Block.UPDATE_ALL);
		}
		for (int place = 0; place < SUSPECT_COUNT; place++) {
			pen(level, corner, place, false);
			level.setBlock(leverAt(corner, place), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), Block.UPDATE_ALL);
		}
		level.setBlock(bellAt(corner), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** The square under a suspect: white while they stand, black once flipped down. */
	private static void pen(ServerLevel level, BlockPos corner, int place, boolean down) {
		BlockState wool = Terrain.block(down ? "minecraft:black_wool" : "minecraft:white_wool", Blocks.STONE.defaultBlockState());
		level.setBlock(spot(corner, place).below(), wool, Block.UPDATE_ALL);
	}

	/** A suspect stood in their place: still, silent, unhurt, named, and turned to face the lever. */
	private static @Nullable Mob stand(ServerLevel level, BlockPos corner, int team, int place) {
		Suspect suspect = SUSPECTS.get(place);
		Mob mob = suspect.type().create(level, EntitySpawnReason.EVENT);
		if (mob == null) return null;
		BlockPos at = spot(corner, place);
		mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		mob.setYHeadRot(0);
		mob.setNoAi(true);
		mob.setSilent(true);
		mob.setPermanentlyInvulnerable(true);
		mob.setPersistenceRequired();
		mob.setCustomName(Component.literal(suspect.name()));
		mob.setCustomNameVisible(true);
		mob.addTag(TAG);
		if (mob instanceof AgeableMob young) young.setAge(suspect.baby() ? YOUNG : 0);
		suspect.dress().accept(mob);
		level.addFreshEntity(mob);
		return mob;
	}

	private static void clearSuspects(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob -> mob.entityTags().contains(TAG))) mob.discard();
	}

	/** Whether this block is part of a gallery, its levers or its bell, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.GUESS_WHO) return false;
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			int up = pos.getY() - corner.getY();
			if (x >= -1 && x <= COLUMNS * ACROSS && z >= -3 && z <= ROWS * DOWN && (up == -1 || up == 0)) return true;
		}
		return false;
	}

	// --- Playing ---

	/**
	 * A player right-clicked a block: a lever in their own gallery flips its suspect down or back
	 * up; their bell, on their turn, ends it. Anything else here is somebody else's.
	 */
	public static InteractionResult useBlock(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		Game game = arena == null ? null : games.get(arena.id);
		if (game == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.GUESS_WHO || arena.bases.size() < 2) return InteractionResult.PASS;
		for (int team = 0; team < 2; team++) {
			BlockPos corner = arena.bases.get(team);
			int place = -1;
			for (int i = 0; i < SUSPECT_COUNT; i++) if (leverAt(corner, i).equals(pos)) place = i;
			boolean bell = bellAt(corner).equals(pos);
			if (place < 0 && !bell) continue;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Arena.Member member = arena.member(player.getUUID());
			if (member == null || member.watching || member.out) return InteractionResult.FAIL;
			if (member.team != team) {
				Say.bar(player, "That's " + TeamColors.of(team).name() + "'s gallery; yours is at your own end");
				return InteractionResult.FAIL;
			}
			if (bell) {
				if (game.turn.side != team) {
					Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
					return InteractionResult.FAIL;
				}
				Arenas.tellInside(player.level().getServer(), arena, member.name + " rings the bell: " + TeamColors.of(team).name() + "'s turn is over");
				turnTo(player.level().getServer(), arena, game, 1 - team);
				// Let it ring.
				return InteractionResult.PASS;
			}
			flip(level, arena, game, team, place, !game.down[team][place]);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** A suspect flipped down, hidden in their pen, or stood back up. */
	public static void flip(ServerLevel level, Arena arena, int team, int place, boolean down) {
		Game game = games.get(arena.id);
		if (game != null) flip(level, arena, game, team, place, down);
	}

	private static void flip(ServerLevel level, Arena arena, Game game, int team, int place, boolean down) {
		game.down[team][place] = down;
		BlockPos corner = arena.bases.get(team);
		pen(level, corner, place, down);
		BlockPos lever = leverAt(corner, place);
		BlockState state = level.getBlockState(lever);
		if (state.is(Blocks.LEVER)) level.setBlock(lever, state.setValue(LeverBlock.POWERED, down), Block.UPDATE_ALL);
		Mob mob = game.standing[team][place];
		if (mob != null) {
			mob.setInvisible(down);
			mob.setCustomNameVisible(!down);
		}
	}

	/**
	 * A player right-clicked a suspect. Never a trade, a shearing or a lead: a suspect is only ever
	 * accused, by a second click within a few seconds of the first, on their own team's turn and in
	 * their own gallery.
	 */
	public static InteractionResult useEntity(ServerPlayer player, Entity entity, InteractionHand hand) {
		if (!entity.entityTags().contains(TAG)) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Arena arena = Arenas.of(player);
		Game game = arena == null ? null : games.get(arena.id);
		Arena.Member member = arena == null ? null : arena.member(player.getUUID());
		if (game == null || member == null || member.watching || member.out || arena.closesAt > 0
				|| member.team < 0 || member.team > 1) return InteractionResult.FAIL;
		int place = -1;
		for (int i = 0; i < SUSPECT_COUNT; i++) if (game.standing[member.team][i] == entity) place = i;
		if (place < 0) {
			Say.bar(player, "Accuse from your own gallery");
			return InteractionResult.FAIL;
		}
		if (game.turn.side != member.team) {
			Say.bar(player, "Accuse on your own turn");
			return InteractionResult.FAIL;
		}
		long now = System.currentTimeMillis();
		Integer first = game.accusing.get(player.getUUID());
		if (first == null || first != place || now - game.accusedAt.getOrDefault(player.getUUID(), 0L) > ACCUSE_MILLIS) {
			game.accusing.put(player.getUUID(), place);
			game.accusedAt.put(player.getUUID(), now);
			Say.bar(player, "Right-click " + SUSPECTS.get(place).name() + " again to accuse them. Wrong, and your team loses");
			return InteractionResult.SUCCESS;
		}
		game.accusing.remove(player.getUUID());
		accuse(player.level().getServer(), arena, member.team, place, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team names the other team's suspect: right wins it, wrong loses it. */
	public static void accuse(MinecraftServer server, Arena arena, int team, int place, String by) {
		Game game = games.get(arena.id);
		if (game == null || arena.closesAt > 0) return;
		int enemy = 1 - team;
		Suspect named = SUSPECTS.get(place);
		Suspect actual = SUSPECTS.get(game.secret[enemy]);
		if (place == game.secret[enemy]) {
			Goals.winTeam(server, arena, team, by + " named " + named.name() + ", " + named.what());
		} else {
			Goals.winTeam(server, arena, enemy, by + " accused " + named.name() + ", but it was " + actual.name() + ", " + actual.what());
		}
	}

	/** Which suspect this team is hiding, by place in the gallery: for the tests to aim at. */
	public static int secretOf(Arena arena, int team) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.secret[team];
	}

	public static boolean isDown(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		return game != null && game.down[team][place];
	}

	public static BlockPos leverOf(Arena arena, int team, int place) {
		return leverAt(arena.bases.get(team), place);
	}

	public static BlockPos bellOf(Arena arena, int team) {
		return bellAt(arena.bases.get(team));
	}

	public static @Nullable Mob suspectAt(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.standing[team][place];
	}

	public static int side(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.turn.side;
	}

	// --- Turns ---

	/** The turn to this side, or past it to the other where it has nobody to play it. */
	private static void turnTo(MinecraftServer server, Arena arena, Game game, int side) {
		if (game.turn.to(arena, side)) announce(server, arena, game);
	}

	private static void announce(MinecraftServer server, Arena arena, Game game) {
		int to = game.turn.side;
		Arenas.tellInside(server, arena, TeamColors.of(to).name() + " asks: one yes-or-no question, out loud. Flip down who it rules out, then ring your bell");
		Component title = Component.literal("Your turn").withStyle(style -> style.withColor(TeamColors.of(to).team().textColor()));
		Component subtitle = Component.literal("Ask a question, flip, ring the bell").withStyle(ChatFormatting.YELLOW);
		for (Arena.Member member : arena.inside()) {
			if (member.team != to) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
		}
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: nobody wrote down who anybody's suspect was. A new game, in the same galleries.
			if (arena.bases.size() < 2) return;
			game = new Game(0);
			games.put(arena.id, game);
			setUp(server, level, arena, game);
			Arenas.tellInside(server, arena, "The case went cold; new suspects are in, and nobody is flipped down");
			turnTo(server, arena, game, 0);
			return;
		}
		for (int team = 0; team < 2; team++) {
			for (int place = 0; place < SUSPECT_COUNT; place++) {
				Mob mob = game.standing[team][place];
				// The young stay young, however long the game runs.
				if (mob instanceof AgeableMob young && SUSPECTS.get(place).baby() && young.getAge() > YOUNG / 2) young.setAge(YOUNG);
			}
		}
		int was = game.turn.side;
		Turns.Passed passed = game.turn.check(arena, now);
		if (passed == Turns.Passed.TOO_LONG) Arenas.tellInside(server, arena, TeamColors.of(was).name() + " took too long; the turn passes");
		if (passed != Turns.Passed.NO) announce(server, arena, game);
	}

	// --- Words ---

	/** The bar's line: the viewer's team's suspect, and whose turn it is. */
	static @Nullable String status(Arena arena, Arena.@Nullable Member member) {
		Game game = games.get(arena.id);
		if (game == null) return null;
		String turn = TeamColors.of(game.turn.side).name() + " asks";
		if (member == null || member.team < 0 || member.team > 1) return turn;
		return "Your suspect: " + SUSPECTS.get(game.secret[member.team]).name() + "  ·  " + turn;
	}

	static String whoseTurn(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? "The suspects are coming in" : TeamColors.of(game.turn.side).name() + " asks";
	}

	static @Nullable String secretWords(Arena arena, Arena.Member viewer) {
		Game game = games.get(arena.id);
		if (game == null || viewer.team < 0 || viewer.team > 1) return null;
		Suspect mine = SUSPECTS.get(game.secret[viewer.team]);
		return "Yours: " + mine.name() + ", " + mine.what();
	}

	/** The suspects this team still has standing in its gallery. */
	static int standing(Arena arena, int team) {
		Game game = games.get(arena.id);
		if (game == null || team < 0 || team > 1) return SUSPECT_COUNT;
		int up = 0;
		for (boolean down : game.down[team]) if (!down) up++;
		return up;
	}

	public static void forget(Arena arena) {
		Game game = games.remove(arena.id);
		if (game == null) return;
		for (Mob[] gallery : game.standing) for (Mob mob : gallery) if (mob != null) mob.discard();
	}
}
