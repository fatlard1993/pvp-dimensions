package justfatlard.pvp_dimensions.arena;

import java.util.Optional;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jspecify.annotations.Nullable;

/**
 * A compass pointed at the goal, handed out with the kit where the preset says: the hill, the
 * finish, or the nearest other team's base for capture the flag and banking. It turns when the hill
 * moves. Where Map++ is installed it carries Mob Sight and goes straight into the compass slot, so
 * it draws Map++'s radar with its needle on the goal; anywhere else it is a compass that points.
 *
 * <p>It belongs to the arena: none leaves with a player, whatever the arena lets out.
 */
public final class GoalCompass {
	private GoalCompass() {}

	private static final String MARK = "pvp_dimensions_goal_compass";
	private static final ResourceKey<Enchantment> MOB_SIGHT = ResourceKey.create(Registries.ENCHANTMENT,
		Identifier.fromNamespaceAndPath("map-plus-plus", "mob_sight"));
	private static final Identifier MAP_PLUS_PLUS_SLOTS = Identifier.fromNamespaceAndPath("map-plus-plus", "slots");

	/** Whether this preset hands one out: it asks to, and its goal has somewhere to point. */
	public static boolean wanted(Preset preset) {
		if (!preset.goalCompass) return false;
		return switch (preset.activeGoal()) {
			case HILL, RACE, CTF, BANK -> true;
			default -> false;
		};
	}

	/** A fresh one, the old taken away first, into the compass slot where there is one free. */
	public static void give(ServerPlayer player, Arena arena, Arena.Member member) {
		if (!wanted(arena.preset)) return;
		BlockPos target = target(arena, member, player);
		if (target == null) return;
		strip(player);
		ItemStack compass = make(player, arena, target, name(arena, target));
		if (!intoSlot(player, compass)) justfatlard.pvp_dimensions.preset.ItemList.giveStack(player, compass);
		player.containerMenu.broadcastChanges();
	}

	/** Every compass inside turned to where the goal is now: the hill has moved. */
	public static void retarget(MinecraftServer server, Arena arena) {
		if (!wanted(arena.preset)) return;
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			BlockPos target = target(arena, member, player);
			if (target == null) continue;
			LodestoneTracker tracker = new LodestoneTracker(Optional.of(GlobalPos.of(arena.dimension, target)), false);
			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				if (ours(inventory.getItem(slot))) inventory.getItem(slot).set(DataComponents.LODESTONE_TRACKER, tracker);
			}
			var added = PandoricalApi.playerInventory();
			for (var registration : added.registeredSlots()) {
				for (var entry : registration.slots()) {
					ItemStack stack = added.getSlot(player, registration.namespace(), entry.slotIndex());
					if (!ours(stack)) continue;
					ItemStack turned = stack.copy();
					turned.set(DataComponents.LODESTONE_TRACKER, tracker);
					added.setSlot(player, registration.namespace(), entry.slotIndex(), turned);
				}
			}
			player.containerMenu.broadcastChanges();
		}
	}

	/** Every one of ours taken back, from the pack and from the slots other mods add. */
	public static void strip(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (ours(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
		}
		var added = PandoricalApi.playerInventory();
		for (var registration : added.registeredSlots()) {
			for (var entry : registration.slots()) {
				if (ours(added.getSlot(player, registration.namespace(), entry.slotIndex()))) {
					added.setSlot(player, registration.namespace(), entry.slotIndex(), ItemStack.EMPTY);
				}
			}
		}
	}

	/** The hill or the finish; for flags and banks, the other team's base nearest the player. */
	private static @Nullable BlockPos target(Arena arena, Arena.Member member, ServerPlayer player) {
		return switch (arena.preset.activeGoal()) {
			case HILL, RACE -> arena.marker;
			case CTF, BANK -> {
				BlockPos nearest = null;
				for (int team = 0; team < arena.bases.size(); team++) {
					if (team == member.team) continue;
					BlockPos base = arena.bases.get(team);
					if (nearest == null || base.distSqr(player.blockPosition()) < nearest.distSqr(player.blockPosition())) nearest = base;
				}
				yield nearest;
			}
			default -> null;
		};
	}

	private static String name(Arena arena, BlockPos target) {
		return switch (arena.preset.activeGoal()) {
			case HILL -> "To the hill";
			case RACE -> "To the finish";
			default -> {
				int team = arena.bases.indexOf(target);
				yield team >= 0 ? "To " + TeamColors.of(team).name() + "'s base" : "To their base";
			}
		};
	}

	private static ItemStack make(ServerPlayer player, Arena arena, BlockPos target, String name) {
		ItemStack compass = new ItemStack(Items.COMPASS);
		compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(arena.dimension, target)), false));
		compass.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
		CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> tag.putBoolean(MARK, true));
		player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(MOB_SIGHT).ifPresent(sight -> compass.enchant(sight, 1));
		return compass;
	}

	/** Into Map++'s compass slot, if it is installed and the slot is empty. */
	private static boolean intoSlot(ServerPlayer player, ItemStack compass) {
		var added = PandoricalApi.playerInventory();
		for (var registration : added.registeredSlots()) {
			if (!registration.namespace().equals(MAP_PLUS_PLUS_SLOTS)) continue;
			for (var entry : registration.slots()) {
				if (!entry.validator().test(compass) || !added.getSlot(player, registration.namespace(), entry.slotIndex()).isEmpty()) continue;
				added.setSlot(player, registration.namespace(), entry.slotIndex(), compass);
				return true;
			}
		}
		return false;
	}

	private static boolean ours(ItemStack stack) {
		if (!stack.is(Items.COMPASS)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(MARK, false);
	}
}
