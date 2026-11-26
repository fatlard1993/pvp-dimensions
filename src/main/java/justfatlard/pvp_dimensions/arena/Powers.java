package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * What custom gives on top of survival, as the preset sets it: flight, breaking at a touch, and
 * blocks that never run out, one of each handed out. The game knows nothing of custom; a player
 * in it is in survival, given these besides.
 *
 * <p>Only a player the arena has in survival gets them, so the out watching, the horde in
 * adventure, and an admin who switched to creative are left as they are.
 */
public final class Powers {
	private Powers() {}

	private static final Identifier INSTANT = PvpDimensions.id("instant_breaking");
	/** Break speed to the game's cap of 1024: the client is sent it and mines by it, so creative's instant breaking needs no client mod. */
	private static final AttributeModifier INSTANT_MODIFIER = new AttributeModifier(INSTANT, 1023, AttributeModifier.Operation.ADD_VALUE);

	private static boolean custom(ServerPlayer player, Arena arena) {
		return player.gameMode() == GameType.SURVIVAL && arena.phase == Arena.Phase.LIVE && ModeChanges.current(arena) == Preset.GameRule.CUSTOM;
	}

	/** Flight and breaking on or off to match the arena's mode now. Setting survival over survival leaves abilities alone, so this says. */
	public static void give(ServerPlayer player, Arena arena) {
		boolean custom = custom(player, arena);
		instantBreaking(player, custom && arena.preset.instantBreaking);
		if (player.gameMode() != GameType.SURVIVAL) return;
		boolean fly = custom && arena.preset.flight;
		Abilities abilities = player.getAbilities();
		if (abilities.mayfly == fly) return;
		abilities.mayfly = fly;
		if (!fly) abilities.flying = false;
		player.onUpdateAbilities();
	}

	/** Every power gone, on the way out of the arena. */
	public static void take(ServerPlayer player) {
		instantBreaking(player, false);
	}

	private static void instantBreaking(ServerPlayer player, boolean on) {
		AttributeInstance speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (speed == null || speed.hasModifier(INSTANT) == on) return;
		if (on) speed.addTransientModifier(INSTANT_MODIFIER);
		else speed.removeModifier(INSTANT);
	}

	/** One of each endless block the player isn't holding already, while the arena is in custom: one never runs out, so one is all anyone needs. */
	public static void handOut(ServerPlayer player, Arena arena) {
		if (!custom(player, arena)) return;
		Inventory inventory = player.getInventory();
		for (ItemStack block : arena.preset.endlessBlocks.stacks()) {
			if (inventory.contains(held -> held.is(block.getItem()))) continue;
			ItemList.giveStack(player, block.copyWithCount(1));
		}
		player.containerMenu.broadcastChanges();
	}

	/** Whether putting this stack down leaves it as it was. */
	public static boolean endless(ServerPlayer player, ItemStack stack) {
		if (player.gameMode() != GameType.SURVIVAL) return false;
		Arena arena = Arenas.of(player);
		return arena != null && arena.phase == Arena.Phase.LIVE && player.level().dimension().equals(arena.dimension)
			&& ModeChanges.current(arena) == Preset.GameRule.CUSTOM && arena.preset.endlessBlocks.allows(stack.getItem());
	}
}
