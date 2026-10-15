package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.math.Transformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Guess Who, between two teams: each has the same gallery of sixteen suspects at its own end of
 * the ground, and a suspect of its own that the other team is trying to find. On your turn, ask the
 * other team one yes-or-no question out loud and they answer it, truthfully; flip down the suspects
 * the answer rules out with the levers in front of them; and ring your bell to end the turn. When
 * you think you know, right-click your suspect instead: right wins it, wrong loses it.
 *
 * <p>The questions are the players' own. Nothing here hears them, so nothing here can answer them
 * or check an answer; what the game keeps is whose turn it is, who each team's suspect is, and
 * which faces each team has flipped down. Each team flips in its own gallery, which is all its own
 * thinking: the other team's is across the ground and none of its business.
 *
 * <p>The suspects are real things, not made-up people: eight kinds of mob, and eight blocks and
 * items between them, drawn fresh each game from pools of dozens of each, so the questions are the
 * ones anybody can ask about Minecraft. Is it a mob? Is it hostile? Can it fly? Is it something you
 * mine? Can you hold it? An item floats over its pen, water and lava as their buckets. Our other
 * mods add to the pools while they are loaded: their blocks and items, and the bears and baby
 * skeletons they make of vanilla mobs. Nothing here needs them; what isn't on the server isn't drawn. A wall down the
 * middle of the ground keeps each team's gallery, and its flipping, its own.
 *
 * <p>A gallery's first corner is {@link Arena#bases}, one per team. The turn, the suspects and the
 * flips are not saved: a game carried over a restart starts over with new suspects.
 */
public final class GuessWho {
	private GuessWho() {}

	public static final String TAG = "pvp-dimensions.suspect";
	/** The mark on the copy of a team's own suspect stood on its pedestal, which is to look at and nothing else. */
	public static final String CARD = "pvp-dimensions.suspect_card";
	/** The mark on a suspect stood as a baby, which the gallery keeps young for the whole game. */
	private static final String YOUNG = "pvp-dimensions.suspect_young";
	/** A baby's age, held: young for longer than any game. */
	private static final int YOUNG_AGE = -1_000_000;
	/** Columns and rows of suspects in a gallery. */
	private static final int COLUMNS = 4;
	private static final int ROWS = 4;
	/** Blocks between neighbouring suspects across a row, and between rows. */
	private static final int ACROSS = 3;
	private static final int DOWN = 4;
	/** How far in front of its suspect a lever stands. */
	private static final int LEVER = 2;
	private static final long TURN_MILLIS = 120_000L;
	/** How long a first right-click on a suspect waits for the second that accuses them. */
	private static final long ACCUSE_MILLIS = 5_000L;
	private static final int BEHIND = 6;

	public static final int SUSPECT_COUNT = COLUMNS * ROWS;
	/** Of a gallery's sixteen, how many are mobs; the rest are blocks and items. */
	private static final int MOB_COUNT = 8;

	/**
	 * A suspect: a kind of mob, a block, or an item, called by what it is. A mob can be dressed as it
	 * is made, into one of the variants another of our mods makes of it, and then it needs that mod.
	 */
	private record Suspect(String name, @Nullable EntityType<? extends Mob> type, @Nullable String block, @Nullable String item,
			Consumer<Mob> dress, @Nullable String mod) {
		/**
		 * Whether this server can show it: a block or an item by an id it knows, so one from a mod that
		 * isn't loaded never stands in as the stone or the nothing it would fall back to; a mob whose
		 * mod, if any, is loaded.
		 */
		boolean here() {
			if (block != null) {
				Identifier id = Identifier.tryParse(block);
				return id != null && BuiltInRegistries.BLOCK.containsKey(id);
			}
			if (item != null) {
				Identifier id = Identifier.tryParse(item);
				return id != null && BuiltInRegistries.ITEM.containsKey(id);
			}
			return mod == null || FabricLoader.getInstance().isModLoaded(mod);
		}
	}

	private static final Consumer<Mob> PLAIN = mob -> { };

	private static Suspect mob(String name, EntityType<? extends Mob> type) {
		return new Suspect(name, type, null, null, PLAIN, null);
	}

	/** One of the mobs another of our mods makes out of a vanilla one, there only when that mod is. */
	private static Suspect variant(String name, String mod, EntityType<? extends Mob> type, Consumer<Mob> dress) {
		return new Suspect(name, type, null, null, dress, mod);
	}

	private static final Consumer<Mob> BABY = mob -> {
		mob.setBaby(true);
		mob.addTag(YOUNG);
	};

	private static Suspect baby(String name, EntityType<? extends Mob> type) {
		return new Suspect(name, type, null, null, BABY, null);
	}

	private static Suspect block(String name, String id) {
		return new Suspect(name, null, "minecraft:" + id, null, PLAIN, null);
	}

	/** A block from one of our mods, by its full id: in the pool only while that mod is loaded. */
	private static Suspect ours(String name, String id) {
		return new Suspect(name, null, id, null, PLAIN, null);
	}

	private static Suspect item(String name, String id) {
		return new Suspect(name, null, null, "minecraft:" + id, PLAIN, null);
	}

	/** An item from one of our mods, by its full id: in the pool only while that mod is loaded. */
	private static Suspect ourItem(String name, String id) {
		return new Suspect(name, null, null, id, PLAIN, null);
	}

	private static final String BEARS = "beets-bears-justfatlard";
	private static final String BABY_SKELETONS = "baby-skeletons-justfatlard";

