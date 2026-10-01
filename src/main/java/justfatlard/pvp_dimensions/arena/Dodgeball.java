package justfatlard.pvp_dimensions.arena;

import java.util.List;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Dodgeball: a hits match where what lands is a ball. A ball kicked into somebody, moving fast
 * enough to be a throw and not a roll, is a hit for whoever kicked it. Counted by {@link Hits}
 * like any other hit, so the targets, the teams and the winning are the same as a snowball fight.
 */
public final class Dodgeball {
	private Dodgeball() {}

	/** Blocks a tick a ball has to be going to hit somebody rather than bump them. */
	private static final double HITTING = 0.15;
	private static final double SPREAD = 4;

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		if (!Hits.counts(arena.preset, Preset.HitKind.BALL)) return;
		List<Balls.Ball> balls = Balls.of(level, arena);
		Vec3 middle = Balls.middle(level, arena);
		while (balls.size() < arena.preset.ballCount) {
			double angle = Math.PI * 2 * balls.size() / arena.preset.ballCount;
			Vec3 at = Spawns.near(level, arena, (int) Math.floor(middle.x + Math.cos(angle) * SPREAD), (int) Math.floor(middle.z + Math.sin(angle) * SPREAD), 1);
			if (Balls.spawn(level, arena, at, Balls.swallowed(arena.preset.ballBlock), null) == null) return;
		}
		for (Balls.Ball ball : List.copyOf(balls)) Balls.rescue(server, arena, ball, middle, now);
	}

	/** A ball has reached a player: a hit, if somebody else sent it and it came hard enough. */
	static void struck(MinecraftServer server, Arena arena, Balls.Ball ball, ServerPlayer player) {
		if (!Hits.counts(arena.preset, Preset.HitKind.BALL) || ball.lastTouch == null || ball.lastTouch.equals(player.getUUID())) return;
		if (ball.cube.getDeltaMovement().horizontalDistance() < HITTING) return;
		ServerPlayer thrower = server.getPlayerList().getPlayer(ball.lastTouch);
		if (thrower == null) return;
		Hits.landed(server, arena, thrower, player);
	}
}
