package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.integration.LootEnder;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * A team's chest, and who besides the team may open it: anyone, nobody, or whoever picks its lock
 * with Loot Ender's picks. A chest set against one to make a pair is part of it.
 */
public final class TeamChests {
	private TeamChests() {}

	/** Opening a chest: let alone unless it is another team's, and this preset keeps it from them. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos) {
		Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
		if (arena == null || arena.teamChests.isEmpty()) return InteractionResult.PASS;
		BlockState state = level.getBlockState(pos);
		Integer team = owner(arena, state, pos);
		if (team == null || team < 0) return InteractionResult.PASS;
		Arena.Member member = arena.member(player.getUUID());
		if (member != null && member.team == team && !member.zombie) return InteractionResult.PASS;
		if (Access.admin(player) && player.isCreative()) return InteractionResult.PASS;
		String whose = TeamColors.of(team).name() + "'s chest";
		return switch (arena.preset.chestAccess) {
			case OPEN -> InteractionResult.PASS;
			case SHUT -> {
				Say.bar(player, whose + " is shut to you");
				yield InteractionResult.FAIL;
			}
			case PICKABLE -> {
				if (!LootEnder.picking()) yield InteractionResult.PASS;
				if (!LootEnder.hasPick(player)) {
					Say.bar(player, whose + " is locked; a lockpick gets you in");
					yield InteractionResult.FAIL;
				}
				Say.to(player, "Picking " + whose);
				boolean started = LootEnder.pick(player, pos, picked -> open(level, picked, pos, state));
				yield started ? InteractionResult.SUCCESS : InteractionResult.PASS;
			}
		};
	}

	/** The chest behind a beaten lock, if it is still the chest it was. */
	private static void open(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
		if (!level.getBlockState(pos).equals(state)) return;
		MenuProvider menu = state.getMenuProvider(level, pos);
		if (menu != null) player.openMenu(menu);
	}

	/** The team whose chest this is, itself or as the other half of a pair; null for nobody's. */
	private static @Nullable Integer owner(Arena arena, BlockState state, BlockPos pos) {
		Integer team = arena.teamChests.get(pos);
		if (team != null || !(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return team;
		Direction partner = ChestBlock.getConnectedDirection(state);
		return arena.teamChests.get(pos.relative(partner));
	}

	/** A team's chest, and the block under it, which nobody breaks. */
	public static boolean kept(Arena arena, BlockPos pos) {
		return arena.teamChests.containsKey(pos) || arena.teamChests.containsKey(pos.above());
	}

	/** Whether the preset keeps team chests from other teams at all. */
	public static boolean guarded(Preset preset) {
		return preset.chestAccess == Preset.ChestAccess.SHUT
			|| preset.chestAccess == Preset.ChestAccess.PICKABLE && LootEnder.picking();
	}
}
