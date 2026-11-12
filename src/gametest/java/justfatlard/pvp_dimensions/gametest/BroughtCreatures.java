package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Travel;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;

/**
 * Creatures a preset names that the editor doesn't offer, as a demo brings its mod's own: they
 * spawn, a neutral one is left calm rather than set on the player, and a water creature is put in
 * water. An animal and a fish stand in for a mod's own, which this test has none of.
 */
public final class BroughtCreatures implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			start(server, connection, "minecraft:plains", "minecraft:cow", "minecraft:wolf");
			server.waitFor(s -> count(s, EntityTypes.COW) > 0 && count(s, EntityTypes.WOLF) > 0, 1200);
			// Long enough for the arena's every-two-seconds pass that sets its neutral mobs on players.
			context.waitTicks(100);
			server.runOnServer(s -> {
				for (Entity wolf : ours(s, EntityTypes.WOLF)) {
					check(!((Wolf) wolf).isAngry() && ((Wolf) wolf).getTarget() == null, "a wolf the preset brought was set on the player");
				}
				Travel.goHome(connection.getServerPlayer(), Tables.arena(s), Travel.Why.COMMAND);
				Arenas.end(s, Tables.arena(s), "test");
			});
			server.waitFor(s -> Tables.arena(s) == null, 200);

			start(server, connection, "minecraft:warm_ocean", "minecraft:cod");
			server.waitFor(s -> count(s, EntityTypes.COD) > 0, 1200);
			server.runOnServer(s -> {
				for (Entity cod : ours(s, EntityTypes.COD)) {
					check(cod.level().getFluidState(cod.blockPosition()).is(net.minecraft.tags.FluidTags.WATER),
						"a cod the preset brought was put on dry ground at " + cod.position() + ", in " + cod.level().getBlockState(cod.blockPosition()));
				}
			});
		}
	}

	private static void start(TestServerContext server, TestServerConnection connection, String biome, String... kinds) {
		Tables.start(server, connection, Kinds.DEATHMATCH, preset -> {
			preset.pvp = false;
			preset.biome = biome;
			preset.mobs = Preset.MobTime.ALWAYS;
			preset.mobRarity = Preset.Rarity.SWARM;
			preset.mobDefault = Preset.MobLevel.OFF;
			preset.mobLevels.clear();
			for (String kind : kinds) preset.mobLevels.put(kind, Preset.MobLevel.COMMON);
		});
	}

	private static List<? extends Entity> ours(MinecraftServer server, EntityType<?> type) {
		Arena arena = Tables.arena(server);
		var plot = arena.footprint();
		AABB area = new AABB(plot.minX(), -64, plot.minZ(), plot.maxX(), 320, plot.maxZ());
		return Arenas.level(server, arena).getEntities(type, area, entity -> entity.entityTags().contains(justfatlard.pvp_dimensions.arena.Mobs.OURS));
	}

	private static int count(MinecraftServer server, EntityType<?> type) {
		return Tables.arena(server) == null ? 0 : ours(server, type).size();
	}
}
