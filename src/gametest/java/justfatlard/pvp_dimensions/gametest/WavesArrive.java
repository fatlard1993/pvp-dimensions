package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Mobs;
import justfatlard.pvp_dimensions.arena.Waves;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * A wave is announced whole and set down over the second after, a few a tick: the count shown is
 * the whole wave from the start, it is all there in the end, and a game that ends while it is
 * being set down sets down no more of it.
 */
public final class WavesArrive implements FabricClientGameTest {
	private static final int WAVE = 40;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			// The whole wave arrives, though not in the tick it is announced.
			start(server, connection);
			AtomicInteger first = new AtomicInteger(-1);
			AtomicReference<String> status = new AtomicReference<>();
			server.waitFor(s -> {
				Arena arena = Tables.arena(s);
				if (arena == null || arena.waveNumber < 1) return false;
				first.set(ours(s, arena));
				status.set(Waves.status(arena, System.currentTimeMillis()));
				return true;
			}, 1200);
			check(first.get() < WAVE, "the whole wave of " + WAVE + " was set down in one tick");
			check(status.get() != null && status.get().endsWith(": " + WAVE + " left"),
				"the wave being set down was not shown as the whole wave: " + status.get());
			server.waitFor(s -> ours(s, Tables.arena(s)) >= WAVE, 100);
			server.runOnServer(s -> Arenas.end(s, Tables.arena(s), "the test is over"));
			server.waitFor(s -> Tables.arena(s) == null, 200);

			// Ended while it is being set down: no more of it comes.
			start(server, connection);
			AtomicReference<Arena> ended = new AtomicReference<>();
			server.waitFor(s -> {
				Arena arena = Tables.arena(s);
				if (arena == null || arena.waveNumber < 1) return false;
				first.set(ours(s, arena));
				Arenas.end(s, arena, "ended mid-wave");
				ended.set(arena);
				return true;
			}, 1200);
			context.waitTicks(30);
			server.runOnServer(s -> {
				int now = ours(s, ended.get());
				check(now <= first.get(), "a wave went on being set down after its game ended: " + first.get() + " then " + now);
			});
		}
	}

	private static void start(TestServerContext server, TestServerConnection connection) {
		Tables.start(server, connection, Kinds.HOLD_THE_LINE, preset -> {
			preset.shape = Preset.Shape.FLAT;
			preset.waveDelay = 1;
			preset.waveLimit = 0;
			preset.afterWaves = Preset.AfterWaves.STOP;
			Preset.Wave wave = new Preset.Wave();
			wave.mobs.add(new Preset.WaveMob("minecraft:zombie", WAVE * 10));
			preset.waves = new java.util.ArrayList<>(List.of(wave));
		});
		server.runOnServer(s -> {
			ServerPlayer player = connection.getServerPlayer();
			player.getAbilities().invulnerable = true;
			player.onUpdateAbilities();
		});
	}

	private static int ours(MinecraftServer server, Arena arena) {
		ServerLevel level = Arenas.level(server, arena);
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		return level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && mob.entityTags().contains(Mobs.OURS)).size();
	}
}
