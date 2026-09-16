package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Each arena's own weather. The arena worlds would otherwise share the overworld's, and arenas
 * share a world; so the world's own weather is stopped there, and each arena tells the players in
 * it how hard it is raining and thundering, eased in and out over a few seconds.
 *
 * <p>The rain is real where it falls: it soaks, puts out fires, lets a trident carry its thrower,
 * and thunder brings lightning down near the players. Only in the overworld's arenas: the nether
 * and the end have no sky to rain from.
 */
public final class Weather {
	private Weather() {}

	private static final RandomSource RANDOM = RandomSource.create();
	/** How far rain and thunder move toward what they should be, each half second. */
	private static final float EASE = 0.05F;
	/** Half seconds between one bolt and the next, on average, in a thunderstorm. */
	private static final int BOLTS = 30;

	/** Each arena's rain and thunder as last shown, nought to one. */
	private static final Map<String, float[]> shown = new HashMap<>();
	/** The weather each arena last announced. */
	private static final Map<String, Preset.Weather> said = new HashMap<>();

	/** Whether the arena runs weather at all: it needs a sky. */
	public static boolean has(Arena arena) {
		return arena.preset.world == Preset.World.OVERWORLD;
	}

	/** The weather this arena should have now, by its preset and the clock. */
	public static Preset.Weather now(Arena arena, long now) {
		Preset preset = arena.preset;
		boolean later = preset.weatherLater != Preset.Weather.OUTSIDE && arena.liveAt > 0
			&& now >= arena.liveAt + preset.weatherAfter * 60_000L;
		return later ? preset.weatherLater : preset.weather;
	}

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (!has(arena)) return;
		Preset.Weather weather = now(arena, now);
		float rain;
		float thunder;
		if (weather == Preset.Weather.OUTSIDE) {
			ServerLevel overworld = server.overworld();
			rain = overworld.getRainLevel(1F);
			thunder = overworld.getThunderLevel(1F);
		} else {
			rain = weather == Preset.Weather.CLEAR ? 0F : 1F;
			thunder = weather == Preset.Weather.THUNDER ? 1F : 0F;
		}
		float[] sky = shown.computeIfAbsent(arena.id, id -> new float[] {rain, thunder});
		sky[0] = ease(sky[0], rain);
		sky[1] = ease(sky[1], thunder);

		Preset.Weather before = said.put(arena.id, weather);
		if (before != null && before != weather && weather != Preset.Weather.OUTSIDE) {
			Arenas.tellInside(server, arena, switch (weather) {
				case CLEAR -> "The sky is clearing";
				case RAIN -> "Here comes the rain";
				case THUNDER -> "A storm is rolling in";
				case OUTSIDE -> "";
			});
		}

		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || player.level() != level) continue;
			player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, sky[0]));
			player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, sky[1]));
		}
		if (sky[1] > 0.9F && sky[0] > 0.9F && RANDOM.nextInt(BOLTS) == 0) bolt(server, level, arena);
	}

	private static float ease(float from, float to) {
		if (Math.abs(to - from) <= EASE) return to;
		return from + Math.signum(to - from) * EASE;
	}

	/** Lightning somewhere near one of the players, where the rain can reach the ground. */
	private static void bolt(MinecraftServer server, ServerLevel level, Arena arena) {
		List<Arena.Member> fighting = arena.fighting();
		if (fighting.isEmpty()) return;
		ServerPlayer near = server.getPlayerList().getPlayer(fighting.get(RANDOM.nextInt(fighting.size())).id);
		if (near == null || near.level() != level) return;
		int x = (int) near.getX() + RANDOM.nextInt(49) - 24;
		int z = (int) near.getZ() + RANDOM.nextInt(49) - 24;
		if (!arena.footprint().containsBlock(x, z) || !level.hasChunk(x >> 4, z >> 4)) return;
		BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
		if (level.precipitationAt(ground) != Biome.Precipitation.RAIN) return;
		LightningBolt lightning = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
		if (lightning == null) return;
		lightning.snapTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
		level.addFreshEntity(lightning);
	}

	/**
	 * What falls here, in an arena world: the arena's own rain, where it is raining hard enough to
	 * reach the ground and the sky is open over it, as the biome has it fall.
	 */
	public static Biome.Precipitation at(ServerLevel level, BlockPos pos) {
		Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
		float[] sky = arena == null ? null : shown.get(arena.id);
		if (sky == null || sky[0] < 0.2F) return Biome.Precipitation.NONE;
		if (!level.canSeeSky(pos) || level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
			return Biome.Precipitation.NONE;
		}
		return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
	}

	public static void forget(Arena arena) {
		shown.remove(arena.id);
		said.remove(arena.id);
	}
}
