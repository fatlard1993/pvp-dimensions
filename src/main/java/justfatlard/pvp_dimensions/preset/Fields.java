package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import static justfatlard.pvp_dimensions.preset.Field.Section.*;

/**
 * Every setting a preset has, in the order the menu shows them. Some come and go with others:
 * team settings only for teams, a material's blocks only for that material, and each layer, swap
 * and pinata list gets its own rows, so the list is built for the preset being edited.
 */
public final class Fields {
	private Fields() {}

	public static final boolean PINATA_INSTALLED = FabricLoader.getInstance().isModLoaded("pinata");
	public static final boolean DEAD_HEADS_INSTALLED = FabricLoader.getInstance().isModLoaded("dead-heads");

	private static final int[] LIFE_MINUTES = {0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 720, 1440, 2880, 4320, 10080};
	private static final int[] DEPTH = {0, 4, 8, 12, 16, 24, 32, 48, 64, 96};
	private static final int[] DIVISIONS = Field.Number.range(1, 9, 1);
	private static final int[] WALLS_FALL = {0, 1, 2, 3, 5, 10, 15, 20, 30};
	private static final int[] PINATA_MINUTES = {1, 2, 3, 5, 10, 15, 20, 30, 45, 60};
	private static final int[] HITS = {1, 3, 5, 10, 15, 20, 30, 50, 100};
	private static final int[] KILL_TARGETS = {0, 3, 5, 10, 15, 20, 25, 30, 50, 100};
	private static final int[] MOB_TARGETS = {5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200};
	private static final int[] HILL_MOVES = {0, 1, 2, 3, 5, 10, 15};
	private static final int[] HILL_TARGETS = {0, 1, 2, 3, 5, 10, 15, 20, 30};
	private static final int[] BANK_TARGETS = {0, 50, 100, 200, 300, 500, 1000, 2000, 5000};
	private static final int[] HEAD_LOCKS = {-1, 0, 1, 2, 3, 5, 10, 15, 30, 60};
	private static final int[] WEATHER_AFTER = {1, 2, 3, 5, 10, 15, 20, 30, 45, 60};
	/** Chunks a player may see through the fog, thickest last; nought for no fog. */
	private static final int[] FOG = {0, 12, 8, 6, 4, 3, 2};

	/** Biggest arena a preset may ask for. Past sixteen it says so, since smaller plays better. */
	public static final int MAX_SIZE = 32;
	public static final int ROOMY_SIZE = 16;

	public static List<Field> of(Preset preset) {
		List<Field> fields = new ArrayList<>();

		// --- Game: what it is, how it is won, and how it reads ---
		fields.add(new Field.Text(GAME, "name", "Name", 24, p -> p.name, (p, v) -> p.name = v));
		fields.add(new Field.Text(GAME, "icon", "Picture", 64, p -> p.icon, (p, v) -> p.icon = v)
			.help("The item shown for this preset; the menu sets it from what you hold"));
		fields.add(new Field.Heading(GAME, "won_heading", "How it is won"));
		fields.add(new Field.Choice(GAME, "goal", "Played for", Fields::goalOptions,
			p -> p.goal.name().toLowerCase(Locale.ROOT), (p, v) -> p.goal = Preset.Goal.valueOf(v.toUpperCase(Locale.ROOT)))
			.help("How the arena is won before its time runs out; capture the flag and the rest need teams"));
		fields.add(new Field.Number(GAME, "kill_target", "Kills to win", KILL_TARGETS, v -> v == 0 ? "No target" : v + " kills",
			p -> p.killTarget, (p, v) -> p.killTarget = v).help(
				"The first player to reach it wins; with teams, the first team between them")
			.when(p -> p.activeGoal() == Preset.Goal.KILLS));
		fields.add(new Field.Choice(GAME, "mob_goal_kind", "Which mobs", Fields::mobGoalOptions, p -> p.mobGoalKind, (p, v) -> p.mobGoalKind = v)
			.help("The kind that counts; half of what spawns, even if its own level is off")
			.when(p -> p.activeGoal() == Preset.Goal.MOBS));
		fields.add(new Field.Number(GAME, "mob_goal_count", "How many", MOB_TARGETS, v -> v + " kills",
			p -> p.mobGoalCount, (p, v) -> p.mobGoalCount = v)
			.when(p -> p.activeGoal() == Preset.Goal.MOBS));
		fields.add(new Field.Choice(GAME, "mob_goal_scope", "Counted for", Fields::scopeOptions,
			p -> p.mobScope().name().toLowerCase(Locale.ROOT), (p, v) -> p.mobGoalScope = Preset.Scope.valueOf(v.toUpperCase(Locale.ROOT)))
			.help("Everyone together: all of you win or none of you do")
			.when(p -> p.activeGoal() == Preset.Goal.MOBS));
		fields.add(new Field.Note(GAME, "mob_goal.note", false, p -> "Which mobs come, and how many, is on Timeline")
			.when(p -> p.activeGoal() == Preset.Goal.MOBS));
		fields.add(new Field.Number(GAME, "captures", "Captures to win", Field.Number.range(1, 5, 1), v -> v + (v == 1 ? " capture" : " captures"),
			p -> p.captures, (p, v) -> p.captures = v)
			.help("Take their banner from their chest and put it in yours")
			.when(p -> p.activeGoal() == Preset.Goal.CTF));
		fields.add(new Field.Number(GAME, "takeover_percent", "Win at", Field.Number.range(0, 90, 10),
			v -> v == 0 ? "Most when time's up" : v + "% of the ground", p -> p.takeoverPercent, (p, v) -> p.takeoverPercent = v)
			.help("Ground covered in your team's terracotta; everyone is handed some at every spawn")
			.when(p -> p.activeGoal() == Preset.Goal.TAKEOVER));
		fields.add(new Field.Number(GAME, "base_size", "Base size", Field.Number.range(1, 7, 1), v -> v + " x " + v + " x " + v,
			p -> p.baseSize, (p, v) -> p.baseSize = v)
			.help("A cube of team terracotta; once every block of it is gone the team is out")
			.when(p -> p.activeGoal() == Preset.Goal.DESTRUCTION));
		fields.add(Field.Choice.ofEnum(GAME, "hill_place", "The hill stands", Preset.MarkerPlace.class,
			new String[] {"In the middle", "Anywhere"}, p -> p.hillPlace, (p, v) -> p.hillPlace = v)
			.help("A gold pad under a beacon's beam; time on it counts only while nobody else is on it")
			.when(p -> p.activeGoal() == Preset.Goal.HILL));
		fields.add(new Field.Number(GAME, "hill_moves", "The hill moves", HILL_MOVES, v -> v == 0 ? "Never" : "Every " + duration(v).toLowerCase(Locale.ROOT),
			p -> p.hillMoves, (p, v) -> p.hillMoves = v).help("Somewhere new each time, so nobody digs in round it")
			.when(p -> p.activeGoal() == Preset.Goal.HILL));
		fields.add(new Field.Number(GAME, "hill_target", "Hold to win", HILL_TARGETS, v -> v == 0 ? "Longest at the end" : duration(v),
			p -> p.hillTarget, (p, v) -> p.hillTarget = v).when(p -> p.activeGoal() == Preset.Goal.HILL));
		fields.add(Field.Items.list(GAME, "bank_values", "Points each", p -> p.bankValues, (p, v) -> p.bankValues = v).shuffles(Loot.BANK)
			.help("Each item's count is what one of it scores in a bank; anything not listed scores nothing")
			.when(p -> p.activeGoal() == Preset.Goal.BANK));
		fields.add(new Field.Number(GAME, "bank_target", "Win at", BANK_TARGETS, v -> v == 0 ? "Richest at the end" : v + " points",
			p -> p.bankTarget, (p, v) -> p.bankTarget = v).help("Every team has a chest that can't be broken; what's in it is its score")
			.when(p -> p.activeGoal() == Preset.Goal.BANK));
		fields.add(Field.Choice.ofEnum(GAME, "finish_place", "The finish stands", Preset.MarkerPlace.class,
			new String[] {"In the middle", "Anywhere"}, p -> p.finishPlace, (p, v) -> p.finishPlace = v)
			.help("An emerald pad under a beacon's beam; everyone starts the same distance from it")
			.when(p -> p.activeGoal() == Preset.Goal.RACE));
		fields.add(Field.Choice.ofEnum(GAME, "finish_style", "Getting there", Preset.FinishStyle.class,
			new String[] {"Across the ground", "Up a tower", "Up in the sky", "Dug down to"}, p -> p.finishStyle, (p, v) -> p.finishStyle = v)
			.help("In the sky you build up to it, buried you dig down: both play in survival")
			.when(p -> p.activeGoal() == Preset.Goal.RACE));
		fields.add(Field.Choice.ofEnum(GAME, "lives", "Lives", Preset.Lives.class,
			new String[] {"Endless", "Each player's own", "One pool per team"}, p -> p.lives, (p, v) -> p.lives = v)
			.help("Out of lives is out: watching. Fighting each other, the last one standing wins; side by side, everyone out ends it"));
		fields.add(new Field.Note(GAME, "lives.note", true, Summary::pooledLivesAlone));
		fields.add(new Field.Number(GAME, "lives_count", "Lives each", Field.Number.range(1, 20, 1), v -> v == 1 ? "1 life" : v + " lives",
			p -> p.livesCount, (p, v) -> p.livesCount = v)
			.when(Preset::livesOn));
		fields.add(new Field.Toggle(GAME, "last_side", "End with one side left", p -> p.endWhenOneSideLeft, (p, v) -> p.endWhenOneSideLeft = v)
			.help("Once only one team, or one player, is still inside"));
		fields.add(new Field.Number(GAME, "life", "Lasts", LIFE_MINUTES, Fields::duration, p -> p.lifeMinutes, (p, v) -> p.lifeMinutes = v)
			.help("When time is up, whoever leads the goal wins"));
		Predicate<Preset> winnable = p -> p.activeGoal() != Preset.Goal.TIME || p.endWhenOneSideLeft || p.livesOn();
		fields.add(new Field.Heading(GAME, "prize_heading", preset.prizes.size() > 1
			? "Prizes: one drawn at random for each win"
			: "Prize: handed to every winner on the way home").when(winnable));
		for (int i = 0; i < preset.prizes.size(); i++) {
			int index = i;
			fields.add(Field.Items.list(GAME, "prize." + i, "Prize " + (i + 1),
				p -> index < p.prizes.size() ? p.prizes.get(index) : new ItemList(),
				(p, v) -> { if (index < p.prizes.size()) p.prizes.set(index, v); }).shuffles(Loot.PRIZE).when(winnable));
			fields.add(new Field.Action(GAME, "prize." + i + ".remove", "", "Remove prize " + (i + 1), p -> {
				if (index < p.prizes.size()) p.prizes.remove(index);
			}).when(winnable));
		}
		if (preset.prizes.size() < 10) {
			fields.add(new Field.Action(GAME, "prize.add", "", preset.prizes.isEmpty() ? "Add a prize" : "Add another prize",
				p -> p.prizes.add(new ItemList())).when(winnable));
		}

		fields.add(new Field.Heading(GAME, "sharing", "Sharing"));
		fields.add(new Field.Toggle(GAME, "for_users", "Users can start it", p -> p.forUsers, (p, v) -> p.forUsers = v)
			.help("Off keeps it for admins, for a sandbox or a test"));
		fields.add(new Field.Grid(GAME, "adjustable", "Users may change", Fields::adjustableCells,
			(p, key) -> { if (!p.adjustable.remove(key)) p.adjustable.add(key); },
			p -> p.adjustable.isEmpty() ? "Nothing" : p.adjustable.size() + " settings")
			.help("Settings a user can set for their own game when they start this preset").when(p -> p.forUsers));
		fields.add(new Field.Action(GAME, "export", "Share", "Export", p -> {})
			.help("To a file in config/pvp-dimensions/shared, saying which mods it needs, for another server to import"));
		fields.add(new Field.Heading(GAME, "summary_heading", "This match"));
		List<String> lines = new ArrayList<>();
		for (String sentence : Summary.sentences(preset)) lines.addAll(wrap(sentence, SUMMARY_WIDTH));
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			fields.add(new Field.Note(GAME, "summary." + i, false, p -> line));
		}
		List<String> overridden = Summary.overrides(preset);
		for (int i = 0; i < overridden.size(); i++) {
			List<String> wrapped = wrap(overridden.get(i), SUMMARY_WIDTH);
			for (int j = 0; j < wrapped.size(); j++) {
				String line = wrapped.get(j);
				fields.add(new Field.Note(GAME, "override." + i + "." + j, true, p -> line));
			}
		}

