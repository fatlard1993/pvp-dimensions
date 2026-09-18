package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A preset written out as the match it makes, in a few plain sentences, and every place the game
 * plays a setting differently from how it reads: made from the preset each time, so it can't
 * drift from it.
 *
 * <p>The overrides are all right in themselves, each there for a reason; what was wrong was the
 * editor showing the setting as chosen while the arena did something else. Each is said here, and
 * again beside the setting it overrules.
 */
public final class Summary {
	private Summary() {}

	/** The match in sentences, then the overrides, each as its own line. */
	public static List<String> sentences(Preset p) {
		List<String> said = new ArrayList<>();
		said.add(who(p) + " " + goal(p) + ".");
		said.add(where(p) + ".");
		said.add(length(p) + ".");
		said.add(deaths(p) + ".");
		String mobs = mobs(p);
		if (mobs != null) said.add(mobs + ".");
		String weather = weather(p);
		if (weather != null) said.add(weather + ".");
		said.add(gear(p) + ".");
		said.add(waysHome(p) + ".");
		return said;
	}

	/** Every override that applies to this preset. */
	public static List<String> overrides(Preset p) {
		List<String> all = new ArrayList<>();
		for (String line : new String[] {huntBringsMobs(p), kindsAllOff(p), livesRespawnInside(p), playsInSurvival(p), creativeKeepsApart(p),
				pooledLivesAlone(p), teammateNamesAlone(p), netherKeepsCavern(p), noWayOut(p), hordeNeverRises(p), basesNeedSides(p),
				picksNeedLootEnder(p), noSkyForWeather(p)}) {
			if (line != null) all.add(line);
		}
		return all;
	}

	// --- The overrides, each shown beside the setting it overrules ---

	public static @Nullable String huntBringsMobs(Preset p) {
		return p.mobStyle == Preset.MobStyle.STEADY && p.activeGoal() == Preset.Goal.MOBS && p.mobs == Preset.MobTime.OFF
			? "The hunt brings mobs day and night anyway" : null;
	}

	public static @Nullable String kindsAllOff(Preset p) {
		if (p.activeMobs() == Preset.MobTime.OFF || p.anyMobKind()) return null;
		if (p.activeGoal() != Preset.Goal.MOBS) return "Every kind is off, so no mobs will come";
		return p.mobGoalKind.equals("any") ? "Every kind is off: the hunt brings them all anyway" : "Every kind is off: only the hunted kind comes";
	}

	public static @Nullable String livesRespawnInside(Preset p) {
		return p.livesOn() && !p.deathCapture ? "With lives on, deaths come back inside anyway" : null;
	}

	public static @Nullable String playsInSurvival(Preset p) {
		return p.gameMode == Preset.GameRule.ADVENTURE && p.needsSurvival() ? "Plays in survival: this goal needs digging and building" : null;
	}

	public static @Nullable String creativeKeepsApart(Preset p) {
		return p.creative() && p.traversal != Preset.Traversal.ISOLATED ? "Creative keeps everything apart and lets nothing out" : null;
	}

	public static @Nullable String pooledLivesAlone(Preset p) {
		return p.lives == Preset.Lives.TEAM_POOL && !p.teamsOn() ? "Free for all, so each player has their own" : null;
	}

	public static @Nullable String teammateNamesAlone(Preset p) {
		return p.nameTags == Preset.NameTags.TEAMMATES && !p.teamsOn() ? "Free for all, so nobody's name shows" : null;
	}

	public static @Nullable String netherKeepsCavern(Preset p) {
		return p.source == Preset.Source.GENERATE && p.roofed() ? "Natural nether ground keeps its whole cavern, roof to floor" : null;
	}

	public static @Nullable String noWayOut(Preset p) {
		if (p.exitPortal || p.exitCommand) return null;
		return p.respawnsInside() ? "Nobody can leave until it ends" : "Nobody can leave until it ends, but by dying";
	}

	public static @Nullable String hordeNeverRises(Preset p) {
		return p.hordeOn() && !p.canBeOut() ? "Nobody is ever out with deaths coming back and no lives, so nobody rises" : null;
	}

	public static @Nullable String basesNeedSides(Preset p) {
		return p.teamBases != Preset.BaseStyle.NONE && !p.basesOn() ? "Everyone for themselves, fighting: no bases are built" : null;
	}

	public static @Nullable String picksNeedLootEnder(Preset p) {
		return p.chestAccess == Preset.ChestAccess.PICKABLE && !justfatlard.pvp_dimensions.integration.LootEnder.picking()
			? "Loot Ender's lockpicking is off or missing here, so team chests stay open" : null;
	}