	/**
	 * One of Beets Bears' bears, as that mod makes one: a polar bear with the tag it reads for which
	 * bear this is, grown or shrunk by the same modifier at the same id. The coat is the mod's own
	 * part; it paints it on any bear wearing the tag as it loads.
	 */
	private static Consumer<Mob> bear(String kind, float scale) {
		return mob -> {
			mob.addTag(kind);
			AttributeModifier size = new AttributeModifier(Identifier.fromNamespaceAndPath(BEARS, "size_" + kind), scale - 1F,
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
			for (var attribute : List.of(Attributes.SCALE, Attributes.MAX_HEALTH)) {
				AttributeInstance instance = mob.getAttribute(attribute);
				if (instance != null) instance.addOrReplacePermanentModifier(size);
			}
			mob.setHealth(mob.getMaxHealth());
		};
	}

	/**
	 * The mobs a gallery is drawn from: a spread of hostile and peaceful, big and small, ones that
	 * fly and ones that swim, so a question about any of those cuts the gallery somewhere useful.
	 * Nothing too big for its spot, three across and four deep: no ghasts, withers, dragons or
	 * giants. Those that burn in daylight are stood in an unbreakable leather cap and those that
	 * would turn zombie away from the Nether are kept from it: see {@link #freeze}.
	 */
	private static final List<Suspect> MOBS = List.of(
		mob("Cow", EntityTypes.COW), mob("Pig", EntityTypes.PIG), mob("Sheep", EntityTypes.SHEEP), mob("Chicken", EntityTypes.CHICKEN),
		mob("Wolf", EntityTypes.WOLF), mob("Cat", EntityTypes.CAT), mob("Fox", EntityTypes.FOX), mob("Rabbit", EntityTypes.RABBIT),
		mob("Parrot", EntityTypes.PARROT), mob("Bee", EntityTypes.BEE), mob("Bat", EntityTypes.BAT), mob("Allay", EntityTypes.ALLAY),
		mob("Creeper", EntityTypes.CREEPER), mob("Spider", EntityTypes.SPIDER), mob("Witch", EntityTypes.WITCH), mob("Blaze", EntityTypes.BLAZE),
		mob("Enderman", EntityTypes.ENDERMAN), mob("Slime", EntityTypes.SLIME), mob("Magma cube", EntityTypes.MAGMA_CUBE),
		mob("Pillager", EntityTypes.PILLAGER), mob("Iron golem", EntityTypes.IRON_GOLEM), mob("Snow golem", EntityTypes.SNOW_GOLEM),
		mob("Villager", EntityTypes.VILLAGER), mob("Frog", EntityTypes.FROG), mob("Turtle", EntityTypes.TURTLE), mob("Axolotl", EntityTypes.AXOLOTL),
		mob("Panda", EntityTypes.PANDA), mob("Polar bear", EntityTypes.POLAR_BEAR), mob("Llama", EntityTypes.LLAMA), mob("Goat", EntityTypes.GOAT),
		// The dead, capped against the sun.
		mob("Zombie", EntityTypes.ZOMBIE), mob("Husk", EntityTypes.HUSK), mob("Drowned", EntityTypes.DROWNED),
		mob("Zombie villager", EntityTypes.ZOMBIE_VILLAGER), mob("Skeleton", EntityTypes.SKELETON), mob("Stray", EntityTypes.STRAY),
		mob("Bogged", EntityTypes.BOGGED), mob("Wither skeleton", EntityTypes.WITHER_SKELETON), mob("Phantom", EntityTypes.PHANTOM),
		// The Nether's, and the End's.
		mob("Piglin", EntityTypes.PIGLIN), mob("Hoglin", EntityTypes.HOGLIN), mob("Zombified piglin", EntityTypes.ZOMBIFIED_PIGLIN),
		mob("Strider", EntityTypes.STRIDER), mob("Shulker", EntityTypes.SHULKER),
		// Raiders, and the rest of what hunts you.
		mob("Evoker", EntityTypes.EVOKER), mob("Vindicator", EntityTypes.VINDICATOR), mob("Ravager", EntityTypes.RAVAGER),
		mob("Warden", EntityTypes.WARDEN), mob("Breeze", EntityTypes.BREEZE), mob("Creaking", EntityTypes.CREAKING),
		mob("Silverfish", EntityTypes.SILVERFISH), mob("Cave spider", EntityTypes.CAVE_SPIDER),
		// The sea's, standing in the air: they neither flop nor dry out while frozen.
		mob("Squid", EntityTypes.SQUID), mob("Glow squid", EntityTypes.GLOW_SQUID), mob("Dolphin", EntityTypes.DOLPHIN),
		mob("Pufferfish", EntityTypes.PUFFERFISH), mob("Guardian", EntityTypes.GUARDIAN),
		// More animals, and more that help.
		mob("Horse", EntityTypes.HORSE), mob("Donkey", EntityTypes.DONKEY), mob("Camel", EntityTypes.CAMEL), mob("Mooshroom", EntityTypes.MOOSHROOM),
		mob("Ocelot", EntityTypes.OCELOT), mob("Sniffer", EntityTypes.SNIFFER), mob("Armadillo", EntityTypes.ARMADILLO),
		mob("Wandering trader", EntityTypes.WANDERING_TRADER), mob("Copper golem", EntityTypes.COPPER_GOLEM),
		// Our own mods' takes on vanilla mobs, while those mods are loaded. Each is made the way its
		// mod makes one: the tag it reads, and its size; the mod dresses the rest as the mob loads.
		variant("Black bear", BEARS, EntityTypes.POLAR_BEAR, bear("black_bear", 0.8F)),
		variant("Brown bear", BEARS, EntityTypes.POLAR_BEAR, bear("brown_bear", 1.25F)),
		variant("Baby skeleton", BABY_SKELETONS, EntityTypes.SKELETON, mob -> mob.addTag("baby_skeleton")),
		variant("Baby stray", BABY_SKELETONS, EntityTypes.STRAY, mob -> mob.addTag("baby_skeleton")),
		variant("Baby bogged", BABY_SKELETONS, EntityTypes.BOGGED, mob -> mob.addTag("baby_skeleton")),
		variant("Baby wither skeleton", BABY_SKELETONS, EntityTypes.WITHER_SKELETON, mob -> mob.addTag("baby_skeleton")),
		variant("Black bear cub", BEARS, EntityTypes.POLAR_BEAR, bear("black_bear", 0.8F).andThen(BABY)),
		variant("Brown bear cub", BEARS, EntityTypes.POLAR_BEAR, bear("brown_bear", 1.25F).andThen(BABY)),
		// Every baby the game has, each its own suspect. No tadpole: it turns into a frog while it stands.
		baby("Calf", EntityTypes.COW), baby("Piglet", EntityTypes.PIG), baby("Lamb", EntityTypes.SHEEP), baby("Chick", EntityTypes.CHICKEN),
		baby("Puppy", EntityTypes.WOLF), baby("Kitten", EntityTypes.CAT), baby("Fox kit", EntityTypes.FOX), baby("Baby rabbit", EntityTypes.RABBIT),
		baby("Baby bee", EntityTypes.BEE), baby("Baby villager", EntityTypes.VILLAGER), baby("Baby turtle", EntityTypes.TURTLE),
		baby("Baby axolotl", EntityTypes.AXOLOTL), baby("Panda cub", EntityTypes.PANDA), baby("Polar bear cub", EntityTypes.POLAR_BEAR),
		baby("Baby llama", EntityTypes.LLAMA), baby("Goat kid", EntityTypes.GOAT), baby("Baby zombie", EntityTypes.ZOMBIE),
		baby("Baby husk", EntityTypes.HUSK), baby("Baby drowned", EntityTypes.DROWNED), baby("Baby zombie villager", EntityTypes.ZOMBIE_VILLAGER),
		baby("Baby piglin", EntityTypes.PIGLIN), baby("Hoglet", EntityTypes.HOGLIN), baby("Baby zombified piglin", EntityTypes.ZOMBIFIED_PIGLIN),
		baby("Baby strider", EntityTypes.STRIDER), baby("Baby squid", EntityTypes.SQUID), baby("Baby glow squid", EntityTypes.GLOW_SQUID),
		baby("Baby dolphin", EntityTypes.DOLPHIN), baby("Foal", EntityTypes.HORSE), baby("Baby donkey", EntityTypes.DONKEY),
		baby("Baby camel", EntityTypes.CAMEL), baby("Baby mooshroom", EntityTypes.MOOSHROOM), baby("Ocelot kitten", EntityTypes.OCELOT),
		baby("Snifflet", EntityTypes.SNIFFER), baby("Baby armadillo", EntityTypes.ARMADILLO));

	/**
	 * The blocks a gallery is drawn from: ores and crops, wood and stone, things that glow and things
	 * you use. Each stands whole on its own on the wool, and none answers redstone: the lever is two
	 * blocks off, too far to power it.
	 */
	private static final List<Suspect> BLOCKS = List.of(
		block("Diamond block", "diamond_block"), block("Gold block", "gold_block"), block("Emerald block", "emerald_block"),
		block("Redstone block", "redstone_block"), block("Lapis block", "lapis_block"), block("TNT", "tnt"),
		block("Pumpkin", "pumpkin"), block("Melon", "melon"), block("Hay bale", "hay_block"), block("Crafting table", "crafting_table"),
		block("Bookshelf", "bookshelf"), block("Furnace", "furnace"), block("Chest", "chest"), block("Oak log", "oak_log"),
		block("Glass", "glass"), block("Sponge", "sponge"), block("Obsidian", "obsidian"), block("Glowstone", "glowstone"),
		block("Bricks", "bricks"), block("Sand", "sand"), block("Blue ice", "blue_ice"), block("Slime block", "slime_block"),
		block("Honey block", "honey_block"), block("Jukebox", "jukebox"), block("Sea lantern", "sea_lantern"), block("Moss block", "moss_block"),
		// The ground, and what's mined out of it.
		block("Grass block", "grass_block"), block("Stone", "stone"), block("Dirt", "dirt"), block("Cobblestone", "cobblestone"),
		block("Gravel", "gravel"), block("Clay", "clay"), block("Mycelium", "mycelium"), block("Terracotta", "terracotta"),
		block("Bedrock", "bedrock"), block("Diamond ore", "diamond_ore"), block("Iron ore", "iron_ore"), block("Coal ore", "coal_ore"),
		block("Gold ore", "gold_ore"), block("Iron block", "iron_block"), block("Netherite block", "netherite_block"),
		block("Coal block", "coal_block"), block("Copper block", "waxed_copper_block"), block("Amethyst block", "amethyst_block"),
		block("Quartz block", "quartz_block"), block("Snow block", "snow_block"), block("Packed ice", "packed_ice"),
		block("White wool", "white_wool"), block("Red mushroom block", "red_mushroom_block"), block("Dried kelp block", "dried_kelp_block"),
		block("Honeycomb block", "honeycomb_block"), block("Prismarine", "prismarine"), block("Sculk", "sculk"),
		// The Nether and the End.
		block("Netherrack", "netherrack"), block("Soul sand", "soul_sand"), block("Magma block", "magma_block"),
		block("Ancient debris", "ancient_debris"), block("Crying obsidian", "crying_obsidian"), block("Respawn anchor", "respawn_anchor"),
		block("End stone", "end_stone"), block("Purpur block", "purpur_block"), block("Ender chest", "ender_chest"),
		block("Shulker box", "shulker_box"),
		// Things you make and use.
		block("Beacon", "beacon"), block("Enchanting table", "enchanting_table"), block("Anvil", "anvil"), block("Spawner", "spawner"),
		block("Cake", "cake"), block("Barrel", "barrel"), block("Composter", "composter"), block("Cauldron", "cauldron"),
		block("Brewing stand", "brewing_stand"), block("Lodestone", "lodestone"),
		// Our own mods' blocks, while those mods are loaded.
		ours("Ship's helm", "big-boats-justfatlard:helm"), ours("Cheese wheel", "lets-cook-justfatlard:cheese_block"),
		ours("Chocolate cake", "lets-cook-justfatlard:chocolate_cake"), ours("Block of leather", "more-leather-justfatlard:leather_block"),
		ours("Player detector", "player-detector-justfatlard:player_detector"), ours("Poop block", "poopsmith:poop_block"),
		ours("Guano block", "poopsmith:guano_block"), ours("Mailbox", "village-mail:mailbox"),
		ours("Upward hopper", "hopper-upper-justfatlard:upward_hopper"));

	/**
	 * The items a gallery draws from along with its blocks, each floating over its pen as itself:
	 * treasure, gear, the things that take you places, food. Water and lava are here too, as their
	 * buckets, since neither would stand still in a pen as a block.
	 */
	private static final List<Suspect> ITEMS = List.of(
		item("Water", "water_bucket"), item("Lava", "lava_bucket"), item("Milk bucket", "milk_bucket"),
		// Treasure.
		item("Diamond", "diamond"), item("Emerald", "emerald"), item("Gold ingot", "gold_ingot"), item("Nether star", "nether_star"),
		item("Heart of the sea", "heart_of_the_sea"), item("Totem of undying", "totem_of_undying"), item("Music disc", "music_disc_cat"),
		// Gear.
		item("Diamond sword", "diamond_sword"), item("Diamond pickaxe", "diamond_pickaxe"), item("Bow", "bow"), item("Arrow", "arrow"),
		item("Trident", "trident"), item("Shield", "shield"), item("Elytra", "elytra"), item("Fishing rod", "fishing_rod"),
		item("Shears", "shears"), item("Flint and steel", "flint_and_steel"), item("Spyglass", "spyglass"), item("Saddle", "saddle"),
		item("Name tag", "name_tag"),
		// Finding your way, and getting there.
		item("Ender pearl", "ender_pearl"), item("Eye of ender", "ender_eye"), item("Enchanted book", "enchanted_book"),
		item("Compass", "compass"), item("Clock", "clock"), item("Map", "map"), item("Firework rocket", "firework_rocket"),
		// Food, and what mobs leave.
		item("Golden apple", "golden_apple"), item("Cookie", "cookie"), item("Carrot", "carrot"), item("Egg", "egg"), item("Bone", "bone"),
		// Our own mods' items, while those mods are loaded.
		ourItem("Emerald sword", "emerald-tools-justfatlard:emerald_sword"), ourItem("Quartz sword", "quartz-tools-justfatlard:quartz_sword"),
		ourItem("Pale staff", "pale-staff-justfatlard:pale_staff"), ourItem("Magic egg", "magic-egg-justfatlard:magic_egg"),
		ourItem("Kragle", "kragle:kragle"), ourItem("Spawner shards", "spawner-shards-justfatlard:spawner_shards"),
		ourItem("Poop", "poopsmith:poop"), ourItem("Christening bottle", "big-boats-justfatlard:christening_bottle"));

	/** A gallery's lineup: so many mobs, and blocks and items, drawn from what this server has of each pool, shuffled together. */
	private static List<Suspect> lineup(Random random) {
		List<Suspect> mobs = new java.util.ArrayList<>(MOBS.stream().filter(Suspect::here).toList());
		List<Suspect> things = new java.util.ArrayList<>(BLOCKS.stream().filter(Suspect::here).toList());
		things.addAll(ITEMS.stream().filter(Suspect::here).toList());
		java.util.Collections.shuffle(mobs, random);
		java.util.Collections.shuffle(things, random);
		List<Suspect> lineup = new java.util.ArrayList<>(mobs.subList(0, MOB_COUNT));
		lineup.addAll(things.subList(0, SUSPECT_COUNT - MOB_COUNT));
		java.util.Collections.shuffle(lineup, random);
		return lineup;
	}

	private static final Random DRAW = new Random();

	private static final class Game {
		/** Each team's suspect, the one the other team is after, by place in the gallery. */
		final int[] secret = new int[2];
		/** When each team's pedestal hides its suspect again; 0 while it is hidden. */
		final long[] shownUntil = new long[2];
		/** Each team's gallery: which suspects it has flipped down. */
		final boolean[][] down = new boolean[2][SUSPECT_COUNT];
		/** The mobs standing in each gallery, by place. */
		final Mob[][] standing = new Mob[2][SUSPECT_COUNT];
		/** The items floating in each gallery, by place, and the boxes round them that take the clicks. */
		final Display.ItemDisplay[][] floating = new Display.ItemDisplay[2][SUSPECT_COUNT];
		final Interaction[][] boxes = new Interaction[2][SUSPECT_COUNT];
		/** Who is in each place, the same in both galleries. */
		List<Suspect> lineup = lineup(DRAW);
		final Turns turn;
		/** A suspect somebody has right-clicked once, waiting for the second click that accuses. */
		final Map<UUID, Integer> accusing = new HashMap<>();
		final Map<UUID, Long> accusedAt = new HashMap<>();

		Game(int first) {
			turn = new Turns(TURN_MILLIS, first);
		}
	}

	private static final Map<String, Game> games = new HashMap<>();

	// --- The galleries ---

	static void begin(MinecraftServer server, ServerLevel level, Arena arena) {
		arena.bases.clear();
		for (int team = 0; team < 2; team++) arena.bases.add(gallerySpot(level, arena, team));
		Game game = new Game(DRAW.nextInt(2));
		games.put(arena.id, game);
		setUp(server, level, arena, game);
		Boards.wall(level, arena);
		turnTo(server, arena, game, game.turn.side);
	}

	/** Both galleries built and stood full, and each team told its suspect. */
	private static void setUp(MinecraftServer server, ServerLevel level, Arena arena, Game game) {
		clearSuspects(level, arena);
		for (int team = 0; team < 2; team++) {
			build(level, arena.bases.get(team), team);
			for (int place = 0; place < SUSPECT_COUNT; place++) stand(level, arena.bases.get(team), game, team, place);
			game.secret[team] = DRAW.nextInt(SUSPECT_COUNT);
			pedestal(level, arena.bases.get(team));
		}
		for (Arena.Member member : arena.inside()) tellSecret(server, arena, game, member);
	}

	private static void tellSecret(MinecraftServer server, Arena arena, Game game, Arena.Member member) {
		ServerPlayer player = server.getPlayerList().getPlayer(member.id);
		if (player == null || member.team < 0 || member.team > 1) return;
		Say.to(player, "Press the button on the gold pedestal by your bell to see your team's suspect. " + TeamColors.of(1 - member.team).name()
			+ " is trying to find it: answer their questions truthfully, out loud.");
	}

	/** Each team's end of the ground, on its own spoke from the middle, as far out as leaves room behind. */
	private static BlockPos gallerySpot(ServerLevel level, Arena arena, int team) {
		Footprint footprint = arena.footprint();
		int width = footprint.width();
		int deep = ROWS * DOWN;
		double radius = Math.max(deep, Math.min(width * Preset.GOAL_RING, width / 2.0 - deep / 2.0 - BEHIND));
		double angle = arena.preset.ringAngle(team);
		int x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) Math.floor(footprint.centerZ() + Math.sin(angle) * radius);
		int y = Builds.ground(level, arena, x, z);
		return new BlockPos(x - COLUMNS * ACROSS / 2, y, z - deep / 2);
	}

