package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Preset;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Hot potato: a ball with TNT inside, stuck over somebody's head, on a fuse nobody can see until it
 * starts to flash. Whoever has it glows; hit somebody, or right-click them, and it is theirs
 * instead. Whoever has it when it goes is caught: a life gone where there are lives, out otherwise.
 * Then a new one, stuck on somebody at random, and the last side not caught wins.
 *
 * <p>With teams it can only be passed to the other side: handing it to a teammate would just be
 * holding it longer. And it cannot be passed straight back, for a moment, so a pass has to be got
 * rid of to somebody else or chased down again.
 *
 * <p>The blast is the arena's, not the game's. The cube's own would crater the ground and kill
 * whoever stood near; this one only throws them back. The cube is lit for the flashing and the
 * hiss, and taken away a moment before its own fuse would have gone off.
 */
public final class HotPotato {
	private HotPotato() {}

	private static final RandomSource RANDOM = RandomSource.create();
	/** The quiet part of the fuse, before the ball starts to flash: somewhere in this range. */
	private static final long QUIET_LEAST = 12_000L;
	private static final long QUIET_MOST = 25_000L;
	/** Between one blast and the next ball. */
	private static final long BETWEEN = 3_000L;
	/** How long somebody who just passed it cannot be given it back. */
	private static final long NO_PASS_BACK = 1_500L;
	/** Fuse ticks left at which the arena's blast takes the place of the cube's own. */
	private static final int CAUGHT_AT = 3;
	private static final double BLAST_REACH = 5;
	/** How far over the holder's feet the ball sits: on top of their head. */
	private static final double OVERHEAD = 2.05;

	private static final class Potato {
		@Nullable UUID holder;
		@Nullable UUID passedBy;
		long passedAt;
		long litAt;
	}

	private static final Map<String, Potato> potatoes = new HashMap<>();
	/** When the next ball comes, between one blast and another. */
	private static final Map<String, Long> nextAt = new HashMap<>();

	static void begin(ServerLevel level, Arena arena, long now) {
		nextAt.put(arena.id, now);
	}

	// --- Every tick: the ball kept over its holder's head ---

	public static void physics(MinecraftServer server) {
		if (potatoes.isEmpty()) return;
		for (Arena arena : List.copyOf(Arenas.vault(server).arenas().values())) {
			Potato potato = potatoes.get(arena.id);
			if (potato == null || potato.holder == null || arena.phase != Arena.Phase.LIVE) continue;
			ServerLevel level = Arenas.level(server, arena);
			ServerPlayer holder = server.getPlayerList().getPlayer(potato.holder);
			if (level == null || holder == null || holder.level() != level) continue;
			Balls.Ball ball = Balls.single(level, arena, new ItemStack(Items.TNT));
			if (ball == null) continue;
			ball.cube.setNoGravity(true);
			ball.cube.setDeltaMovement(Vec3.ZERO);
			ball.cube.setPos(holder.getX(), holder.getY() + OVERHEAD, holder.getZ());
			ball.cube.needsSync = true;
			// Watched every tick: a fuse can run its last few ticks out between two of the slower checks.
			Arena.Member caught = arena.member(potato.holder);
			if (caught != null && ball.cube.isPrimed() && ball.cube.getFuse() <= CAUGHT_AT) blast(server, level, arena, ball, caught, System.currentTimeMillis());
		}
	}

	// --- Every half second ---

