package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything an arena is made from. Admins save these; users start from them; an arena keeps its
 * own copy from the moment it starts, so editing a preset never changes a game already running.
 *
 * <p>Plain fields, read and written through {@link Fields}, which is the one place that knows a
 * setting's name, limits and label: the menu, the commands and the saved file all go through it.
 */
public final class Preset {
	public enum Traversal { FREE, ISOLATED, REWARDS }
	public enum RespawnKit { NONE, ENTRY, CUSTOM }
	public enum Mode { FFA, TEAMS }
	public enum LateJoin { ALLOW, SPECTATE, DENY }
	public enum GameRule { SURVIVAL, ADVENTURE, CREATIVE }
	public enum Spawn { RANDOM, TEAM, CENTER }
	public enum NameTags { SHOWN, TEAMMATES, HIDDEN }
	public enum Source { GENERATE, SAVED }
	public enum World { OVERWORLD, NETHER, END }
	public enum Shape { NATURAL, FLAT }
	public enum Material { NATURAL, SINGLE, SWAP, LAYERS }
	/** Divisions as slices of a pie, all meeting in the middle, or as a grid of cells. */
	public enum DivisionShape { PIE, GRID }
	public enum Bedrock { OFF, BOTTOM, BOTTOM_WALLS, SHELL }
	public enum PinataMode { OFF, ONCE, EVERY, AFTER_BREAK }
	public enum PinataPlace { CENTER, RANDOM, ROAMING }
	public enum MobTime { OFF, NIGHT, ALWAYS }
	public enum Rarity { RARE, NORMAL, COMMON, SWARM }
	/** How often one kind of mob turns up among the rest; any kind not given one is normal. */
	public enum MobLevel { OFF, RARE, NORMAL, COMMON }
	public enum Goal { TIME, KILLS, MOBS, CTF, TAKEOVER, DESTRUCTION, WAVES, HILL, BANK, RACE, SPY }
	public enum MobStyle { STEADY, WAVES }
	public enum AfterWaves { REPEAT_LAST, START_OVER, STOP }
	public enum Scope { PLAYER, TEAM, EVERYONE }
	public enum Lives { OFF, PLAYER, TEAM_POOL }
	/** What each team is given to start from: nothing, or a building in one of a few styles. */
	public enum BaseStyle { NONE, CAMP, FORT, TOWER, BUNKER }
	/** Who may open a team's chest besides its own team: anyone, nobody, or whoever picks its lock. */
	public enum ChestAccess { OPEN, SHUT, PICKABLE }
	/** Where the hill, or a race's finish, is put: the middle, or anywhere. */
	public enum MarkerPlace { CENTER, RANDOM }
	/** An arena's own weather, or the world outside's. */
	public enum Weather { OUTSIDE, CLEAR, RAIN, THUNDER }
	/** How a race's finish is got to: walked up to, climbed, built up to, or dug down to. */
	public enum FinishStyle { GROUND, TOWER, SKY, BURIED }

	/** A kill that earns something on top of the plain kill reward. */
	public enum KillType {
		FIRST_BLOOD, MELEE, RANGED, KNOCKOUT, REVENGE, SHUTDOWN, STREAK_3, STREAK_5, STREAK_10,
		DOUBLE_KILL, TRIPLE_KILL, LONG_SHOT, TRAP, AVENGER, CARRIER
	}

	/** Something done besides a kill, announced, and paid for where the preset says. */
	public enum Feat {
		ASSIST, CAPTURE, HILL_TAKEN, HILL_MINUTE, WAVE_CLEARED, FLAWLESS_WAVE, BIG_GAME, SURVIVOR
	}

	public record Layer(String block, int percent) {}

	/** One kind in a wave, and how many come for each player fighting, in tenths of a mob. */
	public record WaveMob(String kind, int tenths) {}

	/** One wave: the kinds in it and how many of each. */
	public static final class Wave {
		public List<WaveMob> mobs = new ArrayList<>();

		static Wave of(WaveMob... mobs) {
			Wave wave = new Wave();
			wave.mobs.addAll(List.of(mobs));
			return wave;
		}
	}