	private static BlockPos spot(BlockPos corner, int place) {
		return corner.offset(1 + (place % COLUMNS) * ACROSS, 0, 1 + (place / COLUMNS) * DOWN);
	}

	private static BlockPos leverAt(BlockPos corner, int place) {
		return spot(corner, place).offset(0, 0, LEVER);
	}

	private static BlockPos bellAt(BlockPos corner) {
		return corner.offset(COLUMNS * ACROSS / 2, 0, -2);
	}

	/** The gold block a team's own suspect stands on, beside its bell, for the team to look at. */
	private static BlockPos pedestalAt(BlockPos corner) {
		return corner.offset(COLUMNS * ACROSS / 2 + 3, 0, -2);
	}

	/** The button on the pedestal's face that shows what is on it. */
	private static BlockPos buttonAt(BlockPos corner) {
		return pedestalAt(corner).north();
	}

	/** How long a press of the pedestal's button shows the suspect for. */
	private static final long SHOW_MILLIS = 3_000L;

	/**
	 * The team's pedestal, empty, with a button on its face: press it to see the team's own suspect
	 * stood there for a moment, the thing itself, where the other side across the wall cannot.
	 * Hidden the rest of the time, so nobody's suspect is left in sight for anyone passing to read.
	 */
	private static void pedestal(ServerLevel level, BlockPos corner) {
		BlockPos pedestal = pedestalAt(corner);
		level.setBlock(pedestal, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(buttonAt(corner), Blocks.STONE_BUTTON.defaultBlockState()
			.setValue(net.minecraft.world.level.block.ButtonBlock.FACE, AttachFace.WALL)
			.setValue(net.minecraft.world.level.block.ButtonBlock.FACING, net.minecraft.core.Direction.NORTH), Block.UPDATE_ALL);
	}

	/** A team's suspect stood on its pedestal, for a few seconds. */
	public static void show(ServerLevel level, Arena arena, int team) {
		Game game = games.get(arena.id);
		if (game == null) return;
		BlockPos corner = arena.bases.get(team);
		if (game.shownUntil[team] == 0) card(level, corner, game, team);
		game.shownUntil[team] = System.currentTimeMillis() + SHOW_MILLIS;
	}

	private static void hide(ServerLevel level, BlockPos corner, Game game, int team) {
		game.shownUntil[team] = 0;
		BlockPos top = pedestalAt(corner).above();
		level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (Entity shown : level.getEntitiesOfClass(Entity.class, new AABB(top).inflate(1), entity -> entity.entityTags().contains(CARD))) shown.discard();
	}

	public static boolean shown(Arena arena, int team) {
		Game game = games.get(arena.id);
		return game != null && game.shownUntil[team] > 0;
	}

	public static BlockPos buttonOf(Arena arena, int team) {
		return buttonAt(arena.bases.get(team));
	}

	/** The suspect itself on the pedestal. */
	private static void card(ServerLevel level, BlockPos corner, Game game, int team) {
		Suspect mine = game.lineup.get(game.secret[team]);
		BlockPos pedestal = pedestalAt(corner);
		level.setBlock(pedestal.above(), mine.block() == null ? Blocks.AIR.defaultBlockState() : look(mine), Block.UPDATE_ALL);
		if (mine.item() != null) {
			Display.ItemDisplay shown = floatItem(level, pedestal.above(), mine);
			if (shown == null) return;
			shown.addTag(CARD);
			level.addFreshEntity(shown);
			return;
		}
		Mob mob = make(level, mine);
		if (mob == null) return;
		mob.addTag(CARD);
		mob.snapTo(pedestal.getX() + 0.5, pedestal.getY() + 1, pedestal.getZ() + 0.5, 0, 0);
		level.addFreshEntity(mob);
	}

	/** A suspect's mob, made, dressed and frozen but not yet in the world; null for a block, or a mob that won't make. */
	private static @Nullable Mob make(ServerLevel level, Suspect suspect) {
		if (suspect.type() == null) return null;
		Mob mob = suspect.type().create(level, EntitySpawnReason.EVENT);
		if (mob == null) return null;
		suspect.dress().accept(mob);
		freeze(mob);
		return mob;
	}

	/**
	 * A mob made a still, silent, unhurt picture of itself. One that would burn under the sky wears
	 * a leather cap, unbreakable since the sun wears a worn cap down and it would be gone in seconds;
	 * a piglin or hoglin is kept from turning zombie away from the Nether.
	 */
	private static void freeze(Mob mob) {
		mob.setNoAi(true);
		mob.setNoGravity(true);
		mob.setSilent(true);
		mob.setPermanentlyInvulnerable(true);
		mob.setPersistenceRequired();
		mob.addTag(Mobs.OURS);
		if (mob instanceof AgeableMob young) young.setAge(mob.entityTags().contains(YOUNG) ? YOUNG_AGE : 0);
		if (mob.is(EntityTypeTags.BURN_IN_DAYLIGHT)) {
			ItemStack cap = new ItemStack(Items.LEATHER_HELMET);
			cap.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
			mob.setItemSlot(EquipmentSlot.HEAD, cap);
			mob.setDropChance(EquipmentSlot.HEAD, 0F);
		}
		if (mob instanceof AbstractPiglin piglin) piglin.setImmuneToZombification(true);
		if (mob instanceof Hoglin hoglin) hoglin.setImmuneToZombification(true);
	}

	/** The gallery floor in the team's colour, a pen of wool under each suspect, a lever before each, and the bell. */
	private static void build(ServerLevel level, BlockPos corner, int team) {
		int wide = COLUMNS * ACROSS;
		int deep = ROWS * DOWN;
		Builds.yard(level, corner.getX() + wide / 2, corner.getY(), corner.getZ() + deep / 2, Math.max(wide, deep) / 2 + 3, 4);
		BlockState floor = Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
		for (int x = -1; x <= wide; x++) {
			for (int z = -3; z <= deep; z++) level.setBlock(corner.offset(x, -1, z), floor, Block.UPDATE_ALL);
		}
		for (int place = 0; place < SUSPECT_COUNT; place++) {
			pen(level, corner, place, false);
			level.setBlock(leverAt(corner, place), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), Block.UPDATE_ALL);
		}
		level.setBlock(bellAt(corner), Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** The square under a suspect: white while they stand, black once flipped down. */
	private static void pen(ServerLevel level, BlockPos corner, int place, boolean down) {
		BlockState wool = Terrain.block(down ? "minecraft:black_wool" : "minecraft:white_wool", Blocks.STONE.defaultBlockState());
		level.setBlock(spot(corner, place).below(), wool, Block.UPDATE_ALL);
	}

	/**
	 * A suspect stood in their place: a block set on the pen, an item floating over it, or a mob
	 * there, still, silent and unhurt. No names: a suspect is what it looks like, which is what the
	 * questions are about.
	 *
	 * <p>An item is a display, which nothing can click, so it floats inside an interaction box the
	 * size of the spot: the box is what a right-click lands on, and what {@link #useEntity} accuses.
	 */
	private static void stand(ServerLevel level, BlockPos corner, Game game, int team, int place) {
		Suspect suspect = game.lineup.get(place);
		BlockPos at = spot(corner, place);
		level.setBlock(at, suspect.block() == null ? Blocks.AIR.defaultBlockState() : look(suspect), Block.UPDATE_ALL);
		game.standing[team][place] = null;
		game.floating[team][place] = null;
		game.boxes[team][place] = null;
		if (suspect.item() != null) {
			Display.ItemDisplay shown = floatItem(level, at, suspect);
			Interaction box = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
			if (shown == null || box == null) return;
			shown.addTag(TAG);
			level.addFreshEntity(shown);
			box.setWidth(0.9F);
			box.setHeight(1.2F);
			box.setResponse(true);
			box.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			box.addTag(TAG);
			level.addFreshEntity(box);
			game.floating[team][place] = shown;
			game.boxes[team][place] = box;
			return;
		}
		Mob mob = make(level, suspect);
		if (mob == null) return;
		mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		mob.setYHeadRot(0);
		mob.addTag(TAG);
		level.addFreshEntity(mob);
		game.standing[team][place] = mob;
	}

	private static BlockState look(Suspect suspect) {
		return Terrain.block(suspect.block(), Blocks.STONE.defaultBlockState());
	}

	/** What an item suspect is, as a stack to show; empty if this server has no such item. */
	private static ItemStack stack(Suspect suspect) {
		Identifier id = suspect.item() == null ? null : Identifier.tryParse(suspect.item());
		if (id == null) return ItemStack.EMPTY;
		ItemStack stack = BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
		// A book with nothing in it doesn't shine; this one is to look enchanted.
		if (stack.is(Items.ENCHANTED_BOOK)) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	/** An item display's size against an item frame's, which draws an item half a block across. */
	private static final float ITEM_SCALE = 1.4F;

	/**
	 * An item suspect floating over the block at {@code at}, turned always to whoever looks at it,
	 * about two-thirds of a block across: a vanilla display, so every client draws it. Not yet in
	 * the world.
	 */
	private static Display.@Nullable ItemDisplay floatItem(ServerLevel level, BlockPos at, Suspect suspect) {
		Display.ItemDisplay shown = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (shown == null) return null;
		shown.setItemStack(stack(suspect));
		shown.setItemTransform(ItemDisplayContext.FIXED);
		shown.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
		shown.setTransformation(new Transformation(null, null, new Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE), null));
		shown.snapTo(at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 0, 0);
		return shown;
	}

	private static void clearSuspects(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		// Mobs, item displays and their boxes alike, and any a restart left standing with no game to belong to.
		for (Entity left : level.getEntitiesOfClass(Entity.class, area, entity -> entity.entityTags().contains(TAG) || entity.entityTags().contains(CARD))) left.discard();
	}

	/** Whether this block is part of a gallery, its levers or its bell, which nobody breaks. */
	public static boolean part(Arena arena, BlockPos pos) {
		if (arena.preset.activeGoal() != Preset.Goal.GUESS_WHO) return false;
		if (Boards.inWall(arena, pos)) return true;
		for (BlockPos corner : arena.bases) {
			int x = pos.getX() - corner.getX();
			int z = pos.getZ() - corner.getZ();
			int up = pos.getY() - corner.getY();
			if (x >= -1 && x <= COLUMNS * ACROSS && z >= -3 && z <= ROWS * DOWN && (up == -1 || up == 0)) return true;
			if (pos.equals(pedestalAt(corner).above()) || pos.equals(buttonAt(corner))) return true;
		}
		return false;
	}

	// --- Playing ---

	/**
	 * A player right-clicked a block: a lever in their own gallery flips its suspect down or back
	 * up; their bell, on their turn, ends it. Anything else here is somebody else's.
	 */
	public static InteractionResult useBlock(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
		Arena arena = Arenas.of(player);
		Game game = arena == null ? null : games.get(arena.id);
		if (game == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0
				|| arena.preset.activeGoal() != Preset.Goal.GUESS_WHO || arena.bases.size() < 2) return InteractionResult.PASS;
		for (int team = 0; team < 2; team++) {
			BlockPos corner = arena.bases.get(team);
			int place = -1;
			for (int i = 0; i < SUSPECT_COUNT; i++) if (leverAt(corner, i).equals(pos)) place = i;
			boolean bell = bellAt(corner).equals(pos);
			if (buttonAt(corner).equals(pos)) {
				Arena.Member presser = arena.member(player.getUUID());
				if (presser == null || presser.team != team) return InteractionResult.FAIL;
				show(level, arena, team);
				// Let it press.
				return InteractionResult.PASS;
			}
			int suspect = -1;
			for (int i = 0; i < SUSPECT_COUNT; i++) if (game.lineup.get(i).block() != null && spot(corner, i).equals(pos)) suspect = i;
			if (place < 0 && !bell && suspect < 0) continue;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
			Arena.Member member = arena.member(player.getUUID());
			if (member == null || member.watching || member.out) return InteractionResult.FAIL;
			if (member.team != team) {
				Say.bar(player, "That's " + TeamColors.of(team).name() + "'s gallery; yours is at your own end");
				return InteractionResult.FAIL;
			}
			if (suspect >= 0) return accuseClick(player, arena, game, member, suspect);
			if (bell) {
				if (game.turn.side != team) {
					Say.bar(player, "It's " + TeamColors.of(game.turn.side).name() + "'s turn");
					return InteractionResult.FAIL;
				}
				int last = lastStanding(game, team);
				if (last >= 0) {
					accuse(player.level().getServer(), arena, team, last, member.name);
					return InteractionResult.PASS;
				}
				Arenas.tellInside(player.level().getServer(), arena, member.name + " rings the bell: " + TeamColors.of(team).name() + "'s turn is over");
				turnTo(player.level().getServer(), arena, game, 1 - team);
				// Let it ring.
				return InteractionResult.PASS;
			}
			flip(level, arena, game, team, place, !game.down[team][place]);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** The one suspect a team has left standing, which ringing the bell accuses; -1 while more than one stands. */
	private static int lastStanding(Game game, int team) {
		int last = -1;
		for (int i = 0; i < SUSPECT_COUNT; i++) {
			if (game.down[team][i]) continue;
			if (last >= 0) return -1;
			last = i;
		}
		return last;
	}

	/** A suspect flipped down, hidden in their pen, or stood back up. */
	public static void flip(ServerLevel level, Arena arena, int team, int place, boolean down) {
		Game game = games.get(arena.id);
		if (game != null) flip(level, arena, game, team, place, down);
	}

	private static void flip(ServerLevel level, Arena arena, Game game, int team, int place, boolean down) {
		game.down[team][place] = down;
		BlockPos corner = arena.bases.get(team);
		pen(level, corner, place, down);
		BlockPos lever = leverAt(corner, place);
		BlockState state = level.getBlockState(lever);
		if (state.is(Blocks.LEVER)) level.setBlock(lever, state.setValue(LeverBlock.POWERED, down), Block.UPDATE_ALL);
		Mob mob = game.standing[team][place];
		if (mob != null) mob.setInvisible(down);
		Suspect suspect = game.lineup.get(place);
		// An item flipped down is emptied out of its display; its box stays, to say it's down if clicked.
		Display.ItemDisplay shown = game.floating[team][place];
		if (shown != null) shown.setItemStack(down ? ItemStack.EMPTY : stack(suspect));
		if (suspect.block() != null) level.setBlock(spot(corner, place), down ? Blocks.AIR.defaultBlockState() : look(suspect), Block.UPDATE_ALL);
	}

	/**
	 * A player right-clicked a suspect, a mob or the box round an item. Never a trade, a shearing or
	 * a lead: a suspect is only ever accused, by a second click within a few seconds of the first, on
	 * their own team's turn and in their own gallery.
	 */
	public static InteractionResult useEntity(ServerPlayer player, Entity entity, InteractionHand hand) {
		if (entity.entityTags().contains(CARD)) return InteractionResult.FAIL;
		if (!entity.entityTags().contains(TAG)) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
		Arena arena = Arenas.of(player);
		Game game = arena == null ? null : games.get(arena.id);
		Arena.Member member = arena == null ? null : arena.member(player.getUUID());
		if (game == null || member == null || member.watching || member.out || arena.closesAt > 0
				|| member.team < 0 || member.team > 1) return InteractionResult.FAIL;
		int place = -1;
		for (int i = 0; i < SUSPECT_COUNT; i++) if (game.standing[member.team][i] == entity || game.boxes[member.team][i] == entity) place = i;
		if (place < 0) {
			Say.bar(player, "Accuse from your own gallery");
			return InteractionResult.FAIL;
		}
		return accuseClick(player, arena, game, member, place);
	}

	/** A click on a suspect in a team's own gallery: the first asks, a second soon after accuses. */
	private static InteractionResult accuseClick(ServerPlayer player, Arena arena, Game game, Arena.Member member, int place) {
		if (game.down[member.team][place]) {
			Say.bar(player, "That one is flipped down");
			return InteractionResult.FAIL;
		}
		if (game.turn.side != member.team) {
			Say.bar(player, "Accuse on your own turn");
			return InteractionResult.FAIL;
		}
		long now = System.currentTimeMillis();
		Integer first = game.accusing.get(player.getUUID());
		if (first == null || first != place || now - game.accusedAt.getOrDefault(player.getUUID(), 0L) > ACCUSE_MILLIS) {
			game.accusing.put(player.getUUID(), place);
			game.accusedAt.put(player.getUUID(), now);
			Say.bar(player, "Right-click the " + game.lineup.get(place).name() + " again to accuse it. Wrong, and your team loses");
			return InteractionResult.SUCCESS;
		}
		game.accusing.remove(player.getUUID());
		accuse(player.level().getServer(), arena, member.team, place, member.name);
		return InteractionResult.SUCCESS;
	}

	/** A team names the other team's suspect: right wins it, wrong loses it. */
	public static void accuse(MinecraftServer server, Arena arena, int team, int place, String by) {
		Game game = games.get(arena.id);
		if (game == null || arena.closesAt > 0) return;
		int enemy = 1 - team;
		Suspect named = game.lineup.get(place);
		Suspect actual = game.lineup.get(game.secret[enemy]);
		if (place == game.secret[enemy]) {
			Goals.winTeam(server, arena, team, by + " named the " + named.name());
		} else {
			Goals.winTeam(server, arena, enemy, by + " accused the " + named.name() + ", but it was the " + actual.name());
		}
	}

	/** Which suspect this team is hiding, by place in the gallery: for the tests to aim at. */
	public static int secretOf(Arena arena, int team) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.secret[team];
	}

	public static boolean isDown(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		return game != null && game.down[team][place];
	}

	public static BlockPos leverOf(Arena arena, int team, int place) {
		return leverAt(arena.bases.get(team), place);
	}

	public static BlockPos bellOf(Arena arena, int team) {
		return bellAt(arena.bases.get(team));
	}

	/** What stands in a place, for the tests: its name, and whether it is a block. */
	public static String nameOf(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game == null ? "" : game.lineup.get(place).name();
	}

	public static boolean isBlock(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game != null && game.lineup.get(place).block() != null;
	}

	public static boolean isItem(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game != null && game.lineup.get(place).item() != null;
	}

	/** The item id a suspect is, or null for a mob or a block: for the tests. */
	public static @Nullable String itemOf(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.lineup.get(place).item();
	}

	/** The display an item suspect floats in, in a team's gallery: for the tests. */
	public static Display.@Nullable ItemDisplay floatingAt(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.floating[team][place];
	}

	/** What a right-click on a suspect lands on: its mob, or the box round its item; null for a block. */
	public static @Nullable Entity clickableAt(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		if (game == null) return null;
		return game.standing[team][place] != null ? game.standing[team][place] : game.boxes[team][place];
	}

	public static BlockPos pedestalOf(Arena arena, int team) {
		return pedestalAt(arena.bases.get(team));
	}

	/** The block id a suspect is, or null for a mob: for the tests. */
	public static @Nullable String blockOf(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.lineup.get(place).block();
	}

	public static @Nullable EntityType<? extends Mob> typeOf(Arena arena, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.lineup.get(place).type();
	}

	public static BlockPos spotOf(Arena arena, int team, int place) {
		return spot(arena.bases.get(team), place);
	}

	public static @Nullable Mob suspectAt(Arena arena, int team, int place) {
		Game game = games.get(arena.id);
		return game == null ? null : game.standing[team][place];
	}

	public static int side(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? -1 : game.turn.side;
	}

	// --- Turns ---

	/** The turn to this side, or past it to the other where it has nobody to play it. */
	private static void turnTo(MinecraftServer server, Arena arena, Game game, int side) {
		if (game.turn.to(arena, side)) announce(server, arena, game);
	}

	private static void announce(MinecraftServer server, Arena arena, Game game) {
		int to = game.turn.side;
		Arenas.tellInside(server, arena, TeamColors.of(to).name() + " asks: one yes-or-no question, out loud. Flip down who it rules out, then ring your bell");
		Component title = Component.literal("Your turn").withStyle(style -> style.withColor(TeamColors.of(to).team().textColor()));
		Component subtitle = Component.literal("Ask a question, flip, ring the bell").withStyle(ChatFormatting.YELLOW);
		for (Arena.Member member : arena.inside()) {
			if (member.team != to) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
		}
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Game game = games.get(arena.id);
		if (game == null) {
			// Over a restart: nobody wrote down who anybody's suspect was. A new game, in the same galleries.
			if (arena.bases.size() < 2) return;
			game = new Game(0);
			games.put(arena.id, game);
			setUp(server, level, arena, game);
			Arenas.tellInside(server, arena, "The case went cold; new suspects are in, and nobody is flipped down");
			turnTo(server, arena, game, 0);
			return;
		}
		for (int team = 0; team < 2; team++) {
			if (game.shownUntil[team] > 0 && now >= game.shownUntil[team]) hide(level, arena.bases.get(team), game, team);
			for (int place = 0; place < SUSPECT_COUNT; place++) {
				Mob mob = game.standing[team][place];
				// The young grow up and the grown have young; neither, however long the game runs.
				if (!(mob instanceof AgeableMob grown)) continue;
				if (mob.entityTags().contains(YOUNG)) {
					if (!grown.isBaby()) grown.setAge(YOUNG_AGE);
				} else if (grown.getAge() != 0) {
					grown.setAge(0);
				}
			}
		}
		int was = game.turn.side;
		Turns.Passed passed = game.turn.check(arena, now);
		if (passed == Turns.Passed.TOO_LONG) Arenas.tellInside(server, arena, TeamColors.of(was).name() + " took too long; the turn passes");
		if (passed != Turns.Passed.NO) announce(server, arena, game);
	}

	// --- Words ---

	/** The bar's line: the viewer's team's suspect, and whose turn it is. */
	static @Nullable String status(Arena arena, Arena.@Nullable Member member) {
		Game game = games.get(arena.id);
		if (game == null) return null;
		String turn = TeamColors.of(game.turn.side).name() + " asks";
		return turn;
	}

	static String whoseTurn(Arena arena) {
		Game game = games.get(arena.id);
		return game == null ? "The suspects are coming in" : TeamColors.of(game.turn.side).name() + " asks";
	}

	/** The suspects this team still has standing in its gallery. */
	static int standing(Arena arena, int team) {
		Game game = games.get(arena.id);
		if (game == null || team < 0 || team > 1) return SUSPECT_COUNT;
		int up = 0;
		for (boolean down : game.down[team]) if (!down) up++;
		return up;
	}

	public static void forget(Arena arena) {
		Game game = games.remove(arena.id);
		if (game == null) return;
		for (Mob[] gallery : game.standing) for (Mob mob : gallery) if (mob != null) mob.discard();
		for (Display.ItemDisplay[] gallery : game.floating) for (Display.ItemDisplay shown : gallery) if (shown != null) shown.discard();
		for (Interaction[] gallery : game.boxes) for (Interaction box : gallery) if (box != null) box.discard();
	}
}
