package justfatlard.pvp_dimensions.arena;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.preset.Fields;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;

/**
 * Dead Heads hands a player back from the dead a compass pointing at where they fell. An arena
 * whose preset says no takes its compasses back the moment they are handed over: out of the pack,
 * the offhand, and any slot another mod has added, which is where Map Plus Plus puts them.
 *
 * <p>Read off the compass rather than out of Dead Heads' classes: one of theirs carries a marker
 * in its custom data, and its lodestone target says which arena it points into.
 */
public final class DeathCompasses {
	private DeathCompasses() {}

	private static final String MARKER = "dead_heads_compass";
	private static final Identifier DEAD_HEADS_PHASE = Identifier.fromNamespaceAndPath("dead-heads", "respawn");
	private static final Identifier PHASE = Identifier.fromNamespaceAndPath(PvpDimensions.MOD_ID, "death_compasses");

	public static void register() {
		if (!Fields.DEAD_HEADS_INSTALLED) return;
		ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(DEAD_HEADS_PHASE, PHASE);
		ServerPlayerEvents.AFTER_RESPAWN.register(PHASE, (old, fresh, alive) -> {
			if (!alive) takeBack(fresh);
		});
	}

	private static void takeBack(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		boolean taken = false;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (unwanted(server, inventory.getItem(slot))) {
				inventory.setItem(slot, ItemStack.EMPTY);
				taken = true;
			}
		}
		var added = PandoricalApi.playerInventory();
		for (var registration : added.registeredSlots()) {
			for (var entry : registration.slots()) {
				if (unwanted(server, added.getSlot(player, registration.namespace(), entry.slotIndex()))) {
					added.setSlot(player, registration.namespace(), entry.slotIndex(), ItemStack.EMPTY);
					taken = true;
				}
			}
		}
		if (taken) player.containerMenu.broadcastChanges();
	}

	private static boolean unwanted(MinecraftServer server, ItemStack stack) {
		if (!stack.is(Items.COMPASS)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
		if (data == null || !data.copyTag().contains(MARKER) || tracker == null || tracker.target().isEmpty()) return false;
		GlobalPos target = tracker.target().get();
		ServerLevel level = Places.isArena(target.dimension()) ? server.getLevel(target.dimension()) : null;
		if (level == null) return false;
		Arena arena = Arenas.at(level, target.pos().getX(), target.pos().getZ());
		return arena != null && !arena.preset.deathCompasses;
	}
}
