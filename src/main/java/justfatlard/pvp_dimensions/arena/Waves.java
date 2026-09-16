package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Mobs in waves, where the preset asks for them: each wave its own kinds, so many of each for every
 * player fighting, sent all at once around the players. The next comes a short break after the
 * last is cleared, or sooner if the preset gives a wave only so long; the last few of a wave glow,
 * so a straggler behind a hill doesn't hold everyone up.
 *
 * <p>After the last wave: it comes again, the waves start over, or they stop; and where surviving
 * the waves is the goal, clearing the last one wins.
 */
public final class Waves {
	private Waves() {}

	private static final RandomSource RANDOM = RandomSource.create();
	/** Before the first wave, a moment to get your bearings. */
	private static final long FIRST_BREAK_MILLIS = 10_000L;
	/** A wave is never more than this many, however many players or per player. */
	private static final int MOST = 150;
	/** At this many left, the rest glow. */
	private static final int GLOW_AT = 3;

	/** How many of the wave on now are still standing, by arena, as last counted. */
	private static final Map<String, Integer> left = new HashMap<>();
	/** How many the wave on now started with, by arena. */
	private static final Map<String, Integer> sentCount = new HashMap<>();

	public static void begin(Arena arena, long now) {
		if (arena.preset.mobStyle != Preset.MobStyle.WAVES) return;
		arena.waveIndex = -1;
		arena.waveNumber = 0;
		arena.wavesOver = false;
		arena.nextWaveAt = now + FIRST_BREAK_MILLIS;
	}

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now, int ticks) {
		Preset preset = arena.preset;
		if (preset.mobStyle != Preset.MobStyle.WAVES || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0 || arena.wavesOver) return;
		if (arena.nextWaveAt > 0) {
			if (now >= arena.nextWaveAt) send(server, level, arena, now);
			return;
		}
		if (ticks % 20 != 0) return;

		List<Mob> standing = standing(level, arena);
		left.put(arena.id, standing.size());
		if (standing.size() <= GLOW_AT) {
			for (Mob mob : standing) mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
		}
		if (standing.isEmpty()) {
			cleared(server, arena, now);
		} else if (preset.waveLimit > 0 && now - arena.waveStartedAt >= preset.waveLimit * 60_000L && next(arena) >= 0
				&& !(preset.activeGoal() == Preset.Goal.WAVES && last(arena))) {
			Arenas.tellInside(server, arena, "The next wave won't wait");
			send(server, level, arena, now);
		}
	}

	private static void cleared(MinecraftServer server, Arena arena, long now) {
		Preset preset = arena.preset;
		left.remove(arena.id);
		Moments.waveCleared(server, arena, arena.waveNumber);
		if (last(arena) && preset.activeGoal() == Preset.Goal.WAVES) {
			arena.wavesOver = true;
			Goals.winTogether(server, arena, "every wave cleared");
			return;
		}
		if (next(arena) < 0) {
			arena.wavesOver = true;
			Arenas.tellInside(server, arena, "Wave " + arena.waveNumber + " cleared. That was the last of them");
			return;
		}
		arena.nextWaveAt = now + preset.waveBreak * 1000L;
		Arenas.tellInside(server, arena, "Wave " + arena.waveNumber + " cleared! The next comes in " + preset.waveBreak + " seconds");
		Arenas.vault(server).touch();
	}

	private static boolean last(Arena arena) {
		return arena.waveIndex >= arena.preset.waves.size() - 1;
	}

	/** Which wave comes next, or -1 for none. */
	private static int next(Arena arena) {
		int count = arena.preset.waves.size();
		if (count == 0) return -1;
		if (arena.waveIndex + 1 < count) return arena.waveIndex + 1;
		return switch (arena.preset.afterWaves) {
			case REPEAT_LAST -> count - 1;
			case START_OVER -> 0;
			case STOP -> -1;
		};
	}

	/** The next wave, all at once, around whoever is fighting; later, if nobody is there to meet it. */
	private static void send(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		int index = next(arena);
		if (index < 0) {
			arena.wavesOver = true;
			return;
		}
		List<ServerPlayer> players = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null && player.level() == level && !player.isSpectator() && player.isAlive()) players.add(player);
		}
		if (players.isEmpty()) {
			arena.nextWaveAt = now + 5_000L;
			return;
		}

		Preset.Wave wave = arena.preset.waves.get(index);
		int sent = 0;
		for (Preset.WaveMob entry : wave.mobs) {
			String kind = entry.kind();
			int count = Math.max(1, Math.round(entry.tenths() * players.size() / 10F));
			for (int i = 0; i < count && sent < MOST; i++, sent++) {
				ServerPlayer near = players.get(RANDOM.nextInt(players.size()));
				if (!Mobs.spawnNear(level, arena, near, kind, players, true)) Mobs.spawnAt(level, kind, Spawns.random(level, arena), true, near);
			}
		}

		arena.waveIndex = index;
		arena.waveNumber++;
		arena.waveStartedAt = now;
		arena.nextWaveAt = 0;
		left.put(arena.id, sent);
		sentCount.put(arena.id, sent);
		Moments.waveSent(arena);
		Arenas.vault(server).touch();

		String title = "Wave " + arena.waveNumber;
		String under = sent + (sent == 1 ? " mob" : " mobs") + (last(arena) && arena.preset.activeGoal() == Preset.Goal.WAVES ? ", the last" : "");
		for (ServerPlayer player : players) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(under).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title).withStyle(ChatFormatting.RED)));
		}
	}

	private static List<Mob> standing(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		return level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && mob.entityTags().contains(Mobs.OURS));
	}

	/** What the bar says about the waves: the one on and what is left of it, or how long till the next. */
	public static @Nullable String status(Arena arena, long now) {
		if (arena.preset.mobStyle != Preset.MobStyle.WAVES || arena.wavesOver) return null;
		String of = arena.preset.activeGoal() == Preset.Goal.WAVES ? " of " + arena.preset.waves.size() : "";
		if (arena.nextWaveAt > 0) {
			long seconds = Math.max(0, (arena.nextWaveAt - now + 999) / 1000);
			return "Wave " + (arena.waveNumber + 1) + of + " in " + seconds + "s";
		}
		return "Wave " + arena.waveNumber + of + ": " + left.getOrDefault(arena.id, 0) + " left";
	}

	/** How many of the wave on now still stand, and how many it started with. */
	static int[] left(Arena arena) {
		return new int[] {left.getOrDefault(arena.id, 0), sentCount.getOrDefault(arena.id, 0)};
	}

	public static void forget(Arena arena) {
		left.remove(arena.id);
		sentCount.remove(arena.id);
	}
}
