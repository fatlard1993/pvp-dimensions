package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.preset.ItemList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The slots other mods add to the inventory screen through Pandorical: Map Plus Plus's map and
 * compass, and whatever else turns up. They sit outside the inventory, so emptying it leaves them
 * be: an arena that keeps things apart takes them aside and hands them back itself, or a compass
 * goes in with its owner and a death compass comes out in its place.
 */
public final class AddedSlots {
	private AddedSlots() {}

	/** What one added slot held. */
	public record Held(Identifier namespace, int slot, ItemStack stack) {
		static final Codec<Held> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("namespace").forGetter(Held::namespace),
			Codec.INT.fieldOf("slot").forGetter(Held::slot),
			ItemStack.CODEC.fieldOf("item").forGetter(Held::stack)
		).apply(instance, Held::new));
	}

	/** Everything sitting in an added slot, taken out of it. */
	public static List<Held> take(ServerPlayer player) {
		var slots = PandoricalApi.playerInventory();
		List<Held> taken = new ArrayList<>();
		for (var registration : slots.registeredSlots()) {
			for (var entry : registration.slots()) {
				ItemStack stack = slots.getSlot(player, registration.namespace(), entry.slotIndex());
				if (stack.isEmpty()) continue;
				taken.add(new Held(registration.namespace(), entry.slotIndex(), stack.copy()));
				slots.setSlot(player, registration.namespace(), entry.slotIndex(), ItemStack.EMPTY);
			}
		}
		return taken;
	}

	/** Each back in the slot it came from, or into the pack where that slot is gone, taken, or no longer takes it. */
	public static void putBack(ServerPlayer player, List<Held> held) {
		var slots = PandoricalApi.playerInventory();
		for (Held one : held) {
			boolean fits = slots.registeredSlots().stream()
				.filter(registration -> registration.namespace().equals(one.namespace()))
				.flatMap(registration -> registration.slots().stream())
				.anyMatch(entry -> entry.slotIndex() == one.slot() && entry.validator().test(one.stack()));
			if (fits && slots.getSlot(player, one.namespace(), one.slot()).isEmpty()) {
				slots.setSlot(player, one.namespace(), one.slot(), one.stack().copy());
			} else {
				ItemList.giveStack(player, one.stack().copy());
			}
		}
	}
}
