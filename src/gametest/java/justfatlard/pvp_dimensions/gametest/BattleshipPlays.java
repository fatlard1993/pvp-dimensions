package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Battleship;
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
 * Battleship, played as at the table: a wall down the middle; a fleet laid by hand on the team's
 * own grid, turned and picked up again; the bell putting the rest out and starting the shooting;
 * shots fired from their waters landing on their fleet; and the last ship sunk winning it.
 */
public final class BattleshipPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		fleetsAreLegal();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.BATTLESHIP, preset -> {});
			server.waitFor(s -> arena(s).bases.size() == 4 && Battleship.settingUp(arena(s)), 200);

			int[] team = new int[1];
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				int enemy = 1 - team[0];

				// The wall stands between the stations, higher than anyone sees over.
				int middle = (int) Math.floor(arena.footprint().centerX());
				BlockPos mine = arena.bases.get(team[0]);
				check(Tables.block(level, new BlockPos(middle, mine.getY() + 8, mine.getZ())).equals("polished_deepslate"), "no wall down the middle");
				check(Integer.signum(arena.bases.get(team[0]).getX() - middle) != Integer.signum(arena.bases.get(enemy).getX() - middle),
					"both stations are on one side of the wall");

				BlockPos fleet = arena.bases.get(2 + team[0]);
				// The carrier, laid across from the first square by a real click.
				check(Tables.click(player, level, Battleship.posOf(fleet, 0)).consumesAction(), "a click on our fleet grid laid nothing");
				check(Battleship.fleetOf(arena, team[0]).equals(Set.of(0, 1, 2, 3, 4)), "the carrier lies at " + Battleship.fleetOf(arena, team[0]));
				for (int cell = 0; cell < 5; cell++) check(Tables.block(level, Battleship.posOf(fleet, cell)).equals("gray_concrete"), "our ship does not show");
				check(Tables.block(level, Battleship.posOf(fleet, 5)).equals("light_blue_concrete"), "the sea shows as " + Tables.block(level, Battleship.posOf(fleet, 5)));
				// Its first square again turns it.
				Tables.click(player, level, Battleship.posOf(fleet, 0));
				check(Battleship.fleetOf(arena, team[0]).equals(Set.of(0, 10, 20, 30, 40)), "the carrier did not turn: " + Battleship.fleetOf(arena, team[0]));
				check(Tables.block(level, Battleship.posOf(fleet, 1)).equals("light_blue_concrete"), "the old place still shows a ship");
				// Another square of it picks it up.
				Tables.click(player, level, Battleship.posOf(fleet, 20));
				check(Battleship.fleetOf(arena, team[0]).isEmpty(), "the carrier was not picked up");
				// Laid again, and the battleship after it.
				Tables.click(player, level, Battleship.posOf(fleet, 0));
				Tables.click(player, level, Battleship.posOf(fleet, 22));
				check(Battleship.fleetOf(arena, team[0]).size() == 9, "two ships cover " + Battleship.fleetOf(arena, team[0]).size() + " squares");

				// Theirs is not ours, and nobody fires during setup.
				check(Tables.click(player, level, Battleship.posOf(arena.bases.get(2 + enemy), 50)) == InteractionResult.FAIL, "we laid a ship in their fleet");
				check(Tables.click(player, level, Battleship.posOf(arena.bases.get(team[0]), 50)) == InteractionResult.FAIL, "we fired during setup");

				// The bell: the rest of ours laid at random, theirs too with nobody to lay it, and the shooting starts.
				Tables.click(player, level, Battleship.bellAt(arena, team[0]));
				check(!Battleship.settingUp(arena), "ringing the bell did not start the shooting");
				check(Battleship.fleetOf(arena, team[0]).size() == 17, "our fleet covers " + Battleship.fleetOf(arena, team[0]).size());
				check(Battleship.fleetOf(arena, team[0]).containsAll(Set.of(0, 1, 2, 3, 4)), "the bell moved the ships we laid");
				check(Battleship.fleetOf(arena, enemy).size() == 17, "their fleet was not put out");
			});

			BlockPos station = server.computeOnServer(s -> arena(s).bases.get(2 + team[0]));
			int toward = server.computeOnServer(s -> arena(s).bases.get(team[0]).getX() > arena(s).footprint().centerX() ? 1 : -1);
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(station).add(5 + toward * 12, 1, -3),
				Vec3.atCenterOf(station).add(5, -1, -3));
			Tables.shoot(context, "battleship-station");

			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int enemy = 1 - team[0];
				Set<Integer> theirs = Battleship.fleetOf(arena, enemy);
				int miss = 0;
				while (theirs.contains(miss)) miss++;
				check(Tables.click(player, level, Battleship.posOf(arena.bases.get(team[0]), miss)).consumesAction(), "a click on their waters did not fire");
				check(Tables.block(level, Battleship.posOf(arena.bases.get(team[0]), miss)).equals("white_wool"), "a miss shows as " + Tables.block(level, Battleship.posOf(arena.bases.get(team[0]), miss)));
				check(Tables.block(level, Battleship.posOf(arena.bases.get(2 + enemy), miss)).equals("white_wool"), "the miss does not show on their fleet");
				check(Tables.click(player, level, Battleship.posOf(arena.bases.get(team[0]), miss)) == InteractionResult.FAIL, "the same square fired at twice");
				for (int cell : theirs) Battleship.fire(s, level, arena, team[0], cell, "tester");
				check(arena.scores.getOrDefault(team[0], 0) == 17, "17 hits counted as " + arena.scores.getOrDefault(team[0], 0));
				for (int cell : theirs) check(Tables.block(level, Battleship.posOf(arena.bases.get(2 + enemy), cell)).equals("black_wool"), "a sunk ship does not show on their fleet");
				check(arena.closesAt > 0 && arena.winners.contains(player.getUUID()), "the whole fleet sunk and nobody won");
			});
		}
	}

	/** Two hundred fleets, every one the classic five ships, straight, on the board, and apart. */
	private static void fleetsAreLegal() {
		Random random = new Random(7);
		for (int round = 0; round < 200; round++) {
			List<Set<Integer>> ships = Battleship.fleet(random);
			int[] lengths = ships.stream().mapToInt(Set::size).toArray();
			check(java.util.Arrays.equals(lengths, new int[] {5, 4, 3, 3, 2}), "a fleet of " + java.util.Arrays.toString(lengths));
			Set<Integer> all = new HashSet<>();
			for (Set<Integer> ship : ships) {
				for (int cell : ship) check(cell >= 0 && cell < Battleship.SIZE * Battleship.SIZE && all.add(cell), "ships overlap or leave the board");
				Set<Integer> rows = new HashSet<>();
				Set<Integer> columns = new HashSet<>();
				for (int cell : ship) {
					rows.add(cell / Battleship.SIZE);
					columns.add(cell % Battleship.SIZE);
				}
				boolean straight = rows.size() == 1 && columns.size() == ship.size() || columns.size() == 1 && rows.size() == ship.size();
				check(straight, "a ship is not in a straight unbroken line: " + ship);
			}
		}
	}
}
