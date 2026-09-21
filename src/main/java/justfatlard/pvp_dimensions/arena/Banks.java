package justfatlard.pvp_dimensions.arena;

import java.util.List;
import justfatlard.pvp_dimensions.integration.ChestUtils;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Banking: each team has a chest, and what is in it when time is up is its score, every item worth
 * what the preset's list says one of it is worth and anything off the list worth nothing. Gather,
 * bring it home, and take from theirs if you can get at it: a capture the flag where everything is
 * a flag and the game never stops to reset.
 *
 * <p>A bank can't be broken, nor the block it stands on, so it can't be dug out from under or
 * drained by a hopper. Whether another team may open it is {@link Preset#chestAccess}.
 */
public final class Banks {
	private Banks() {}

	/** Each team's bank, where its base is, a banner beside it, painted its colour. */
	static void build(ServerLevel level, Arena arena, int team) {
		BlockPos bank = arena.bases.get(team);
		level.setBlock(bank, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		// Held loaded for the length of the game. A bank is counted where it stands, so a chunk
		// nobody is standing in is a team whose score quietly stops moving - and the last thing
		// anybody suspects, because the number on the board is still there, just wrong.
		level.setChunkForced(bank.getX() >> 4, bank.getZ() >> 4, true);
		BlockState banner = Terrain.block(TeamColors.of(team).banner(), Blocks.OBSIDIAN.defaultBlockState());
		level.setBlock(bank.east(), banner, Block.UPDATE_ALL);
		if (!Spawns.solid(level.getBlockState(bank.below()))) level.setBlock(bank.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ChestUtils.paint(level, bank, TeamColors.of(team).dye());
		arena.teamChests.put(bank, team);
	}

	/** What a chest's contents score. */
	public static int worth(Preset preset, Container chest) {
		int total = 0;
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (!stack.isEmpty()) total += value(preset, stack) * stack.getCount();
		}
		return total;
	}

	/** What one of this item scores: the size of its stack in the preset's list, or nothing. */
	public static int value(Preset preset, ItemStack stack) {
		for (ItemStack listed : preset.bankValues.stacks()) {
			if (ItemStack.isSameItem(listed, stack)) return listed.getCount();
		}
		return 0;
	}

	/** "Diamond 10, Emerald 8" and so on, for a line of chat. */
	public static String values(Preset preset, int limit) {
		List<ItemStack> listed = preset.bankValues.stacks();
		if (listed.isEmpty()) return "nothing is on the list";
		StringBuilder words = new StringBuilder();
		for (int i = 0; i < listed.size() && i < limit; i++) {
			if (i > 0) words.append(", ");
			words.append(listed.get(i).getHoverName().getString()).append(" ").append(listed.get(i).getCount());
		}
		if (listed.size() > limit) words.append(", and ").append(listed.size() - limit).append(" more");
		return words.toString();
	}

	/** Every bank counted once a second, and a team at the preset's target wins. */
	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, int ticks) {
		if (ticks % 2 != 0) return;
		Preset preset = arena.preset;
		for (int team = 0; team < preset.teams && team < arena.bases.size(); team++) {
			BlockPos bank = arena.bases.get(team);
			// Kept loaded at build time, but a reload or a hand-edited arena can still lose it:
			// say so rather than leaving a stale number standing.
			if (!level.hasChunk(bank.getX() >> 4, bank.getZ() >> 4)) {
				level.setChunkForced(bank.getX() >> 4, bank.getZ() >> 4, true);
				continue;
			}
			if (!level.getBlockState(bank).is(Blocks.CHEST)) level.setBlock(bank, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
			int points = level.getBlockEntity(bank) instanceof Container chest ? worth(preset, chest) : 0;
			arena.scores.put(team, points);
			if (preset.bankTarget > 0 && points >= preset.bankTarget) Goals.winTeam(server, arena, team, points + " points in the bank");
		}
	}

	/** Let the banks' chunks go when the game is over. */
	public static void done(ServerLevel level, Arena arena) {
		for (int team = 0; team < arena.preset.teams && team < arena.bases.size(); team++) {
			BlockPos bank = arena.bases.get(team);
			level.setChunkForced(bank.getX() >> 4, bank.getZ() >> 4, false);
		}
	}

	/** Every team's total, for the line said when the game ends. */
	public static String tally(Arena arena) {
		StringBuilder line = new StringBuilder();
		for (int team = 0; team < arena.preset.teams; team++) {
			if (team > 0) line.append(", ");
			line.append(TeamColors.of(team).name()).append(" ")
				.append(arena.scores.getOrDefault(team, 0));
		}
		return line.toString();
	}

	public static String status(Arena arena) {
		StringBuilder line = new StringBuilder();
		for (int team = 0; team < arena.preset.teams; team++) {
			if (team > 0) line.append("  ");
			line.append(TeamColors.of(team).name()).append(" ").append(arena.scores.getOrDefault(team, 0));
		}
		line.append(" points");
		if (arena.preset.bankTarget > 0) line.append("  ·  ").append(arena.preset.bankTarget).append(" wins");
		return line.toString();
	}
}
