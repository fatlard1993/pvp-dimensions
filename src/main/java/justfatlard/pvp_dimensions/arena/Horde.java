package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.Say;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * Where everyone fights the mobs together, the fallen can come back as zombies instead of watching:
 * on the mobs' side, which leave them be, hunting the players still standing, who can fight them
 * back. A zombie wears a zombie's head and rags it can't take off, carries the preset's zombie kit
 * (a stone sword unless it says otherwise), can't pick anything up or open anything, and can't
 * break or build. Put down, it rises again elsewhere.
 *
 * <p>When nobody is left standing, the horde has won.
 */
public final class Horde {
	private Horde() {}

	/** On a zombie player, so the mobs, the damage rules and the pickup rule all know one. */
	public static final String TAG = "pvp-dimensions.horde";
	/** On everything a zombie is handed, so none of it leaves the arena with them. */
	private static final String MARK = "pvp_dimensions_horde";
	private static final int RAGS = 0x4A6B3A;

	public static boolean is(@Nullable Entity entity) {
		return entity != null && entity.entityTags().contains(TAG);
	}

	/** Out of the fight, and back as one of the horde: kitted, on its team, somewhere away from the living. */
	public static void rise(ServerPlayer player, Arena arena, Arena.Member member) {
		boolean first = !member.zombie;
		member.zombie = true;
		member.out = true;
		member.watching = false;
		player.addTag(TAG);
		player.setGameMode(GameType.ADVENTURE);
		player.getInventory().clearContent();
		kit(player, arena);
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5);
		player.removeAllEffects();
		Teams.horde(player, arena);
		player.containerMenu.broadcastChanges();
		if (first) {
			Say.to(player, "You've risen with the horde. The mobs are with you now: hunt down whoever is still standing");
			Arenas.tellInside(player.level().getServer(), arena, member.name + " has risen with the horde!");
		}
	}

	/**
	 * The preset's zombie kit, every piece of it marked as the horde's and gone when its zombie dies,
	 * then the look: a zombie's
	 * head whatever the kit wears, and rags wherever it wears nothing.
	 */
	private static void kit(ServerPlayer player, Arena arena) {
		var enchantments = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		Holder<Enchantment> binding = enchantments.getOrThrow(Enchantments.BINDING_CURSE);
		Holder<Enchantment> vanishing = enchantments.getOrThrow(Enchantments.VANISHING_CURSE);
		arena.preset.hordeKit.give(player);
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) continue;
			marked(stack);
			stack.enchant(vanishing, 1);
		}
		player.setItemSlot(EquipmentSlot.HEAD, worn(Items.ZOMBIE_HEAD, binding, vanishing, false));
		if (player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) player.setItemSlot(EquipmentSlot.CHEST, worn(Items.LEATHER_CHESTPLATE, binding, vanishing, true));
		if (player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) player.setItemSlot(EquipmentSlot.LEGS, worn(Items.LEATHER_LEGGINGS, binding, vanishing, true));
		if (player.getItemBySlot(EquipmentSlot.FEET).isEmpty()) player.setItemSlot(EquipmentSlot.FEET, worn(Items.LEATHER_BOOTS, binding, vanishing, true));
	}

	private static ItemStack worn(Item item, Holder<Enchantment> binding, Holder<Enchantment> vanishing, boolean dyed) {
		ItemStack stack = marked(new ItemStack(item));
		if (dyed) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(RAGS));
		stack.enchant(binding, 1);
		stack.enchant(vanishing, 1);
		return stack;
	}

	private static ItemStack marked(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARK, true));
		return stack;
	}

	private static boolean horde(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return false;
		CompoundTag tag = data.copyTag();
		return tag.getBooleanOr(MARK, false);
	}

	/** Human again, on the way out: the tag off, and the horde's things taken back. */
	public static void leave(ServerPlayer player) {
		player.removeTag(TAG);
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (horde(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (horde(player.getItemBySlot(slot))) player.setItemSlot(slot, ItemStack.EMPTY);
		}
	}

	/**
	 * Whether one player may hurt another, where the horde decides it: a zombie and a living
	 * player always, two zombies never, and null for two of the living, which is the arena's rule.
	 */
	public static @Nullable Boolean mayHarm(ServerPlayer one, Player other) {
		boolean zombie = is(one);
		if (zombie == is(other)) return zombie ? Boolean.FALSE : null;
		return Boolean.TRUE;
	}
}