		// --- Players: who, and with whom ---
		fields.add(Field.Choice.ofEnum(PLAYERS, "mode", "Mode", Preset.Mode.class,
			new String[] {"Free for all", "Teams"}, p -> p.mode, (p, v) -> {
				p.mode = v;
				if (!p.teamsOn() && p.teamGoal()) p.goal = Preset.Goal.TIME;
			}));
		fields.add(new Field.Number(PLAYERS, "teams", "Teams", Field.Number.range(2, TeamColors.MAX, 1), v -> v + " teams",
			p -> p.teams, (p, v) -> p.teams = v).when(Preset::teamsOn));
		fields.add(new Field.Toggle(PLAYERS, "pvp", "Players fight", p -> p.pvp, (p, v) -> {
				p.pvp = v;
				if (!v && p.goal == Preset.Goal.KILLS) p.goal = Preset.Goal.MOBS;
			}).help("Off makes it players against the mobs: nobody can hurt anybody else"));
		fields.add(new Field.Toggle(PLAYERS, "friendly_fire", "Friendly fire", p -> p.friendlyFire, (p, v) -> p.friendlyFire = v)
			.when(p -> p.teamsOn() && p.pvp));
		fields.add(new Field.Choice(PLAYERS, "chest_access", "Other teams' chests", Fields::chestAccessOptions,
			p -> p.chestAccess.name().toLowerCase(Locale.ROOT), (p, v) -> p.chestAccess = Preset.ChestAccess.valueOf(v.toUpperCase(Locale.ROOT)))
			.help("A team's own chest in its base, or its bank: whether another team may open it. Nobody can lock a chest in an arena")
			.when(p -> p.teamsOn() && (p.basesOn() || p.goal == Preset.Goal.BANK)));
		fields.add(new Field.Note(PLAYERS, "chest_access.note", true, Summary::picksNeedLootEnder));
		fields.add(Field.Choice.ofEnum(PLAYERS, "name_tags", "Names over heads", Preset.NameTags.class,
			new String[] {"Shown", "Teammates only", "Hidden"}, p -> p.nameTags, (p, v) -> p.nameTags = v)
			.help("Hidden, nobody can pick out who is who from across the arena"));
		fields.add(new Field.Note(PLAYERS, "name_tags.note", true, Summary::teammateNamesAlone));
		fields.add(Field.Choice.ofEnum(PLAYERS, "game_mode", "Game mode", Preset.GameRule.class,
			new String[] {"Survival", "Adventure (no digging)", "Creative (building, no harm)"}, p -> p.gameMode, (p, v) -> p.gameMode = v)
			.help("Admins keep their own game mode"));
		fields.add(new Field.Note(PLAYERS, "game_mode.note", true, Summary::playsInSurvival));
		fields.add(Field.Choice.ofEnum(PLAYERS, "spawn", "Spawn at", Preset.Spawn.class,
			new String[] {"Random spots", "Team corners", "The middle"}, p -> p.spawn, (p, v) -> p.spawn = v));
		fields.add(new Field.Toggle(PLAYERS, "waiting_room", "Waiting room", p -> p.waitingRoom, (p, v) -> p.waitingRoom = v)
			.help("Players gather first, pick a team by standing on its colour, and the host starts the fight"));
		fields.add(Field.Choice.ofEnum(PLAYERS, "late_join", "Late arrivals", Preset.LateJoin.class,
			new String[] {"Join in", "Watch", "Turned away"}, p -> p.lateJoin, (p, v) -> p.lateJoin = v)
			.help("Whoever arrives after the fight has started"));
		fields.add(Field.Choice.ofEnum(PLAYERS, "ways_home", "Ways home", WaysHome.class,
			new String[] {"Portal or command", "Exit portal", "/pvp leave", "Only the end"}, WaysHome::of, WaysHome::apply)
			.help("An exit portal inside, the /pvp leave command, both, or neither"));
		fields.add(new Field.Note(PLAYERS, "ways_home.note", true, Summary::noWayOut));

