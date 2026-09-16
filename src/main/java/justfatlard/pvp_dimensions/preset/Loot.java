package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Item lists rolled at random from a few rules each, for filling a preset out in a hurry: a
 * loadout in one armour tier with a weapon to match, small things for a kill, prizes worth
 * winning, pinata loot that climbs with each pinata. A starting point to edit, not a balance sheet.
 */
public final class Loot {
	private Loot() {}

	/** Rolls one list. */
	public interface Template {
		List<ItemStack> roll(RandomSource random);
	}

	private static final RandomSource RANDOM = RandomSource.create();

	public static List<ItemStack> roll(Template template) {
		return template.roll(RANDOM);
	}

	/** One thing that might be picked: an item, and how many. */
	private record Pick(String key, Function<RandomSource, ItemStack> make) {}

	private static Pick item(String id, int min, int max) {
		return new Pick(id, random -> stack(id, min + random.nextInt(max - min + 1)));
	}

	private static Pick potion(String id, Holder<Potion> potion, int min, int max) {
		return new Pick(id + "/" + potion.getRegisteredName(), random -> {
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id)).orElse(null);
			return item == null ? ItemStack.EMPTY : PotionContents.createItemStack(item, potion).copyWithCount(min + random.nextInt(max - min + 1));
		});
	}

	/** The named item, or nothing where this game has no such thing. */
	private static ItemStack stack(String id, int count) {
		return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id)).map(item -> new ItemStack(item, count)).orElse(ItemStack.EMPTY);
	}

	private static final List<Pick> SMALL = List.of(
		item("golden_apple", 1, 1), item("arrow", 8, 16), item("ender_pearl", 1, 2), item("cooked_beef", 3, 6),
		item("iron_ingot", 2, 4), item("gold_ingot", 2, 4), item("emerald", 1, 3), item("experience_bottle", 3, 6),
		potion("splash_potion", Potions.HEALING, 1, 1), potion("potion", Potions.SWIFTNESS, 1, 1));

	private static final List<Pick> MEDIUM = List.of(
		item("diamond", 1, 3), item("golden_apple", 2, 3), item("emerald", 4, 8), item("ender_pearl", 4, 8),
		item("experience_bottle", 8, 16), item("iron_block", 1, 2), item("gold_block", 1, 2), item("spectral_arrow", 8, 16),
		potion("splash_potion", Potions.STRONG_HEALING, 1, 2), potion("potion", Potions.STRENGTH, 1, 1),
		potion("potion", Potions.FIRE_RESISTANCE, 1, 1), potion("potion", Potions.REGENERATION, 1, 1));

	private static final List<Pick> BIG = List.of(
		item("diamond", 4, 10), item("enchanted_golden_apple", 1, 2), item("totem_of_undying", 1, 1), item("netherite_ingot", 1, 1),
		item("emerald_block", 2, 4), item("experience_bottle", 24, 48), item("diamond_block", 1, 2), item("trident", 1, 1),
		item("elytra", 1, 1), item("mace", 1, 1));

	private static final List<Pick> FOOD = List.of(
		item("cooked_beef", 6, 16), item("bread", 8, 16), item("baked_potato", 8, 16), item("cooked_porkchop", 6, 16),
		item("cooked_chicken", 8, 16), item("golden_carrot", 4, 8));

	private static final List<Pick> HANDY = List.of(
		item("golden_apple", 1, 2), item("ender_pearl", 1, 3), item("water_bucket", 1, 1), item("cobblestone", 16, 48),
		item("oak_planks", 16, 32), item("cobweb", 2, 4), item("snowball", 8, 16), item("fishing_rod", 1, 1),
		potion("splash_potion", Potions.HEALING, 1, 2), potion("potion", Potions.SWIFTNESS, 1, 1),
		potion("potion", Potions.FIRE_RESISTANCE, 1, 1));

	private static final String[] ARMOUR = {"leather", "chainmail", "iron", "diamond"};
	private static final String[] WEAPON = {"wooden", "stone", "iron", "diamond"};
	private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

	/** A fighting loadout: armour of one tier, mostly whole, a weapon to match, food, a few handy things. */
	public static final Template LOADOUT = random -> loadout(random, weighted(random, 3, 4, 3, 1), 3);

	/** Lighter, for coming back from a death: the lower tiers and less to spare. */
	public static final Template RESPAWN = random -> loadout(random, weighted(random, 3, 2), 1);

	/** A small thing or two for a kill. */
	public static final Template KILL = random -> pick(random, SMALL, 1, 2);

	/** Something worth winning, sometimes with a little more. */
	public static final Template PRIZE = random -> {
		List<ItemStack> out = pick(random, BIG, 1, 2);
		out.addAll(pick(random, MEDIUM, 0, 2));
		return out;
	};

	/** A shortlist of kinds allowed out: the valuable ones, one each, since only the kind matters. */
	public static final Template KINDS = random -> {
		List<Pick> valuable = new ArrayList<>(MEDIUM);
		valuable.addAll(BIG);
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack stack : pick(random, valuable, 3, 6)) {
			if (out.stream().noneMatch(kept -> kept.is(stack.getItem()))) out.add(new ItemStack(stack.getItem()));
		}
		return out;
	};

	/** A price of admission: a little of something everyone has. */
	public static final Template FEE = random -> {
		List<ItemStack> out = new ArrayList<>();
		out.add(switch (random.nextInt(4)) {
			case 0 -> stack("diamond", 1 + random.nextInt(3));
			case 1 -> stack("emerald", 4 + random.nextInt(9));
			case 2 -> stack("iron_ingot", 8 + random.nextInt(17));
			default -> stack("gold_ingot", 4 + random.nextInt(9));
		});
		return out;
	};

	/** What banks count: the valuables, the rarer worth more, a few picked with their points. */
	public static final Template BANK = random -> {
		String[][] valuables = {{"diamond", "10"}, {"emerald", "8"}, {"gold_ingot", "5"}, {"iron_ingot", "3"}, {"lapis_lazuli", "2"},
			{"redstone", "1"}, {"copper_ingot", "1"}, {"coal", "1"}, {"amethyst_shard", "2"}, {"quartz", "2"}, {"netherite_scrap", "25"},
			{"ancient_debris", "25"}, {"gold_nugget", "1"}, {"diamond_block", "90"}, {"emerald_block", "72"}, {"iron_block", "27"}};
		List<ItemStack> out = new ArrayList<>();
		Set<Integer> picked = new HashSet<>();
		int wanted = 4 + random.nextInt(4);
		while (out.size() < wanted && picked.size() < valuables.length) {
			int at = random.nextInt(valuables.length);
			if (!picked.add(at)) continue;
			ItemStack stack = stack(valuables[at][0], Math.min(64, Integer.parseInt(valuables[at][1])));
			if (!stack.isEmpty()) out.add(stack);
		}
		return out;
	};

	/** On top of a kill: the longer the streak, the bigger. */
	public static Template bonus(Preset.KillType type) {
		return switch (type) {
			case STREAK_5, TRIPLE_KILL -> random -> pick(random, MEDIUM, 1, 2);
			case STREAK_10 -> random -> pick(random, BIG, 1, 1);
			default -> random -> pick(random, SMALL, 1, 1);
		};
	}

	/** For a feat: small for the everyday ones, better for the rare. */
	public static Template feat(Preset.Feat feat) {
		return switch (feat) {
			case CAPTURE, FLAWLESS_WAVE, BIG_GAME -> random -> pick(random, MEDIUM, 1, 2);
			default -> random -> pick(random, SMALL, 1, 1);
		};
	}

	/** Pinata {@code index}'s loot, counting from nought: plain to start, better with each one. */
	public static Template pinata(int index) {
		return switch (Math.min(index, 2)) {
			case 0 -> random -> pick(random, SMALL, 3, 5);
			case 1 -> random -> {
				List<ItemStack> out = pick(random, SMALL, 2, 3);
				out.addAll(pick(random, MEDIUM, 1, 2));
				return out;
			};
			default -> random -> {
				List<ItemStack> out = pick(random, MEDIUM, 2, 3);
				out.addAll(pick(random, BIG, 1, 1));
				return out;
			};
		};
	}

	private static List<ItemStack> loadout(RandomSource random, int tier, int handy) {
		List<ItemStack> out = new ArrayList<>();
		out.add(stack(WEAPON[tier] + (random.nextInt(4) == 0 ? "_axe" : "_sword"), 1));
		int ranged = random.nextInt(10);
		if (ranged < 7) {
			out.add(stack(ranged < 5 ? "bow" : "crossbow", 1));
			out.add(stack("arrow", 8 + random.nextInt(17)));
		}
		out.addAll(pick(random, FOOD, 1, 1));
		for (String piece : PIECES) {
			if (random.nextInt(5) != 0) out.add(stack(ARMOUR[tier] + "_" + piece, 1));
		}
		if (random.nextInt(3) == 0) out.add(stack("shield", 1));
		out.addAll(pick(random, HANDY, 1, handy));
		out.removeIf(ItemStack::isEmpty);
		return out;
	}

	/** Between {@code min} and {@code max} different picks from the table. */
	private static List<ItemStack> pick(RandomSource random, List<Pick> table, int min, int max) {
		List<Pick> left = new ArrayList<>(table);
		Set<String> items = new HashSet<>();
		List<ItemStack> out = new ArrayList<>();
		int wanted = min + random.nextInt(max - min + 1);
		while (out.size() < wanted && !left.isEmpty()) {
			Pick pick = left.remove(random.nextInt(left.size()));
			ItemStack stack = pick.make().apply(random);
			if (stack.isEmpty() || !items.add(pick.key())) continue;
			out.add(stack);
		}
		return out;
	}

	/** An index chosen by the weights given, one per index. */
	private static int weighted(RandomSource random, int... weights) {
		int total = 0;
		for (int weight : weights) total += weight;
		int roll = random.nextInt(total);
		for (int i = 0; i < weights.length; i++) {
			roll -= weights[i];
			if (roll < 0) return i;
		}
		return 0;
	}
}