	static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now) {
		Long next = nextAt.get(arena.id);
		if (next != null) {
			if (now < next) return;
			nextAt.remove(arena.id);
			Potato potato = new Potato();
			potato.litAt = now + QUIET_LEAST + (long) (RANDOM.nextDouble() * (QUIET_MOST - QUIET_LEAST));
			potatoes.put(arena.id, potato);
			Balls.single(level, arena, new ItemStack(Items.TNT));
			Arena.Member first = anybody(arena, null);
			if (first != null) give(server, arena, potato, first, null);
			return;
		}
		Potato potato = potatoes.get(arena.id);
		Balls.Ball ball = Balls.single(level, arena, new ItemStack(Items.TNT));
		if (potato == null || ball == null) return;

		// Whoever has it has to still be playing: gone, out or watching, and it is somebody else's.
		Arena.Member holder = potato.holder == null ? null : arena.member(potato.holder);
		if (holder == null || !holder.inside || holder.out || holder.watching) {
			Arena.Member next2 = anybody(arena, null);
			if (next2 == null) return;
			give(server, arena, potato, next2, null);
			holder = next2;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(holder.id);
		if (player != null) {
			player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
			Say.bar(player, "You have the potato! Hit somebody to pass it");
		}

		if (!ball.cube.isPrimed() && now >= potato.litAt) ball.cube.primeTime(false);
		// The cube lights only where TNT is let explode; where it is not, the arena's clock says when.
		if (!ball.cube.isPrimed() && now >= potato.litAt + 6_000L) blast(server, level, arena, ball, holder, now);
	}

	/** Somebody still playing to stick it on, other than this one; on the other side where there are sides. */
	private static Arena.@Nullable Member anybody(Arena arena, Arena.@Nullable Member not) {
		List<Arena.Member> can = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) {
			if (!member.inside || member == not) continue;
			if (not != null && arena.preset.teamsOn() && member.team == not.team) continue;
			can.add(member);
		}
		return can.isEmpty() ? null : can.get(RANDOM.nextInt(can.size()));
	}

	private static void give(MinecraftServer server, Arena arena, Potato potato, Arena.Member to, Arena.@Nullable Member from) {
		ServerPlayer old = potato.holder == null ? null : server.getPlayerList().getPlayer(potato.holder);
		if (old != null) old.removeEffect(MobEffects.GLOWING);
		potato.holder = to.id;
		potato.passedBy = from == null ? null : from.id;
		potato.passedAt = System.currentTimeMillis();
		ServerPlayer player = server.getPlayerList().getPlayer(to.id);
		if (player != null) {
			player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
			player.connection.send(new ClientboundSetTitlesAnimationPacket(3, 25, 8));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Hit somebody to pass it on").withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("You've got the potato!").withStyle(ChatFormatting.RED)));
			player.level().playSound(null, player.getX(), player.getY() + 2, player.getZ(), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 1.0F, 1.0F);
		}
		Arenas.tellInside(server, arena, from == null ? to.name + " has the potato" : from.name + " passes the potato to " + to.name);
	}

	/**
	 * A player hit or right-clicked somebody: the potato passed, if it was theirs to pass.
	 *
	 * @return whether it was, so the hit itself goes no further
	 */
	public static boolean pass(ServerPlayer from, Entity target) {
		if (!(target instanceof ServerPlayer to)) return false;
		Arena arena = Arenas.of(from);
		if (arena == null || arena.phase != Arena.Phase.LIVE || arena.closesAt > 0 || arena.preset.activeGoal() != Preset.Goal.POTATO) return false;
		Potato potato = potatoes.get(arena.id);
		if (potato == null || !from.getUUID().equals(potato.holder)) return false;
		Arena.Member giver = arena.member(from.getUUID());
		Arena.Member taker = arena.member(to.getUUID());
		if (giver == null || taker == null || taker.out || taker.watching || !taker.inside) return false;
		if (arena.preset.teamsOn() && giver.team == taker.team) {
			Say.bar(from, "Not to your own side: pass it to the other team");
			return true;
		}
		if (to.getUUID().equals(potato.passedBy) && System.currentTimeMillis() - potato.passedAt < NO_PASS_BACK) {
			Say.bar(from, "No passing straight back");
			return true;
		}
		give(from.level().getServer(), arena, potato, taker, giver);
		return true;
	}

	/** Whether this cube is a hot potato, which nobody kicks or shoves: it goes where its holder goes. */
	public static boolean isPotato(SulfurCube cube) {
		if (!cube.entityTags().contains(Balls.TAG) || !(cube.level() instanceof ServerLevel level)) return false;
		Arena arena = Arenas.at(level, cube.getX(), cube.getZ());
		return arena != null && arena.preset.activeGoal() == Preset.Goal.POTATO;
	}

	/** Who has it now, for the tests and the board. */
	public static @Nullable UUID holder(Arena arena) {
		Potato potato = potatoes.get(arena.id);
		return potato == null ? null : potato.holder;
	}

	private static void blast(MinecraftServer server, ServerLevel level, Arena arena, Balls.Ball ball, Arena.Member caught, long now) {
		Vec3 at = ball.cube.position();
		Balls.remove(arena, ball);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 1.0F);
		for (ServerPlayer near : level.getEntitiesOfClass(ServerPlayer.class, ball.cube.getBoundingBox().inflate(BLAST_REACH))) {
			Vec3 away = near.position().subtract(at).normalize().scale(1.2);
			near.push(away.x, 0.5, away.z);
			near.connection.send(new ClientboundSetEntityMotionPacket(near.getId(), near.getDeltaMovement()));
		}
		potatoes.remove(arena.id);
		nextAt.put(arena.id, now + BETWEEN);

		Goals.died(arena, caught);
		if (!arena.preset.livesOn()) caught.out = true;
		Arenas.vault(server).touch();
		ServerPlayer player = server.getPlayerList().getPlayer(caught.id);
		Arenas.tellInside(server, arena, "Boom! " + caught.name + " was holding it" + (caught.out ? " and is out" : ""));
		if (player != null) {
			player.removeEffect(MobEffects.GLOWING);
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(caught.out ? "You're out" : livesWords(arena, caught)).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Caught!").withStyle(ChatFormatting.RED)));
			if (caught.out) {
				player.setGameMode(GameType.SPECTATOR);
				Say.to(player, "Caught holding the potato; you're watching now");
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

	/** The bar's line: who has it, and how many are still in. */
	static String status(Arena arena) {
		Potato potato = potatoes.get(arena.id);
		Arena.Member holder = potato == null || potato.holder == null ? null : arena.member(potato.holder);
		return (holder == null ? "A new potato is coming" : holder.name + " has the potato") + "  ·  " + arena.fighting().size() + " still in";
	}

	public static void forget(Arena arena) {
		potatoes.remove(arena.id);
		nextAt.remove(arena.id);
	}
}