		// --- Arena: where ---
		fields.add(new Field.Picture(ARENA, "map", 4, Pictures::map));
		fields.add(Field.Choice.ofEnum(ARENA, "source", "Ground", Preset.Source.class,
			new String[] {"Generate", "Saved"}, p -> p.source, (p, v) -> p.source = v));
		fields.add(new Field.Choice(ARENA, "saved", "Saved terrain", p -> savedOptions(), p -> p.saved, (p, v) -> p.saved = v)
			.help("Made with /pvp terrain save, standing in an arena").when(p -> p.source == Preset.Source.SAVED));
		fields.add(new Field.Number(ARENA, "size", "Size", Field.Number.range(2, MAX_SIZE, 1), Fields::size, p -> p.size, (p, v) -> p.size = v)
			.help("Chunks across, sixteen blocks each. Small arenas find a fight fast").when(Fields::generated));
		fields.add(Field.Choice.ofEnum(ARENA, "world", "World", Preset.World.class,
			new String[] {"Overworld", "Nether", "The End"}, p -> p.world, (p, v) -> {
				p.world = v;
				p.biome = defaultBiome(v);
				p.swaps.clear();
			}).help("Its biomes, its sky and the blocks its ground is made of").when(Fields::generated));
		fields.add(Field.Choice.ofEnum(ARENA, "shape", "Shape", Preset.Shape.class,
			new String[] {"Natural", "Flat"}, p -> p.shape, (p, v) -> p.shape = v)
			.help("Natural: the world's own hills, caverns or islands. Flat: level ground, laid by the biome").when(Fields::generated));
		fields.add(new Field.Choice(ARENA, "biome", "Biome", p -> biomeOptions(p.world), p -> p.biome, (p, v) -> p.biome = v)
			.when(Fields::generated));
		fields.add(new Field.Number(ARENA, "depth", "Ground depth", DEPTH, v -> v == 0 ? "All the way down" : v + " blocks",
			p -> p.depth, (p, v) -> p.depth = v).help("How deep the ground goes under its surface")
			.when(p -> generated(p) && !p.roofed()));
		fields.add(new Field.Note(ARENA, "depth.note", true, Summary::netherKeepsCavern));
		fields.add(Field.Choice.ofEnum(ARENA, "material", "Material", Preset.Material.class,
			new String[] {"As it grows", "All one block", "Swap blocks", "Layers"}, p -> p.material, (p, v) -> p.material = v)
			.when(Fields::generated));
		fields.add(new Field.BlockRef(ARENA, "single", "Made of", false, p -> p.single, (p, v) -> p.single = v)
			.when(p -> generated(p) && p.material == Preset.Material.SINGLE));
		if (preset.material == Preset.Material.SWAP && generated(preset)) {
			for (String from : Palettes.of(preset.world, preset.swaps)) {
				fields.add(new Field.BlockRef(ARENA, "swap." + from.replace(':', '.'), blockName(from) + " becomes", true,
					p -> p.swaps.getOrDefault(from, ""), (p, v) -> {
						if (v.isEmpty() || v.equals(from)) p.swaps.remove(from);
						else p.swaps.put(from, v);
					}));
			}
			fields.add(new Field.Action(ARENA, "swap.add", "Another block", "Add held", p -> {}));
		}
		if (preset.material == Preset.Material.LAYERS && generated(preset)) {
			for (int i = 0; i < preset.layers.size(); i++) {
				int index = i;
				fields.add(new Field.BlockRef(ARENA, "layer." + i + ".block", "Layer " + (i + 1), false,
					p -> index < p.layers.size() ? p.layers.get(index).block() : "",
					(p, v) -> { if (index < p.layers.size()) p.layers.set(index, new Preset.Layer(v, p.layers.get(index).percent())); }));
				fields.add(new Field.Number(ARENA, "layer." + i + ".percent", "Layer " + (i + 1) + " depth", Field.Number.range(5, 100, 5),
					v -> v + "%", p -> index < p.layers.size() ? p.layers.get(index).percent() : 0,
					(p, v) -> { if (index < p.layers.size()) p.layers.set(index, new Preset.Layer(p.layers.get(index).block(), v)); }));
				if (preset.layers.size() > 1) {
					fields.add(new Field.Action(ARENA, "layer." + i + ".remove", "", "Remove layer " + (i + 1), p -> {
						if (index < p.layers.size() && p.layers.size() > 1) p.layers.remove(index);
					}));
				}
			}
			if (preset.layers.size() < 8) {
				fields.add(new Field.Action(ARENA, "layer.add", "", "Add a layer",
					p -> p.layers.add(new Preset.Layer("minecraft:stone", 10))));
			}
		}
		fields.add(Field.Choice.ofEnum(ARENA, "bedrock", "Bedrock", Preset.Bedrock.class,
			new String[] {"None", "A floor", "Floor and walls", "A sealed shell"}, p -> p.bedrock, (p, v) -> p.bedrock = v)
			.when(Fields::generated));
		fields.add(new Field.Toggle(ARENA, "flat_features", "Biome features", p -> p.flatFeatures, (p, v) -> p.flatFeatures = v)
			.help("Trees, cacti, spikes and pools: whatever the biome grows. Off leaves the flat bare")
			.when(p -> generated(p) && p.shape == Preset.Shape.FLAT));
		fields.add(new Field.Toggle(ARENA, "structures", "Villages and ruins", p -> p.structures, (p, v) -> p.structures = v)
			.when(p -> generated(p) && p.shape == Preset.Shape.NATURAL));
		fields.add(Field.Choice.ofEnum(ARENA, "team_bases", "Team bases", Preset.BaseStyle.class,
			new String[] {"None", "Camp", "Fort", "Tower", "Bunker"}, p -> p.teamBases, (p, v) -> p.teamBases = v)
			.help("Built at the middle of each team's ground when the fight starts, bigger for bigger teams, with a chest, crafting table and furnace. Against the mobs with no teams, one in the middle")
			.when(p -> p.teamsOn() || !p.pvp));
		fields.add(new Field.Note(ARENA, "team_bases.note", true, Summary::basesNeedSides));
		fields.add(new Field.Number(ARENA, "fog", "Fog", FOG, Fields::fogWords, p -> p.fog, (p, v) -> p.fog = v)
			.help("How far anyone inside can see; nobody sees further than their own render distance"));
		fields.add(new Field.Number(ARENA, "divisions", "Divisions", DIVISIONS, v -> v <= 1 ? "None" : v + " parts",
			p -> p.divisions, (p, v) -> p.divisions = v).help("Walls two blocks thick splitting the arena").when(Fields::generated));
		fields.add(Field.Choice.ofEnum(ARENA, "division_shape", "Divided as", Preset.DivisionShape.class,
			new String[] {"Pie slices", "A grid"}, p -> p.divisionShape, (p, v) -> p.divisionShape = v)
			.help("Slices all meet in the middle, so every part has two neighbours; a grid boxes the middle ones in")
			.when(p -> generated(p) && p.divisions > 2));
		fields.add(new Field.BlockRef(ARENA, "division_block", "Wall block", false, p -> p.divisionBlock, (p, v) -> p.divisionBlock = v)
			.when(p -> generated(p) && p.divisions > 1 && !(p.teamsOn() && p.teamWalls)));
		fields.add(new Field.Toggle(ARENA, "team_walls", "Walls in team colours", p -> p.teamWalls, (p, v) -> p.teamWalls = v)
			.help("Each side of a wall wears the colour of the team it faces").when(p -> generated(p) && p.divisions > 1 && p.teamsOn()));
		if (generated(preset) && preset.divisions > 1 && preset.teamsOn() && preset.teamWalls) {
			for (int i = 0; i < preset.teams; i++) {
				int team = i;
				fields.add(new Field.BlockRef(ARENA, "wall." + i, TeamColors.of(i).name() + " side", true,
					p -> p.teamWallBlock(team), (p, v) -> {
						if (v.isEmpty() || v.equals(TeamColors.of(team).glass())) p.teamWallBlocks.remove(team);
						else p.teamWallBlocks.put(team, v);
					}));
			}
		}
		fields.add(new Field.Number(ARENA, "walls_fall", "Walls fall after", WALLS_FALL, v -> v == 0 ? "Never" : duration(v),
			p -> p.wallsFall, (p, v) -> p.wallsFall = v).help("From the top down, like a curtain").when(p -> generated(p) && p.divisions > 1));
		fields.add(new Field.Toggle(ARENA, "walls_hold", "Walls hold till then", p -> p.wallsHold, (p, v) -> p.wallsHold = v)
			.help("Nobody can break or blow through a wall before it falls").when(p -> generated(p) && p.divisions > 1));
		modeFields(fields, preset);

