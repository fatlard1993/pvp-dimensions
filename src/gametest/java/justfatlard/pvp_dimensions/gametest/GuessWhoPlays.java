package justfatlard.pvp_dimensions.gametest;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.GuessWho;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Guess who, played: two full galleries of suspects dressed as they are described, a lever in your
 * own gallery that flips its suspect down and back up and one in theirs that does nothing, a first
 * right-click on a suspect that only asks, and a second on the right one that wins it.
 */
public final class GuessWhoPlays implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runOnServer(s -> {
				Preset preset = Kinds.GUESS_WHO.make();
				preset.waitingRoom = false;
				Arena arena = Arenas.start(s, connection.getServerPlayer(), "guess-who-test", preset,
					new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, true), complaint -> {
						throw new AssertionError("the arena would not start: " + complaint);
					});
				check(arena != null, "no arena");
			});
			server.waitFor(s -> arena(s) != null && arena(s).phase == Arena.Phase.LIVE && GuessWho.side(arena(s)) >= 0, 2400);
			// Long enough for a tick of the game to have held the young back.
			context.waitTicks(40);

			server.runOnServer(s -> {
				Arena arena = arena(s);
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel level = Arenas.level(s, arena);
				int team = arena.member(player.getUUID()).team;
				check(team == 0 || team == 1, "the player is on no side: " + team);
				int enemy = 1 - team;

				var footprint = arena.footprint();
				AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
				int suspects = level.getEntitiesOfClass(Mob.class, area, mob -> mob.entityTags().contains(GuessWho.TAG)).size();
				check(suspects == 2 * GuessWho.SUSPECT_COUNT, suspects + " suspects, not two galleries of " + GuessWho.SUSPECT_COUNT);

				// Basil, second in the gallery, is a desert librarian; Hazel, eighth, a child.
				Mob basil = GuessWho.suspectAt(arena, team, 1);
				check(basil instanceof Villager, "Basil is not a villager");
				var data = ((Villager) basil).getVillagerData();
				check(data.type().is(VillagerType.DESERT) && data.profession().is(VillagerProfession.LIBRARIAN), "Basil is " + data);
				check("Basil".equals(basil.getCustomName().getString()), "Basil is named " + basil.getCustomName().getString());
				check(GuessWho.suspectAt(arena, team, 7).isBaby(), "Hazel, a child, is grown");
				check(!basil.isBaby(), "Basil is a baby");

				// Our lever flips Basil down, and back up.
				check(click(player, level, GuessWho.leverOf(arena, team, 1)).consumesAction(), "our lever did nothing");
				check(GuessWho.isDown(arena, team, 1) && basil.isInvisible(), "Basil was not flipped down");
				click(player, level, GuessWho.leverOf(arena, team, 1));
				check(!GuessWho.isDown(arena, team, 1) && !basil.isInvisible(), "Basil was not stood back up");

				// Theirs does nothing.
				check(click(player, level, GuessWho.leverOf(arena, enemy, 1)) == InteractionResult.FAIL, "their lever answered us");
				check(!GuessWho.isDown(arena, enemy, 1), "we flipped a suspect in their gallery");

				// A right-click on the suspect they are hiding asks first, and is never a trade.
				Mob theirs = GuessWho.suspectAt(arena, team, GuessWho.secretOf(arena, enemy));
				check(GuessWho.side(arena) == team, "it is not our turn with nobody on the other side");
				check(GuessWho.useEntity(player, theirs, InteractionHand.MAIN_HAND).consumesAction(), "a suspect could be traded with or led off");
				check(arena.closesAt == 0, "one right-click accused");
				GuessWho.useEntity(player, theirs, InteractionHand.MAIN_HAND);
				check(arena.closesAt > 0, "the second right-click on their suspect did not end it");
				check(arena.winners.contains(player.getUUID()), "naming the right suspect did not win it");
			});
		}
	}

	private static InteractionResult click(ServerPlayer player, ServerLevel level, BlockPos pos) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		return player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
	}

	private static Arena arena(MinecraftServer server) {
		List<Arena> running = Arenas.running(server);
		return running.isEmpty() ? null : running.get(0);
	}

	private static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}
}
