package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.SideTripApi;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.onboarding.Demos;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A player stepping out of a demo to another mod's world and back: the demo waits for them however
 * long they are gone, their own things stay put aside, coming back hands them the demo's pack, and
 * the demo ending while they are away calls them back before handing their own things back. The
 * other mod is a stand-in host that holds packs aside the way a Miniature Bench's workshop does.
 */
public final class SideTripsWait implements FabricClientGameTest {
	private record Trip(ResourceKey<Level> from, Vec3 at, List<ItemStack> pack) {}

	private static final Map<UUID, Trip> trips = new HashMap<>();
	private static boolean hosting;

	private static void host() {
		if (hosting) return;
		hosting = true;
		PandoricalApi.sideTrips().host(new SideTripApi.Host() {
			@Override
			public boolean holds(ServerPlayer player) {
				return trips.containsKey(player.getUUID());
			}

			@Override
			public void sendBack(ServerPlayer player) {
				Trip trip = trips.remove(player.getUUID());
				player.getInventory().clearContent();
				for (ItemStack stack : trip.pack()) player.getInventory().add(stack.copy());
				ServerLevel there = player.level().getServer().getLevel(trip.from());
				player.teleportTo(there, trip.at().x, trip.at().y, trip.at().z, Set.of(), 0, 0, true);
			}
		});
	}

	private static void stepAside(ServerPlayer player) {
		List<ItemStack> pack = new ArrayList<>();
		for (ItemStack stack : player.getInventory()) if (!stack.isEmpty()) pack.add(stack.copy());
		trips.put(player.getUUID(), new Trip(player.level().dimension(), player.position(), pack));
		player.getInventory().clearContent();
		player.getInventory().add(new ItemStack(Items.BRICK, 2));
		ServerLevel overworld = player.level().getServer().overworld();
		BlockPos spawn = overworld.getRespawnData().pos();
		player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), 0, 0, true);
	}

	private static int packSize(ServerPlayer player) {
		int count = 0;
		for (ItemStack stack : player.getInventory()) count += stack.getCount();
		return count;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				host();
				ServerPlayer player = connection.getServerPlayer();
				check(!PandoricalApi.sideTrips().mayStepAside(player), "a player in the ordinary world was let step aside as if from a demo");
				player.getInventory().clearContent();
				player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
				Demos.play(player, PvpDimensions.MOD_ID);
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) != null, 2400);

			int[] kit = new int[1];
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(PandoricalApi.sideTrips().mayStepAside(player), "a demo did not let its player step aside");
				kit[0] = packSize(player);
				stepAside(player);
				check(Visit.of(player) != null, "stepping aside ended the demo visit");
			});
			// Longer than the sweep's three seconds of grace for anyone gone from their arena.
			context.waitTicks(120);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				Arena arena = Arenas.of(player);
				check(arena != null && arena.phase == Arena.Phase.LIVE, "the demo did not wait for a player away on a side trip");
				check(player.getInventory().countItem(Items.BRICK) == 2 && player.getInventory().countItem(Items.DIAMOND) == 0,
					"the player's own pack was handed back while another mod held theirs aside");
				PandoricalApi.sideTrips().callBack(player);
				check(player.level().dimension().equals(arena.dimension), "back from the side trip, the player is not in the demo");
				check(Visit.of(player) != null, "coming back ended the demo visit");
				check(packSize(player) == kit[0] && player.getInventory().countItem(Items.BRICK) == 0,
					"back from the side trip, the player does not hold the demo's pack");

				stepAside(player);
				Arenas.end(s, arena, "a test ending it");
			});
			server.waitFor(s -> Visit.of(connection.getServerPlayer()) == null, 400);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(!PandoricalApi.sideTrips().away(player), "the demo ended without calling the player back from their side trip");
				check(player.getInventory().countItem(Items.DIAMOND) == 3, "the player's own pack did not come home: " + packSize(player));
				check(player.getInventory().countItem(Items.BRICK) == 0, "the side trip's pack came home");
				check(packSize(player) == 3, "something of the demo's came home with the player's own pack");
			});
		}
	}
}
