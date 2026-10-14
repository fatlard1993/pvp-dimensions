package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Balls;
import justfatlard.pvp_dimensions.arena.Bowling;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Bowling, played: the score sheet kept as an alley keeps it, a lane racked with ten pins, the ball
 * that only its bowler may hit, a ball bowled straight at the head pin knocking pins down - the
 * knocks between cubes are the part the game does not do - and the frame moving on.
 */
public final class BowlingPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		sheet();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.BOWLING, preset -> {});
			server.waitFor(s -> arena(s).bases.size() == 2 && Bowling.ballOf(arena(s), 0) != null, 200);

			int[] team = new int[1];
			BlockPos[] foul = new BlockPos[1];
			server.runOnServer(s -> {
				Arena arena = arena(s);
				team[0] = arena.member(connection.getServerPlayer().getUUID()).team;
				foul[0] = arena.bases.get(team[0]);
				for (int pin = 0; pin < 10; pin++) check(!Bowling.pinDown(arena, team[0], pin), "pin " + pin + " is not on its spot at the start");
				var level = justfatlard.pvp_dimensions.arena.Arenas.level(s, arena);
				// A gutter each side, sunk: open over the pit, the lane's boards between.
				for (int side : new int[] {-4, 4}) {
					check(Tables.block(level, foul[0].offset(8, -1, side)).equals("air"), "no gutter at " + side + ": " + Tables.block(level, foul[0].offset(8, -1, side)));
					check(Tables.block(level, foul[0].offset(8, -2, side)).equals("black_concrete"), "no pit under the gutter");
				}
				check(Tables.block(level, foul[0].offset(8, -1, 0)).equals("birch_planks"), "the lane is " + Tables.block(level, foul[0].offset(8, -1, 0)));
				check(Balls.mayStrike(Bowling.ballOf(arena, team[0]), connection.getServerPlayer()), "the bowler may not hit their own ball");
				check(!Balls.mayStrike(Bowling.ballOf(arena, 1 - team[0]), connection.getServerPlayer()), "the bowler may hit the other lane's ball");
			});
			// Up close to the rack: ten pins, each a head on a foot.
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(foul[0]).add(10, 0, 3), Vec3.atCenterOf(foul[0]).add(16.5, 0.5, 0));
			Tables.shoot(context, "bowling-pins");
						Vec3 behind = Vec3.atBottomCenterOf(foul[0]).add(-4, 0, 0);
			Tables.look(context, server, connection, behind, Vec3.atCenterOf(foul[0]).add(16, 0, 0));

			// Off to the side and into the gutter: nothing down, and the same frame's second ball.
			server.runOnServer(s -> Bowling.bowl(arena(s), team[0], new Vec3(0.6, 0, 0.35)));
			server.waitFor(s -> Bowling.rolls(arena(s), team[0]).size() == 1, 600);
			server.runOnServer(s -> {
				List<Integer> rolls = Bowling.rolls(arena(s), team[0]);
				check(rolls.get(0) == 0, "a ball into the gutter knocked down " + rolls.get(0));
				check(Bowling.ballOf(arena(s), team[0]).getX() < foul[0].getX() + 3, "the ball is not back at the line for the second");
			});

			// Straight down the middle, as a good hard hit sends it: the pins it meets knock on into the rest.
			server.runOnServer(s -> Bowling.bowl(arena(s), team[0], new Vec3(1.0, 0, 0)));
			context.waitTicks(22);
			Tables.shoot(context, "bowling-lane");
			server.waitFor(s -> Bowling.rolls(arena(s), team[0]).size() == 2, 600);
			server.runOnServer(s -> {
				Arena arena = arena(s);
				List<Integer> rolls = Bowling.rolls(arena, team[0]);
				check(rolls.get(1) >= 4, "a ball straight at the head pin knocked down only " + rolls.get(1));
				check(arena.scores.getOrDefault(team[0], 0) == rolls.get(1) + (rolls.get(1) == 10 ? 0 : 0), "two balls scored " + arena.scores.get(team[0]) + " for " + rolls);
				for (int pin = 0; pin < 10; pin++) check(!Bowling.pinDown(arena, team[0], pin), "the second frame did not start on a full rack");
			});
		}
	}

	/** The sheet, as an alley keeps it. */
	private static void sheet() {
		check(Bowling.score(times(10, 12)) == 300, "a perfect game scored " + Bowling.score(times(10, 12)));
		check(Bowling.frameOf(times(10, 12)).over(), "a perfect game is not over after twelve strikes");
		check(!Bowling.frameOf(times(10, 11)).over(), "a perfect game is over after eleven strikes");
		check(Bowling.score(times(5, 21)) == 150, "all fives scored " + Bowling.score(times(5, 21)));
		check(Bowling.score(times(0, 20)) == 0, "a gutter game scored");
		List<Integer> nines = new ArrayList<>();
		for (int frame = 0; frame < 10; frame++) {
			nines.add(9);
			nines.add(0);
		}
		check(Bowling.score(nines) == 90, "nine a frame scored " + Bowling.score(nines));
		check(Bowling.frameOf(nines).over(), "ten open frames are not the end");
		check(Bowling.frameOf(List.of(3)).standing() == 7, "after three down, not seven standing");
		check(Bowling.frameOf(List.of(10)).frame() == 1, "a strike does not end the frame");
		check(!Bowling.frameOf(List.of(0)).fresh(), "the second ball after a gutter ball counts as a fresh rack, so ten would be a strike");
		List<Integer> late = new ArrayList<>(times(0, 18));
		late.add(0);
		check(!Bowling.frameOf(late).fresh(), "the tenth's second ball after a gutter ball counts as a fresh rack");
		late.set(18, 10);
		check(Bowling.frameOf(late).fresh(), "the tenth's second ball after a strike is not at a fresh rack");
		List<Integer> tenth = new ArrayList<>(times(0, 18));
		tenth.add(3);
		tenth.add(7);
		check(!Bowling.frameOf(tenth).over() && Bowling.frameOf(tenth).standing() == 10, "a spare in the tenth earns no third ball on a full rack");
		tenth.add(4);
		check(Bowling.frameOf(tenth).over(), "the tenth goes on past its third ball");
		check(Bowling.score(tenth) == 14, "a spare and four in the tenth scored " + Bowling.score(tenth));
	}

	private static List<Integer> times(int pins, int balls) {
		return new ArrayList<>(Collections.nCopies(balls, pins));
	}
}
