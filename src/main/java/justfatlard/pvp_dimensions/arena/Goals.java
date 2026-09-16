package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * What an arena is played for, beyond lasting till time runs out. Kill count, with lives or a
 * team's shared pool if the preset says; a mob hunt; capture the flag; colour takeover; destroying
 * the other teams' bases. Whichever side gets there first wins: a title for everyone inside, a
 * prize drawn for the winners, and a few seconds to take it in before everyone goes home.
 */
public final class Goals {
	private Goals() {}

	/** How long a win is shown before the arena closes. */
	private static final long CELEBRATION_MILLIS = 10_000L;
	private static final Random PRIZE_DRAW = new Random();
	private static final String FLAG_TEAM = "pvp_dimensions_flag";
	private static final String FLAG_ARENA = "pvp_dimensions_arena";
	private static final String FLAG_NUMBER = "pvp_dimensions_flag_number";
	/** Terracotta handed to each player at every spawn, for a takeover. */
	private static final int TERRACOTTA = 64;

	private static final Map<String, Map<Integer, Integer>> covered = new HashMap<>();
	/** Each arena's teams' share of the ground, in percent, as last counted. */
	private static final Map<String, Map<Integer, Integer>> coveredPercent = new HashMap<>();
	private static int ticks;

	// --- The start ---

