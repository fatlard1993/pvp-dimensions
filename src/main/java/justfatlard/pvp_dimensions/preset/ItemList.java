package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.List;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Items and how many of each, with whatever enchantments, names or potion contents they carry:
 * the lists are made by an admin arranging real items, not by typing ids, so a list can hold
 * anything an inventory can.
 */
public final class ItemList {
	public static final Codec<ItemList> CODEC = ItemStack.CODEC.listOf().xmap(ItemList::new, list -> list.stacks);

	private final List<ItemStack> stacks;

	public ItemList() {
		this.stacks = new ArrayList<>();
	}

	private ItemList(List<ItemStack> stacks) {
		this.stacks = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (!stack.isEmpty()) this.stacks.add(stack.copy());
		}
	}

	public static ItemList of(String id, int count) {
		ItemList list = new ItemList();
		list.add(id, count);
		return list;
	}

	public static ItemList copyOf(List<ItemStack> stacks) {
		return new ItemList(stacks);
	}

	public void add(String id, int count) {
		BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
			.ifPresent(item -> stacks.add(new ItemStack(item, count)));
	}

	public void add(ItemStack stack) {
		if (!stack.isEmpty()) stacks.add(stack.copy());
	}

	public List<ItemStack> stacks() {
		return stacks.stream().map(ItemStack::copy).toList();
	}

	public boolean isEmpty() {
		return stacks.isEmpty();
	}

	public int size() {
		return stacks.size();
	}

	public ItemList copy() {
		return new ItemList(stacks);
	}

	/** Whether this list names the item, whatever else the stack carries. */
	public boolean allows(Item item) {
		for (ItemStack stack : stacks) {
			if (stack.is(item)) return true;
		}
		return false;
	}

	/** Into the player's inventory, dropping at their feet whatever has no room. */
	public void give(ServerPlayer player) {
		for (ItemStack stack : stacks) {
			giveStack(player, stack.copy());
		}
	}

	public static void giveStack(ServerPlayer player, ItemStack stack) {
		player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
	}

	/** "3 golden apple, 16 arrow" and so on, for a line of chat or a tooltip. */
	public String describe(int limit) {
		if (stacks.isEmpty()) return "nothing";
		StringBuilder words = new StringBuilder();
		for (int i = 0; i < stacks.size() && i < limit; i++) {
			if (i > 0) words.append(", ");
			ItemStack stack = stacks.get(i);
			words.append(stack.getCount()).append(" ").append(stack.getHoverName().getString());
		}
		if (stacks.size() > limit) words.append(", and ").append(stacks.size() - limit).append(" more");
		return words.toString();
	}
}