	/**
	 * A kit a player can pick instead of the entry kit, with what it respawns with: nothing, the
	 * same kit, or a respawn pack of its own.
	 */
	public static final class Loadout {
		public String name = "Loadout";
		public Kit kit = new Kit();
		public RespawnKit respawn = RespawnKit.ENTRY;
		public Kit respawnCustom = new Kit();
		/** How many may have it at once, each team its own where there are teams; 0 for as many as like. */
		public int cap = 0;

		public Loadout copy() {
			Loadout copy = new Loadout();
			copy.name = name;
			copy.cap = cap;
			copy.kit = kit.copy();
			copy.respawn = respawn;
			copy.respawnCustom = respawnCustom.copy();
			return copy;
		}
	}

	/** The game mode switching partway through: to what, when, with a minute's warning, and fresh kits. */
	public static final class ModeChange {
		public int minute = 5;
		public GameRule mode = GameRule.SURVIVAL;
		public boolean warn = true;
		/** Whether everyone's pack is emptied and their kit handed out again as it switches. */
		public boolean kits = true;

		public ModeChange copy() {
			ModeChange copy = new ModeChange();
			copy.minute = minute;
			copy.mode = mode;
			copy.warn = warn;
			copy.kits = kits;
			return copy;
		}
	}

	public static final class PinataLoot {
		public int hits = 10;
		public ItemList items = new ItemList();

		public PinataLoot copy() {
			PinataLoot copy = new PinataLoot();
			copy.hits = hits;
			copy.items = items.copy();
			return copy;
		}
	}

	// --- General ---
	public String name = "New preset";
	public String icon = "minecraft:iron_sword";
	public boolean forUsers = true;
	/** Settings, by key, a user may change for their own game when they start this preset. */
	public java.util.Set<String> adjustable = new java.util.TreeSet<>();

	// --- Items ---
	public Traversal traversal = Traversal.ISOLATED;
	public ItemList rewards = new ItemList();
	public Kit entryKit = Kit.starter();
	public boolean deathCapture = true;
	/** Whether Dead Heads hands out a compass pointing at a death in this arena. */
	public boolean deathCompasses = true;
	/** Minutes a Dead Heads head stays its owner's in this arena; 0 for anyone's at once, -1 for the server's. */
	public int headLock = -1;
	public RespawnKit respawnKit = RespawnKit.ENTRY;
	public Kit respawnCustom = new Kit();
	/** What the entry kit is called where there are loadouts to pick from. */
	public String firstLoadout = "Standard";
	/** More kits to pick from besides the entry kit, each with its own respawn. */
	public List<Loadout> loadouts = new ArrayList<>();
	/** Whether players pick again each time they come back from a death. */
	public boolean loadoutRepick = false;
	/** Whether a loadout's cap counts each team apart, each seeing only its own picks, or the whole match, all seeing all. */
	public boolean capsPerTeam = true;
	public ItemList killReward = ItemList.of("minecraft:golden_apple", 1);
	/** Given for each of the arena's own mobs a player takes down. */
	public ItemList mobKillReward = new ItemList();
	public Map<KillType, ItemList> killBonus = new EnumMap<>(KillType.class);
	/** What each feat pays, where it pays anything. */
	public Map<Feat, ItemList> featRewards = new EnumMap<>(Feat.class);

	// --- Rules ---
	public Mode mode = Mode.FFA;
	public int teams = 2;
	public boolean friendlyFire = false;
	/** Off makes it players against the arena's mobs: nobody can hurt anybody else. */
	public boolean pvp = true;
	public boolean waitingRoom = true;
	public LateJoin lateJoin = LateJoin.ALLOW;
	public GameRule gameMode = GameRule.SURVIVAL;
	public Spawn spawn = Spawn.RANDOM;
	/** The names over players' heads: everyone's, only teammates', or nobody's. */
	public NameTags nameTags = NameTags.SHOWN;
	public boolean exitPortal = true;
	public boolean exitCommand = true;
	public boolean endWhenOneSideLeft = false;

