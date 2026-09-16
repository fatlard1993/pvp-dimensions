package justfatlard.pvp_dimensions.preset;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The kinds of match a new preset can start from: each fills in the settings that make that game
 * work together, so a new preset begins as something playable rather than a list of defaults to
 * reconcile. Everything stays editable after.
 *
 * <p>They come in families, and no two are the same game with one setting flipped: each is a
 * different shape of match, and between them they use everything the mod can do.
 */
public enum Kinds {
	// --- Everyone for themselves ---
	DEATHMATCH(Family.FREE_FOR_ALL, "Deathmatch", "minecraft:iron_sword", "First to ten kills", p -> {
		p.goal = Preset.Goal.KILLS;
		p.killTarget = 10;
		p.lifeMinutes = 15;
		p.size = 5;
		p.killBonus.put(Preset.KillType.DOUBLE_KILL, items("minecraft:golden_apple", 1));
		p.killBonus.put(Preset.KillType.LONG_SHOT, items("minecraft:arrow", 8));
	}),
	LAST_STAND(Family.FREE_FOR_ALL, "Last stand", "minecraft:totem_of_undying", "One life each, last standing", p -> {
		p.goal = Preset.Goal.TIME;
		p.lives = Preset.Lives.PLAYER;
		p.livesCount = 1;
		p.endWhenOneSideLeft = true;
		p.borderShrink = 50;
		p.lifeMinutes = 10;
		p.size = 5;
		p.featRewards.put(Preset.Feat.SURVIVOR, items("minecraft:golden_apple", 1));
	}),
	KING_OF_THE_HILL(Family.FREE_FOR_ALL, "King of the hill", "minecraft:gold_block", "Hold the hill; it moves about", p -> {
		p.goal = Preset.Goal.HILL;
		p.hillTarget = 3;
		p.hillMoves = 3;
		p.goalCompass = true;
		p.lifeMinutes = 15;
		p.size = 5;
		p.featRewards.put(Preset.Feat.HILL_MINUTE, items("minecraft:golden_carrot", 2));
	}),
	RACE(Family.FREE_FOR_ALL, "Race", "minecraft:emerald_block", "First up the middle tower", p -> {
		p.goal = Preset.Goal.RACE;
		p.finishStyle = Preset.FinishStyle.TOWER;
		p.goalCompass = true;
		p.lifeMinutes = 10;
		p.size = 5;
	}),

	// --- Side against side ---
	TEAM_BATTLE(Family.TEAMS, "Team battle", "minecraft:shield", "Two teams, kits to pick, a wall", p -> {
		teams(p);
		p.goal = Preset.Goal.KILLS;
		p.killTarget = 20;
		p.divisions = 2;
		p.wallsFall = 1;
		p.wallsHold = true;
		loadouts(p);
	}),
	CAPTURE_THE_FLAG(Family.TEAMS, "Capture the flag", "minecraft:white_banner", "Two teams, three captures win", p -> {
		teams(p);
		p.goal = Preset.Goal.CTF;
		p.captures = 3;
		p.divisions = 2;
		p.wallsFall = 2;
		p.size = 6;
		p.lifeMinutes = 20;
		p.featRewards.put(Preset.Feat.CAPTURE, items("minecraft:golden_apple", 2));
		p.killBonus.put(Preset.KillType.CARRIER, items("minecraft:golden_apple", 1));
	}),
	BASE_RAID(Family.TEAMS, "Base raid", "minecraft:tnt", "Break their base, keep yours", p -> {
		teams(p);
		p.goal = Preset.Goal.DESTRUCTION;
		p.divisions = 2;
		p.wallsFall = 3;
		p.lifeMinutes = 20;
	}),
	COLOUR_WAR(Family.TEAMS, "Colour war", "minecraft:red_terracotta", "Cover the ground in your colour", p -> {
		teams(p);
		p.goal = Preset.Goal.TAKEOVER;
		p.lifeMinutes = 10;
		p.size = 5;
		p.shape = Preset.Shape.FLAT;
	}),
	BANKING(Family.TEAMS, "Banking", "minecraft:emerald", "Dig, gather, steal; richest wins", p -> {
		teams(p);
		p.goal = Preset.Goal.BANK;
		p.teamBases = Preset.BaseStyle.FORT;
		p.goalCompass = true;
		p.lifeMinutes = 20;
		p.size = 6;
	}),
	FORTIFY(Family.TEAMS, "Fortify then fight", "minecraft:scaffolding", "Build five minutes, then fight", p -> {
		teams(p);
		p.goal = Preset.Goal.KILLS;
		p.killTarget = 15;
		p.gameMode = Preset.GameRule.CREATIVE;
		p.modeChanges.add(change(5, Preset.GameRule.SURVIVAL));
		p.divisions = 2;
		p.wallsFall = 5;
		p.wallsHold = true;
		p.lifeMinutes = 25;
		p.size = 6;
	}),

