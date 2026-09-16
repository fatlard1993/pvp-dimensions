package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import justfatlard.pvp_dimensions.preset.ItemList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What it costs to come in, paid out of what a player brings, so a game that hands out gear and
 * prizes costs something on a survival server too.
 *
 * <p>Nobody pays twice for one arena. A fight that never starts hands every fee back, and so does
 * leaving before it starts. After that, the fees are the winners' to share, where the preset says
 * so, or simply spent; a fight nobody wins hands them back to whoever is still there at the end.
 * Leave a game early and your fee stays in it.
 */
public final class Fees {
	private Fees() {}

	/** What this player is short of to pay, in words, or null if they can. */
	public static @Nullable String shortOf(ServerPlayer player, ItemList fee) {
		List<String> missing = new ArrayList<>();
		for (ItemStack wanted : fee.stacks()) {
			int have = count(player.getInventory(), wanted);
			if (have < wanted.getCount()) missing.add((wanted.getCount() - have) + " " + wanted.getHoverName().getString());
		}
		return missing.isEmpty() ? null : String.join(", ", missing);
	}

	/** The fee, out of the player's pack; what was taken, to keep for the pool. */
	public static ItemList take(ServerPlayer player, ItemList fee) {
		Inventory inventory = player.getInventory();
		for (ItemStack wanted : fee.stacks()) {
			int left = wanted.getCount();
			for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!ItemStack.isSameItemSameComponents(stack, wanted)) continue;
				int taken = Math.min(left, stack.getCount());
				stack.shrink(taken);
				left -= taken;
			}
		}
		player.containerMenu.broadcastChanges();
		return fee.copy();
	}

	private static int count(Inventory inventory, ItemStack wanted) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (ItemStack.isSameItemSameComponents(stack, wanted)) total += stack.getCount();
		}
		return total;
	}

	/**
	 * Everyone's fee shared out among the winners, owed to them as they go home: each item's total
	 * split evenly, the odd ones over going to winners picked at random. The whole pot, to say what
	 * it was, or null where there was none to share.
	 */
	public static @Nullable ItemList shareOut(Arena arena, List<UUID> winners) {
		if (winners.isEmpty()) return null;
		if (!arena.preset.feesToWinners) {
			for (Arena.Member member : arena.members.values()) member.fee = new ItemList();
			return null;
		}
		List<ItemStack> pool = new ArrayList<>();
		for (Arena.Member member : arena.members.values()) {
			for (ItemStack paid : member.fee.stacks()) {
				ItemStack same = null;
				for (ItemStack held : pool) {
					if (ItemStack.isSameItemSameComponents(held, paid)) same = held;
				}
				if (same != null) same.grow(paid.getCount());
				else pool.add(paid.copy());
			}
			member.fee = new ItemList();
		}
		List<UUID> order = new ArrayList<>(winners);
		Collections.shuffle(order);
		for (ItemStack total : pool) {
			int each = total.getCount() / order.size();
			int over = total.getCount() % order.size();
			for (int i = 0; i < order.size(); i++) {
				int share = each + (i < over ? 1 : 0);
				if (share > 0) owe(arena, order.get(i), total.copyWithCount(share));
			}
		}
		return pool.isEmpty() ? null : ItemList.copyOf(pool);
	}

	/** At the end, the fees still unclaimed: back to whoever paid them, unless they were spent on a fight that happened. */
	public static void settle(Arena arena) {
		boolean refund = arena.liveAt == 0 || arena.preset.feesToWinners;
		for (Arena.Member member : arena.members.values()) {
			if (refund && member.inside) {
				for (ItemStack paid : member.fee.stacks()) owe(arena, member.id, paid.copy());
			}
			member.fee = new ItemList();
		}
	}

	/** Leaving before the fight starts: the fee back. */
	public static void leftEarly(Arena arena, Arena.Member member) {
		if (arena.liveAt != 0 || member.fee.isEmpty()) return;
		for (ItemStack paid : member.fee.stacks()) owe(arena, member.id, paid.copy());
		member.fee = new ItemList();
		member.paid = false;
	}

	private static void owe(Arena arena, UUID player, ItemStack stack) {
		arena.owed.computeIfAbsent(player, id -> new ItemList()).add(stack);
	}
}