	// --- Goal ---
	public Goal goal = Goal.TIME;
	public int killTarget = 0;
	public Lives lives = Lives.OFF;
	public int livesCount = 3;
	/** "any" for any hostile mob, or the id of the one kind that counts. */
	public String mobGoalKind = "any";
	public int mobGoalCount = 20;
	public Scope mobGoalScope = Scope.EVERYONE;
	public int captures = 1;
	public int takeoverPercent = 0;
	public int baseSize = 3;
	/** What a win can pay; one is drawn for each win. Empty lists are never drawn. */
	public List<ItemList> prizes = new ArrayList<>();
	/** Whether a winner also takes home what they carried inside, from an arena that keeps things apart. */
	public boolean winnersKeepPack = false;
	public MarkerPlace hillPlace = MarkerPlace.CENTER;
	/** Minutes between the hill moving somewhere new; 0 leaves it where it is. */
	public int hillMoves = 0;
	/** Minutes on the hill that win; 0 for whoever held it longest when time is up. */
	public int hillTarget = 0;
	/** What a bank counts: each stack's size is what one of that item scores. */
	public ItemList bankValues = defaultBankValues();
	/** Points that win; 0 for the richest bank when time is up. */
	public int bankTarget = 0;
	public MarkerPlace finishPlace = MarkerPlace.CENTER;
	public FinishStyle finishStyle = FinishStyle.GROUND;
	/** Paid out of what each player brings, on the way in. */
	public ItemList entryFee = new ItemList();
	/** Whether everyone is handed a compass pointed at the goal: the hill, the finish, the other team's base. */
	public boolean goalCompass = false;
	/** Whether the fees paid are shared among the winners, or simply spent. */
	public boolean feesToWinners = true;
	/** Players fighting the mobs who are out come back as zombies, on the mobs' side, instead of watching. */
	public boolean horde = false;
	/** What the horde rises with, besides a zombie's head and the rags it can't take off. */
	public Kit hordeKit = Kit.zombie();

	// --- Mobs ---
	public MobTime mobs = MobTime.OFF;
	public Rarity mobRarity = Rarity.NORMAL;
	/** Steadily, by time of day and how many, or in waves, one after another. */
	public MobStyle mobStyle = MobStyle.STEADY;
	public List<Wave> waves = new ArrayList<>(List.of(
		Wave.of(new WaveMob("minecraft:zombie", 20)),
		Wave.of(new WaveMob("minecraft:zombie", 20), new WaveMob("minecraft:skeleton", 10)),
		Wave.of(new WaveMob("minecraft:zombie", 20), new WaveMob("minecraft:skeleton", 20), new WaveMob("minecraft:creeper", 10))));
	/** Seconds between one wave cleared and the next coming. */
	public int waveBreak = 15;
	/** Minutes a wave is given before the next comes anyway; 0 waits for it to be cleared. */
	public int waveLimit = 3;
	public AfterWaves afterWaves = AfterWaves.REPEAT_LAST;
	/** How often a kind turns up unless {@link #mobLevels} says otherwise: off, in a new preset. */
	public MobLevel mobDefault = MobLevel.OFF;
	/** How often each kind turns up, by id, for the kinds not at {@link #mobDefault}. */
	public Map<String, MobLevel> mobLevels = new java.util.TreeMap<>();

	// --- Life ---
	public int lifeMinutes = 30;
	public int borderShrink = 0;
	public Weather weather = Weather.OUTSIDE;
	/** What the weather turns to after {@link #weatherAfter} minutes; outside, for no change. */
	public Weather weatherLater = Weather.OUTSIDE;
	public int weatherAfter = 10;

