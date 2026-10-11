package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.arena;
import static justfatlard.pvp_dimensions.gametest.Tables.check;

import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Memory;
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
 * Memory, played: a deal of eighteen pairs, a right-click turning a card over, two that differ
 * turning back down after a look, a pair staying up and counting, and every pair found ending it.
 */
public final class MemoryPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			Tables.start(server, connection, Kinds.MEMORY, preset -> {});
			server.waitFor(s -> Memory.side(arena(s)) >= 0, 200);

			BlockPos corner = server.computeOnServer(s -> arena(s).bases.get(0));
			Tables.look(context, server, connection, Vec3.atBottomCenterOf(corner).add(5.5, 0, -4), Vec3.atCenterOf(corner).add(5.5, -1, 5));
			int[] team = new int[1];
			int[] odd = new int[2];
			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				team[0] = arena.member(player.getUUID()).team;
				int[] count = new int[Memory.PAIRS];
				for (int card = 0; card < Memory.PAIRS * 2; card++) count[Memory.faceOf(arena, card)]++;
				for (int face = 0; face < Memory.PAIRS; face++) check(count[face] == 2, "face " + face + " is dealt " + count[face] + " times");
				check(Tables.block(level, Memory.cardPos(arena, 0)).equals("white_concrete"), "a card starts face up");

				// Two that differ, by real clicks: both up, then both down again after a look.
				odd[0] = 0;
				odd[1] = 1;
				while (Memory.faceOf(arena, odd[1]) == Memory.faceOf(arena, odd[0])) odd[1]++;
				check(Tables.click(player, level, Memory.cardPos(arena, odd[0])).consumesAction(), "a click on a card did nothing");
				check(Memory.isUp(arena, odd[0]) && !Tables.block(level, Memory.cardPos(arena, odd[0])).equals("white_concrete"), "the card did not turn over");
				Tables.click(player, level, Memory.cardPos(arena, odd[1]));
				check(Memory.isUp(arena, odd[1]), "the second card did not turn over");
				int third = odd[1] + 1;
				check(!Tables.click(player, level, Memory.cardPos(arena, third)).consumesAction(), "a third card turned while two were up");
			});
			context.waitTicks(3);
			Tables.shoot(context, "memory");
			server.waitFor(s -> !Memory.isUp(arena(s), odd[0]) && !Memory.isUp(arena(s), odd[1]), 200);

			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerLevel level = Arenas.level(s, arena);
				check(Tables.block(level, Memory.cardPos(arena, odd[0])).equals("white_concrete"), "the card shows after turning back");
				// Every pair, one after another: each stays up and counts, and the last ends it.
				for (int card = 0; card < Memory.PAIRS * 2; card++) {
					if (Memory.isUp(arena, card)) continue;
					int mate = card + 1;
					while (Memory.faceOf(arena, mate) != Memory.faceOf(arena, card)) mate++;
					Memory.flip(s, level, arena, team[0], card, "tester");
					Memory.flip(s, level, arena, team[0], mate, "tester");
					check(arena.closesAt > 0 || Memory.isUp(arena, card) && Memory.isUp(arena, mate), "a pair turned back down");
				}
				check(arena.scores.getOrDefault(team[0], 0) == Memory.PAIRS, "pairs counted " + arena.scores.get(team[0]));
				check(arena.closesAt > 0 && arena.winners.contains(connection.getServerPlayer().getUUID()), "every pair found did not win it");
			});
		}
	}
}