		// --- Timeline: what happens along the way ---
		fields.add(new Field.Picture(TIMELINE, "clock", 3, Pictures::clock));
		fields.add(new Field.Number(TIMELINE, "border_shrink", "Border closes to", Field.Number.range(0, 90, 10),
			v -> v == 0 ? "Stays put" : (100 - v) + "% of it", p -> p.borderShrink, (p, v) -> p.borderShrink = v)
			.help("The border draws in over the arena's life").when(p -> p.lifeMinutes > 0));
		fields.add(Field.Choice.ofEnum(TIMELINE, "weather", "Weather", Preset.Weather.class,
			new String[] {"As outside", "Clear", "Rain", "Thunder"}, p -> p.weather, (p, v) -> p.weather = v)
			.help("The arena's own; thunder brings lightning down near the players").when(p -> p.world == Preset.World.OVERWORLD));
		fields.add(Field.Choice.ofEnum(TIMELINE, "weather_later", "Then", Preset.Weather.class,
			new String[] {"No change", "Clear", "Rain", "Thunder"}, p -> p.weatherLater, (p, v) -> p.weatherLater = v)
			.when(p -> p.world == Preset.World.OVERWORLD));
		fields.add(new Field.Number(TIMELINE, "weather_after", "After", WEATHER_AFTER, Fields::duration,
			p -> p.weatherAfter, (p, v) -> p.weatherAfter = v)
			.when(p -> p.world == Preset.World.OVERWORLD && p.weatherLater != Preset.Weather.OUTSIDE));
		fields.add(new Field.Note(TIMELINE, "weather.note", true, Summary::noSkyForWeather));
		fields.add(Field.Choice.ofEnum(TIMELINE, "mob_style", "Mobs come", Preset.MobStyle.class,
			new String[] {"Steadily", "In waves"}, p -> p.mobStyle, (p, v) -> {
				p.mobStyle = v;
				if (v != Preset.MobStyle.WAVES && p.goal == Preset.Goal.WAVES) p.goal = Preset.Goal.TIME;
			}).help("In waves: each one sent at the players, the next when it is cleared"));
		fields.add(Field.Choice.ofEnum(TIMELINE, "mobs", "Hostile mobs", Preset.MobTime.class,
			new String[] {"None (peaceful)", "At night", "Day and night"}, p -> p.mobs, (p, v) -> p.mobs = v)
			.help("The arena spawns its own, around its players; none arrive any other way")
			.when(p -> p.mobStyle == Preset.MobStyle.STEADY));
		fields.add(new Field.Note(TIMELINE, "mobs.note", true, Summary::huntBringsMobs));
		fields.add(Field.Choice.ofEnum(TIMELINE, "mob_rarity", "How many", Preset.Rarity.class,
			new String[] {"A few", "Some", "Lots", "A swarm"}, p -> p.mobRarity, (p, v) -> p.mobRarity = v)
			.when(p -> p.activeMobs() != Preset.MobTime.OFF));
		if (preset.activeMobs() != Preset.MobTime.OFF) {
			fields.add(new Field.Grid(TIMELINE, "mob_kinds", "Each kind", Fields::mobCells,
				(p, kind) -> p.setMobLevel(kind, Preset.MobLevel.values()[(p.mobLevel(kind).ordinal() + 1) % Preset.MobLevel.values().length]),
				p -> MobKinds.of(p.world).stream().filter(kind -> p.mobLevel(kind) != Preset.MobLevel.OFF).count() + " kinds on")
				.help("Click a mob to step it from off through rare and normal to common"));
			fields.add(new Field.Note(TIMELINE, "mob_kinds.note", true, Summary::kindsAllOff));
			fields.add(new Field.Action(TIMELINE, "mobs.all_off", "", "All off", p -> p.setAllMobs(Preset.MobLevel.OFF)));
			fields.add(new Field.Action(TIMELINE, "mobs.all_on", "", "All normal", p -> p.setAllMobs(Preset.MobLevel.NORMAL)));
		}
		if (preset.mobStyle == Preset.MobStyle.WAVES) waveFields(fields, preset);
		if (PINATA_INSTALLED) {
			fields.add(Field.Choice.ofEnum(TIMELINE, "pinata", "Pinatas", Preset.PinataMode.class,
				new String[] {"None", "One at the start", "Every so often", "A new one after each"}, p -> p.pinata, (p, v) -> p.pinata = v)
				.help("Their loot is set in Gear"));
			fields.add(new Field.Number(TIMELINE, "pinata_minutes", "Pinata every", PINATA_MINUTES, Fields::duration,
				p -> p.pinataMinutes, (p, v) -> p.pinataMinutes = v).when(p -> p.pinata == Preset.PinataMode.EVERY || p.pinata == Preset.PinataMode.AFTER_BREAK)
				.help("After a break, this is the wait before the next"));
			fields.add(new Field.Number(TIMELINE, "pinata_count", "At once", Field.Number.range(1, 5, 1), v -> v == 1 ? "1 pinata" : v + " pinatas",
				p -> p.pinataCount, (p, v) -> p.pinataCount = v).help("How many stand at a time; each broken one is replaced after the wait")
				.when(p -> p.pinata != Preset.PinataMode.OFF));
			fields.add(new Field.Toggle(TIMELINE, "pinata_regardless", "Even if some still stand", p -> p.pinataRegardless, (p, v) -> p.pinataRegardless = v)
				.when(p -> p.pinata == Preset.PinataMode.EVERY));
			fields.add(Field.Choice.ofEnum(TIMELINE, "pinata_place", "Pinatas stand", Preset.PinataPlace.class,
				new String[] {"In the middle", "Anywhere", "Anywhere, moving"}, p -> p.pinataPlace, (p, v) -> p.pinataPlace = v)
				.when(p -> p.pinata != Preset.PinataMode.OFF));
		}