	// --- Terrain ---
	public Source source = Source.GENERATE;
	public String saved = "";
	public int size = 6;
	public World world = World.OVERWORLD;
	public Shape shape = Shape.NATURAL;
	public String biome = "minecraft:plains";
	public int depth = 24;
	public Material material = Material.NATURAL;
	public String single = "minecraft:stone";
	public Map<String, String> swaps = new LinkedHashMap<>();
	public List<Layer> layers = new ArrayList<>(List.of(
		new Layer("minecraft:grass_block", 5), new Layer("minecraft:dirt", 20), new Layer("minecraft:stone", 75)));
	public Bedrock bedrock = Bedrock.BOTTOM;
	public boolean structures = false;
	public boolean flatFeatures = true;
	public int divisions = 1;
	public DivisionShape divisionShape = DivisionShape.PIE;
	public String divisionBlock = "minecraft:glass";
	public boolean teamWalls = true;
	public BaseStyle teamBases = BaseStyle.NONE;
	/** How far anyone inside can see, in chunks, before the fog; 0 for as far as their own settings let them. */
	public int fog = 0;
	public ChestAccess chestAccess = ChestAccess.OPEN;
	public Map<Integer, String> teamWallBlocks = new LinkedHashMap<>();
	public int wallsFall = 0;
	/** Whether division walls can't be broken or blown through until they fall. */
	public boolean wallsHold = false;
	/** The game mode changing partway through, soonest first once sorted. */
	public List<ModeChange> modeChanges = new ArrayList<>();

	// --- Pinata ---
	public PinataMode pinata = PinataMode.OFF;
	public int pinataMinutes = 5;
	public boolean pinataRegardless = false;
	/** How many stand at once. */
	public int pinataCount = 1;
	public PinataPlace pinataPlace = PinataPlace.CENTER;
	public List<PinataLoot> pinataLoot = new ArrayList<>(List.of(defaultLoot()));

	private static PinataLoot defaultLoot() {
		PinataLoot loot = new PinataLoot();
		loot.items = ItemList.of("minecraft:golden_apple", 2);
		loot.items.add("minecraft:ender_pearl", 4);
		loot.items.add("minecraft:arrow", 16);
		return loot;
	}

	private static ItemList defaultBankValues() {
		ItemList values = ItemList.of("minecraft:diamond", 10);
		values.add("minecraft:emerald", 8);
		values.add("minecraft:gold_ingot", 5);
		values.add("minecraft:iron_ingot", 3);
		values.add("minecraft:copper_ingot", 1);
		values.add("minecraft:coal", 1);
		return values;
	}

	/** Whether there is more than one loadout, so players pick. */
	public boolean picksLoadout() {
		return !loadouts.isEmpty();
	}

	public int loadoutCount() {
		return 1 + loadouts.size();
	}

	/** A loadout's name, by index: 0 is the entry kit. */
	public String loadoutName(int index) {
		return index <= 0 || index > loadouts.size() ? firstLoadout : loadouts.get(index - 1).name;
	}

	public Kit loadoutKit(int index) {
		return index <= 0 || index > loadouts.size() ? entryKit : loadouts.get(index - 1).kit;
	}

	public RespawnKit loadoutRespawn(int index) {
		return index <= 0 || index > loadouts.size() ? respawnKit : loadouts.get(index - 1).respawn;
	}

	/** How many may have a loadout at once; the entry kit, which anyone can fall back on, has no cap. */
	public int loadoutCap(int index) {
		return index <= 0 || index > loadouts.size() ? 0 : loadouts.get(index - 1).cap;
	}

	public Kit loadoutRespawnCustom(int index) {
		return index <= 0 || index > loadouts.size() ? respawnCustom : loadouts.get(index - 1).respawnCustom;
	}

	public boolean teamsOn() {
		return mode == Mode.TEAMS;
	}

	/** The goals that need sides to score for. */
	public boolean teamGoal() {
		return goal == Goal.CTF || goal == Goal.TAKEOVER || goal == Goal.DESTRUCTION || goal == Goal.BANK;
	}

	/** What the arena is actually played for: a team goal only counts with teams, a kill count only with fighting. */
	public Goal activeGoal() {
		if (teamGoal() && !teamsOn()) return Goal.TIME;
		if (goal == Goal.KILLS && !pvp) return Goal.TIME;
		if (goal == Goal.WAVES && mobStyle != MobStyle.WAVES) return Goal.TIME;
		return goal;
	}

	/** Whether deaths are counted against lives, whatever the goal. */
	public boolean livesOn() {
		return lives != Lives.OFF;
	}

	/** Lives shared by a team, which needs teams: a free for all counts each player's own. */
	public boolean pooledLives() {
		return lives == Lives.TEAM_POOL && teamsOn();
	}

