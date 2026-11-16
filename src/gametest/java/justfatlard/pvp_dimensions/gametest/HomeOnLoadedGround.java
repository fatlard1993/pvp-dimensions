package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.concurrent.atomic.AtomicReference;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Landing;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * An arena ending sends its players home without loading their ground in that tick: each is held
 * until the ground they're going to is there, then set down on it, and stays on it.
 */
public final class HomeOnLoadedGround implements FabricClientGameTest {
	/** Far enough out that nothing has loaded it. */
	private static final int FAR = 40_000;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			Tables.start(server, connection, Kinds.DEATHMATCH, preset -> {
				preset.pvp = false;
				preset.shape = Preset.Shape.FLAT;
				preset.size = 2;
			});

			int chunk = SectionPos.blockToSectionCoord(FAR);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Arena arena = Tables.arena(s);
				Visit visit = Visit.of(player);
				check(visit != null, "the player is not in the arena");
				Visit.set(player, new Visit(visit.arena(), new Visit.Home(Level.OVERWORLD, FAR + 0.5, 200, FAR + 0.5, 0, 0),
					visit.stash(), visit.experience(), visit.team(), visit.gameMode(), visit.added()));
				ServerLevel overworld = s.overworld();
				check(overworld.getChunkSource().getChunkNow(chunk, chunk) == null, "the far ground was already loaded");

				Arenas.end(s, arena, "the test is over");
				check(overworld.getChunkSource().getChunkNow(chunk, chunk) == null, "the ground at home was loaded in the tick the arena ended");
				check(player.level().dimension().equals(arena.dimension), "the player was sent home before their ground was there");
				check(Visit.of(player) != null, "the player's visit ended before they could go home");
				check(Landing.waiting(player.getUUID()), "the player is not waiting on their ground");
			});

			server.waitFor(s -> connection.getServerPlayer().level().dimension().equals(Level.OVERWORLD), 1200);
			AtomicReference<Vec3> landed = new AtomicReference<>();
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(Visit.of(player) == null, "the player came home with their visit still open");
				check(!Landing.waiting(player.getUUID()), "the player is home and still waiting");
				check(Math.abs(player.getX() - FAR) < 2 && Math.abs(player.getZ() - FAR) < 2, "the player was not set down at home: " + player.position());
				check(s.overworld().getChunkSource().getChunkNow(chunk, chunk) != null, "the player arrived over ground that isn't loaded");
				landed.set(player.position());
			});
			context.waitTicks(60);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.level().dimension().equals(Level.OVERWORLD), "the player left home again");
				check(player.getY() > landed.get().y - 2, "the player fell after coming home: from " + landed.get() + " to " + player.position());
			});
		}
	}
}
