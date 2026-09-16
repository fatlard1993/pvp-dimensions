package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

/**
 * A whole inventory, laid out: which slot each item goes in, armour and off hand included. A kit
 * is what a player is handed on the way in, so the sword should be in their hand and the helmet
 * on their head, not both somewhere in their pack.
 */
public final class Kit {
	private record Slot(int slot, ItemStack stack) {
		static final Codec<Slot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("slot").forGetter(Slot::slot),
			ItemStack.CODEC.fieldOf("item").forGetter(Slot::stack)
		).apply(instance, Slot::new));
	}

	public static final Codec<Kit> CODEC = Slot.CODEC.listOf().xmap(Kit::fromSlots, Kit::toSlots);

	private static final int HELD = 0;
	private static final int FEET = 36;
	private static final int LEGS = 37;
	private static final int CHEST = 38;
	private static final int HEAD = 39;

	private final Map<Integer, ItemStack> slots = new TreeMap<>();

	/** A plain fighting kit, so a preset made in a hurry still sends people in with something. */
	public static Kit starter() {
		Kit kit = new Kit();
		kit.put(HELD, "minecraft:stone_sword", 1);
		kit.put(1, "minecraft:bow", 1);
		kit.put(2, "minecraft:cooked_beef", 8);
		kit.put(9, "minecraft:arrow", 16);
		kit.put(HEAD, "minecraft:leather_helmet", 1);
		kit.put(CHEST, "minecraft:leather_chestplate", 1);
		kit.put(LEGS, "minecraft:leather_leggings", 1);
		kit.put(FEET, "minecraft:leather_boots", 1);
		return kit;
	}

	/** What the horde rises with unless a preset says otherwise: a stone sword in hand. */
	public static Kit zombie() {
		Kit kit = new Kit();
		kit.put(HELD, "minecraft:stone_sword", 1);
		return kit;
	}

	private void put(int slot, String id, int count) {
		BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
			.ifPresent(item -> slots.put(slot, new ItemStack(item, count)));
	}

	/** Everything the player is carrying, where they are carrying it. */
	public static Kit of(ServerPlayer player) {
		Kit kit = new Kit();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) kit.slots.put(slot, stack.copy());
		}
		return kit;
	}

	/** A plain list, laid into the pack from the first hotbar slot on. */
	public static Kit laid(List<ItemStack> stacks) {
		Kit kit = new Kit();
		int slot = 0;
		for (ItemStack stack : stacks) {
			if (slot >= Inventory.INVENTORY_SIZE) break;
			if (!stack.isEmpty()) kit.slots.put(slot++, stack.copy());
		}
		return kit;
	}

	/** A list put on: armour worn, a shield in the off hand, the rest from the first hotbar slot. */
	public static Kit dressed(List<ItemStack> stacks) {
		Kit kit = new Kit();
		int slot = 0;
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) continue;
			Equippable worn = stack.get(DataComponents.EQUIPPABLE);
			int to = stack.is(Items.SHIELD) ? Inventory.SLOT_OFFHAND : worn == null ? -1 : switch (worn.slot()) {
				case HEAD -> HEAD;
				case CHEST -> CHEST;
				case LEGS -> LEGS;
				case FEET -> FEET;
				default -> -1;
			};
			if (to >= 0 && !kit.slots.containsKey(to)) kit.slots.put(to, stack.copy());
			else if (slot < Inventory.INVENTORY_SIZE) kit.slots.put(slot++, stack.copy());
		}
		return kit;
	}

	public boolean isEmpty() {
		return slots.isEmpty();
	}

	public int count() {
		return slots.size();
	}

	public List<ItemStack> stacks() {
		List<ItemStack> stacks = new ArrayList<>();
		for (ItemStack stack : slots.values()) stacks.add(stack.copy());
		return stacks;
	}

	public Kit copy() {
		return fromSlots(toSlots(this));
	}

	/**
	 * Lay the kit out in the player's inventory. A slot already taken keeps what is in it and the
	 * kit's item goes wherever there is room, so a kit handed over a full pack loses nothing.
	 */
	public void give(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
			int slot = entry.getKey();
			ItemStack stack = entry.getValue().copy();
			if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) {
				inventory.setItem(slot, stack);
			} else {
				ItemList.giveStack(player, stack);
			}
		}
		player.containerMenu.broadcastChanges();
	}

	public String describe(int limit) {
		return ItemList.copyOf(stacks()).describe(limit);
	}

	private static Kit fromSlots(List<Slot> list) {
		Kit kit = new Kit();
		for (Slot slot : list) {
			if (!slot.stack().isEmpty()) kit.slots.put(slot.slot(), slot.stack().copy());
		}
		return kit;
	}

	private static List<Slot> toSlots(Kit kit) {
		List<Slot> list = new ArrayList<>();
		kit.slots.forEach((slot, stack) -> list.add(new Slot(slot, stack.copy())));
		return list;
	}
}
