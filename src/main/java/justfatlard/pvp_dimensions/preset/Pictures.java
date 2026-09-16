package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import justfatlard.pvp_dimensions.world.Divisions;

/**
 * The editor's two pictures, drawn from a preset before any arena exists: the arena from above,
 * with its walls, bases, exits and spawns where the arena will put them; and the match's clock,
 * with everything on it that happens at a time.
 */
public final class Pictures {
	private Pictures() {}

	/**
	 * Cells across the map; walls are drawn two cells thick, whatever the arena's real size. The
	 * canvas draws a cell a whole number of pixels, so this divides the map's 84 into twos.
	 */
	private static final int MAP = 40;

	private static final int GROUND_OVERWORLD = 0xFF5F9E3A;
	private static final int GROUND_NETHER = 0xFF8B3A34;
	private static final int GROUND_END = 0xFFD9D39B;
	private static final int BEDROCK = 0xFF2E2E2E;
	private static final int PLAIN_WALL = 0xFFA9C4D6;
	private static final int BASE = 0xFF8A5A2B;
	private static final int EXIT = 0xFF7B3FD1;
	private static final int SPAWN = 0xFFFFFFFF;
	private static final int HILL = 0xFFF2C12E;
	private static final int FINISH = 0xFF2ECC71;

	public static Field.Picture.Canvas map(Preset p) {
		Palette palette = new Palette();
		int ground = palette.add(switch (p.world) {
			case OVERWORLD -> GROUND_OVERWORLD;
			case NETHER -> GROUND_NETHER;
			case END -> GROUND_END;
		});
		Divisions divisions = p.source == Preset.Source.SAVED ? Divisions.NONE : Divisions.of(p.divisions, p.divisionShape == Preset.DivisionShape.PIE);
		boolean teamWalls = p.teamsOn() && p.teamWalls;
		int[] partGround = new int[divisions.parts()];
		int[] partWall = new int[divisions.parts()];
		for (int part = 0; part < divisions.parts(); part++) {
			int team = TeamColors.of(part % Math.max(1, p.teams)).team().rgb();
			partGround[part] = p.teamsOn() && divisions.parts() > 1 ? palette.add(mix(0xFF000000 | palette.colour(ground), 0xFF000000 | team, 0.18)) : ground;
			partWall[part] = palette.add(teamWalls ? 0xFF000000 | team : PLAIN_WALL);
		}
		byte[] cells = new byte[MAP * MAP];
		boolean bedrockWalls = p.bedrock == Preset.Bedrock.BOTTOM_WALLS || p.bedrock == Preset.Bedrock.SHELL;
		int bedrock = palette.add(BEDROCK);
		for (int z = 0; z < MAP; z++) {
			for (int x = 0; x < MAP; x++) {
				int part = divisions.part(MAP, x, z);
				int colour = divisions.wall(MAP, x, z) ? partWall[part] : partGround[part];
				if (bedrockWalls && (x == 0 || z == 0 || x == MAP - 1 || z == MAP - 1)) colour = bedrock;
				cells[z * MAP + x] = (byte) colour;
			}
		}

		List<Field.Picture.Key> key = new ArrayList<>();
		int blocks = p.size * 16;
		key.add(new Field.Picture.Key(p.size + " chunks across", 0xFF404040));
		if (divisions.parts() > 1) {
			key.add(new Field.Picture.Key(divisions.slices() > 0 ? divisions.parts() + " slices, equal ground"
				: divisions.rows() > 1 ? divisions.cols() + " by " + divisions.rows() : divisions.parts() + " parts",
				teamWalls ? 0xFF404040 : darker(PLAIN_WALL)));
		}
		Preset.Goal goal = p.activeGoal();
		boolean bases = goal == Preset.Goal.CTF || goal == Preset.Goal.DESTRUCTION || goal == Preset.Goal.BANK;
		int teams = p.teamsOn() ? p.teams : 0;
		if (p.basesOn()) {
			int reach = Math.clamp(Math.round(MAP * 7f / Math.max(32, blocks)), 2, 5);
			if (teams > 0) {
				for (int team = 0; team < teams; team++) {
					int[] at = spot(divisions, team, teams, 0.3);
					ring(cells, at, reach, palette.add(darker(0xFF000000 | TeamColors.of(team).team().rgb())));
				}
			} else {
				ring(cells, new int[] {MAP / 2 - 1, MAP / 2 - 1}, reach, palette.add(darker(PLAIN_WALL)));
			}
			String style = p.teamBases.name().toLowerCase(Locale.ROOT);
			key.add(new Field.Picture.Key(teams > 0 ? style + "s" : "a " + style + " for everyone", 0xFF404040));
		}
		if (bases) {
			int mark = palette.add(BASE);
			for (int team = 0; team < teams; team++) dot(cells, spot(divisions, team, teams, 0.3), mark, 3);
			key.add(new Field.Picture.Key(switch (goal) {
				case CTF -> "flag chests";
				case BANK -> "banks";
				default -> "bases";
			}, BASE));
		}
		if (goal == Preset.Goal.HILL || goal == Preset.Goal.RACE) {
			boolean hill = goal == Preset.Goal.HILL;
			boolean middle = (hill ? p.hillPlace : p.finishPlace) == Preset.MarkerPlace.CENTER;
			if (middle) dot(cells, new int[] {MAP / 2 - 1, MAP / 2 - 1}, palette.add(hill ? HILL : FINISH), 3);
			key.add(new Field.Picture.Key((hill ? "the hill" : "the finish") + (middle ? "" : ", anywhere"), hill ? darker(HILL) : darker(FINISH)));
		}
		if (p.spawn != Preset.Spawn.RANDOM) {
			int mark = palette.add(SPAWN);
			if (p.spawn == Preset.Spawn.CENTER || teams == 0) dot(cells, new int[] {MAP / 2 - 1, MAP / 2 - 1}, mark, 2);
			else for (int team = 0; team < teams; team++) {
				int[] at = spot(divisions, team, teams, 0.32);
				dot(cells, new int[] {at[0], at[1] + 4}, mark, 2);
			}
			key.add(new Field.Picture.Key("spawns", 0xFF909090));
		}
		if (p.exitPortal) {
			int mark = palette.add(EXIT);
			if (divisions.parts() > 1) {
				for (int part = 0; part < divisions.parts(); part++) {
					int[] at = divisions.center(MAP, part);
					dot(cells, new int[] {at[0] + 4, at[1]}, mark, 2);
				}
			} else {
				dot(cells, new int[] {MAP / 2 + Math.max(3, 6 * MAP / blocks), MAP / 2}, mark, 2);
			}
			key.add(new Field.Picture.Key(divisions.parts() > 1 ? "an exit in each part" : "exit", EXIT));
		}
		return new Field.Picture.Canvas(MAP, MAP, palette.colours(), cells, key);
	}

