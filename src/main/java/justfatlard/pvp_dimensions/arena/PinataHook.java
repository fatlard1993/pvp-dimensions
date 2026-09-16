package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pinata.Pinata;
import justfatlard.pinata.block.PinataBlockEntity;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The only class that names Pinata's own. Loaded only when Pinata is installed: every call to it
 * is behind {@code Fields.PINATA_INSTALLED}.
 *
 * <p>Loot goes in as the items themselves, so a potion spills as that potion and an enchanted
 * sword as that sword. A Pinata from before it kept more than kind and count spills them plain.
 */
final class PinataHook {
	private PinataHook() {}

	static boolean place(ServerLevel level, BlockPos pos, Preset.PinataLoot loot) {
		level.setBlock(pos, Pinata.PINATA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		if (!(level.getBlockEntity(pos) instanceof PinataBlockEntity pinata)) return false;
		pinata.setHitsToBreak(Math.max(1, loot.hits));
		pinata.setSpreadDistance(1.5);
		pinata.clearContentSets();
		List<PinataBlockEntity.ContentEntry> contents = new ArrayList<>();
		for (ItemStack stack : loot.items.stacks()) contents.add(entry(stack, Math.min(64, stack.getCount())));
		if (!contents.isEmpty()) pinata.addContentSet(contents);
		return true;
	}

	private static PinataBlockEntity.ContentEntry entry(ItemStack stack, int count) {
		try {
			return PinataBlockEntity.ContentEntry.of(stack, count);
		} catch (NoSuchMethodError olderPinata) {
			return new PinataBlockEntity.ContentEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), count);
		}
	}

	static boolean standing(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof PinataBlockEntity;
	}

	/** Picked up and put down somewhere else, as hit as it was, holding what it held. */
	static boolean move(ServerLevel level, BlockPos from, BlockPos to) {
		if (!(level.getBlockEntity(from) instanceof PinataBlockEntity old)) return false;
		int remaining = old.getHitsRemaining();
		List<List<PinataBlockEntity.ContentEntry>> sets = new ArrayList<>();
		for (List<PinataBlockEntity.ContentEntry> set : old.getContentSets()) sets.add(new ArrayList<>(set));
		double spread = old.getSpreadDistance();
		remove(level, from);
		level.setBlock(to, Pinata.PINATA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		if (!(level.getBlockEntity(to) instanceof PinataBlockEntity moved)) return false;
		moved.setHitsToBreak(Math.max(1, remaining));
		moved.setSpreadDistance(spread);
		moved.clearContentSets();
		for (List<PinataBlockEntity.ContentEntry> set : sets) moved.addContentSet(set);
		return true;
	}

	static void remove(ServerLevel level, BlockPos pos) {
		if (standing(level, pos)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}
}
