package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.integration.ChestUtils;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Cops and robbers, both of them in one: the playground game of tag and jail, and a heist.
 *
 * <p>Two sides, and lopsided on purpose: the cops are about a third of the players, at least one,
 * and the robbers the rest. Nobody is hurt by anybody. A cop who hits a robber jails them: they are
 * put straight into the cell, a cage of iron bars at the cops' end that everyone can see into, with
 * an iron door nobody inside can open. A free robber who right-clicks that door, or the lever on the
 * post beside it, springs everyone inside, and each of them has a few seconds before they can be
 * caught again.
 *
 * <p>Beside the jail is the vault, a chest of gold the cops are guarding, and at the robbers' end
 * their hideout. Robbers take gold out of the vault, a handful at a time, and carry it home; whoever
 * is carrying it glows for everyone to see, and a robber jailed with gold on them has it put back in
 * the vault. What is in the hideout is the robbers' score, and the cops can't open it to take it
 * back. The robbers win by banking the target, or by having a single one of them still free when
 * time runs out; the cops win the moment every robber is in jail at once.
 *
 * <p>The vault starts with more than the target, and gold that goes missing - dropped and left
 * lying, carried out of the arena, burnt - finds its way back to it, so the heist can always be
 * pulled. The hideout is {@link Arena#bases} at the robbers' index and the jail's front at the
 * cops'; who is in jail is not saved, and a game carried over a restart opens the cell.
 */
public final class CopsRobbers {
	private CopsRobbers() {}

	/** The two sides, as team numbers: the robbers red, the cops blue. */
	public static final int ROBBERS = 0;
	public static final int COPS = 1;

	/** How many the vault holds over the target. */
	public static final int SPARE = 5;
	/** The most a robber can carry at once: a handful, so the heist takes more than one trip. */
	public static final int CARRY = 5;
	/** How long a sprung robber can't be caught again. */
	public static final long FREED_MILLIS = 3_000L;
	/** How long gold may lie on the ground before it goes back to the vault, in ticks. */
	private static final int LYING_TICKS = 100;

	/** The cell: side walls this far either side of the door, the back wall this far behind it, the roof this far up. */
	private static final int WALL = 3;
	private static final int DEPTH = 4;
	private static final int ROOF = 3;
	/** The vault's place against the jail: across from the door, and back from its front. */
	private static final int VAULT_ACROSS = -6;
	private static final int VAULT_BACK = 2;
	/** Ground levelled round the jail and the vault. */
	private static final int YARD = 7;
	/** Blocks kept between the back of the jail's yard and the arena's edge. */
	private static final int BEHIND = 11;

	private static final class Game {
		final Set<UUID> jailed = new HashSet<>();
		/** Sprung robbers, and when they can be caught again. */
		final Map<UUID, Long> freed = new HashMap<>();
	}

	private static final Map<String, Game> games = new HashMap<>();

	private static Game game(Arena arena) {
		return games.computeIfAbsent(arena.id, id -> new Game());
	}

	// --- The sides ---

	/** What a side is called: the cops and the robbers here, the team's colour anywhere else. */
	public static String side(Arena arena, int team) {
		return side(arena.preset, team);
	}

	public static String side(Preset preset, int team) {
		if (preset.activeGoal() != Preset.Goal.COPS_AND_ROBBERS) return TeamColors.of(team).name();
		return team == COPS ? "Cops" : "Robbers";
	}

	/**
	 * How big a share of the players a team is meant to have, for {@link Teams}: every team the
	 * same, except here, where there are two robbers to every cop.
	 */
	public static int share(Arena arena, int team) {
		return arena.preset.activeGoal() == Preset.Goal.COPS_AND_ROBBERS && team == ROBBERS ? 2 : 1;
	}

	/** How many of this many players are cops: a third, rounded, and at least one once there are two to share out. */
	public static int copsFor(int players) {
		return players < 2 ? 0 : Math.max(1, (players + 1) / 3);
	}

	/**
	 * The sides dealt as the game starts: whatever colours were stood on in the waiting room, the
	 * cops are made a third. Only as many are moved as it takes, picked at random, and each is told.
	 */
	private static void deal(MinecraftServer server, Arena arena) {
		List<Arena.Member> playing = new ArrayList<>();
		for (Arena.Member member : arena.inside()) {
			if (member.watching || server.getPlayerList().getPlayer(member.id) == null) continue;
			playing.add(member);
		}
		int wanted = copsFor(playing.size());
		List<Arena.Member> cops = new ArrayList<>();
		List<Arena.Member> robbers = new ArrayList<>();
		for (Arena.Member member : playing) (member.team == COPS ? cops : robbers).add(member);
		Collections.shuffle(cops);
		Collections.shuffle(robbers);
		while (cops.size() > wanted) move(server, arena, cops.removeLast(), ROBBERS);
		while (cops.size() < wanted && !robbers.isEmpty()) {
			Arena.Member member = robbers.removeLast();
			move(server, arena, member, COPS);
			cops.add(member);
		}
		// Anybody stood on no colour at all, or on a team past the two, is a robber.
		for (Arena.Member member : playing) if (member.team != COPS && member.team != ROBBERS) move(server, arena, member, ROBBERS);
	}

	private static void move(MinecraftServer server, Arena arena, Arena.Member member, int team) {
		ServerPlayer player = server.getPlayerList().getPlayer(member.id);
		if (player == null) return;
		Teams.put(player, arena, member, team);
		Say.to(player, "You're one of the " + side(arena, team).toLowerCase() + ": the cops are a third of everyone");
	}

	// --- The start ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		deal(server, arena);
		games.put(arena.id, new Game());
		arena.bases.clear();
		arena.bases.add(spot(level, arena, ROBBERS));
		arena.bases.add(spot(level, arena, COPS));
		buildJail(level, arena);
		buildVault(level, arena);
		buildHideout(level, arena);
		arena.scores.put(ROBBERS, 0);
		String loot = lootWord(arena.preset);
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || member.watching) continue;
			boolean cop = member.team == COPS;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 15));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(cop
				? "Hit a robber to jail them; guard the vault"
				: "Steal " + arena.preset.heistTarget + " " + loot + " from the vault; don't get caught").withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(cop ? "You're a cop" : "You're a robber")
				.withStyle(style -> style.withColor(TeamColors.of(member.team).team().textColor()))));
		}
	}

	/** On a side's spoke from the middle, as far out as leaves the jail's yard clear of the edge. */
	private static BlockPos spot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		int width = footprint.width();
		double radius = Math.max(WALL + 2, Math.min(width * Preset.GOAL_RING, width / 2.0 - BEHIND));
		double angle = arena.preset.ringAngle(team);
		int x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) Math.floor(footprint.centerZ() + Math.sin(angle) * radius);
		return new BlockPos(x, Builds.ground(level, arena, x, z), z);
	}

	/** A place against the jail: across its front, back from it away from the middle, and up from its floor. */
	private static BlockPos at(Arena arena, BlockPos mouth, int across, int back, int up) {
		Direction behind = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
		return mouth.relative(behind.getClockWise(), across).relative(behind, back).above(up);
	}

	private static void buildJail(ServerLevel level, Arena arena) {
		BlockPos mouth = arena.bases.get(COPS);
		Direction front = Builds.front(arena, mouth.getX(), mouth.getZ());
		BlockPos middle = at(arena, mouth, 0, VAULT_BACK, 0);
		Builds.yard(level, middle.getX(), mouth.getY(), middle.getZ(), YARD, ROOF + 3);
		BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
		List<BlockPos> bars = new ArrayList<>();
		for (int across = -WALL; across <= WALL; across++) {
			for (int back = 0; back <= DEPTH; back++) {
				level.setBlock(at(arena, mouth, across, back, -1), floor, Block.UPDATE_ALL);
				level.setBlock(at(arena, mouth, across, back, ROOF), stone, Block.UPDATE_ALL);
				for (int up = 0; up < ROOF; up++) {
					BlockPos pos = at(arena, mouth, across, back, up);
					boolean side = Math.abs(across) == WALL;
					boolean end = back == 0 || back == DEPTH;
					if (side && end) level.setBlock(pos, stone, Block.UPDATE_ALL);
					// One post beside the door, for the lever; bars everywhere else, so the whole cell can be seen into.
					else if (back == 0 && across == 1) level.setBlock(pos, stone, Block.UPDATE_ALL);
					else if (back == 0 && across == 0) {
						if (up == ROOF - 1) level.setBlock(pos, stone, Block.UPDATE_ALL);
					} else if (side || end) {
						level.setBlock(pos, Blocks.IRON_BARS.defaultBlockState(), Block.UPDATE_ALL);
						bars.add(pos);
					} else {
						Builds.air(level, pos);
					}
				}
			}
		}
		// Bars joined up to each other and the stone once every one of them is in.
		for (BlockPos pos : bars) Builds.set(level, pos, Blocks.IRON_BARS.defaultBlockState());
		// The door goes in last, both halves at once, so neither half is ever left alone to drop.
		BlockState door = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, front.getOpposite())
			.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.OPEN, false);
		level.setBlock(at(arena, mouth, 0, 0, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
		level.setBlock(at(arena, mouth, 0, 0, 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
		level.setBlock(leverAt(arena, mouth), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
			.setValue(LeverBlock.FACING, front), Block.UPDATE_ALL);
	}

	/** On the front of the post beside the door, outside, where a robber walking up can't miss it. */
	private static BlockPos leverAt(Arena arena, BlockPos mouth) {
		return at(arena, mouth, 1, -1, 1);
	}

	private static BlockPos vaultAt(Arena arena) {
		return at(arena, arena.bases.get(COPS), VAULT_ACROSS, VAULT_BACK, 0);
	}

	private static BlockPos hideoutAt(Arena arena) {
		return arena.bases.get(ROBBERS);
	}

	private static void buildVault(ServerLevel level, Arena arena) {
		BlockPos vault = vaultAt(arena);
		BlockPos mouth = arena.bases.get(COPS);
		Direction front = Builds.front(arena, mouth.getX(), mouth.getZ());
		level.setBlock(vault.below(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(vault, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, front), Block.UPDATE_ALL);
		ChestUtils.paint(level, vault, TeamColors.of(COPS).dye());
		arena.teamChests.put(vault, COPS);
		if (level.getBlockEntity(vault) instanceof Container chest) {
			chest.clearContent();
			put(chest, loot(arena.preset), stock(arena.preset));
		}
	}

	private static void buildHideout(ServerLevel level, Arena arena) {
		BlockPos hideout = hideoutAt(arena);
		Direction front = Builds.front(arena, hideout.getX(), hideout.getZ());
		Builds.yard(level, hideout.getX(), hideout.getY(), hideout.getZ(), 2, 3);
		if (!Spawns.solid(level.getBlockState(hideout.below()))) level.setBlock(hideout.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(hideout, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, front), Block.UPDATE_ALL);
		BlockState banner = Terrain.block(TeamColors.of(ROBBERS).banner(), Blocks.OBSIDIAN.defaultBlockState());
		level.setBlock(hideout.relative(front.getClockWise()), banner, Block.UPDATE_ALL);
		ChestUtils.paint(level, hideout, TeamColors.of(ROBBERS).dye());
		arena.teamChests.put(hideout, ROBBERS);
		if (level.getBlockEntity(hideout) instanceof Container chest) chest.clearContent();
	}

	// --- The loot ---

	/** What is stolen: the preset's item, or gold where that names nothing this server has. */
	public static Item loot(Preset preset) {
		Identifier id = Identifier.tryParse(preset.heistItem);
		return id == null ? Items.GOLD_INGOT : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.GOLD_INGOT);
	}

	/** The loot in a word, for a line of chat: "gold", "diamonds". */
	public static String lootWord(Preset preset) {
		Item item = loot(preset);
		if (item == Items.GOLD_INGOT) return "gold";
		if (item == Items.IRON_INGOT) return "iron";
		String name = new ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
		return name.endsWith("s") ? name : name + "s";
	}

	/** What the vault starts with, and how much is kept in play: the target and a few to spare. */
	public static int stock(Preset preset) {
		return Math.max(1, preset.heistTarget) + SPARE;
	}

	private static int count(Container container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	/** Up to {@code most} of the item taken out; how many were. */
	private static int take(Container container, Item item, int most) {
		int taken = 0;
		for (int slot = 0; slot < container.getContainerSize() && taken < most; slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.is(item)) continue;
			int some = Math.min(stack.getCount(), most - taken);
			stack.shrink(some);
			if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
			taken += some;
		}
		if (taken > 0) container.setChanged();
		return taken;
	}

	/** So many of the item put in, onto stacks of it first; how many would not fit. */
	private static int put(Container container, Item item, int count) {
		int left = count;
		int most = new ItemStack(item).getMaxStackSize();
		for (int pass = 0; pass < 2 && left > 0; pass++) {
			for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (pass == 0 && stack.is(item) && stack.getCount() < most) {
					int some = Math.min(left, most - stack.getCount());
					stack.grow(some);
					left -= some;
				} else if (pass == 1 && stack.isEmpty()) {
					int some = Math.min(left, most);
					container.setItem(slot, new ItemStack(item, some));
					left -= some;
				}
			}
		}
		container.setChanged();
		return left;
	}

	/** Loot sent back to the vault; what won't fit there is dropped on top of it, to go back in later. */
	private static void toVault(ServerLevel level, Arena arena, int count) {
		if (count <= 0) return;
		BlockPos vault = vaultAt(arena);
		int left = level.getBlockEntity(vault) instanceof Container chest ? put(chest, loot(arena.preset), count) : count;
		if (left > 0) {
			ItemEntity dropped = new ItemEntity(level, vault.getX() + 0.5, vault.getY() + 1.2, vault.getZ() + 0.5, new ItemStack(loot(arena.preset), left));
			level.addFreshEntity(dropped);
		}
	}

	/** How much of the loot a player has on them, in their pack and on the cursor of a menu. */
	private static int carried(ServerPlayer player, Item item) {
		int total = count(player.getInventory(), item);
		if (player.containerMenu.getCarried().is(item)) total += player.containerMenu.getCarried().getCount();
		return total;
	}

	/** Everything of the loot taken off a player, the cursor included; how much. */
	private static int strip(ServerPlayer player, Item item) {
		player.closeContainer();
		int taken = take(player.getInventory(), item, Integer.MAX_VALUE);
		ItemStack cursor = player.containerMenu.getCarried();
		if (cursor.is(item)) {
			taken += cursor.getCount();
			player.containerMenu.setCarried(ItemStack.EMPTY);
		}
		player.containerMenu.broadcastChanges();
		return taken;
	}

	// --- The jail ---

	/** A cop hit somebody. A free robber is jailed; anything else between two players is nothing. @return whether it was between two players here */
	public static boolean tag(ServerPlayer attacker, Entity target) {
		if (!(target instanceof ServerPlayer hit)) return false;
		Arena arena = Arenas.of(attacker);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.preset.activeGoal() != Preset.Goal.COPS_AND_ROBBERS) return false;
		if (arena.closesAt > 0 || arena.bases.size() < 2) return true;
		Arena.Member cop = arena.member(attacker.getUUID());
		Arena.Member robber = arena.member(hit.getUUID());
		if (cop == null || robber == null || cop.watching || cop.out || robber.watching || robber.out) return true;
		if (cop.team != COPS || robber.team != ROBBERS) return true;
		Game game = game(arena);
		if (game.jailed.contains(robber.id)) return true;
		if (shielded(arena, robber.id)) {
			Say.bar(attacker, robber.name + " was just sprung: give them a moment");
			return true;
		}
		jail(attacker.level().getServer(), arena, robber, cop.name);
		return true;
	}

	/** Whether a robber was sprung moments ago, and can't be caught again yet. */
	public static boolean shielded(Arena arena, UUID robber) {
		Game game = games.get(arena.id);
		Long until = game == null ? null : game.freed.get(robber);
		return until != null && System.currentTimeMillis() < until;
	}

	/**
	 * A robber in jail: into the cell, whatever they were carrying back in the vault, and the cops
	 * winning if that was the last of them.
	 */
	public static void jail(MinecraftServer server, Arena arena, Arena.Member robber, String by) {
		ServerLevel level = Arenas.level(server, arena);
		if (level == null || arena.bases.size() < 2) return;
		Game game = game(arena);
		if (!game.jailed.add(robber.id)) return;
		game.freed.remove(robber.id);
		ServerPlayer player = server.getPlayerList().getPlayer(robber.id);
		int returned = 0;
		if (player != null) {
			returned = strip(player, loot(arena.preset));
			toVault(level, arena, returned);
			player.removeEffect(MobEffects.GLOWING);
			intoCell(level, arena, player, game.jailed.size() - 1);
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("A free robber can spring you from outside").withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Jailed!").withStyle(ChatFormatting.RED)));
		}
		BlockPos door = arena.bases.get(COPS);
		level.playSound(null, door.getX() + 0.5, door.getY() + 1, door.getZ() + 0.5, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.8F);
		Arenas.vault(server).touch();
		Arenas.tellInside(server, arena, by + " jails " + robber.name
			+ (returned > 0 ? ", and " + returned + " " + lootWord(arena.preset) + " goes back in the vault" : "")
			+ " (" + jailedCount(arena) + " in jail)");
		allCaught(server, arena);
	}

	/** Into the cell, a square of its own where there is room, facing out through the bars. */
	private static void intoCell(ServerLevel level, Arena arena, ServerPlayer player, int index) {
		BlockPos mouth = arena.bases.get(COPS);
		int squares = (2 * (WALL - 1) + 1) * (DEPTH - 1);
		int place = Math.floorMod(index, squares);
		int across = place % (2 * (WALL - 1) + 1) - (WALL - 1);
		int back = 1 + place / (2 * (WALL - 1) + 1);
		BlockPos square = at(arena, mouth, across, back, 0);
		float yaw = Builds.front(arena, mouth.getX(), mouth.getZ()).toYRot();
		player.teleportTo(level, square.getX() + 0.5, square.getY(), square.getZ() + 0.5, Set.of(), yaw, 0, true);
		player.resetFallDistance();
	}

	/** Whether this spot is inside the cell. */
	public static boolean inCell(Arena arena, Vec3 position) {
		if (arena.bases.size() < 2) return false;
		BlockPos mouth = arena.bases.get(COPS);
		Direction behind = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
		Direction side = behind.getClockWise();
		BlockPos feet = BlockPos.containing(position.x, position.y + 0.1, position.z);
		int dx = feet.getX() - mouth.getX();
		int dz = feet.getZ() - mouth.getZ();
		int across = dx * side.getStepX() + dz * side.getStepZ();
		int back = dx * behind.getStepX() + dz * behind.getStepZ();
		int up = feet.getY() - mouth.getY();
		return Math.abs(across) < WALL && back > 0 && back < DEPTH && up >= 0 && up < ROOF;
	}

	/** Everyone in jail let out, in front of the door, and safe for a moment. */
	private static void spring(MinecraftServer server, ServerLevel level, Arena arena, Arena.Member by) {
		Game game = game(arena);
		List<UUID> out = List.copyOf(game.jailed);
		game.jailed.clear();
		long until = System.currentTimeMillis() + FREED_MILLIS;
		BlockPos mouth = arena.bases.get(COPS);
		float yaw = Builds.front(arena, mouth.getX(), mouth.getZ()).toYRot();
		int index = 0;
		for (UUID id : out) {
			game.freed.put(id, until);
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null) continue;
			int place = index++;
			BlockPos square = at(arena, mouth, place % 5 - 2, -2 - place / 5, 0);
			player.teleportTo(level, square.getX() + 0.5, Builds.ground(level, arena, square.getX(), square.getZ()), square.getZ() + 0.5, Set.of(), yaw, 0, true);
			player.connection.send(new ClientboundSetTitlesAnimationPacket(3, 30, 8));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(by.name + " sprang you: run!").withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Free!").withStyle(ChatFormatting.GREEN)));
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1, player.getZ(), 12, 0.4, 0.6, 0.4, 0);
		}
		level.playSound(null, mouth.getX() + 0.5, mouth.getY() + 1, mouth.getZ() + 0.5, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
		Arenas.vault(server).touch();
		Arenas.tellInside(server, arena, by.name + " springs the jail: " + out.size() + (out.size() == 1 ? " robber" : " robbers") + " free!");
	}

	/**
	 * A right-click on the jail, the vault or the hideout. The door and the lever spring the jail
	 * for a free robber and nobody else; the vault and the hideout are the robbers', and shut to the
	 * cops, who are guarding the one and must not empty the other.
	 */
	public static InteractionResult useBlock(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.preset.activeGoal() != Preset.Goal.COPS_AND_ROBBERS
				|| arena.bases.size() < 2) return InteractionResult.PASS;
		BlockPos mouth = arena.bases.get(COPS);
		boolean door = pos.equals(mouth) || pos.equals(mouth.above());
		boolean lever = pos.equals(leverAt(arena, mouth));
		boolean vault = pos.equals(vaultAt(arena));
		boolean hideout = pos.equals(hideoutAt(arena));
		if (!door && !lever && !vault && !hideout) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Arena.Member member = arena.member(player.getUUID());
		boolean admin = justfatlard.pvp_dimensions.Access.admin(player) && player.isCreative();
		if (member == null || member.watching || member.out) return admin ? InteractionResult.PASS : InteractionResult.FAIL;
		Game game = game(arena);
		if (door || lever) {
			if (arena.closesAt > 0) return InteractionResult.FAIL;
			if (member.team != ROBBERS) {
				Say.bar(player, "Only a free robber can spring the jail");
				return InteractionResult.FAIL;
			}
			if (game.jailed.contains(member.id)) {
				Say.bar(player, "It only opens from outside: wait for a free robber");
				return InteractionResult.FAIL;
			}
			if (game.jailed.isEmpty()) {
				Say.bar(player, "Nobody's in jail");
				return InteractionResult.FAIL;
			}
			spring(player.level().getServer(), level, arena, member);
			return InteractionResult.SUCCESS;
		}
		if (member.team == COPS && !admin) {
			Say.bar(player, vault ? "The vault is yours to guard, not to empty" : "The robbers' hideout is shut to the cops");
			return InteractionResult.FAIL;
		}
		if (game.jailed.contains(member.id)) return InteractionResult.FAIL;
		// Opened here rather than left to the chest, so no setting about other teams' chests can
		// shut a robber out of the vault they are meant to be robbing.
		BlockState state = level.getBlockState(pos);
		MenuProvider menu = state.getMenuProvider(level, pos);
		if (menu == null) return InteractionResult.PASS;
		player.openMenu(menu);
		return InteractionResult.SUCCESS;
	}

	/** Whether this block is part of the jail, the vault or the hideout, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.COPS_AND_ROBBERS || arena.bases.size() < 2) return false;
		BlockPos mouth = arena.bases.get(COPS);
		Direction behind = Builds.front(arena, mouth.getX(), mouth.getZ()).getOpposite();
		Direction side = behind.getClockWise();
		int dx = pos.getX() - mouth.getX();
		int dz = pos.getZ() - mouth.getZ();
		int across = dx * side.getStepX() + dz * side.getStepZ();
		int back = dx * behind.getStepX() + dz * behind.getStepZ();
		int up = pos.getY() - mouth.getY();
		if (Math.abs(across) <= WALL && back >= 0 && back <= DEPTH && up >= -1 && up <= ROOF) return true;
		if (pos.equals(leverAt(arena, mouth))) return true;
		BlockPos vault = vaultAt(arena);
		BlockPos hideout = hideoutAt(arena);
		Direction hideoutFront = Builds.front(arena, hideout.getX(), hideout.getZ());
		return pos.equals(vault) || pos.equals(vault.below()) || pos.equals(hideout) || pos.equals(hideout.below())
			|| pos.equals(hideout.relative(hideoutFront.getClockWise()));
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (arena.bases.size() < 2) return;
		Game game = game(arena);
		game.freed.values().removeIf(until -> now >= until);
		Item item = loot(arena.preset);
		BlockPos vault = vaultAt(arena);
		BlockPos hideout = hideoutAt(arena);
		if (!level.getBlockState(vault).is(Blocks.CHEST)) buildVault(level, arena);
		if (!level.getBlockState(hideout).is(Blocks.CHEST)) buildHideout(level, arena);
		int inPlay = 0;

		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || player.level() != level || member.watching) continue;
			boolean jailed = game.jailed.contains(member.id);
			int carrying = carried(player, item);
			if (member.team == ROBBERS && jailed) {
				// Nobody walks out: back in the cell, from wherever a respawn or a trip home and back
				// put them, with anything they picked up on the way going back where it came from.
				if (carrying > 0) toVault(level, arena, strip(player, item));
				if (!inCell(arena, player.position())) intoCell(level, arena, player, List.copyOf(game.jailed).indexOf(member.id));
				Say.bar(player, "In jail: a free robber can spring you from outside");
				continue;
			}
			if (member.team != ROBBERS) {
				// A cop who picks up dropped loot has recovered it.
				if (carrying > 0) {
					int recovered = strip(player, item);
					toVault(level, arena, recovered);
					Arenas.tellInside(server, arena, member.name + " puts " + recovered + " " + lootWord(arena.preset) + " back in the vault");
				}
				continue;
			}
			if (carrying > CARRY) {
				int over = take(player.getInventory(), item, carrying - CARRY);
				player.containerMenu.broadcastChanges();
				toVault(level, arena, over);
				carrying -= over;
				Say.bar(player, "You can only carry " + CARRY + " at a time; the rest stays in the vault");
			}
			inPlay += carrying;
			if (carrying > 0) player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
		}

		// Loot left lying goes back to the vault after a moment.
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (ItemEntity lying : level.getEntitiesOfClass(ItemEntity.class, area, entity -> entity.getItem().is(item))) {
			if (lying.getAge() < LYING_TICKS) {
				inPlay += lying.getItem().getCount();
				continue;
			}
			int count = lying.getItem().getCount();
			lying.discard();
			toVault(level, arena, count);
		}

		int banked = level.getBlockEntity(hideout) instanceof Container chest ? count(chest, item) : 0;
		int inVault = level.getBlockEntity(vault) instanceof Container chest ? count(chest, item) : 0;
		// What went missing - carried out of the arena, burnt, despawned - is put back, so the
		// target can always be reached.
		int missing = stock(arena.preset) - banked - inVault - inPlay;
		if (missing > 0) toVault(level, arena, missing);

		if (arena.scores.getOrDefault(ROBBERS, 0) != banked) {
			arena.scores.put(ROBBERS, banked);
			Arenas.vault(server).touch();
		}
		if (banked >= arena.preset.heistTarget) {
			Goals.winTeam(server, arena, ROBBERS, "Robbers win!", "banked " + banked + " " + lootWord(arena.preset));
			return;
		}
		allCaught(server, arena);
	}

	/** The robbers who count: in the fight, on the ground here right now. */
	private static List<Arena.Member> robbers(MinecraftServer server, Arena arena) {
		List<Arena.Member> robbers = new ArrayList<>();
		ServerLevel level = Arenas.level(server, arena);
		for (Arena.Member member : arena.fighting()) {
			if (member.team != ROBBERS) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null && player.level() == level) robbers.add(member);
		}
		return robbers;
	}

	/** Every robber in jail at once: the cops have won. */
	private static void allCaught(MinecraftServer server, Arena arena) {
		if (arena.closesAt > 0) return;
		List<Arena.Member> robbers = robbers(server, arena);
		if (robbers.isEmpty()) return;
		Game game = game(arena);
		for (Arena.Member robber : robbers) if (!game.jailed.contains(robber.id)) return;
		Goals.winTeam(server, arena, COPS, "Cops win!", robbers.size() == 1 ? "the robber is behind bars" : "every robber is behind bars");
	}

	/** Time is up: one robber still free and the robbers have got away with it. */
	static boolean timeUp(MinecraftServer server, Arena arena) {
		List<Arena.Member> robbers = robbers(server, arena);
		if (robbers.isEmpty()) return false;
		Game game = game(arena);
		long free = robbers.stream().filter(robber -> !game.jailed.contains(robber.id)).count();
		if (free > 0) {
			Goals.winTeam(server, arena, ROBBERS, "Robbers win!", free == 1 ? "a robber was still free when time ran out"
				: free + " robbers were still free when time ran out");
		} else {
			Goals.winTeam(server, arena, COPS, "Cops win!", "every robber is behind bars");
		}
		return true;
	}

	// --- The board ---

	/**
	 * How many are in jail now, of the robbers here: counted the way the cops' win counts them, so a
	 * robber who has logged off is on neither side of "every robber in jail".
	 */
	public static int jailedCount(Arena arena) {
		Game game = games.get(arena.id);
		if (game == null) return 0;
		int count = 0;
		for (UUID id : game.jailed) {
			Arena.Member member = arena.member(id);
			if (member != null && member.inside && online(member)) count++;
		}
		return count;
	}

	private static boolean online(Arena.Member member) {
		MinecraftServer server = justfatlard.pvp_dimensions.PvpDimensions.server();
		return server != null && server.getPlayerList().getPlayer(member.id) != null;
	}

	public static boolean isJailed(Arena arena, UUID player) {
		Game game = games.get(arena.id);
		return game != null && game.jailed.contains(player);
	}

	/** Robbers here, jailed or free. */
	public static int robberCount(Arena arena) {
		int count = 0;
		for (Arena.Member member : arena.inside()) if (member.team == ROBBERS && !member.watching && !member.out && online(member)) count++;
		return count;
	}

	/** The bar's line: the loot banked, and who is in jail. */
	static String status(Arena arena, Arena.@Nullable Member viewer) {
		String line = "Banked " + arena.scores.getOrDefault(ROBBERS, 0) + " of " + arena.preset.heistTarget + " " + lootWord(arena.preset)
			+ "  ·  " + jailedCount(arena) + " of " + robberCount(arena) + " robbers in jail";
		if (viewer != null && isJailed(arena, viewer.id)) line += "  ·  You're in jail";
		return line;
	}

	/** Said as it ends. */
	static String tally(Arena arena) {
		return "Banked " + arena.scores.getOrDefault(ROBBERS, 0) + " of " + arena.preset.heistTarget + " " + lootWord(arena.preset)
			+ ", " + jailedCount(arena) + " of " + robberCount(arena) + " robbers in jail";
	}

	/** Who is carrying loot, for the foot of the board: "Ann 3, Bob 2"; empty for nobody. */
	static String carriers(MinecraftServer server, Arena arena) {
		List<String> who = new ArrayList<>();
		Item item = loot(arena.preset);
		for (Arena.Member member : arena.fighting()) {
			if (member.team != ROBBERS) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			int carrying = player == null ? 0 : carried(player, item);
			if (carrying > 0) who.add(member.name + " " + carrying);
		}
		return String.join(", ", who);
	}

	// --- For the tests ---

	public static @Nullable BlockPos leverOf(Arena arena) {
		return arena.bases.size() < 2 ? null : leverAt(arena, arena.bases.get(COPS));
	}

	public static @Nullable BlockPos doorOf(Arena arena) {
		return arena.bases.size() < 2 ? null : arena.bases.get(COPS);
	}

	public static @Nullable BlockPos vaultOf(Arena arena) {
		return arena.bases.size() < 2 ? null : vaultAt(arena);
	}

	public static @Nullable BlockPos hideoutOf(Arena arena) {
		return arena.bases.size() < 2 ? null : hideoutAt(arena);
	}

	/** How much of the loot a chest holds. */
	public static int held(ServerLevel level, BlockPos chest, Preset preset) {
		return level.getBlockEntity(chest) instanceof Container container ? count(container, loot(preset)) : 0;
	}

	public static void forget(Arena arena) {
		games.remove(arena.id);
	}
}
