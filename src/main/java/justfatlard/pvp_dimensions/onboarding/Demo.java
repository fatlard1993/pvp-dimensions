package justfatlard.pvp_dimensions.onboarding;

import java.util.List;
import justfatlard.pvp_dimensions.api.DemoHook;
import justfatlard.pvp_dimensions.preset.Preset;
import org.jspecify.annotations.Nullable;

/**
 * One mod's onboarding demo: a small level that shows what the mod does by letting a player do it.
 *
 * @param id       the mod's id; one demo per mod
 * @param name     what the list calls it, the mod's name unless the file says otherwise
 * @param about    a line or two said on the card and on arrival
 * @param finishes whether it is only played once finished (its goal won, or its hook saying so),
 *                 rather than as soon as it is stepped into
 * @param preset   the arena it is played as, already made safe for a demo
 * @param hook     the mod's scripted steps, where it declared any
 */
public record Demo(String id, String name, String icon, List<String> about, boolean finishes, Preset preset,
		@Nullable DemoHook hook) {

	/** "Fire Arrows demo"; a demo already named as one is not called a "demo demo". */
	public static String title(String name) {
		return name.toLowerCase(java.util.Locale.ROOT).endsWith("demo") ? name : name + " demo";
	}

	public String title() {
		return title(name);
	}

	/** The preset id an arena played from this demo is recorded under; never a file in the presets folder. */
	public String presetId() {
		return "demo:" + id;
	}
}