	public static @Nullable String noSkyForWeather(Preset p) {
		boolean set = p.weather != Preset.Weather.OUTSIDE || p.weatherLater != Preset.Weather.OUTSIDE;
		return set && p.world != Preset.World.OVERWORLD ? "The nether and the end have no weather" : null;
	}

	// --- The sentences ---

	private static String who(Preset p) {
		String sides = p.teamsOn() ? teamNames(p) : "Everyone for themselves";
		if (!p.pvp) return (p.teamsOn() ? sides + ", side by side," : "Everyone, side by side,") + " against the mobs,";
		return sides + (p.teamsOn() ? " fight" : ", fighting,");
	}

	private static String teamNames(Preset p) {
		if (p.teams > 3) return p.teams + " teams";
		List<String> names = new ArrayList<>();
		for (int i = 0; i < p.teams; i++) names.add(TeamColors.of(i).name());
		return String.join(p.teams == 2 ? " and " : ", ", names);
	}

	private static String goal(Preset p) {
		return switch (p.activeGoal()) {
			case TIME -> p.livesOn() ? "until one side is left" : "until time is up";
			case KILLS -> p.killTarget > 0 ? "to " + p.killTarget + " kills" : "for the most kills";
			case MOBS -> "to take down " + p.mobGoalCount + " " + mobWord(p) + (p.mobScope() == Preset.Scope.EVERYONE ? " together"
				: p.mobScope() == Preset.Scope.TEAM ? ", team by team" : ", each for themselves");
			case CTF -> "to capture the flag, " + p.captures + (p.captures == 1 ? " capture" : " captures") + " to win";
			case TAKEOVER -> p.takeoverPercent > 0 ? "to cover " + p.takeoverPercent + "% of the ground in their colour" : "to cover the most ground in their colour";
			case DESTRUCTION -> "to break the other bases and keep theirs";
			case WAVES -> "to survive " + p.waves.size() + (p.waves.size() == 1 ? " wave" : " waves");
			case HILL -> (p.hillTarget > 0 ? "to hold the hill for " + Fields.duration(p.hillTarget).toLowerCase(Locale.ROOT)
				: "to hold the hill longest") + (p.hillMoves > 0 ? ", which moves every " + Fields.duration(p.hillMoves).toLowerCase(Locale.ROOT) : "");
			case BANK -> p.bankTarget > 0 ? "to bank " + p.bankTarget + " points" : "to have the richest bank";
			case RACE -> "to race to the finish" + switch (p.finishStyle) {
				case GROUND -> "";
				case TOWER -> " atop a tower";
				case SKY -> " up in the sky";
				case BURIED -> " buried underground";
			};
			case SPY -> "to find the one who was not told where they are";
			case HITS -> p.hitTarget > 0 ? "to land " + p.hitTarget + " hits" : "to land the most hits";
		};
	}

	private static String mobWord(Preset p) {
		if (p.mobGoalKind.equals("any")) return "mobs";
		String name = MobKinds.name(p.mobGoalKind).toLowerCase(Locale.ROOT);
		return name.endsWith("s") ? name : name + "s";
	}

	private static String where(Preset p) {
		if (p.source == Preset.Source.SAVED) return "On saved ground" + (p.saved.isEmpty() ? "" : ", " + p.saved);
		String world = switch (p.world) {
			case OVERWORLD -> "";
			case NETHER -> " nether";
			case END -> " end";
		};
		String ground = (p.shape == Preset.Shape.FLAT ? "Flat" + world + " ground" : "Natural" + world + " ground")
			+ " (" + Fields.biomeName(p.biome) + "), " + p.size + " chunks across";
		if (p.divisions > 1) {
			ground += ", cut into " + p.divisions + (p.divisionShape == Preset.DivisionShape.PIE && p.divisions > 2 ? " slices" : " parts");
			if (p.wallsFall > 0) ground += " whose walls fall after " + Fields.duration(p.wallsFall).toLowerCase(Locale.ROOT)
				+ (p.wallsHold ? " and hold till then" : "");
		}
		if (p.basesOn()) {
			String style = p.teamBases.name().toLowerCase(Locale.ROOT);
			ground += p.teamsOn() ? ", each team starting in a " + style : ", with a " + style + " in the middle for everyone";
		}
		if (p.fog > 0) ground += ", in " + Fields.fogWords(p.fog).toLowerCase(Locale.ROOT);
		return ground;
	}

