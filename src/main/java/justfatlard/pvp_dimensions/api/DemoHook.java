package justfatlard.pvp_dimensions.api;

/**
 * Scripted steps for a mod's onboarding demo, beyond what its preset and its own blocks already do.
 *
 * <p>Most demos need none of this: a level built with the mod's blocks, a kit of its items and a
 * goal is a demo, shipped as {@code pvp-dimensions/demo.json} in the mod's resources. Reach for a
 * hook when the demo has to react: a sign that changes once something is tried, a mob that walks
 * up at the right moment, a finish that no preset goal describes.
 *
 * <p>Declared as a Fabric entrypoint, so the class is only loaded where PvP Dimensions is, and a
 * mod compiles against this jar without depending on it at runtime:
 *
 * <pre>{@code
 * // fabric.mod.json
 * "entrypoints": { "pvp-dimensions-demo": ["com.example.mymod.MyDemo"] },
 * "suggests": { "pvp-dimensions-justfatlard": "*" }
 * }</pre>
 *
 * <p>Every method runs on the server thread, for one player in one copy of the demo; two players
 * playing it at once each get their own ground and their own calls.
 */
public interface DemoHook {
	/** The entrypoint key a hook is declared under. */
	String ENTRYPOINT = "pvp-dimensions-demo";

	/** Set down in the demo, kit in hand. */
	default void arrived(DemoRun run) {}

	/** Once a second while they are inside. */
	default void tick(DemoRun run) {}

	/** Gone: out through the exit, on to another demo, or the demo closed. Their own pack is already back. */
	default void left(DemoRun run) {}
}