	// --- Together against the mobs ---
	HUNT(Family.MOBS, "Hunt", "minecraft:bow", "Together against thirty mobs", p -> {
		p.pvp = false;
		p.goal = Preset.Goal.MOBS;
		p.mobGoalCount = 30;
		p.mobs = Preset.MobTime.ALWAYS;
		p.mobRarity = Preset.Rarity.COMMON;
		for (String kind : List.of("minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper")) {
			p.setMobLevel(kind, Preset.MobLevel.NORMAL);
		}
		p.lives = Preset.Lives.PLAYER;
		p.lifeMinutes = 20;
	}),
	BIG_GAME(Family.MOBS, "Big game", "minecraft:zombie_head", "Three giants to bring down", p -> {
		p.pvp = false;
		p.goal = Preset.Goal.MOBS;
		p.mobGoalKind = "minecraft:giant";
		p.mobGoalCount = 3;
		p.mobs = Preset.MobTime.ALWAYS;
		p.mobRarity = Preset.Rarity.RARE;
		p.setMobLevel("minecraft:giant", Preset.MobLevel.NORMAL);
		p.setMobLevel("minecraft:ravager", Preset.MobLevel.NORMAL);
		p.lives = Preset.Lives.PLAYER;
		p.livesCount = 5;
		p.lifeMinutes = 20;
		p.size = 6;
		p.featRewards.put(Preset.Feat.BIG_GAME, items("minecraft:diamond", 2));
	}),
	HOLD_THE_LINE(Family.MOBS, "Hold the line", "minecraft:crossbow", "Survive three waves of mobs", p -> {
		p.pvp = false;
		p.mobStyle = Preset.MobStyle.WAVES;
		p.goal = Preset.Goal.WAVES;
		p.lives = Preset.Lives.PLAYER;
		p.lifeMinutes = 30;
		p.featRewards.put(Preset.Feat.WAVE_CLEARED, items("minecraft:cooked_beef", 4));
		p.featRewards.put(Preset.Feat.FLAWLESS_WAVE, items("minecraft:golden_apple", 1));
	}),
	INFECTION(Family.MOBS, "Infection", "minecraft:rotten_flesh", "Waves; whoever falls rises", p -> {
		p.pvp = false;
		p.mobStyle = Preset.MobStyle.WAVES;
		p.goal = Preset.Goal.WAVES;
		p.lives = Preset.Lives.PLAYER;
		p.livesCount = 1;
		p.horde = true;
		p.teamBases = Preset.BaseStyle.FORT;
		p.lifeMinutes = 30;
	}),

