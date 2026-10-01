package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import justfatlard.pvp_dimensions.Say;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Hot potato: a ball with TNT inside, on a fuse nobody can see until it starts to flash. Whoever
 * touched it last when it goes is caught: a life gone where there are lives, out otherwise. Then a
 * new one, and the last side not caught wins.
 *
 * <p>The blast is the arena's, not the game's. The cube's own would crater the ground and kill
 * whoever stood near; this one only throws them back. The cube is lit for the flashing and the
 * hiss, and taken away a moment before its own fuse would have gone off.
 */
public final class HotPotato {
	private HotPotato() {}

	private static final RandomSource RANDOM = RandomSource.create();
	/** The quiet part of the fuse, before the ball starts to flash: somewhere in this range. */
	private static final long QUIET_LEAST = 8_000L;
	private static final long QUIET_MOST = 20_000L;
	/** Between one blast and the next ball. */
	private static final long BETWEEN = 3_000L;
	/** Fuse ticks left at which the arena's blast takes the place of the cube's own. */
	private static final int CAUGHT_AT = 3;
	private static final double BLAST_REACH = 5;

	/** When each arena's ball is lit; and when the next comes, between one blast and another. */
	private static final Map<String, Long> litAt = new HashMap<>();
	private static final Map<String, Long> nextAt = new HashMap<>();

	static void begin(ServerLevel level, Arena arena, long now) {
		nextAt.put(arena.id, now);
	}

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Long next = nextAt.get(arena.id);
		if (next != null) {
			if (now < next) return;
			nextAt.remove(arena.id);
			Balls.Ball fresh = Balls.single(level, arena, new ItemStack(Items.TNT));
			if (fresh != null) Balls.place(fresh, Balls.middle(level, arena));
			litAt.put(arena.id, now + QUIET_LEAST + (long) (RANDOM.nextDouble() * (QUIET_MOST - QUIET_LEAST)));
			Arenas.tellInside(server, arena, "A new ball is in the middle. Don't be the one holding it");
			return;
		}
		Balls.Ball ball = Balls.single(level, arena, new ItemStack(Items.TNT));
		if (ball == null) return;
		long lit = litAt.computeIfAbsent(arena.id, id -> now + QUIET_LEAST);
		if (!ball.cube.isPrimed() && now >= lit) ball.cube.primeTime(false);
		// The cube lights only where TNT is let explode; where it is not, the arena's clock says when.
		boolean due = ball.cube.isPrimed() ? ball.cube.getFuse() <= CAUGHT_AT : now >= lit + 6_000L;
		if (due) blast(server, level, arena, ball, now);
		else if (!ball.cube.isPrimed()) Balls.rescue(server, arena, ball, Balls.middle(level, arena), now);
	}

	private static void blast(MinecraftServer server, ServerLevel level, Arena arena, Balls.Ball ball, long now) {
		Vec3 at = ball.cube.position();
		Arena.Member caught = Balls.lastTouch(arena, ball);
		Balls.remove(arena, ball);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 1.0F);
		for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, ball.cube.getBoundingBox().inflate(BLAST_REACH))) {
			Vec3 away = player.position().subtract(at).normalize().scale(1.2);
			player.push(away.x, 0.5, away.z);
			player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), player.getDeltaMovement()));
		}
		litAt.remove(arena.id);
		nextAt.put(arena.id, now + BETWEEN);

		if (caught == null) {
			Arenas.tellInside(server, arena, "Boom! Nobody had touched it");
			return;
		}
		Goals.died(arena, caught);
		if (!arena.preset.livesOn()) caught.out = true;
		Arenas.vault(server).touch();
		ServerPlayer player = server.getPlayerList().getPlayer(caught.id);
		Arenas.tellInside(server, arena, "Boom! " + caught.name + " was holding it" + (caught.out ? " and is out" : ""));
		if (player != null) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(caught.out ? "You're out" : livesWords(arena, caught)).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Caught!").withStyle(ChatFormatting.RED)));
			if (caught.out) {
				player.setGameMode(GameType.SPECTATOR);
				Say.to(player, "Caught holding the ball; you're watching now");
			}
		}
		lastSide(server, arena);
	}

	private static String livesWords(Arena arena, Arena.Member member) {
		if (arena.preset.pooledLives() && member.team >= 0) return "Your team has " + arena.pools.getOrDefault(member.team, 0) + " lives left";
		return member.lives + (member.lives == 1 ? " life left" : " lives left");
	}

	/** One side left not caught: theirs. */
	private static void lastSide(MinecraftServer server, Arena arena) {
		if (arena.members.size() < 2) return;
		Set<String> sides = new HashSet<>();
		Arena.Member last = null;
		for (Arena.Member member : arena.fighting()) {
			sides.add(arena.preset.teamsOn() ? "team" + member.team : member.id.toString());
			last = member;
		}
		if (sides.size() != 1 || last == null) return;
		if (arena.preset.teamsOn()) Goals.winTeam(server, arena, last.team, "the last team not caught");
		else Goals.winPlayer(server, arena, last, "the last one not caught");
	}

	/** The bar's line: how many are still in. */
	static String status(Arena arena) {
		return arena.fighting().size() + " still in  ·  don't be holding it when it blows";
	}

	public static void forget(Arena arena) {
		litAt.remove(arena.id);
		nextAt.remove(arena.id);
	}
}
