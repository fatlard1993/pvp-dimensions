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
import net.minecraft.world.phys.Vec3;

/**
 * Connect four, played: four in a row found every way it can lie, a right-click on a column
 * dropping a piece to the bottom of it, four up a column taking a game and the frame emptying for
 * the next, and the third game won taking the arena.
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
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(corner).add(3, -1, -7), Vec3.atCenterOf(corner).add(3, 2.5, 0));
			int[] team = new int[1];
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				check(ConnectFour.side(arena) == team[0], "with nobody on the other side, it is not our turn");
				// A click high on the third column drops the piece all the way down it.
				check(Tables.click(player, level, corner.offset(2, 4, 0)).consumesAction(), "a click on a column dropped nothing");
				check(ConnectFour.slot(arena, 2, 0) == team[0], "the piece did not land at the bottom");
				check(Tables.block(level, corner.offset(2, 0, 0)).endsWith("_concrete"), "the bottom slot shows " + Tables.block(level, corner.offset(2, 0, 0)));
				check(Tables.block(level, corner.offset(2, 4, 0)).equals("glass"), "the slot clicked was filled instead");
				// Nobody to take the other side's turn: it comes straight back.
				check(ConnectFour.side(arena) == team[0], "the turn went to a side with nobody on it");
				for (int i = 0; i < 2; i++) ConnectFour.drop(s, level, arena, team[0], 2, "tester");
				ConnectFour.drop(s, level, arena, team[0], 3, "tester");
			});
			context.waitTicks(5);
			Tables.shoot(context, "connect-four");
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ConnectFour.drop(s, Arenas.level(s, arena), arena, team[0], 2, "tester");
				check(arena.scores.getOrDefault(team[0], 0) == 1, "four up a column did not win the game");
				check(ConnectFour.between(arena), "the frame did not wait to show off the four");
			});

			server.waitFor(s -> !ConnectFour.between(arena(s)), 200);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				for (int column = 0; column < ConnectFour.COLUMNS; column++) {
					check(ConnectFour.slot(arena, column, 0) < 0, "the frame did not empty for the next game");
				}
				check(Tables.block(level, arena.bases.get(0).offset(2, 0, 0)).equals("glass"), "the old piece still shows");
				// Across the bottom, twice more: the third game takes it.
				for (int column = 0; column < 4; column++) ConnectFour.drop(s, level, arena, team[0], column, "tester");
			});
			server.waitFor(s -> !ConnectFour.between(arena(s)), 200);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				for (int column = 3; column < 7; column++) ConnectFour.drop(s, level, arena, team[0], column, "tester");
				check(arena.closesAt > 0 && arena.winners.contains(connection.getServerPlayer().getUUID()), "three games won did not win it");
			});
		}
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