	/** Where a team's base or spawn goes: its part's middle, or its point on the ring round the middle. */
	private static int[] spot(Divisions divisions, int team, int teams, double ring) {
		if (divisions.parts() > 1) return divisions.center(MAP, team % divisions.parts());
		double angle = Math.PI * 2 * team / Math.max(1, teams) + Math.PI / 4;
		return new int[] {(int) (MAP / 2.0 + Math.cos(angle) * MAP * ring), (int) (MAP / 2.0 + Math.sin(angle) * MAP * ring)};
	}

	/** A square outline round a point: a building, from above. */
	private static void ring(byte[] cells, int[] at, int reach, int colour) {
		for (int dz = -reach; dz <= reach; dz++) {
			for (int dx = -reach; dx <= reach; dx++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != reach) continue;
				int x = Math.clamp(at[0] + dx, 0, MAP - 1);
				int z = Math.clamp(at[1] + dz, 0, MAP - 1);
				cells[z * MAP + x] = (byte) colour;
			}
		}
	}

	private static void dot(byte[] cells, int[] at, int colour, int size) {
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = Math.clamp(at[0] + dx, 0, MAP - 1);
				int z = Math.clamp(at[1] + dz, 0, MAP - 1);
				cells[z * MAP + x] = (byte) colour;
			}
		}
	}

	// --- The clock ---

	/** Cells across the clock: a minute each for an hour's match, and so on in proportion. */
	private static final int CLOCK = 60;
	private static final int LANE = 3;
	private static final int TRACK = 0xFF3A3A3A;
	private static final int WALLS = 0xFF7EC8E3;
	private static final int BORDER = 0xFFB03A3A;
	private static final int MOBS = 0xFF4E9A3A;
	private static final int PINATA = 0xFFE36AD0;
	private static final int RAIN = 0xFF5A7FA8;
	private static final int THUNDER = 0xFF6A4FA3;

	public static Field.Picture.Canvas clock(Preset p) {
		int minutes = p.lifeMinutes > 0 ? p.lifeMinutes : 60;
		List<int[]> lanes = new ArrayList<>();
		List<Field.Picture.Key> key = new ArrayList<>();
		Palette palette = new Palette();
		int track = palette.add(TRACK);

		if (p.divisions > 1 && p.wallsFall > 0 && p.source == Preset.Source.GENERATE) {
			int colour = palette.add(WALLS);
			int[] lane = blank(track);
			fill(lane, 0, at(p.wallsFall, minutes), colour);
			lanes.add(lane);
			key.add(new Field.Picture.Key("walls up", darker(WALLS)));
		}
		if (p.borderShrink > 0 && p.lifeMinutes > 0) {
			int[] lane = blank(track);
			for (int i = 0; i < CLOCK; i++) lane[i] = palette.add(mix(TRACK, BORDER, (i + 1) / (double) CLOCK));
			lanes.add(lane);
			key.add(new Field.Picture.Key("border closing", BORDER));
		}
		if (p.mobStyle == Preset.MobStyle.WAVES && !p.waves.isEmpty()) {
			int colour = palette.add(MOBS);
			int faded = palette.add(mix(TRACK, MOBS, 0.5));
			int[] lane = blank(track);
			int count = p.waves.size();
			boolean more = p.afterWaves != Preset.AfterWaves.STOP && p.activeGoal() != Preset.Goal.WAVES;
			int span = Math.max(2, CLOCK / (count + (more ? 2 : 1)));
			int x = 1;
			for (int i = 0; x < CLOCK; i++) {
				boolean extra = i >= count;
				if (extra && !more) break;
				fill(lane, x, Math.min(CLOCK, x + span - 1), extra ? faded : colour);
				x += span;
			}
			lanes.add(lane);
			key.add(new Field.Picture.Key(count + (count == 1 ? " wave" : " waves") + (more ? ", then more" : ""), MOBS));
		} else if (p.activeMobs() != Preset.MobTime.OFF) {
			int colour = palette.add(MOBS);
			int[] lane = blank(track);
			if (p.activeMobs() == Preset.MobTime.ALWAYS) fill(lane, 0, CLOCK, colour);
			else for (int i = 0; i < CLOCK; i += 2) lane[i] = colour;
			lanes.add(lane);
			key.add(new Field.Picture.Key(p.activeMobs() == Preset.MobTime.ALWAYS ? "mobs" : "mobs at night", MOBS));
		}
		if (!p.modeChanges.isEmpty()) {
			int[] lane = blank(track);
			Preset.GameRule mode = p.gameMode;
			int from = 0;
			for (Preset.ModeChange change : p.modeChanges.stream().sorted(java.util.Comparator.comparingInt(c -> c.minute)).toList()) {
				int to = at(change.minute, minutes);
				fill(lane, from, to, palette.add(modeColour(mode)));
				from = to;
				mode = change.mode;
			}
			fill(lane, from, CLOCK, palette.add(modeColour(mode)));
			lanes.add(lane);
			key.add(new Field.Picture.Key("game mode", darker(modeColour(Preset.GameRule.CREATIVE))));
		}
		if (p.world == Preset.World.OVERWORLD && (p.weather != Preset.Weather.OUTSIDE || p.weatherLater != Preset.Weather.OUTSIDE)) {
			int[] lane = blank(track);
			int change = p.weatherLater == Preset.Weather.OUTSIDE ? CLOCK : at(p.weatherAfter, minutes);
			fill(lane, 0, change, weatherColour(palette, p.weather, track));
			if (change < CLOCK) fill(lane, change, CLOCK, weatherColour(palette, p.weatherLater, track));
			lanes.add(lane);
			key.add(new Field.Picture.Key("weather", RAIN));
		}
		if (p.activeGoal() == Preset.Goal.HILL && p.hillMoves > 0) {
			int colour = palette.add(HILL);
			int[] lane = blank(track);
			for (int minute = p.hillMoves; minute < minutes; minute += p.hillMoves) lane[Math.min(CLOCK - 1, at(minute, minutes))] = colour;
			lanes.add(lane);
			key.add(new Field.Picture.Key("the hill moves", darker(HILL)));
		}
		if (Fields.PINATA_INSTALLED && p.pinata != Preset.PinataMode.OFF) {
			int colour = palette.add(PINATA);
			int[] lane = blank(track);
			lane[0] = colour;
			if (p.pinata != Preset.PinataMode.ONCE) {
				for (int minute = p.pinataMinutes; minute < minutes; minute += p.pinataMinutes) lane[Math.min(CLOCK - 1, at(minute, minutes))] = colour;
			}
			lanes.add(lane);
			key.add(new Field.Picture.Key(p.pinata == Preset.PinataMode.ONCE ? "a pinata" : "pinatas", PINATA));
		}

		int rows = Math.max(1, lanes.size()) * (LANE + 1) - 1;
		byte[] cells = new byte[CLOCK * rows];
		int gap = palette.add(0x00000000);
		java.util.Arrays.fill(cells, (byte) gap);
		for (int l = 0; l < lanes.size(); l++) {
			for (int r = 0; r < LANE; r++) {
				for (int i = 0; i < CLOCK; i++) cells[(l * (LANE + 1) + r) * CLOCK + i] = (byte) lanes.get(l)[i];
			}
		}
		key.add(0, new Field.Picture.Key(p.lifeMinutes > 0 ? "0 to " + Fields.duration(minutes).toLowerCase(Locale.ROOT) : "an hour of a match that never ends", 0xFF404040));
		if (lanes.isEmpty()) key.add(new Field.Picture.Key("nothing on the clock", 0xFF707070));
		return new Field.Picture.Canvas(CLOCK, rows, palette.colours(), cells, key);
	}

	/** Creative bright, survival and adventure in quieter colours of their own. */
	private static int modeColour(Preset.GameRule mode) {
		return switch (mode) {
			case CREATIVE -> 0xFFE8C85A;
			case SURVIVAL -> 0xFFB05A4A;
			case ADVENTURE -> 0xFF5A8AB0;
		};
	}

	/** Rain and thunder in their colours; clear, or the weather outside, as the bare track. */
	private static int weatherColour(Palette palette, Preset.Weather weather, int track) {
		return switch (weather) {
			case RAIN -> palette.add(RAIN);
			case THUNDER -> palette.add(THUNDER);
			case CLEAR -> palette.add(0xFF9FC9E8);
			case OUTSIDE -> track;
		};
	}

	private static int at(int minute, int of) {
		return Math.clamp(Math.round(minute * CLOCK / (float) of), 0, CLOCK);
	}

	private static int[] blank(int track) {
		int[] lane = new int[CLOCK];
		java.util.Arrays.fill(lane, track);
		return lane;
	}

	private static void fill(int[] lane, int from, int to, int colour) {
		for (int i = Math.max(0, from); i < Math.min(CLOCK, to); i++) lane[i] = colour;
	}

	// --- Colours ---

	private static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	private static int darker(int colour) {
		return mix(colour, 0xFF000000, 0.45);
	}

	/** The colours a picture uses, each given an index the first time it's asked for. */
	private static final class Palette {
		private final List<Integer> colours = new ArrayList<>();

		int add(int colour) {
			int at = colours.indexOf(colour);
			if (at >= 0) return at;
			if (colours.size() >= 255) return 0;
			colours.add(colour);
			return colours.size() - 1;
		}

		int colour(int index) {
			return colours.get(index);
		}

		int[] colours() {
			return colours.stream().mapToInt(Integer::intValue).toArray();
		}
	}
}