		// --- Gear: arriving, fighting, dying, leaving ---
		fields.add(new Field.Heading(GEAR, "arriving", "Arriving"));
		fields.add(Field.Items.kit(GEAR, "entry_kit", preset.picksLoadout() ? "Loadout 1" : "Entry kit", p -> p.entryKit, (p, v) -> p.entryKit = v).shuffles(Loot.LOADOUT)
			.help("Laid out as you hold it: armour worn, sword in hand"));
		loadoutFields(fields, preset);
		fields.add(new Field.Toggle(GEAR, "goal_compass", "Compass to the goal", p -> p.goalCompass, (p, v) -> p.goalCompass = v)
			.help("Pointed at the hill, the finish or the other team's base. With Map++ it draws its radar in the compass slot")
			.when(p -> switch (p.activeGoal()) {
				case HILL, RACE, CTF, BANK -> true;
				default -> false;
			}));
		fields.add(Field.Items.list(GEAR, "entry_fee", "Costs to join", p -> p.entryFee, (p, v) -> p.entryFee = v).shuffles(Loot.FEE)
			.help("Taken from what you bring; nobody gets in without it. Handed back if the fight never starts"));
		fields.add(new Field.Toggle(GEAR, "fees_to_winners", "Winners share the pot", p -> p.feesToWinners, (p, v) -> p.feesToWinners = v)
			.help("Everyone's fee split among the winners, or back to its payers if nobody wins. Off, it's simply spent")
			.when(p -> !p.entryFee.isEmpty()));
		fields.add(new Field.Heading(GEAR, "fighting", "Fighting").when(p -> p.pvp || p.mobsCome() || (PINATA_INSTALLED && p.pinata != Preset.PinataMode.OFF)));
		fields.add(Field.Items.list(GEAR, "kill_reward", "Per player kill", p -> p.killReward, (p, v) -> p.killReward = v).shuffles(Loot.KILL)
			.help("Given to whoever made the kill").when(p -> p.pvp));
		for (Preset.KillType type : Preset.KillType.values()) {
			fields.add(Field.Items.list(GEAR, "bonus." + type.name().toLowerCase(Locale.ROOT), "+ " + killTypeLabel(type),
				p -> p.bonus(type), (p, v) -> p.killBonus.put(type, v)).shuffles(Loot.bonus(type)).help(killTypeHelp(type) + ", on top of the kill")
				.when(p -> p.pvp && (type != Preset.KillType.CARRIER || p.activeGoal() == Preset.Goal.CTF) && (type != Preset.KillType.AVENGER || p.teamsOn())));
		}
		fields.add(new Field.Heading(GEAR, "feats", "Moments"));
		for (Preset.Feat feat : Preset.Feat.values()) {
			fields.add(Field.Items.list(GEAR, "feat." + feat.name().toLowerCase(Locale.ROOT), featLabel(feat),
				p -> p.featReward(feat), (p, v) -> p.featRewards.put(feat, v)).shuffles(Loot.feat(feat)).help(featHelp(feat))
				.when(p -> featApplies(p, feat)));
		}
		fields.add(Field.Items.list(GEAR, "mob_kill_reward", "Per mob kill", p -> p.mobKillReward, (p, v) -> p.mobKillReward = v).shuffles(Loot.KILL)
			.help("Given to whoever takes down one of the arena's mobs").when(Preset::mobsCome));
		if (PINATA_INSTALLED && preset.pinata != Preset.PinataMode.OFF) {
			for (int i = 0; i < preset.pinataLoot.size(); i++) {
				int index = i;
				fields.add(Field.Items.list(GEAR, "loot." + i + ".items", "Pinata " + (i + 1),
					p -> index < p.pinataLoot.size() ? p.pinataLoot.get(index).items : new ItemList(),
					(p, v) -> { if (index < p.pinataLoot.size()) p.pinataLoot.get(index).items = v; }).shuffles(Loot.pinata(index))
					.help("Pinatas come in this order; the last list repeats"));
				fields.add(new Field.Number(GEAR, "loot." + i + ".hits", "", HITS, v -> v + " hits to break",
					p -> index < p.pinataLoot.size() ? p.pinataLoot.get(index).hits : 0,
					(p, v) -> { if (index < p.pinataLoot.size()) p.pinataLoot.get(index).hits = v; }));
				if (preset.pinataLoot.size() > 1) {
					fields.add(new Field.Action(GEAR, "loot." + i + ".remove", "", "Remove pinata " + (i + 1), p -> {
						if (index < p.pinataLoot.size() && p.pinataLoot.size() > 1) p.pinataLoot.remove(index);
					}));
				}
			}
			if (preset.pinataLoot.size() < 10) {
				fields.add(new Field.Action(GEAR, "loot.add", "", "Add a pinata", p -> p.pinataLoot.add(new Preset.PinataLoot())));
			}
		}
		fields.add(new Field.Heading(GEAR, "dying", "Dying"));
		fields.add(new Field.Toggle(GEAR, "death_capture", "Respawn inside", p -> p.deathCapture, (p, v) -> p.deathCapture = v)
			.help("A death comes back in the arena instead of at a bed"));
		fields.add(new Field.Note(GEAR, "death_capture.note", true, Summary::livesRespawnInside));
		fields.add(new Field.Toggle(GEAR, "horde", "The fallen rise as zombies", p -> p.horde, (p, v) -> p.horde = v)
			.help("Out of the fight, they come back on the mobs' side to hunt the rest, instead of watching").when(p -> !p.pvp));
		fields.add(new Field.Note(GEAR, "horde.note", true, Summary::hordeNeverRises));
		fields.add(Field.Items.kit(GEAR, "horde_kit", "Zombie kit", p -> p.hordeKit, (p, v) -> p.hordeKit = v).shuffles(Loot.RESPAWN)
			.help("What the fallen rise with. They always wear a zombie's head, and rags wherever the kit leaves armour off; none of it leaves the arena")
			.when(p -> !p.pvp && p.horde));
		fields.add(Field.Choice.ofEnum(GEAR, "respawn_kit", "Respawn kit", Preset.RespawnKit.class,
			new String[] {"Nothing", "The entry kit", "Its own"}, p -> p.respawnKit, (p, v) -> p.respawnKit = v)
			.when(Preset::respawnsInside));
		fields.add(Field.Items.kit(GEAR, "respawn_custom", "Respawn items", p -> p.respawnCustom, (p, v) -> p.respawnCustom = v).shuffles(Loot.RESPAWN)
			.when(p -> p.respawnsInside() && p.respawnKit == Preset.RespawnKit.CUSTOM));
		if (DEAD_HEADS_INSTALLED) {
			fields.add(new Field.Toggle(GEAR, "death_compasses", "Death compasses", p -> p.deathCompasses, (p, v) -> p.deathCompasses = v)
				.help("Dead Heads' compass pointing back at where you fell"));
			fields.add(new Field.Number(GEAR, "head_lock", "Heads locked for", HEAD_LOCKS,
				v -> v < 0 ? "The server's time" : v == 0 ? "Nobody: anyone's" : duration(v),
				p -> p.headLock, (p, v) -> p.headLock = v)
				.help("How long a dead player's head, with everything they had, stays theirs before anyone can empty it"));
		}
		fields.add(new Field.Heading(GEAR, "leaving", "Leaving"));
		fields.add(Field.Choice.ofEnum(GEAR, "traversal", "Inventories", Preset.Traversal.class,
			new String[] {"Pass freely", "Kept apart", "Kept apart, rewards out"}, p -> p.traversal, (p, v) -> p.traversal = v)
			.help("Kept apart: what you carry waits outside, and what you pick up inside stays in"));
		fields.add(new Field.Note(GEAR, "traversal.note", true, Summary::creativeKeepsApart));
		fields.add(Field.Items.list(GEAR, "rewards", "Allowed out", p -> p.rewards, (p, v) -> p.rewards = v).shuffles(Loot.KINDS)
			.help("Items of these kinds come home with a player; everything else stays")
			.when(Preset::rewardsOut));
		fields.add(Field.Choice.ofEnum(GEAR, "winners_keep", "Winners take home", WinnersKeep.class,
			new String[] {"The prize", "Prize and pack"},
			p -> p.winnersKeepPack ? WinnersKeep.PACK : WinnersKeep.PRIZE, (p, v) -> p.winnersKeepPack = v == WinnersKeep.PACK)
			.help("On top of their own things. Pack: whatever they were carrying inside comes home too")
			.when(p -> winnable.test(p) && p.isolated() && !p.creative()));

