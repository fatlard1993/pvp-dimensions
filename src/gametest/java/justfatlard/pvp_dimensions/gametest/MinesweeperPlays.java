package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.Random;
import java.util.Set;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Minesweeper;
import justfatlard.pvp_dimensions.preset.Kinds;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Minesweeper, played: the first square never a mine nor beside one, a right-click opening the
 * field out from it with numbers lying on the squares, a crouched right-click flagging, a mine
 * blowing the field and a new one coming, and every safe square uncovered winning it.
 */
public final class MinesweeperPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		Random random = new Random(3);
		for (int round = 0; round < 100; round++) {
			int first = random.nextInt(Minesweeper.SIZE * Minesweeper.SIZE);
			Set<Integer> mines = Minesweeper.lay(random, first);
			check(mines.size() == Minesweeper.MINES, mines.size() + " mines laid");
			check(!mines.contains(first), "the first square is a mine");
			for (int round2 : Minesweeper.neighbours(first)) check(!mines.contains(round2), "a mine beside the first square");
		}
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.MINESWEEPER, preset -> {});
			server.waitFor(s -> arena(s).bases.size() == 2, 200);

			BlockPos mine0 = server.computeOnServer(s -> arena(s).bases.get(arena(s).member(connection.getServerPlayer().getUUID()).team));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(mine0).add(6, 0, -4), Vec3.atCenterOf(mine0).add(6, -1, 6));
			int[] team = new int[1];
			int[] points = new int[1];
			int middle = Minesweeper.SIZE / 2 * Minesweeper.SIZE + Minesweeper.SIZE / 2;
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				BlockPos corner = arena.bases.get(team[0]);
				BlockPos square = corner.offset(middle % Minesweeper.SIZE, -1, middle / Minesweeper.SIZE);
				check(Tables.click(player, level, square).consumesAction(), "a click on the field did nothing");
				check(Minesweeper.isOpen(arena, team[0], middle), "the square clicked did not open");
				check(arena.scores.getOrDefault(team[0], 0) > 1, "the first square opened nothing round it: " + arena.scores.get(team[0]));
				AABB area = new AABB(corner.getX(), corner.getY() - 1, corner.getZ(), corner.getX() + Minesweeper.SIZE, corner.getY() + 2, corner.getZ() + Minesweeper.SIZE);
				int numbers = level.getEntitiesOfClass(Display.TextDisplay.class, area, text -> text.entityTags().contains(Minesweeper.TEXT_TAG)).size();
				check(numbers > 0, "no numbers lie on the opened field");

				// A crouched click flags a covered square.
				Set<Integer> mines = Minesweeper.minesOf(arena, team[0], middle);
				int mine = mines.iterator().next();
				player.setShiftKeyDown(true);
				Tables.click(player, level, corner.offset(mine % Minesweeper.SIZE, -1, mine / Minesweeper.SIZE));
				player.setShiftKeyDown(false);
				check(Tables.block(level, corner.offset(mine % Minesweeper.SIZE, -1, mine / Minesweeper.SIZE)).equals("red_wool"), "the crouched click did not flag");
				// A flagged square will not open.
				Minesweeper.uncover(s, level, arena, team[0], mine, "tester");
				check(!Minesweeper.blown(arena, team[0]), "a flagged mine went off");
			});
			context.waitTicks(3);
			Tables.shoot(context, "minesweeper");

			// A mine, uncovered: the field goes up, and a new one comes.
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				Set<Integer> mines = Minesweeper.minesOf(arena, team[0], middle);
				int mine = mines.stream().max(Integer::compare).orElseThrow();
				Minesweeper.flag(level, arena, team[0], mine);
				Minesweeper.flag(level, arena, team[0], mine);
				Minesweeper.uncover(s, level, arena, team[0], mine, "tester");
				check(Minesweeper.blown(arena, team[0]), "a mine uncovered did not go off");
				check(arena.scores.getOrDefault(team[0], 0) > 1, "the squares won were lost with the field: " + arena.scores.get(team[0]));
				points[0] = arena.scores.getOrDefault(team[0], 0);
			});
			server.waitFor(s -> !Minesweeper.blown(arena(s), team[0]), 300);

			// Every safe square of the new field: the field is cleared and it is won.
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				check(!Minesweeper.isOpen(arena, team[0], middle), "the new field came with squares open");
				Set<Integer> mines = Minesweeper.minesOf(arena, team[0], 0);
				for (int square = 0; square < Minesweeper.SIZE * Minesweeper.SIZE; square++) {
					if (!mines.contains(square) && !Minesweeper.isOpen(arena, team[0], square)) Minesweeper.uncover(s, level, arena, team[0], square, "tester");
				}
				check(arena.closesAt > 0 && arena.winners.contains(connection.getServerPlayer().getUUID()), "a cleared field did not win it");
				check(arena.scores.getOrDefault(team[0], 0) == points[0] + Minesweeper.SIZE * Minesweeper.SIZE - Minesweeper.MINES,
					"points did not add up across fields: " + arena.scores.get(team[0]) + " after " + points[0]);
			});
		}
	}
}
