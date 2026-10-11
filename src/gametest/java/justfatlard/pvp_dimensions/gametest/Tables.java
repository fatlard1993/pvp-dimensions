package justfatlard.pvp_dimensions.gametest;

import java.util.List;
import java.util.function.Consumer;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
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

/** What the table-game tests share: an arena started from a kind, a right-click, a look, and a picture. */
final class Tables {
	private Tables() {}

	static void start(TestServerContext server, TestServerConnection connection, Kinds kind, Consumer<Preset> tweak) {
		server.runOnServer(s -> {
			Preset preset = kind.make();
			preset.waitingRoom = false;
			tweak.accept(preset);
			Arena arena = Arenas.start(s, connection.getServerPlayer(), kind.name().toLowerCase() + "-test", preset,
				new Arenas.Start(Arenas.Invite.NOBODY, List.of(), false, true), complaint -> {
					throw new AssertionError("the arena would not start: " + complaint);
				});
			check(arena != null, "no arena");
		});
		server.waitFor(s -> arena(s) != null && arena(s).phase == Arena.Phase.LIVE && !arena(s).bases.isEmpty(), 2400);
	}

	static Arena arena(MinecraftServer server) {
		List<Arena> running = Arenas.running(server);
		return running.isEmpty() ? null : running.get(0);
	}

	/** A real right-click on the top of a block, through the game's own use of one. */
	static InteractionResult click(ServerPlayer player, ServerLevel level, BlockPos pos) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
		return player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
	}

	static String block(ServerLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}

	/** The player stood here, looking at that spot, with time for the view to draw. */
	static void look(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, Vec3 from, Vec3 at) {
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			double dx = at.x - from.x;
			double dy = at.y - (from.y + player.getEyeHeight());
			double dz = at.z - from.z;
			float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			player.teleportTo((ServerLevel) player.level(), from.x, from.y, from.z, java.util.Set.of(), yaw, pitch, true);
		});
		context.waitTicks(40);
		connection.waitForChunksRender();
	}

	static void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 720).disableCounterPrefix());
	}

	static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}
}
