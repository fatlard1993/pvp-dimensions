package justfatlard.pvp_dimensions.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.onboarding.Demo;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every demo installed, each played once: its icon and the mobs it names are things this server has,
 * its ground is made and goes live, the player is taken in with its kit, and brought home again.
 *
 * <p>Demos are written by hand in other mods' jars, where a wrong id is dropped without a word; with
 * only this mod and the test mod loaded it plays the two of theirs, and loaded beside the whole suite
 * (fabric.addMods) it plays all of them. Every problem is gathered and reported together.
 */
public final class EveryDemoPlays implements FabricClientGameTest {
	private static final int TO_GO_LIVE = 2400;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			List<String> ids = server.computeOnServer(s -> Demos.all().stream().map(Demo::id).toList());
			List<String> problems = new ArrayList<>();
			for (String id : ids) {
				server.runOnServer(s -> {
					Demo demo = Demos.get(id);
					if (!known(BuiltInRegistries.ITEM.keySet(), demo.icon())) problems.add(id + ": no icon item " + demo.icon());
					for (Map.Entry<String, Preset.MobLevel> mob : demo.preset().mobLevels.entrySet()) {
						if (mob.getValue() != Preset.MobLevel.OFF && !known(BuiltInRegistries.ENTITY_TYPE.keySet(), mob.getKey())) {
							problems.add(id + ": no mob " + mob.getKey());
						}
					}
					if (!demo.preset().mobGoalKind.equals("any") && demo.preset().activeGoal() == Preset.Goal.MOBS
							&& !known(BuiltInRegistries.ENTITY_TYPE.keySet(), demo.preset().mobGoalKind)) {
						problems.add(id + ": hunts " + demo.preset().mobGoalKind + ", which is no mob here");
					}
					connection.getServerPlayer().getInventory().clearContent();
					Demos.play(connection.getServerPlayer(), id);
				});
				boolean in = waitIn(server, connection, id);
				server.runOnServer(s -> {
					ServerPlayer player = connection.getServerPlayer();
					if (!in) {
						problems.add(id + ": never went live with the player in it");
					} else {
						int kit = Demos.get(id).preset().entryKit.stacks().size();
						int held = justfatlard.pvp_dimensions.preset.Kit.of(player).stacks().size();
						if (held < kit) problems.add(id + ": " + kit + " stacks in the kit, " + held + " arrived");
						System.out.println("DEMO " + id + ": in, " + held + "/" + kit + " stacks, at " + player.blockPosition());
					}
					Arena arena = Arenas.of(player);
					if (arena != null) Travel.goHome(player, arena, Travel.Why.COMMAND);
				});
				server.waitFor(s -> Arenas.demos(s).isEmpty(), 400);
			}
			if (!problems.isEmpty()) throw new AssertionError(problems.size() + " demo problems:\n  " + String.join("\n  ", problems));
		}
	}

	/** Live, with the player inside, or false if it never got there in time. */
	private static boolean waitIn(TestServerContext server, TestServerConnection connection, String id) {
		try {
			server.waitFor(s -> {
				Arena arena = Arenas.of(connection.getServerPlayer());
				return arena != null && arena.demo.equals(id) && arena.phase == Arena.Phase.LIVE
					&& arena.member(connection.getServerPlayer().getUUID()) != null && arena.member(connection.getServerPlayer().getUUID()).inside;
			}, TO_GO_LIVE);
			return true;
		} catch (AssertionError | RuntimeException e) {
			return false;
		}
	}

	private static boolean known(java.util.Set<Identifier> keys, String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed != null && keys.contains(parsed);
	}
}