		fields.removeIf(field -> !field.shown(preset));
		return fields;
	}

	public static @Nullable Field find(Preset preset, String key) {
		for (Field field : of(preset)) {
			if (field.key.equals(key)) return field;
		}
		return null;
	}

	public static boolean generated(Preset preset) {
		return preset.source == Preset.Source.GENERATE;
	}

	public static String duration(int minutes) {
		if (minutes <= 0) return "For good";
		if (minutes % 1440 == 0) return minutes / 1440 + (minutes == 1440 ? " day" : " days");
		if (minutes % 60 == 0) return minutes / 60 + (minutes == 60 ? " hour" : " hours");
		if (minutes > 60) return minutes / 60 + "h " + minutes % 60 + "m";
		return minutes + (minutes == 1 ? " minute" : " minutes");
	}

	/** Short enough for the narrowest control it sits in; the blocks are in the help. */
	private static String size(int chunks) {
		return chunks + " chunks" + (chunks > ROOMY_SIZE ? ", roomy" : "");
	}

	private enum WinnersKeep { PRIZE, PACK }

	private static final int[] LOADOUT_CAPS = {0, 1, 2, 3, 4, 5, 8};
	private static final int[] MODE_MINUTES = {1, 2, 3, 4, 5, 7, 10, 15, 20, 30, 45, 60};

	/** Each change of game mode partway through, then room for another. */
	private static void modeFields(List<Field> fields, Preset preset) {
		for (int i = 0; i < preset.modeChanges.size(); i++) {
			int index = i;
			fields.add(new Field.Number(TIMELINE, "mode." + i + ".minute", "Game mode changes", MODE_MINUTES, v -> "After " + duration(v).toLowerCase(Locale.ROOT),
				p -> index < p.modeChanges.size() ? p.modeChanges.get(index).minute : 1,
				(p, v) -> { if (index < p.modeChanges.size()) p.modeChanges.get(index).minute = v; }));
			fields.add(Field.Choice.ofEnum(TIMELINE, "mode." + i + ".mode", "To", Preset.GameRule.class,
				new String[] {"Survival", "Adventure", "Creative"}, p -> index < p.modeChanges.size() ? p.modeChanges.get(index).mode : Preset.GameRule.SURVIVAL,
				(p, v) -> { if (index < p.modeChanges.size()) p.modeChanges.get(index).mode = v; }));
			fields.add(new Field.Toggle(TIMELINE, "mode." + i + ".warn", "A minute's warning", p -> index < p.modeChanges.size() && p.modeChanges.get(index).warn,
				(p, v) -> { if (index < p.modeChanges.size()) p.modeChanges.get(index).warn = v; }));
			fields.add(new Field.Toggle(TIMELINE, "mode." + i + ".kits", "Kits handed out afresh", p -> index < p.modeChanges.size() && p.modeChanges.get(index).kits,
				(p, v) -> { if (index < p.modeChanges.size()) p.modeChanges.get(index).kits = v; })
				.help("Everyone's pack emptied and their kit given again, so nothing made before is kept"));
			fields.add(new Field.Action(TIMELINE, "mode." + i + ".remove", "", "Remove this change", p -> {
				if (index < p.modeChanges.size()) p.modeChanges.remove(index);
			}));
		}
		if (preset.modeChanges.size() < 6) {
			fields.add(new Field.Action(TIMELINE, "mode.add", "", "Add a game mode change", p -> {
				Preset.ModeChange change = new Preset.ModeChange();
				change.minute = p.modeChanges.stream().mapToInt(c -> c.minute).max().orElse(0) + 5;
				change.mode = p.gameMode == Preset.GameRule.CREATIVE ? Preset.GameRule.SURVIVAL : Preset.GameRule.CREATIVE;
				p.modeChanges.add(change);
			}).help("Say, a creative spell to build in, then survival as the walls come down"));
		}
	}

	/**
	 * The loadouts after the entry kit, each named, with its kit, what it comes back with and how
	 * many may have it, then room for another; and how picking works once there are several.
	 */
	private static void loadoutFields(List<Field> fields, Preset preset) {
		fields.add(new Field.Text(GEAR, "first_loadout", "Loadout 1 name", 16, p -> p.firstLoadout, (p, v) -> p.firstLoadout = v)
			.help("The entry kit is the first loadout, and what anyone who doesn't pick gets").when(Preset::picksLoadout));
		for (int i = 0; i < preset.loadouts.size(); i++) {
			int index = i;
			String number = "Loadout " + (i + 2);
			fields.add(new Field.Text(GEAR, "loadout." + i + ".name", number + " name", 16,
				p -> index < p.loadouts.size() ? p.loadouts.get(index).name : "", (p, v) -> { if (index < p.loadouts.size()) p.loadouts.get(index).name = v; }));
			fields.add(Field.Items.kit(GEAR, "loadout." + i + ".kit", number, p -> index < p.loadouts.size() ? p.loadouts.get(index).kit : new Kit(),
				(p, v) -> { if (index < p.loadouts.size()) p.loadouts.get(index).kit = v; }).shuffles(Loot.LOADOUT));
			fields.add(Field.Choice.ofEnum(GEAR, "loadout." + i + ".respawn", "Comes back with", Preset.RespawnKit.class,
				new String[] {"Nothing", "The same", "Its own pack"}, p -> index < p.loadouts.size() ? p.loadouts.get(index).respawn : Preset.RespawnKit.ENTRY,
				(p, v) -> { if (index < p.loadouts.size()) p.loadouts.get(index).respawn = v; }).when(Preset::respawnsInside));
			fields.add(Field.Items.kit(GEAR, "loadout." + i + ".respawn_custom", "Its respawn pack",
				p -> index < p.loadouts.size() ? p.loadouts.get(index).respawnCustom : new Kit(),
				(p, v) -> { if (index < p.loadouts.size()) p.loadouts.get(index).respawnCustom = v; }).shuffles(Loot.RESPAWN)
				.when(p -> p.respawnsInside() && index < p.loadouts.size() && p.loadouts.get(index).respawn == Preset.RespawnKit.CUSTOM));
			fields.add(new Field.Number(GEAR, "loadout." + i + ".cap", "At most", LOADOUT_CAPS, v -> v == 0 ? "Any number" : v + (v == 1 ? " player" : " players"),
				p -> index < p.loadouts.size() ? p.loadouts.get(index).cap : 0, (p, v) -> { if (index < p.loadouts.size()) p.loadouts.get(index).cap = v; })
				.help("How many may have it at once"));
			fields.add(new Field.Action(GEAR, "loadout." + i + ".remove", "", "Remove " + number.toLowerCase(Locale.ROOT), p -> {
				if (index < p.loadouts.size()) p.loadouts.remove(index);
			}));
		}
		if (preset.loadouts.size() < 8) {
			fields.add(new Field.Action(GEAR, "loadout.add", "", preset.picksLoadout() ? "Add another loadout" : "Add a loadout to pick", p -> {
				Preset.Loadout loadout = new Preset.Loadout();
				loadout.name = "Loadout " + (p.loadouts.size() + 2);
				p.loadouts.add(loadout);
			}).help("Players then pick one on the way in, the entry kit or another"));
		}
		fields.add(new Field.Toggle(GEAR, "loadout_repick", "Pick again after each death", p -> p.loadoutRepick, (p, v) -> p.loadoutRepick = v)
			.help("Off, a pick lasts; the pick is still there with /pvp loadout").when(p -> p.picksLoadout() && p.respawnsInside()));
		fields.add(Field.Choice.ofEnum(GEAR, "caps_per_team", "Caps count", CapsCount.class,
			new String[] {"Each team apart", "The whole match"}, p -> p.capsPerTeam ? CapsCount.TEAM : CapsCount.MATCH,
			(p, v) -> p.capsPerTeam = v == CapsCount.TEAM)
			.help("Each team apart: a team sees only its own picks. The whole match: everyone sees who has what")
			.when(p -> p.teamsOn() && p.loadouts.stream().anyMatch(loadout -> loadout.cap > 0)));
	}

	private enum CapsCount { TEAM, MATCH }

	private static final int[] WAVE_BREAKS = {5, 10, 15, 20, 30, 45, 60, 90, 120};
	private static final int[] WAVE_LIMITS = {0, 1, 2, 3, 5, 10};

	/** The timing, then each wave's kinds and how many of each, then room for another wave. */
	private static void waveFields(List<Field> fields, Preset preset) {
		fields.add(new Field.Number(TIMELINE, "wave_break", "Between waves", WAVE_BREAKS, v -> v + " seconds",
			p -> p.waveBreak, (p, v) -> p.waveBreak = v));
		fields.add(new Field.Number(TIMELINE, "wave_limit", "Next wave comes", WAVE_LIMITS,
			v -> v == 0 ? "When it's cleared" : "After " + (v == 1 ? "a minute" : v + " minutes"), p -> p.waveLimit, (p, v) -> p.waveLimit = v)
			.help("At most this long after the last one, cleared or not; the last few of a wave glow"));
		fields.add(Field.Choice.ofEnum(TIMELINE, "after_waves", "After the last", Preset.AfterWaves.class,
			new String[] {"It comes again", "Start over", "No more"}, p -> p.afterWaves, (p, v) -> p.afterWaves = v)
			.when(p -> p.activeGoal() != Preset.Goal.WAVES));
		for (int i = 0; i < preset.waves.size(); i++) {
			int wave = i;
			fields.add(new Field.Grid(TIMELINE, "wave." + i, "Wave " + (i + 1), p -> waveCells(p, wave), (p, kind) -> stepWave(p, wave, kind),
				p -> wave < p.waves.size() ? p.waves.get(wave).mobs.size() + " kinds" : "")
				.help("Click a mob to add it and step up how many come for each player; past five it leaves the wave"));
			if (preset.waves.size() > 1) {
				fields.add(new Field.Action(TIMELINE, "wave." + i + ".remove", "", "Remove wave " + (i + 1), p -> {
					if (wave < p.waves.size() && p.waves.size() > 1) p.waves.remove(wave);
				}));
			}
		}
		if (preset.waves.size() < 20) {
			fields.add(new Field.Action(TIMELINE, "wave.add", "", "Add a wave, half again the last", p -> {
				Preset.Wave next = new Preset.Wave();
				if (!p.waves.isEmpty()) {
					for (Preset.WaveMob mob : p.waves.get(p.waves.size() - 1).mobs) {
						next.mobs.add(new Preset.WaveMob(mob.kind(), Math.min(100, (int) Math.round(mob.tenths() * 1.5 / 5) * 5)));
					}
				}
				p.waves.add(next);
			}));
		}
	}

	/** A click's steps for a mob in a wave, per player in tenths: not in it, then half a mob up to five. */
	private static final int[] WAVE_STEPS = {0, 5, 10, 20, 30, 50};

	private static List<Field.Grid.Cell> waveCells(Preset preset, int wave) {
		List<Field.Grid.Cell> cells = new ArrayList<>();
		if (wave >= preset.waves.size()) return cells;
		for (String kind : MobKinds.of(preset.world)) {
			int tenths = preset.waves.get(wave).mobs.stream().filter(m -> m.kind().equals(kind)).mapToInt(Preset.WaveMob::tenths).findFirst().orElse(0);
			String count = tenths == 0 ? "" : tenths == 5 ? "½" : tenths % 10 == 0 ? String.valueOf(tenths / 10) : String.valueOf(tenths / 10.0);
			cells.add(new Field.Grid.Cell(kind, MobKinds.egg(kind), count, tenths == 0, tenths > 0 ? ACCENT_ON : 0,
				MobKinds.name(kind) + (tenths == 0 ? ": not in this wave" : ": " + perPlayer(tenths)), MobKinds.small(kind), MobKinds.badge(kind)));
		}
		return cells;
	}

	private static void stepWave(Preset preset, int wave, String kind) {
		if (wave >= preset.waves.size()) return;
		List<Preset.WaveMob> mobs = preset.waves.get(wave).mobs;
		int at = -1;
		for (int i = 0; i < mobs.size(); i++) if (mobs.get(i).kind().equals(kind)) at = i;
		int now = at < 0 ? 0 : mobs.get(at).tenths();
		int next = 0;
		for (int step : WAVE_STEPS) {
			if (step > now) {
				next = step;
				break;
			}
		}
		if (next == 0) {
			if (at >= 0) mobs.remove(at);
		} else if (at >= 0) {
			mobs.set(at, new Preset.WaveMob(kind, next));
		} else {
			mobs.add(new Preset.WaveMob(kind, next));
		}
	}

	/** Green down the side for a mob that comes a lot, gold for a rare one. */
	private static final int ACCENT_ON = 0xFF55C455;
	private static final int ACCENT_RARE = 0xFFE0B040;

	private static List<Field.Grid.Cell> mobCells(Preset preset) {
		List<Field.Grid.Cell> cells = new ArrayList<>();
		for (String kind : MobKinds.of(preset.world)) {
			Preset.MobLevel level = preset.mobLevel(kind);
			String mark = switch (level) {
				case OFF -> "";
				case RARE -> "R";
				case NORMAL -> "N";
				case COMMON -> "C";
			};
			int accent = level == Preset.MobLevel.COMMON ? ACCENT_ON : level == Preset.MobLevel.RARE ? ACCENT_RARE : 0;
			cells.add(new Field.Grid.Cell(kind, MobKinds.egg(kind), mark, level == Preset.MobLevel.OFF, accent,
				MobKinds.name(kind) + ": " + mobLevelWords(level.ordinal()), MobKinds.small(kind), MobKinds.badge(kind)));
		}
		return cells;
	}

	/** The settings an admin can let users change when they start a preset, each a picture. */
	private static final String[][] ADJUSTABLE = {
		{"life", "minecraft:clock", "How long it lasts"},
		{"size", "minecraft:map", "How big it is"},
		{"teams", "minecraft:white_banner", "How many teams"},
		{"lives_count", "minecraft:totem_of_undying", "How many lives"},
		{"kill_target", "minecraft:iron_sword", "Kills to win"},
		{"mob_goal_count", "minecraft:zombie_head", "Mobs to take down"},
		{"divisions", "minecraft:glass", "Divisions"},
		{"mob_rarity", "minecraft:rotten_flesh", "How many mobs"},
	};

	public static List<String> adjustableKeys() {
		List<String> keys = new ArrayList<>();
		for (String[] entry : ADJUSTABLE) keys.add(entry[0]);
		return keys;
	}

	private static List<Field.Grid.Cell> adjustableCells(Preset preset) {
		List<Field.Grid.Cell> cells = new ArrayList<>();
		for (String[] entry : ADJUSTABLE) {
			boolean on = preset.adjustable.contains(entry[0]);
			cells.add(new Field.Grid.Cell(entry[0], entry[1], on ? "✔" : "", !on, on ? ACCENT_ON : 0,
				entry[2] + (on ? ": users may change it" : ": set here only")));
		}
		return cells;
	}

	/** How many letters of the match written out fit a line of the menu. */
	private static final int SUMMARY_WIDTH = 50;

	/** Words broken into lines of at most {@code width} letters, at spaces. */
	static List<String> wrap(String words, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : words.split(" ")) {
			if (line.length() > 0 && line.length() + 1 + word.length() > width) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	/** The two ways out, an exit portal and the leave command, as the one choice they are. */
	private enum WaysHome {
		BOTH, PORTAL, COMMAND, NEITHER;

		static WaysHome of(Preset preset) {
			if (preset.exitPortal) return preset.exitCommand ? BOTH : PORTAL;
			return preset.exitCommand ? COMMAND : NEITHER;
		}

		static void apply(Preset preset, WaysHome ways) {
			preset.exitPortal = ways == BOTH || ways == PORTAL;
			preset.exitCommand = ways == BOTH || ways == COMMAND;
		}
	}

	private static String perPlayer(int tenths) {
		if (tenths == 0) return "Remove";
		String count = tenths % 10 == 0 ? String.valueOf(tenths / 10) : String.valueOf(tenths / 10.0);
		return count + " per player";
	}

	private static String mobLevelWords(int level) {
		return switch (Preset.MobLevel.values()[level]) {
			case OFF -> "Off";
			case RARE -> "Rare";
			case NORMAL -> "Normal";
			case COMMON -> "Common";
		};
	}

	public static String killTypeLabel(Preset.KillType type) {
		return switch (type) {
			case FIRST_BLOOD -> "First blood";
			case MELEE -> "Up close";
			case RANGED -> "From range";
			case KNOCKOUT -> "Knocked off";
			case REVENGE -> "Revenge";
			case SHUTDOWN -> "Shutdown";
			case STREAK_3 -> "Three in a row";
			case STREAK_5 -> "Five in a row";
			case STREAK_10 -> "Ten in a row";
			case DOUBLE_KILL -> "Double kill";
			case TRIPLE_KILL -> "Triple kill";
			case LONG_SHOT -> "Long shot";
			case TRAP -> "Trapper";
			case AVENGER -> "Avenger";
			case CARRIER -> "Carrier down";
		};
	}

	public static String featLabel(Preset.Feat feat) {
		return switch (feat) {
			case ASSIST -> "Assist";
			case CAPTURE -> "Flag captured";
			case HILL_TAKEN -> "Took the hill";
			case HILL_MINUTE -> "A minute on the hill";
			case WAVE_CLEARED -> "Wave cleared";
			case FLAWLESS_WAVE -> "Flawless wave";
			case BIG_GAME -> "Big game";
			case SURVIVOR -> "Survivor";
		};
	}

	private static String featHelp(Preset.Feat feat) {
		return switch (feat) {
			case ASSIST -> "Hurting somebody in the moments before somebody else killed them";
			case CAPTURE -> "Bringing their flag home, to whoever carried it";
			case HILL_TAKEN -> "Getting onto the hill with nobody else on it, to everyone on it";
			case HILL_MINUTE -> "Every full minute a side holds the hill, to whoever is on it";
			case WAVE_CLEARED -> "Every wave cleared, to everyone still standing when it falls";
			case FLAWLESS_WAVE -> "A wave cleared with nobody falling at all, on top of the wave";
			case BIG_GAME -> "Taking down a giant, a warden, a ravager or an evoker";
			case SURVIVOR -> "Every five minutes without dying";
		};
	}

	/** Whether a feat can happen in this preset's game at all. */
	private static boolean featApplies(Preset p, Preset.Feat feat) {
		return switch (feat) {
			case ASSIST -> p.pvp;
			case CAPTURE -> p.activeGoal() == Preset.Goal.CTF;
			case HILL_TAKEN, HILL_MINUTE -> p.activeGoal() == Preset.Goal.HILL;
			case WAVE_CLEARED, FLAWLESS_WAVE -> p.mobsCome() && p.mobStyle == Preset.MobStyle.WAVES;
			case BIG_GAME -> p.mobsCome();
			case SURVIVOR -> true;
		};
	}

	private static String killTypeHelp(Preset.KillType type) {
		return switch (type) {
			case FIRST_BLOOD -> "The arena's first kill";
			case MELEE -> "A kill by hand or blade";
			case RANGED -> "A kill by arrow, trident or anything thrown";
			case KNOCKOUT -> "Into the void, lava or off a height, after your hit";
			case REVENGE -> "Killing whoever last killed you";
			case SHUTDOWN -> "Ending somebody's streak of three or more";
			case STREAK_3, STREAK_5, STREAK_10 -> "Kills without dying in between";
			case DOUBLE_KILL -> "Two kills within ten seconds";
			case TRIPLE_KILL -> "Three or more kills within ten seconds of each other";
			case LONG_SHOT -> "A kill by arrow or anything thrown from forty blocks or more";
			case TRAP -> "A kill by a block you put down: spikes, magma, TNT";
			case AVENGER -> "Killing whoever just killed a teammate";
			case CARRIER -> "Killing somebody carrying a flag";
		};
	}

	/** Capture the flag and the rest are there to pick only once the preset has teams. */
	/** What a preset is played for, in a word, for a list with no room for the whole phrase. */
	public static String goalWord(Preset preset) {
		return switch (preset.activeGoal()) {
			case TIME -> preset.creative() ? "Building" : preset.livesOn() ? "Last standing" : "No goal";
			case KILLS -> "Kills";
			case MOBS -> "Mob hunt";
			case CTF -> "Flags";
			case TAKEOVER -> "Takeover";
			case DESTRUCTION -> "Base raid";
			case WAVES -> "Waves";
			case HILL -> "The hill";
			case BANK -> "Banking";
			case RACE -> "Race";
		};
	}

	/** How long it lasts, in as few letters as it takes. */
	public static String shortDuration(int minutes) {
		if (minutes <= 0) return "no end";
		if (minutes % 60 == 0) return minutes / 60 + "h";
		if (minutes > 60) return minutes / 60 + "h" + minutes % 60;
		return minutes + "m";
	}

	private static List<Field.Choice.Option> goalOptions(Preset preset) {
		List<Field.Choice.Option> options = new ArrayList<>();
		options.add(new Field.Choice.Option("time", "Lasting till time's up"));
		if (preset.pvp) options.add(new Field.Choice.Option("kills", "Kill count"));
		options.add(new Field.Choice.Option("mobs", "Mob kills"));
		if (preset.mobStyle == Preset.MobStyle.WAVES) options.add(new Field.Choice.Option("waves", "Survive the waves"));
		options.add(new Field.Choice.Option("hill", "King of the hill"));
		options.add(new Field.Choice.Option("race", "Race"));
		if (preset.teamsOn()) {
			options.add(new Field.Choice.Option("ctf", "Capture the flag"));
			options.add(new Field.Choice.Option("takeover", "Colour takeover"));
			options.add(new Field.Choice.Option("destruction", "Destroy their base"));
			options.add(new Field.Choice.Option("bank", "Banking"));
		}
		return options;
	}

	private static List<Field.Choice.Option> chestAccessOptions(Preset preset) {
		List<Field.Choice.Option> options = new ArrayList<>();
		options.add(new Field.Choice.Option("open", "Open to anyone"));
		options.add(new Field.Choice.Option("shut", "Shut to them"));
		if (justfatlard.pvp_dimensions.integration.LootEnder.INSTALLED || preset.chestAccess == Preset.ChestAccess.PICKABLE) {
			options.add(new Field.Choice.Option("pickable", "Lockpicked open"));
		}
		return options;
	}

	/** "Thick fog (6 chunks)", and so on. */
	public static String fogWords(int chunks) {
		String how = switch (chunks) {
			case 0 -> "None";
			case 12 -> "Light haze";
			case 8 -> "Haze";
			case 6 -> "Fog";
			case 4 -> "Thick fog";
			case 3 -> "Heavy fog";
			case 2 -> "Pea soup";
			default -> "Fog";
		};
		return chunks == 0 ? how : how + " (" + chunks * 16 + " blocks)";
	}

	private static List<Field.Choice.Option> mobGoalOptions(Preset preset) {
		List<Field.Choice.Option> options = new ArrayList<>();
		options.add(new Field.Choice.Option("any", "Any hostile mob"));
		for (String kind : MobKinds.of(preset.world)) options.add(new Field.Choice.Option(kind, MobKinds.name(kind)));
		return options;
	}

	private static List<Field.Choice.Option> scopeOptions(Preset preset) {
		List<Field.Choice.Option> options = new ArrayList<>();
		options.add(new Field.Choice.Option("player", "Each player"));
		if (preset.teamsOn()) options.add(new Field.Choice.Option("team", "Each team"));
		options.add(new Field.Choice.Option("everyone", "Everyone together"));
		return options;
	}

	public static String defaultBiome(Preset.World world) {
		return switch (world) {
			case OVERWORLD -> "minecraft:plains";
			case NETHER -> "minecraft:nether_wastes";
			case END -> "minecraft:end_highlands";
		};
	}

	/** The biomes an arena in this world may be, as the game's own tags list them, by name. */
	public static List<Field.Choice.Option> biomeOptions(Preset.World world) {
		MinecraftServer server = PvpDimensions.server();
		if (server == null) return List.of(new Field.Choice.Option(defaultBiome(world), biomeName(defaultBiome(world))));
		TagKey<Biome> tag = switch (world) {
			case OVERWORLD -> BiomeTags.IS_OVERWORLD;
			case NETHER -> BiomeTags.IS_NETHER;
			case END -> BiomeTags.IS_END;
		};
		List<Field.Choice.Option> options = new ArrayList<>();
		server.registryAccess().lookupOrThrow(Registries.BIOME).get(tag).ifPresent(set -> set.forEach(holder ->
			holder.unwrapKey().ifPresent(key -> {
				String id = key.identifier().toString();
				options.add(new Field.Choice.Option(id, biomeName(id)));
			})));
		options.sort(Comparator.comparing(Field.Choice.Option::label));
		return options;
	}

	public static String biomeName(String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return id;
		return Language.getInstance().getOrDefault("biome." + parsed.getNamespace() + "." + parsed.getPath(), parsed.getPath());
	}

	public static String blockName(String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return id;
		return Language.getInstance().getOrDefault("block." + parsed.getNamespace() + "." + parsed.getPath(), parsed.getPath());
	}

	private static List<Field.Choice.Option> savedOptions() {
		List<Field.Choice.Option> options = new ArrayList<>();
		for (String name : SavedTerrains.names()) options.add(new Field.Choice.Option(name, name));
		return options;
	}
}
