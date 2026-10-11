package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.Arrays;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Mastermind;
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
 * Code breaking, played: pegs marked as at the table, repeats and all; a right-click turning a
 * square of the lit row, the bell refusing a row not yet set, a wrong row earning its pegs, and the
 * code itself cracking it and counting.
 */
public final class MastermindPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		pegs();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.CODE_BREAKING, preset -> {});
			server.waitFor(s -> Mastermind.codeOf(arena(s), 0).length == Mastermind.PLACES, 200);

			int[] team = new int[1];
			BlockPos mine = server.computeOnServer(s -> arena(s).bases.get(arena(s).member(connection.getServerPlayer().getUUID()).team));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(mine).add(4.5, 0, -5), Vec3.atCenterOf(mine).add(4.5, -1, 4));
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				BlockPos corner = arena.bases.get(team[0]);
				// A click on the first square of the lit row sets it to the first colour.
				check(Tables.click(player, level, corner.offset(0, -1, 0)).consumesAction(), "a click on the lit row did nothing");
				check(Tables.block(level, corner.offset(0, -1, 0)).equals(Mastermind.words(new int[] {0}).replace(' ', '_') + "_concrete"),
					"the square shows " + Tables.block(level, corner.offset(0, -1, 0)));
				// The bell will not try a row with squares unset.
				check(!Mastermind.tryRow(s, level, arena, team[0], "tester"), "a row with three squares unset was tried");
				check(Mastermind.rowsTried(arena, team[0]) == 0, "a row was used up");

				// A row with every colour wrong: no pegs, and the next row lit.
				int[] code = Mastermind.codeOf(arena, team[0]);
				int missing = 0;
				while (contains(code, missing)) missing++;
				set(level, arena, team[0], new int[] {missing, missing, missing, missing});
				check(Mastermind.tryRow(s, level, arena, team[0], "tester"), "a whole row was refused");
				check(Mastermind.rowsTried(arena, team[0]) == 1, "the row was not counted");
				check(Tables.block(level, corner.offset(5, -1, 0)).equals("gray_concrete"), "a row with nothing right earned a peg");
				check(Tables.block(level, corner.offset(-1, -1, 1)).equals("sea_lantern"), "the next row is not lit");

				// One right and in place, to show the pegs.
				int[] near = {code[0], missing, missing, missing};
				set(level, arena, team[0], near);
				Mastermind.tryRow(s, level, arena, team[0], "tester");
				check(Tables.block(level, corner.offset(5, -1, 1)).equals("black_wool"), "one right in place earned no black peg");
			});
			context.waitTicks(5);
			Tables.shoot(context, "code-breaking");
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				BlockPos corner = arena.bases.get(team[0]);
				int[] code = Mastermind.codeOf(arena, team[0]);

				// The code itself.
				set(level, arena, team[0], code);
				Mastermind.tryRow(s, level, arena, team[0], "tester");
				check(arena.scores.getOrDefault(team[0], 0) == 1, "the code did not crack");
				check(Tables.block(level, corner.offset(8, -1, 2)).equals("black_wool"), "a cracked row's last peg is " + Tables.block(level, corner.offset(8, -1, 2)));
			});
			server.waitFor(s -> Mastermind.rowsTried(arena(s), team[0]) == 0, 200);
		}
	}

	private static void set(ServerLevel level, Arena arena, int team, int[] colours) {
		for (int place = 0; place < Mastermind.PLACES; place++) {
			// Round from wherever it is to the colour wanted.
			for (int step = 0; step <= Mastermind.COLOURS_COUNT; step++) {
				if (Mastermind.rowColour(arena, team, place) == colours[place]) break;
				Mastermind.turn(level, arena, team, place, 1);
			}
		}
	}

	private static boolean contains(int[] code, int colour) {
		for (int c : code) if (c == colour) return true;
		return false;
	}

	private static void pegs() {
		check(Arrays.equals(Mastermind.mark(new int[] {0, 1, 2, 3}, new int[] {0, 1, 2, 3}), new int[] {4, 0}), "right is not four black");
		check(Arrays.equals(Mastermind.mark(new int[] {0, 1, 2, 3}, new int[] {3, 2, 1, 0}), new int[] {0, 4}), "all moved is not four white");
		// A repeat in the guess earns only as many as the code has.
		check(Arrays.equals(Mastermind.mark(new int[] {0, 1, 1, 2}, new int[] {1, 1, 1, 1}), new int[] {2, 0}), "repeats over-counted");
		check(Arrays.equals(Mastermind.mark(new int[] {0, 0, 1, 1}, new int[] {1, 1, 0, 0}), new int[] {0, 4}), "swapped pairs not four white");
		check(Arrays.equals(Mastermind.mark(new int[] {0, 1, 2, 3}, new int[] {0, 4, 4, 1}), new int[] {1, 1}), "one right, one moved, marked wrong");
	}
}