	private static String length(Preset p) {
		if (p.lifeMinutes <= 0) return "No time limit";
		String line = "Lasts " + Fields.duration(p.lifeMinutes).toLowerCase(Locale.ROOT);
		if (p.activeGoal() != Preset.Goal.TIME && p.activeGoal() != Preset.Goal.WAVES) line += "; the leader then wins";
		if (p.borderShrink > 0) line += ", the border closing in as it goes";
		if (!p.modeChanges.isEmpty()) {
			List<String> changes = new ArrayList<>();
			for (Preset.ModeChange change : p.modeChanges.stream().sorted(java.util.Comparator.comparingInt(c -> c.minute)).toList()) {
				changes.add(modeWord(change.mode).toLowerCase(Locale.ROOT) + " after " + Fields.duration(change.minute).toLowerCase(Locale.ROOT)
					+ (change.kits ? " with fresh kits" : ""));
			}
			line += "; starts in " + modeWord(p.gameMode).toLowerCase(Locale.ROOT) + ", then " + String.join(", then ", changes);
		}
		return line;
	}

	private static String deaths(Preset p) {
		String lives = p.livesOn() ? "; " + p.livesCount + (p.livesCount == 1 ? " life" : " lives") + (p.pooledLives() ? " per team" : " each") : "";
		if (p.hordeOn() && p.canBeOut()) lives += "; the fallen rise as zombies to hunt the rest";
		if (Fields.DEAD_HEADS_INSTALLED && p.headLock >= 0) {
			lives += p.headLock == 0 ? "; what the dead leave is anyone's at once"
				: "; what the dead leave is theirs for " + Fields.duration(p.headLock).toLowerCase(Locale.ROOT);
		}
		if (!p.respawnsInside()) return "A death goes home" + lives;
		String kit = switch (p.respawnKit) {
			case NONE -> "with nothing";
			case ENTRY -> "with the entry kit";
			case CUSTOM -> "with a respawn kit";
		};
		return "Deaths come back inside " + kit + lives;
	}

	private static @Nullable String mobs(Preset p) {
		if (p.mobStyle == Preset.MobStyle.WAVES) {
			return "Mobs in " + p.waves.size() + (p.waves.size() == 1 ? " wave" : " waves") + ", " + p.waveBreak + " seconds apart";
		}
		return switch (p.activeMobs()) {
			case OFF -> null;
			case NIGHT -> "Mobs at night";
			case ALWAYS -> "Mobs day and night";
		};
	}

	private static @Nullable String weather(Preset p) {
		if (p.world != Preset.World.OVERWORLD) return null;
		boolean changes = p.weatherLater != Preset.Weather.OUTSIDE && p.weatherLater != p.weather;
		if (p.weather == Preset.Weather.OUTSIDE && !changes) return null;
		String first = p.weather == Preset.Weather.OUTSIDE ? "The weather outside" : weatherWord(p.weather);
		if (!changes) return first + " throughout";
		return first + ", turning to " + weatherWord(p.weatherLater).toLowerCase(Locale.ROOT) + " after " + Fields.duration(p.weatherAfter).toLowerCase(Locale.ROOT);
	}

	private static String modeWord(Preset.GameRule mode) {
		return switch (mode) {
			case SURVIVAL -> "Survival";
			case ADVENTURE -> "Adventure";
			case CREATIVE -> "Creative";
		};
	}

	private static String weatherWord(Preset.Weather weather) {
		return switch (weather) {
			case OUTSIDE -> "The weather outside";
			case CLEAR -> "Clear skies";
			case RAIN -> "Rain";
			case THUNDER -> "Thunderstorms";
		};
	}

	private static String gear(Preset p) {
		String carried = switch (p.creative() ? Preset.Traversal.ISOLATED : p.traversal) {
			case FREE -> "Everyone keeps their own things";
			case ISOLATED -> "What you carry waits outside";
			case REWARDS -> "What you carry waits outside; a shortlist comes home";
		};
		int prizes = (int) p.prizes.stream().filter(prize -> !prize.isEmpty()).count();
		if (prizes > 0) carried += prizes == 1 ? "; winners take a prize" : "; winners take one of " + prizes + " prizes";
		if (p.picksLoadout()) carried += "; players pick from " + p.loadoutCount() + " loadouts";
		if (!p.entryFee.isEmpty()) carried += "; it costs " + p.entryFee.describe(3) + " to come in" + (p.feesToWinners ? ", the winners sharing the pot" : "");
		return carried;
	}

	private static String waysHome(Preset p) {
		if (p.exitPortal && p.exitCommand) return "Leave through an exit portal or with /pvp leave";
		if (p.exitPortal) return "Leave through an exit portal";
		if (p.exitCommand) return "Leave with /pvp leave";
		return "No way out until it ends";
	}
}