	/** Lives only matter where a death comes back, so lives bring their players back inside, and so does the horde. */
	public boolean respawnsInside() {
		return deathCapture || livesOn() || hordeOn();
	}

	/** The horde only where it's everyone against the mobs. */
	public boolean hordeOn() {
		return horde && !pvp;
	}

	/** Whether a player can be out of the fight at all: out of lives, or dead where nobody comes back. */
	public boolean canBeOut() {
		return livesOn() || !deathCapture;
	}

	/**
	 * When mobs come steadily: never while they come in waves, and day and night for a goal of mob
	 * kills even if the preset left them off, since there must be something to hunt.
	 */
	public MobTime activeMobs() {
		if (mobStyle == MobStyle.WAVES) return MobTime.OFF;
		return mobs == MobTime.OFF && activeGoal() == Goal.MOBS ? MobTime.ALWAYS : mobs;
	}

	/** How mob kills are counted: each team's only where there are teams. */
	public Scope mobScope() {
		return mobGoalScope == Scope.TEAM && !teamsOn() ? Scope.EVERYONE : mobGoalScope;
	}

	/** Placing and breaking are what some goals are, and adventure mode allows neither. */
	public boolean needsSurvival() {
		Goal active = activeGoal();
		return active == Goal.TAKEOVER || active == Goal.DESTRUCTION || active == Goal.BANK
			|| active == Goal.RACE && (finishStyle == FinishStyle.SKY || finishStyle == FinishStyle.BURIED);
	}

	/**
	 * Whether what a player carries is put aside on the way in. Always, in a creative arena,
	 * whatever the preset says: anything can be made there, so nothing may be carried out.
	 */
	public boolean isolated() {
		return traversal != Traversal.FREE || creative();
	}

	/** Natural Nether ground: a cave under a roof, found and stood on differently from open ground. */
	public boolean roofed() {
		return world == World.NETHER && shape == Shape.NATURAL;
	}

	public MobLevel mobLevel(String kind) {
		return mobLevels.getOrDefault(kind, mobDefault);
	}

	public void setMobLevel(String kind, MobLevel level) {
		if (level == mobDefault) mobLevels.remove(kind);
		else mobLevels.put(kind, level);
	}

	/** Every kind at one level. */
	public void setAllMobs(MobLevel level) {
		mobDefault = level;
		mobLevels.clear();
	}

	/** Whether any kind of this world's mobs is switched on at all. */
	public boolean anyMobKind() {
		for (String kind : MobKinds.of(world)) if (mobLevel(kind) != MobLevel.OFF) return true;
		return false;
	}

	/** Whether this preset builds each team a base, or one for everyone against the mobs. */
	public boolean basesOn() {
		return teamBases != BaseStyle.NONE && (teamsOn() || !pvp);
	}

	/** Whether any mobs come at all: steadily, for a hunt, or in waves. */
	public boolean mobsCome() {
		return activeMobs() != MobTime.OFF || mobStyle == MobStyle.WAVES;
	}

	/** Whether it is creative at any point: anything could be made then, so nothing may be carried out. */
	public boolean creative() {
		return gameMode == GameRule.CREATIVE || modeChanges.stream().anyMatch(change -> change.mode == GameRule.CREATIVE);
	}

	/** Whether the rewards shortlist lets anything out: never from a creative arena. */
	public boolean rewardsOut() {
		return traversal == Traversal.REWARDS && !creative();
	}

	public ItemList bonus(KillType type) {
		return killBonus.computeIfAbsent(type, key -> new ItemList());
	}

	public ItemList featReward(Feat feat) {
		return featRewards.computeIfAbsent(feat, key -> new ItemList());
	}

	/** Which of the fixed team colours team {@code index} wears. */
	public TeamColors.Colour teamColour(int index) {
		return TeamColors.of(index);
	}

	/** The block a division wall shows on team {@code index}'s side. */
	public String teamWallBlock(int index) {
		String chosen = teamWallBlocks.get(index);
		return chosen != null ? chosen : teamColour(index).glass();
	}
}
