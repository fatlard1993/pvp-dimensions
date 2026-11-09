package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * What custom gives on top of survival, as the preset sets it: flight, and blocks that never run
 * out. The game knows nothing of custom; a player in it is in survival, given these besides.
 *
 * <p>Only a player the arena has in survival gets them, so the out watching, the horde in
 * adventure, and an admin who switched to creative are left as they are.
 */
public final class Powers {
	private Powers() {}

	/** Flight on or off to match the arena's mode now. Setting survival over survival leaves abilities alone, so this says. */
	public static void give(ServerPlayer player, Arena arena) {
		if (player.gameMode() != GameType.SURVIVAL) return;
		boolean fly = arena.phase == Arena.Phase.LIVE && ModeChanges.current(arena) == Preset.GameRule.CUSTOM && arena.preset.flight;
		Abilities abilities = player.getAbilities();
		if (abilities.mayfly == fly) return;
		abilities.mayfly = fly;
		if (!fly) abilities.flying = false;
		player.onUpdateAbilities();
	}

	/** Whether putting this stack down leaves it as it was. */
	public static boolean endless(ServerPlayer player, ItemStack stack) {
		if (player.gameMode() != GameType.SURVIVAL) return false;
		Arena arena = Arenas.of(player);
		return arena != null && arena.phase == Arena.Phase.LIVE && player.level().dimension().equals(arena.dimension)
			&& ModeChanges.current(arena) == Preset.GameRule.CUSTOM && arena.preset.endlessBlocks.allows(stack.getItem());
	}
}