	// --- The rest ---
	PINATA_PARTY(Family.OTHER, "Pinata party", "minecraft:firework_rocket", "Pinatas, three at a time", p -> {
		p.goal = Preset.Goal.TIME;
		p.pinata = Preset.PinataMode.EVERY;
		p.pinataMinutes = 2;
		p.pinataCount = 3;
		p.pinataRegardless = true;
		p.lifeMinutes = 10;
		p.size = 4;
	}, () -> Fields.PINATA_INSTALLED),
	BUILD(Family.OTHER, "Build", "minecraft:bricks", "Creative, no harm, no clock", p -> {
		p.pvp = false;
		p.gameMode = Preset.GameRule.CREATIVE;
		p.lifeMinutes = 0;
		p.waitingRoom = false;
		p.shape = Preset.Shape.FLAT;
		p.flatFeatures = false;
		p.size = 8;
		p.forUsers = false;
	}),
	BLANK(Family.OTHER, "Blank", "minecraft:writable_book", "Nothing chosen yet", p -> {});

	/** The families the kinds are shown in, in order. */
	public enum Family {
		FREE_FOR_ALL("Everyone for themselves"),
		TEAMS("Side against side"),
		MOBS("Together against the mobs"),
		OTHER("The rest");

		public final String label;

		Family(String label) {
			this.label = label;
		}
	}

	public final Family family;
	public final String label;
	public final String icon;
	public final String about;
	private final Consumer<Preset> fill;
	private final java.util.function.BooleanSupplier shown;

	Kinds(Family family, String label, String icon, String about, Consumer<Preset> fill) {
		this(family, label, icon, about, fill, () -> true);
	}

	Kinds(Family family, String label, String icon, String about, Consumer<Preset> fill, java.util.function.BooleanSupplier shown) {
		this.family = family;
		this.label = label;
		this.icon = icon;
		this.about = about;
		this.fill = fill;
		this.shown = shown;
	}

	/** Whether this kind can be played on this server: some want a mod that may not be here. */
	public boolean here() {
		return shown.getAsBoolean();
	}

	/** A new preset of this kind, named for it. */
	public Preset make() {
		Preset preset = new Preset();
		preset.name = label;
		preset.icon = icon;
		fill.accept(preset);
		return preset;
	}

	private static void teams(Preset p) {
		p.mode = Preset.Mode.TEAMS;
		p.teams = 2;
		p.spawn = Preset.Spawn.TEAM;
		p.nameTags = Preset.NameTags.TEAMMATES;
	}

	/** Three kits to pick between, picked again on every death. */
	private static void loadouts(Preset p) {
		p.firstLoadout = "Soldier";
		p.entryKit = Kit.dressed(List.of(stack("minecraft:iron_sword", 1), stack("minecraft:shield", 1),
			stack("minecraft:iron_helmet", 1), stack("minecraft:iron_chestplate", 1), stack("minecraft:cooked_beef", 8)));
		p.loadouts.add(loadout("Archer", Kit.dressed(List.of(stack("minecraft:bow", 1), stack("minecraft:arrow", 32),
			stack("minecraft:stone_sword", 1), stack("minecraft:leather_helmet", 1), stack("minecraft:leather_chestplate", 1),
			stack("minecraft:cooked_beef", 8)))));
		p.loadouts.add(loadout("Builder", Kit.dressed(List.of(stack("minecraft:stone_axe", 1), stack("minecraft:cobblestone", 64),
			stack("minecraft:oak_planks", 32), stack("minecraft:iron_helmet", 1), stack("minecraft:chainmail_chestplate", 1),
			stack("minecraft:cooked_beef", 8)))));
		p.loadoutRepick = true;
	}

	private static Preset.Loadout loadout(String name, Kit kit) {
		Preset.Loadout made = new Preset.Loadout();
		made.name = name;
		made.kit = kit;
		return made;
	}

	private static Preset.ModeChange change(int minute, Preset.GameRule mode) {
		Preset.ModeChange made = new Preset.ModeChange();
		made.minute = minute;
		made.mode = mode;
		return made;
	}

	private static ItemStack stack(String id, int count) {
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
			.map(item -> new ItemStack(item, count)).orElse(ItemStack.EMPTY);
	}

	private static ItemList items(String id, int count) {
		ItemList list = new ItemList();
		ItemStack stack = stack(id, count);
		if (!stack.isEmpty()) list.add(stack);
		return list;
	}
}
