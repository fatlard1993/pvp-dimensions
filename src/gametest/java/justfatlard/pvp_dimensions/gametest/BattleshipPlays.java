package justfatlard.pvp_dimensions.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Battleship;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Battleship, played: fleets that are always a legal fleet, a right-click on your own board that
 * fires and on anyone else's that does not, the same square refused twice, and a fleet sunk to the
 * last ship winning it.
 */
public final class BattleshipPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		fleetsAreLegal();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runOnServer(s -> {
				Preset preset = Kinds.BATTLESHIP.make();
				preset.waitingRoom = false;
				Arena arena = Arenas.start(s, connection.getServerPlayer(), "battleship-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, true), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
				check(arena != null, "no arena");
			});
			server.waitFor(s -> arena(s) != null && arena(s).phase == Arena.Phase.LIVE && arena(s).bases.size() == 2, 2400);

			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int team = arena.member(player.getUUID()).team;
				check(team == 0 || team == 1, "the player is on no side: " + team);
				int enemy = 1 - team;
				Set<Integer> fleet = Battleship.fleetOf(arena, enemy);
				check(fleet.size() == 17, "their fleet covers " + fleet.size() + " squares, not 17");

				// Their board is not ours to fire from.
				check(click(player, level, square(arena, enemy, 0)) == InteractionResult.FAIL, "fired from the other team's board");

				// A real right-click on our own board, through the game's own use of a block.
				int first = 0;
				while (fleet.contains(first)) first++;
				check(click(player, level, square(arena, team, first)).consumesAction(), "a right-click on our board did not fire");
				check(block(level, square(arena, team, first)).equals("white_wool"), "a miss shows as " + block(level, square(arena, team, first)));
				check(click(player, level, square(arena, team, first)) == InteractionResult.FAIL, "the same square fired at twice");

				// Every ship of theirs, square by square: their side has nobody to fire back, so the turn stays ours.
				int hit = fleet.iterator().next();
				Battleship.fire(s, level, arena, team, hit, "tester");
				check(arena.closesAt > 0 || block(level, square(arena, team, hit)).matches("(red|black)_wool"),
					"a hit shows as " + block(level, square(arena, team, hit)));
				for (int cell : fleet) Battleship.fire(s, level, arena, team, cell, "tester");
				check(arena.closesAt > 0, "the whole fleet sunk and nobody won");
				check(arena.scores.getOrDefault(team, 0) == 17, "17 hits counted as " + arena.scores.getOrDefault(team, 0));
				for (int cell : fleet) check(block(level, square(arena, team, cell)).equals("black_wool"), "a sunk ship's square shows as " + block(level, square(arena, team, cell)));
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
				int low = rows.size() == 1 ? columns.stream().min(Integer::compare).get() : rows.stream().min(Integer::compare).get();
				int high = rows.size() == 1 ? columns.stream().max(Integer::compare).get() : rows.stream().max(Integer::compare).get();
				check(high - low + 1 == ship.size(), "a ship has a gap: " + ship);
			}
		}
	}

	private static InteractionResult click(ServerPlayer player, ServerLevel level, BlockPos pos) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
		return player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
	}

	private static BlockPos square(Arena arena, int team, int cell) {
		BlockPos corner = arena.bases.get(team);
		return corner.offset(cell % Battleship.SIZE, -1, cell / Battleship.SIZE);
	}

	private static String block(ServerLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}

	private static Arena arena(MinecraftServer server) {
		List<Arena> running = Arenas.running(server);
		return running.isEmpty() ? null : running.get(0);
	}

	private static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}
}
