package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.concurrent.atomic.AtomicInteger;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.onboarding.Demo;
import justfatlard.pvp_dimensions.onboarding.DemoFiles;
import justfatlard.pvp_dimensions.onboarding.Demos;
import justfatlard.pvp_dimensions.onboarding.Progress;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Onboarding as a run of demos: asked about every demo on a first visit, one demo straight into
 * another keeping the first home, the second's hook putting its finish down and hearing it reached,
 * home with the player's own things, and the next unplayed one offered. And a demo's ground,
 * installed from its folder once per version.
 */
public final class DemoChain implements FabricClientGameTest {
	private static final String TEST = "pvp-dimensions-gametest";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.waitFor(s -> Progress.of(connection.getServerPlayer()).offered().size() == Demos.all().size(), 400);

			Vec3 home = server.computeOnServer(s -> {
				Demo test = Demos.get(TEST);
				check(test != null, "the test mod's demo was not read: " + Demos.all());
				check(test.hook() != null, "the test mod's demo hook was not found");
				check(!test.preset().waitingRoom && test.preset().isolated() && test.preset().entryFee.isEmpty(),
					"a demo file asking for a waiting room, free passage and a fee kept them");

				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
				Demos.play(player, PvpDimensions.MOD_ID);
				return player.position();
			});
			server.waitFor(s -> inDemo(connection, PvpDimensions.MOD_ID), 2400);

			String first = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				String id = Arenas.of(player).id;
				Demos.play(player, TEST);
				return id;
			});
			server.waitFor(s -> inDemo(connection, TEST), 2400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Arena old = Arenas.get(s, first);
				check(old == null || old.phase == Arena.Phase.ENDED, "the demo moved on from was left running");
				check(Arenas.demos(s).size() == 1, "more than the one demo being played is running: " + Arenas.demos(s).size());
				Visit.Home kept = Visit.of(player).home();
				check(new Vec3(kept.x(), kept.y(), kept.z()).distanceTo(home) < 1, "going demo to demo lost the home first come from");
				check(player.getInventory().countItem(Items.DIAMOND) == 0, "the player's own pack came along into the second demo");
				check(TestDemoHook.arrived == 1, "the hook heard " + TestDemoHook.arrived + " arrivals");
				check(!Progress.of(player).finished().contains(TEST), "the test demo finished before anything was done");

				BlockPos gold = TestDemoHook.gold.get(player.getUUID());
				check(gold != null, "the hook put no gold block down");
				player.teleportTo(player.level(), gold.getX() + 0.5, gold.getY() + 1, gold.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
			});
			server.waitFor(s -> Progress.of(connection.getServerPlayer()).finished().contains(TEST), 100);

			AtomicInteger saved = new AtomicInteger(-2);
			server.runOnServer(s -> {
				Arena arena = Arenas.of(connection.getServerPlayer());
				SavedTerrains.save(Arenas.level(s, arena), arena.footprint(), "demo-source", "minecraft:plains",
					arena.surfaceY, arena.highestY, arena.wallTop, saved::set);
			});
			server.waitFor(s -> saved.get() != -2, 1200);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(Progress.of(player).played(Demos.get(TEST)), "the hook's finish did not count as played");
				Travel.goHome(player, Arenas.of(player), Travel.Why.COMMAND);
				check(player.getInventory().countItem(Items.DIAMOND) == 3, "the player's own pack did not come home");
				check(player.position().distanceTo(home) < 3, "home was the demo before, not where the run began: " + player.position());
				check(TestDemoHook.left == 1, "the hook heard " + TestDemoHook.left + " departures");
				Demo next = Demos.next(player, TEST);
				check(next != null && !Progress.of(player).played(next), "an unplayed demo was not offered next: " + next);

				check(saved.get() > 0, "the test ground could not be saved");
				try {
					java.nio.file.Path from = SavedTerrains.folder("demo-source");
					check(DemoFiles.install(from, "demo-installed", "1.0"), "a demo's ground was not installed");
					check(!DemoFiles.install(from, "demo-installed", "1.0"), "the same version's ground was copied again");
					check(DemoFiles.install(from, "demo-installed", "1.1"), "a new version's ground did not replace the old");
				} catch (java.io.IOException e) {
					throw new AssertionError("installing a demo's ground failed", e);
				}
				SavedTerrains.load(s);
				check(SavedTerrains.meta("demo-installed") != null, "installed ground is not among the saved terrains");
			});
			server.waitFor(s -> Arenas.demos(s).isEmpty(), 200);
		}
	}

	private static boolean inDemo(TestServerConnection connection, String demo) {
		Arena arena = Arenas.of(connection.getServerPlayer());
		return arena != null && arena.demo.equals(demo) && arena.phase == Arena.Phase.LIVE
			&& arena.member(connection.getServerPlayer().getUUID()) != null;
	}
}
