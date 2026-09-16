package justfatlard.pvp_dimensions.preset;

import java.util.List;
import net.minecraft.world.scores.TeamColor;

/**
 * The teams, in the order they are handed out: two teams are red and blue, as every game of this
 * shape has taught everyone to expect, and the rest follow as the dyes tell apart best.
 */
public final class TeamColors {
	private TeamColors() {}

	public record Colour(String name, TeamColor team, String dye) {
		public String glass() {
			return "minecraft:" + dye + "_stained_glass";
		}

		public String concrete() {
			return "minecraft:" + dye + "_concrete";
		}

		public String wool() {
			return "minecraft:" + dye + "_wool";
		}

		public String banner() {
			return "minecraft:" + dye + "_banner";
		}
	}

	public static final List<Colour> ALL = List.of(
		new Colour("Red", TeamColor.RED, "red"),
		new Colour("Blue", TeamColor.BLUE, "blue"),
		new Colour("Green", TeamColor.GREEN, "lime"),
		new Colour("Yellow", TeamColor.YELLOW, "yellow"),
		new Colour("Purple", TeamColor.DARK_PURPLE, "purple"),
		new Colour("Orange", TeamColor.GOLD, "orange"),
		new Colour("Aqua", TeamColor.AQUA, "light_blue"),
		new Colour("Pink", TeamColor.LIGHT_PURPLE, "pink"));

	public static final int MAX = ALL.size();

	public static Colour of(int index) {
		return ALL.get(Math.floorMod(index, MAX));
	}

	/** The team a word names, by colour, for {@code /pvp team red}; -1 for none. */
	public static int byName(String name) {
		for (int i = 0; i < MAX; i++) {
			if (ALL.get(i).name().equalsIgnoreCase(name)) return i;
		}
		return -1;
	}
}