	/** Bases set out, flags in their chests, cubes built, lives counted: the moment the fight starts. */
	public static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		Preset preset = arena.preset;
		Preset.Goal goal = preset.activeGoal();
		if (goal == Preset.Goal.CTF || goal == Preset.Goal.DESTRUCTION || goal == Preset.Goal.BANK) {
			arena.bases.clear();
			for (int team = 0; team < preset.teams; team++) {
				if (team < arena.hearts.size()) {
					arena.bases.add(arena.hearts.get(team));
					continue;
				}
				BlockPos spot = baseSpot(level, arena, team);
				// Two bases never share ground: a chest set down on another's would spill its flag.
				for (int step = 0; step < 8 && crowds(arena.bases, spot); step++) spot = spot.offset(4, 0, 0);
				arena.bases.add(spot);
			}
			for (int team = 0; team < preset.teams; team++) {
				switch (goal) {
					case CTF -> buildFlagBase(level, arena, team);
					case BANK -> Banks.build(level, arena, team);
					default -> buildCube(level, arena, team);
				}
			}
		}
		if (goal == Preset.Goal.HILL) Hill.begin(level, arena, System.currentTimeMillis());
		if (goal == Preset.Goal.RACE) Race.begin(level, arena);
		if (preset.livesOn() && preset.pooledLives()) {
			for (int team = 0; team < preset.teams; team++) arena.pools.put(team, preset.livesCount);
		}
		String how = switch (goal) {
			case TIME -> null;
			case KILLS -> preset.killTarget > 0 ? "First to " + preset.killTarget + " kills wins" : "Last one standing wins";
			case MOBS -> switch (preset.mobScope()) {
				case EVERYONE -> "Together, take down " + preset.mobGoalCount + " " + mobWords(preset);
				case TEAM -> "The first team to take down " + preset.mobGoalCount + " " + mobWords(preset) + " wins";
				case PLAYER -> "The first to take down " + preset.mobGoalCount + " " + mobWords(preset) + " wins";
			};
			case CTF -> "Take their banner from their chest and put it in yours; " + preset.captures
				+ (preset.captures == 1 ? " capture wins" : " captures win");
			case TAKEOVER -> preset.takeoverPercent > 0 ? "Cover " + preset.takeoverPercent + "% of the ground in your terracotta"
				: "Cover the most ground in your terracotta by the end";
			case DESTRUCTION -> "Break every block of their base; keep yours standing";
			case WAVES -> "Survive " + preset.waves.size() + (preset.waves.size() == 1 ? " wave" : " waves") + " of mobs, together";
			case HILL -> (preset.hillTarget > 0 ? "Hold the hill for " + Fields.duration(preset.hillTarget).toLowerCase() + " to win"
				: "Hold the hill longest to win") + "; it only counts while nobody else is on it"
				+ (preset.hillMoves > 0 ? ", and it moves every " + Fields.duration(preset.hillMoves).toLowerCase() : "");
			case BANK -> (preset.bankTarget > 0 ? "Fill your bank to " + preset.bankTarget + " points" : "The richest bank when time is up wins")
				+ ". Points each: " + Banks.values(preset, 4);
			case RACE -> "Race to the beacon" + switch (preset.finishStyle) {
				case GROUND -> "";
				case TOWER -> ", at the top of the tower";
				case SKY -> ", up in the sky: build your way to it";
				case BURIED -> ", buried: dig down to it";
			};
		};
		if (how != null) Arenas.tellInside(server, arena, how);
	}

	/**
	 * Where a team's base goes: the middle of its first division when the arena is divided, or
	 * its point on the ring round the middle, on open ground.
	 */
	static BlockPos baseSpot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		Terrain terrain = footprint.terrain();
		int width = footprint.width();
		int x;
		int z;
		if (terrain.divided()) {
			int[] middle = terrain.partCenter(footprint, team % terrain.parts());
			x = middle[0];
			z = middle[1];
		} else {
			double angle = Math.PI * 2 * team / arena.preset.teams + Math.PI / 4;
			x = (int) (footprint.centerX() + Math.cos(angle) * width * 0.3);
			z = (int) (footprint.centerZ() + Math.sin(angle) * width * 0.3);
		}
		return BlockPos.containing(Spawns.near(level, arena, x, z, 6));
	}

	private static boolean crowds(List<BlockPos> bases, BlockPos spot) {
		for (BlockPos base : bases) {
			if (base.distManhattan(spot) < 4) return true;
		}
		return false;
	}

	// --- Capture the flag ---

	public static ItemStack flag(Arena arena, int team) {
		TeamColors.Colour colour = TeamColors.of(team);
		Item banner = BuiltInRegistries.ITEM.getOptional(Identifier.parse(colour.banner())).orElse(net.minecraft.world.item.Items.STICK);
		ItemStack stack = new ItemStack(banner);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(colour.name() + " Flag")
			.withStyle(style -> style.withItalic(false).withColor(colour.team().textColor())));
		CompoundTag tag = new CompoundTag();
		tag.putInt(FLAG_TEAM, team);
		tag.putString(FLAG_ARENA, arena.id);
		tag.putInt(FLAG_NUMBER, arena.flagNumbers.getOrDefault(team, 0));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** Which team's flag this is, in this arena, or -1 for anything else, a flag since replaced included. */
	public static int flagTeam(Arena arena, ItemStack stack) {
		CompoundTag tag = flagTag(arena, stack);
		if (tag == null) return -1;
		int team = tag.getIntOr(FLAG_TEAM, -1);
		return tag.getIntOr(FLAG_NUMBER, 0) == arena.flagNumbers.getOrDefault(team, 0) ? team : -1;
	}

	/**
	 * A flag of this arena's that a newer one has replaced: one that went into a grave with its
	 * carrier, or into some other chest, while its team was given another. It counts for nothing,
	 * and wherever the arena comes across it, it goes.
	 */
	private static boolean replacedFlag(Arena arena, ItemStack stack) {
		return flagTag(arena, stack) != null && flagTeam(arena, stack) < 0;
	}

	private static @Nullable CompoundTag flagTag(Arena arena, ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();
		return arena.id.equals(tag.getStringOr(FLAG_ARENA, "")) && tag.contains(FLAG_TEAM) ? tag : null;
	}

	/**
	 * A carrier about to die: whatever flags they hold go home now, before anything else can take
	 * what they were carrying, a grave most of all, which would keep a flag the base had replaced.
	 */
	public static void carrierDying(ServerPlayer player) {
		Arena arena = Arenas.of(player);
		if (arena == null || arena.preset.activeGoal() != Preset.Goal.CTF) return;
		// Asked before a totem has had its say: one in hand means they may well live, flag and all.
		if (player.getMainHandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
			|| player.getOffhandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) return;
		Arena.Member member = arena.member(player.getUUID());
		boolean carrying = false;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (flagTag(arena, inventory.getItem(slot)) == null) continue;
			carrying |= member != null && flagTeam(arena, inventory.getItem(slot)) != member.team;
			inventory.setItem(slot, ItemStack.EMPTY);
		}
		if (flagTag(arena, player.containerMenu.getCarried()) != null) {
			carrying |= member != null && flagTeam(arena, player.containerMenu.getCarried()) != member.team;
			player.containerMenu.setCarried(ItemStack.EMPTY);
		}
		if (carrying) Moments.carrierFalling(player);
	}

	private static void buildFlagBase(ServerLevel level, Arena arena, int team) {
		BlockPos base = arena.bases.get(team);
		level.setBlock(base, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		BlockState banner = Terrain.block(TeamColors.of(team).banner(), Blocks.OBSIDIAN.defaultBlockState());
		level.setBlock(base.east(), banner, Block.UPDATE_ALL);
		if (level.getBlockEntity(base) instanceof Container chest) chest.setItem(13, flag(arena, team));
		justfatlard.pvp_dimensions.integration.ChestUtils.paint(level, base, TeamColors.of(team).dye());
	}

	/**
	 * Whether this block is kept from being broken: a flag chest, a bank or a team's chest and what
	 * it stands on, a banner beside a base, and the hill or a finish, by anyone but an admin in
	 * creative; a base cube, by its own team, and by anything that isn't a player.
	 */
	public static boolean protectedBlock(Arena arena, BlockPos pos, @Nullable ServerPlayer breaker) {
		Preset.Goal goal = arena.preset.activeGoal();
		boolean admin = breaker != null && breaker.isCreative() && justfatlard.pvp_dimensions.Access.admin(breaker);
		if (Markers.part(arena, pos) || TeamChests.kept(arena, pos) || wallHolds(arena, pos)) return !admin;
		if (goal == Preset.Goal.CTF || goal == Preset.Goal.BANK) {
			for (BlockPos base : arena.bases) {
				if (pos.equals(base) || pos.equals(base.east())) return breaker == null || !breaker.isCreative();
			}
		} else if (goal == Preset.Goal.DESTRUCTION && breaker == null) {
			for (int team = 0; team < arena.bases.size(); team++) {
				if (cube(arena, team).contains(pos)) return true;
			}
		} else if (goal == Preset.Goal.DESTRUCTION) {
			Arena.Member member = arena.member(breaker.getUUID());
			if (member == null || member.team < 0 || member.team >= arena.bases.size()) return false;
			return cube(arena, member.team).contains(pos);
		}
		return false;
	}

	/**
	 * Every flag found once a second: in a base chest, in somebody's hands, or lying on the ground.
	 * One in another team's chest is a capture and goes home; one nowhere at all, burnt or hidden
	 * in some other chest, goes home too. Whoever carries another team's flag glows.
	 */
	private static void flags(MinecraftServer server, ServerLevel level, Arena arena) {
		int teams = arena.preset.teams;
		boolean[] found = new boolean[teams];
		List<int[]> captures = new ArrayList<>();

		for (int team = 0; team < teams && team < arena.bases.size(); team++) {
			BlockPos base = arena.bases.get(team);
			if (!level.getBlockState(base).is(Blocks.CHEST)) level.setBlock(base, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
			BlockState banner = Terrain.block(TeamColors.of(team).banner(), Blocks.OBSIDIAN.defaultBlockState());
			if (!level.getBlockState(base.east()).is(banner.getBlock())) level.setBlock(base.east(), banner, Block.UPDATE_ALL);
			BlockEntity entity = level.getBlockEntity(base);
			if (!(entity instanceof Container chest)) continue;
			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				if (replacedFlag(arena, chest.getItem(slot))) chest.setItem(slot, ItemStack.EMPTY);
				int flag = flagTeam(arena, chest.getItem(slot));
				if (flag < 0 || flag >= teams) continue;
				if (flag == team || found[flag]) {
					found[flag] = true;
				} else {
					captures.add(new int[] {team, flag});
					chest.setItem(slot, ItemStack.EMPTY);
				}
			}
		}

		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || player.level() != level) continue;
			boolean carrying = false;
			Inventory inventory = player.getInventory();
			List<ItemStack> held = new ArrayList<>();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				if (replacedFlag(arena, inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
				held.add(inventory.getItem(slot));
			}
			if (replacedFlag(arena, player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
			held.add(player.containerMenu.getCarried());
			for (ItemStack stack : held) {
				int flag = flagTeam(arena, stack);
				if (flag < 0 || flag >= teams) continue;
				found[flag] = true;
				if (flag != member.team) {
					carrying = true;
					Moments.carrying(arena, flag, member);
				}
			}
			if (carrying) player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
		}

		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area, entity -> flagTag(arena, entity.getItem()) != null)) {
			int flag = flagTeam(arena, item.getItem());
			if (flag < 0) item.discard();
			else if (flag < teams) found[flag] = true;
		}

		for (int[] capture : captures) {
			int by = capture[0];
			int of = capture[1];
			homeFlag(level, arena, of);
			found[of] = true;
			int score = arena.scores.merge(by, 1, Integer::sum);
			Arenas.vault(server).touch();
			Arena.Member carrier = Moments.capturedBy(arena, of, by);
			ServerPlayer carrierPlayer = carrier == null ? null : server.getPlayerList().getPlayer(carrier.id);
			if (carrierPlayer != null) Moments.captured(arena, carrierPlayer);
			Arenas.tellInside(server, arena, (carrierPlayer != null ? carrier.name + " captured " + TeamColors.of(of).name() + "'s flag for " + TeamColors.of(by).name()
				: TeamColors.of(by).name() + " captured " + TeamColors.of(of).name() + "'s flag") + "! (" + score + " of " + arena.preset.captures + ")");
			if (score >= arena.preset.captures) {
				winTeam(server, arena, by, "captured " + score + (score == 1 ? " flag" : " flags"));
				return;
			}
		}
		for (int team = 0; team < teams && team < arena.bases.size(); team++) {
			if (!found[team]) {
				homeFlag(level, arena, team);
				Arenas.tellInside(server, arena, TeamColors.of(team).name() + "'s flag is back home");
			}
		}
	}

	/** A new flag in the team's chest, numbered past the one it replaces, wherever that one went. */
	private static void homeFlag(ServerLevel level, Arena arena, int team) {
		if (team >= arena.bases.size()) return;
		if (!(level.getBlockEntity(arena.bases.get(team)) instanceof Container chest)) return;
		arena.flagNumbers.merge(team, 1, Integer::sum);
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			if (chest.getItem(slot).isEmpty()) {
				chest.setItem(slot, flag(arena, team));
				return;
			}
		}
		chest.setItem(13, flag(arena, team));
	}

	// --- Destroying bases ---

	private static List<BlockPos> cube(Arena arena, int team) {
		List<BlockPos> blocks = new ArrayList<>();
		if (team >= arena.bases.size()) return blocks;
		BlockPos base = arena.bases.get(team);
		int size = Math.max(1, arena.preset.baseSize);
		int half = size / 2;
		for (int dx = 0; dx < size; dx++) {
			for (int dy = 0; dy < size; dy++) {
				for (int dz = 0; dz < size; dz++) blocks.add(base.offset(dx - half, dy, dz - half));
			}
		}
		return blocks;
	}

	/** A division wall that holds until it falls, where the preset says it does. */
	public static boolean wallHolds(Arena arena, BlockPos pos) {
		if (!arena.preset.wallsHold || arena.wallsDown || pos.getY() > arena.wallTop) return false;
		Footprint footprint = arena.footprint();
		BlockState wall = footprint.terrain().wallAt(footprint, pos.getX(), pos.getZ());
		return wall != null && wall != Terrain.BEDROCK;
	}

	/** Each team's share of the ground in its colour, in percent, as last counted. */
	static Map<Integer, Integer> coverage(Arena arena) {
		return coveredPercent.getOrDefault(arena.id, Map.of());
	}

	/** How many blocks of a team's base still stand, and of how many it had. */
	static int[] baseLeft(ServerLevel level, Arena arena, int team) {
		List<BlockPos> blocks = cube(arena, team);
		BlockState block = terracotta(team);
		int left = 0;
		for (BlockPos pos : blocks) if (level.getBlockState(pos) == block) left++;
		return new int[] {left, blocks.size()};
	}

	private static BlockState terracotta(int team) {
		return Terrain.block("minecraft:" + TeamColors.of(team).dye() + "_terracotta", Blocks.TERRACOTTA.defaultBlockState());
	}

	private static void buildCube(ServerLevel level, Arena arena, int team) {
		BlockState block = terracotta(team);
		for (BlockPos pos : cube(arena, team)) level.setBlock(pos, block, Block.UPDATE_ALL);
	}

	private static void bases(MinecraftServer server, ServerLevel level, Arena arena) {
		for (int team = 0; team < arena.preset.teams && team < arena.bases.size(); team++) {
			if (arena.fallen.contains(team)) continue;
			BlockState block = terracotta(team);
			boolean standing = false;
			for (BlockPos pos : cube(arena, team)) {
				if (level.getBlockState(pos) == block) {
					standing = true;
					break;
				}
			}
			if (standing) continue;
			arena.fallen.add(team);
			Arenas.vault(server).touch();
			Arenas.tellInside(server, arena, TeamColors.of(team).name() + "'s base has fallen!");
			for (Arena.Member member : arena.inside()) {
				if (member.team != team) continue;
				member.out = true;
				ServerPlayer player = server.getPlayerList().getPlayer(member.id);
				if (player != null && !player.isDeadOrDying()) {
					player.setGameMode(GameType.SPECTATOR);
					Say.to(player, "Your base has fallen; you're watching now");
				}
			}
		}
		Set<Integer> standing = new HashSet<>();
		for (int team = 0; team < arena.preset.teams; team++) if (!arena.fallen.contains(team)) standing.add(team);
		if (standing.size() == 1) winTeam(server, arena, standing.iterator().next(), "the last base standing");
	}

	// --- Colour takeover ---

	private static void takeover(MinecraftServer server, ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		Map<net.minecraft.world.level.block.Block, Integer> teamOf = new HashMap<>();
		for (int team = 0; team < arena.preset.teams; team++) teamOf.put(terracotta(team).getBlock(), team);
		Map<Integer, Integer> counts = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int columns = 0;
		for (int x = footprint.minX(); x < footprint.maxX(); x++) {
			for (int z = footprint.minZ(); z < footprint.maxZ(); z++) {
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				columns++;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				Integer team = teamOf.get(level.getBlockState(pos.set(x, y, z)).getBlock());
				if (team != null) counts.merge(team, 1, Integer::sum);
			}
		}
		covered.put(arena.id, counts);
		Map<Integer, Integer> percents = new HashMap<>();
		for (Map.Entry<Integer, Integer> count : counts.entrySet()) percents.put(count.getKey(), columns == 0 ? 0 : count.getValue() * 100 / columns);
		coveredPercent.put(arena.id, percents);
		StringBuilder line = new StringBuilder();
		int leader = -1;
		for (int team = 0; team < arena.preset.teams; team++) {
			int percent = columns == 0 ? 0 : counts.getOrDefault(team, 0) * 100 / columns;
			if (team > 0) line.append("  ");
			line.append(TeamColors.of(team).name()).append(" ").append(percent).append("%");
			if (arena.preset.takeoverPercent > 0 && percent >= arena.preset.takeoverPercent) leader = team;
		}
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null) Say.bar(player, line.toString());
		}
		if (leader >= 0) winTeam(server, arena, leader, "covered " + arena.preset.takeoverPercent + "% of the ground");
	}

	/** Terracotta in the team's colour, for a takeover: handed over at every spawn. */
	public static void supply(ServerPlayer player, Arena arena, Arena.Member member) {
		if (arena.preset.activeGoal() != Preset.Goal.TAKEOVER || member.team < 0) return;
		ItemStack stack = new ItemStack(terracotta(member.team).getBlock().asItem(), TERRACOTTA);
		justfatlard.pvp_dimensions.preset.ItemList.giveStack(player, stack);
	}

	// --- Kills and lives ---

	/** A kill counted: the kill target, if there is one, may be reached. */
	public static void killed(MinecraftServer server, Arena arena, Arena.Member killer) {
		Preset preset = arena.preset;
		if (preset.activeGoal() != Preset.Goal.KILLS || preset.killTarget <= 0 || arena.closesAt > 0) return;
		if (preset.teamsOn()) {
			int total = 0;
			for (Arena.Member member : arena.members.values()) if (member.team == killer.team) total += member.kills;
			if (total >= preset.killTarget) winTeam(server, arena, killer.team, total + " kills");
		} else if (killer.kills >= preset.killTarget) {
			winPlayer(server, arena, killer, killer.kills + " kills");
		}
	}

	/** A death counted against the player's lives, or their team's pool; the last one puts them out. */
	public static void died(Arena arena, Arena.Member lost) {
		Preset preset = arena.preset;
		if (lost.zombie) return;
		if (!preset.livesOn()) {
			if (preset.hordeOn() && !preset.deathCapture) lost.out = true;
			return;
		}
		if (!preset.pooledLives() || lost.team < 0) {
			if (lost.lives < 0) lost.lives = preset.livesCount;
			lost.lives--;
			if (lost.lives <= 0) lost.out = true;
		} else if (lost.team >= 0) {
			int pool = arena.pools.getOrDefault(lost.team, preset.livesCount);
			if (pool > 0) arena.pools.put(lost.team, pool - 1);
			else lost.out = true;
		}
	}

	/** Back from a death with no lives left: watching now. */
	public static void respawnedOut(ServerPlayer player, Arena arena, Arena.Member member) {
		if (arena.preset.hordeOn()) {
			Horde.rise(player, arena, member);
			return;
		}
		member.watching = true;
		player.setGameMode(GameType.SPECTATOR);
		Say.to(player, arena.preset.pooledLives() ? "Your team is out of lives; you're watching now" : "Out of lives; you're watching now");
	}

	/** What the bar shows about the goal, in a few words; null for nothing. */
	public static @Nullable String status(Arena arena, UUID viewer) {
		Preset preset = arena.preset;
		Arena.Member member = arena.member(viewer);
		String lives = "";
		if (member != null && preset.livesOn()) {
			int left = member.lives >= 0 ? member.lives : preset.livesCount;
			lives = !preset.pooledLives() || member.team < 0 ? ", " + left + (left == 1 ? " life" : " lives")
				: member.team >= 0 ? ", team lives " + arena.pools.getOrDefault(member.team, preset.livesCount) : "";
		}
		String livesLeft = lives;
		return switch (preset.activeGoal()) {
			case KILLS -> member == null ? null
				: member.kills + (preset.killTarget > 0 ? " of " + preset.killTarget : "") + " kills" + livesLeft;
			case MOBS -> {
				if (member == null) yield null;
				int counted = switch (preset.mobScope()) {
					case PLAYER -> member.mobKills;
					case TEAM -> teamMobKills(arena, member.team);
					case EVERYONE -> allMobKills(arena);
				};
				String whose = switch (preset.mobScope()) {
					case PLAYER -> "";
					case TEAM -> "Team: ";
					case EVERYONE -> "Together: ";
				};
				yield whose + counted + " of " + preset.mobGoalCount + " " + mobWords(preset) + livesLeft;
			}
			case CTF -> {
				StringBuilder line = new StringBuilder();
				for (int team = 0; team < preset.teams; team++) {
					if (team > 0) line.append("  ");
					line.append(TeamColors.of(team).name()).append(" ").append(arena.scores.getOrDefault(team, 0));
				}
				yield line + livesLeft;
			}
			case WAVES -> {
				String waves = Waves.status(arena, System.currentTimeMillis());
				yield waves == null ? null : waves + livesLeft;
			}
			case HILL -> Hill.status(arena, member) + livesLeft;
			case BANK -> Banks.status(arena) + livesLeft;
			case RACE -> {
				String race = justfatlard.pvp_dimensions.PvpDimensions.server() == null ? null
					: Race.status(justfatlard.pvp_dimensions.PvpDimensions.server(), arena, member);
				yield race == null ? null : race + livesLeft;
			}
			default -> {
				String waves = Waves.status(arena, System.currentTimeMillis());
				if (waves != null) yield waves + livesLeft;
				yield livesLeft.isEmpty() ? null : livesLeft.substring(2);
			}
		};
	}

	// --- Every tick ---

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (arena.closesAt > 0) {
			if (now >= arena.closesAt) Arenas.end(server, arena, "it has been won");
			return;
		}
		ticks++;
		switch (arena.preset.activeGoal()) {
			case CTF -> flags(server, level, arena);
			case DESTRUCTION -> bases(server, level, arena);
			case TAKEOVER -> {
				if (ticks % 10 == 0) takeover(server, level, arena);
			}
			case MOBS -> {
				if (!outable(arena.preset) || now - arena.liveAt < 5_000L) break;
				if (arena.preset.mobScope() != Preset.Scope.EVERYONE) lastStanding(server, arena, now);
				else if (arena.fighting().isEmpty() && !arena.members.isEmpty()) {
					Arenas.end(server, arena, arena.preset.hordeOn() ? "the horde took everyone" : "everyone fell; the mobs won");
				}
			}
			case WAVES -> {
				if (outable(arena.preset) && now - arena.liveAt >= 5_000L && arena.fighting().isEmpty() && !arena.members.isEmpty()) {
					Arenas.end(server, arena, arena.preset.hordeOn() ? "the horde took everyone" : "everyone fell; the waves won");
				}
			}
			case HILL -> {
				Hill.tick(server, level, arena, now);
				outOfLives(server, arena, now);
			}
			case BANK -> {
				Banks.tick(server, level, arena, ticks);
				outOfLives(server, arena, now);
			}
			case RACE -> {
				Race.tick(server, level, arena, now);
				outOfLives(server, arena, now);
			}
			default -> outOfLives(server, arena, now);
		}
	}

	/** Whether anyone can be out of this fight: out of lives, or risen with the horde. */
	private static boolean outable(Preset preset) {
		return preset.livesOn() || preset.hordeOn() && preset.canBeOut();
	}

	/**
	 * Every other goal where players can be out: fighting each other, the last one standing wins
	 * it; side by side against the mobs, everyone out is the end of it.
	 */
	private static void outOfLives(MinecraftServer server, Arena arena, long now) {
		if (!outable(arena.preset)) return;
		if (arena.preset.pvp) lastStanding(server, arena, now);
		else if (now - arena.liveAt >= 5_000L && arena.fighting().isEmpty() && !arena.members.isEmpty()) {
			Arenas.end(server, arena, arena.preset.hordeOn() ? "the horde took everyone" : "everyone is out of lives");
		}
	}

	// --- Mob kills ---

	static String mobWords(Preset preset) {
		if (preset.mobGoalKind.equals("any")) return "mobs";
		String name = justfatlard.pvp_dimensions.preset.MobKinds.name(preset.mobGoalKind).toLowerCase(java.util.Locale.ROOT);
		return name.endsWith("s") ? name : name + "s";
	}

	private static int teamMobKills(Arena arena, int team) {
		int total = 0;
		for (Arena.Member member : arena.members.values()) if (member.team == team) total += member.mobKills;
		return total;
	}

	private static int allMobKills(Arena arena) {
		int total = 0;
		for (Arena.Member member : arena.members.values()) total += member.mobKills;
		return total;
	}

	/** Whether this mob is one the hunt counts: any hostile one, or the chosen kind. */
	/**
	 * Whether a mob is one the hunt is after: any of the arena's own, or of the kind named. A
	 * baby or a rider counts for its own kind; the grown kind counts every one of its type.
	 */
	private static boolean counts(Preset preset, net.minecraft.world.entity.LivingEntity mob) {
		if (preset.mobGoalKind.equals("any")) {
			return mob.entityTags().contains(Mobs.OURS) || mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
		}
		if (justfatlard.pvp_dimensions.preset.MobKinds.variant(preset.mobGoalKind) != null) return mob.entityTags().contains(Mobs.KIND + preset.mobGoalKind);
		return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString().equals(preset.mobGoalKind);
	}

	/** A mob killed by a player in the fight: counted, and the target maybe reached. */
	public static void mobKilled(MinecraftServer server, Arena arena, Arena.Member killer, net.minecraft.world.entity.LivingEntity mob) {
		Preset preset = arena.preset;
		if (preset.activeGoal() != Preset.Goal.MOBS || arena.closesAt > 0 || !counts(preset, mob)) return;
		killer.mobKills++;
		Arenas.vault(server).touch();
		int target = preset.mobGoalCount;
		switch (preset.mobScope()) {
			case PLAYER -> {
				if (killer.mobKills >= target) winPlayer(server, arena, killer, target + " " + mobWords(preset) + " down");
			}
			case TEAM -> {
				if (killer.team >= 0 && teamMobKills(arena, killer.team) >= target) {
					winTeam(server, arena, killer.team, target + " " + mobWords(preset) + " down");
				}
			}
			case EVERYONE -> {
				if (allMobKills(arena) >= target) winTogether(server, arena, target + " " + mobWords(preset) + " down");
			}
		}
	}

	/** Everyone wins: a hunt done together. */
	public static void winTogether(MinecraftServer server, Arena arena, String how) {
		if (arena.closesAt > 0) return;
		crown(server, arena, arena.members.values().stream().filter(member -> !member.zombie).map(member -> member.id).toList(), "You win!", how);
		arena.closesAt = System.currentTimeMillis() + CELEBRATION_MILLIS;
		Arenas.vault(server).touch();
	}

	private static void lastStanding(MinecraftServer server, Arena arena, long now) {
		if (now - arena.liveAt < 5_000L || arena.members.size() < 2) return;
		Set<String> sides = new HashSet<>();
		Arena.Member last = null;
		for (Arena.Member member : arena.fighting()) {
			sides.add(arena.preset.teamsOn() ? "team" + member.team : member.id.toString());
			last = member;
		}
		if (sides.size() != 1 || last == null) return;
		if (arena.preset.teamsOn()) winTeam(server, arena, last.team, "the last team standing");
		else winPlayer(server, arena, last, "the last one standing");
	}

	/**
	 * Time is up: whoever leads the goal wins it. With no goal, or no leader, it just ends.
	 * @return whether there was a winner to name
	 */
	public static boolean timeUp(MinecraftServer server, Arena arena) {
		Preset preset = arena.preset;
		Map<Integer, Integer> byTeam = new HashMap<>();
		switch (preset.activeGoal()) {
			case CTF, BANK -> byTeam.putAll(arena.scores);
			case HILL -> {
				if (preset.teamsOn()) {
					byTeam.putAll(arena.scores);
				} else {
					Arena.Member best = null;
					boolean tie = false;
					for (Arena.Member member : arena.members.values()) {
						if (best == null || member.held > best.held) {
							best = member;
							tie = false;
						} else if (member.held == best.held) {
							tie = true;
						}
					}
					if (best != null && !tie && best.held > 0) {
						crown(server, arena, List.of(best.id), best.name + " wins!", "held the hill longest");
						return true;
					}
					return false;
				}
			}
			case TAKEOVER -> byTeam.putAll(covered.getOrDefault(arena.id, Map.of()));
			case DESTRUCTION -> {
				ServerLevel level = Arenas.level(server, arena);
				if (level != null) {
					for (int team = 0; team < preset.teams && team < arena.bases.size(); team++) {
						int left = 0;
						for (BlockPos pos : cube(arena, team)) if (level.getBlockState(pos) == terracotta(team)) left++;
						byTeam.put(team, left);
					}
				}
			}
			case MOBS -> {
				if (preset.mobScope() == Preset.Scope.EVERYONE) return false;
				if (preset.mobScope() == Preset.Scope.TEAM) {
					for (Arena.Member member : arena.members.values()) if (member.team >= 0) byTeam.merge(member.team, member.mobKills, Integer::sum);
				} else {
					Arena.Member best = null;
					boolean tie = false;
					for (Arena.Member member : arena.members.values()) {
						if (best == null || member.mobKills > best.mobKills) {
							best = member;
							tie = false;
						} else if (member.mobKills == best.mobKills) {
							tie = true;
						}
					}
					if (best != null && !tie && best.mobKills > 0) {
						crown(server, arena, List.of(best.id), best.name + " wins!", "with " + best.mobKills + " " + mobWords(preset));
						return true;
					}
					return false;
				}
			}
			case KILLS -> {
				if (preset.teamsOn()) {
					for (Arena.Member member : arena.members.values()) if (member.team >= 0) byTeam.merge(member.team, member.kills, Integer::sum);
				} else {
					Arena.Member best = null;
					boolean tie = false;
					for (Arena.Member member : arena.members.values()) {
						if (best == null || member.kills > best.kills) {
							best = member;
							tie = false;
						} else if (member.kills == best.kills) {
							tie = true;
						}
					}
					if (best != null && !tie && best.kills > 0) {
						crown(server, arena, List.of(best.id), best.name + " wins!", "with " + best.kills + " kills");
						return true;
					}
					return false;
				}
			}
			default -> {
				return false;
			}
		}
		int best = -1;
		int bestScore = 0;
		boolean tie = false;
		for (Map.Entry<Integer, Integer> entry : byTeam.entrySet()) {
			if (entry.getValue() > bestScore) {
				best = entry.getKey();
				bestScore = entry.getValue();
				tie = false;
			} else if (entry.getValue() == bestScore && bestScore > 0) {
				tie = true;
			}
		}
		if (best < 0 || tie) return false;
		crown(server, arena, teamMembers(arena, best), TeamColors.of(best).name() + " wins!", "ahead when time ran out");
		return true;
	}

	// --- Winning ---

	public static void winTeam(MinecraftServer server, Arena arena, int team, String how) {
		if (arena.closesAt > 0) return;
		crown(server, arena, teamMembers(arena, team), TeamColors.of(team).name() + " wins!", how);
		arena.closesAt = System.currentTimeMillis() + CELEBRATION_MILLIS;
		Arenas.vault(server).touch();
	}

	public static void winPlayer(MinecraftServer server, Arena arena, Arena.Member winner, String how) {
		if (arena.closesAt > 0) return;
		crown(server, arena, List.of(winner.id), winner.name + " wins!", how);
		arena.closesAt = System.currentTimeMillis() + CELEBRATION_MILLIS;
		Arenas.vault(server).touch();
	}

	private static List<UUID> teamMembers(Arena arena, int team) {
		List<UUID> ids = new ArrayList<>();
		for (Arena.Member member : arena.members.values()) if (member.team == team) ids.add(member.id);
		return ids;
	}

	/**
	 * The title for everyone inside and how it was won beneath it, and the prize drawn and
	 * announced. The winners still inside are owed it, handed over as they go home: given here, a
	 * pack the arena keeps apart would take it back on the way out.
	 */
	private static void crown(MinecraftServer server, Arena arena, List<UUID> winners, String words, String how) {
		Component title = Component.literal(words).withStyle(ChatFormatting.GOLD);
		Component subtitle = Component.literal(how).withStyle(ChatFormatting.YELLOW);
		List<Integer> drawable = new ArrayList<>();
		for (int i = 0; i < arena.preset.prizes.size(); i++) if (!arena.preset.prizes.get(i).isEmpty()) drawable.add(i);
		if (arena.prize < 0 && !drawable.isEmpty()) arena.prize = drawable.get(PRIZE_DRAW.nextInt(drawable.size()));
		ItemList prize = arena.prizeItems();
		String prizeWords = prize == null ? null
			: (drawable.size() > 1 ? "The prize, drawn from " + drawable.size() + ": " : "The prize: ") + prize.describe(8);
		ItemList pot = Fees.shareOut(arena, winners);
		if (pot != null) {
			String potWords = "The pot, shared " + (winners.size() == 1 ? "by the winner" : winners.size() + " ways") + ": " + pot.describe(8);
			prizeWords = prizeWords == null ? potWords : prizeWords + ". " + potWords;
		}
		for (Arena.Member member : arena.inside()) {
			boolean won = winners.contains(member.id);
			if (won) arena.winners.add(member.id);
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			Say.to(player, words + " " + how.substring(0, 1).toUpperCase() + how.substring(1) + ".");
			if (prizeWords != null) Say.to(player, prizeWords + (won ? ". It comes home with you" : ""));
		}
	}

	public static void forget(Arena arena) {
		covered.remove(arena.id);
		coveredPercent.remove(arena.id);
		Hill.forget(arena);
		Moments.forget(arena);
	}
}
