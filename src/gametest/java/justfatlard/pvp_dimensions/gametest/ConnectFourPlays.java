package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.ConnectFour;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/**
 * Connect four, played: four in a row found every way it can lie, a right-click on a chute
 * dropping a cube that falls down its channel and is held at the bottom, the next landing on it,
 * four up a channel taking a game and the frame emptying for the next, and the third game won
 * taking the arena.
 */
public final class ConnectFourPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		lines();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.CONNECT_FOUR, preset -> {});
			server.waitFor(s -> ConnectFour.side(arena(s)) >= 0, 200);

			BlockPos corner = server.computeOnServer(s -> arena(s).bases.get(0));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(corner).add(6.5, -1, -7), Vec3.atCenterOf(corner).add(6.5, 2.5, 0));
			int[] team = new int[1];
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				check(ConnectFour.side(arena) == team[0], "with nobody on the other side, it is not our turn");
				check(Tables.click(player, level, ConnectFour.chute(arena, 2)).consumesAction(), "a click on a chute dropped nothing");
				check(ConnectFour.falling(arena), "no cube is falling");
				check(Tables.click(player, level, ConnectFour.chute(arena, 3)) == InteractionResult.FAIL, "a second cube dropped while the first was falling");
			});
			server.waitFor(s -> !ConnectFour.falling(arena(s)), 100);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				check(ConnectFour.slot(arena, 2, 0) == team[0], "the cube was not counted at the bottom");
				check(ConnectFour.pieceAt(level, arena, 2, 0) != null, "no cube stands at the bottom of the channel");
			});
			// A click on the glass in front of a channel drops down it too.
			server.runOnServer(s -> {
				Arena arena = arena(s);
				Tables.click(connection.getServerPlayer(), Arenas.level(s, arena), arena.bases.get(0).offset(2 * 2, 2, -1));
			});
			server.waitFor(s -> !ConnectFour.falling(arena(s)), 100);
			drop(server, 2);
			drop(server, 3);
			context.waitTicks(5);
			Tables.shoot(context, "connect-four");
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				check(ConnectFour.slot(arena, 2, 0) == team[0] && ConnectFour.slot(arena, 2, 1) == team[0] && ConnectFour.slot(arena, 2, 2) == team[0],
					"three dropped down one channel did not stack");
				check(ConnectFour.pieceAt(level, arena, 2, 2) != null, "the third cube is not held on the second");
				check(ConnectFour.slot(arena, 3, 0) == team[0], "the glass in front of a channel did not drop down it");
			});
			drop(server, 2);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				check(arena.scores.getOrDefault(team[0], 0) == 1, "four up a channel did not win the game");
				check(ConnectFour.between(arena), "the frame did not wait to show off the four");
			});

			server.waitFor(s -> !ConnectFour.between(arena(s)), 200);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				for (int column = 0; column < ConnectFour.COLUMNS; column++) check(ConnectFour.slot(arena, column, 0) < 0, "the frame did not empty");
				check(ConnectFour.pieceAt(level, arena, 2, 0) == null, "an old cube is still in the frame");
			});
			for (int column = 0; column < 4; column++) drop(server, column);
			server.waitFor(s -> !ConnectFour.between(arena(s)), 200);
			for (int column = 3; column < 7; column++) drop(server, column);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				check(arena.closesAt > 0 && arena.winners.contains(connection.getServerPlayer().getUUID()), "three games won did not win it");
			});
		}
	}

	/** A cube dropped down a column for the only team there is, and landed. */
	private static void drop(TestServerContext server, int column) {
		server.runOnServer(s -> {
			Arena arena = arena(s);
			ConnectFour.drop(s, Arenas.level(s, arena), arena, ConnectFour.side(arena), column, "tester");
		});
		server.waitFor(s -> !ConnectFour.falling(arena(s)), 100);
	}

	private static void lines() {
		int[][] across = empty();
		for (int c = 1; c <= 4; c++) across[c][0] = 0;
		check(ConnectFour.four(across, 3, 0) != null, "four across not found");
		int[][] slant = empty();
		for (int i = 0; i < 4; i++) slant[i][i] = 1;
		check(ConnectFour.four(slant, 2, 2) != null, "four slanting up not found");
		int[][] down = empty();
		for (int i = 0; i < 4; i++) down[i + 2][3 - i] = 0;
		check(ConnectFour.four(down, 4, 1) != null, "four slanting down not found");
		int[][] three = empty();
		for (int c = 0; c < 3; c++) three[c][0] = 0;
		three[3][0] = 1;
		check(ConnectFour.four(three, 2, 0) == null, "three and another colour counted as four");
	}

	private static int[][] empty() {
		int[][] slots = new int[ConnectFour.COLUMNS][ConnectFour.ROWS];
		for (int[] column : slots) java.util.Arrays.fill(column, -1);
		return slots;
	}
}
