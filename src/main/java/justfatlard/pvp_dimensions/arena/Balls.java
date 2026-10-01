package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The balls the ball games are played with: sulfur cubes with a block swallowed.
 *
 * <p>The game makes such a cube a ball already. It sits still on its own, takes knockback from
 * every hit aimed by where the hitter looks, is shoved along by anyone walking into it, and
 * shrugs off the damage of fists, arrows, falls and blasts. The block it holds is how it plays.
 * What is kept here is what a game needs on top: which balls are the arena's, who touched each
 * last, and getting one back that has gone somewhere nobody can reach.
 */
public final class Balls {
	private Balls() {}

	/** The mark on an arena's ball: what lets a monster the arena did not spawn stay in it. */
	public static final String TAG = "pvp-dimensions.ball";
	/** The mark naming whose ball it is, where everyone has their own. */
	private static final String OWNER = "pvp-dimensions.ball_owner=";
	/** How long a ball nobody touches is left where it is: on a goal's roof, say, out of reach. */
	private static final long IDLE_MILLIS = 30_000L;
	private static final double NEAR_HOME = 4;
	/** What is left of a ball's speed when it runs into somebody: a body stops a ball. */
	private static final double TRAPPED = 0.2;

	public static final class Ball {
		final SulfurCube cube;
		final @Nullable UUID owner;
		@Nullable UUID lastTouch;
		long touchedAt = System.currentTimeMillis();

		Ball(SulfurCube cube, @Nullable UUID owner) {
			this.cube = cube;
			this.owner = owner;
		}
	}

	private static final Map<String, List<Ball>> held = new HashMap<>();

	/** Whether this arena's goal is played with balls at all. */
	static boolean played(Preset preset) {
		return preset.playsBall() || preset.activeGoal() == Preset.Goal.POTATO;
	}

	/**
	 * The arena's balls still in play: the ones it holds, or, the first time it is asked after a
	 * restart, the ones marked as its in its ground. None are made here; each game makes what it
	 * is short of.
	 */
	static List<Ball> of(ServerLevel level, Arena arena) {
		List<Ball> balls = held.get(arena.id);
		if (balls != null) {
			balls.removeIf(ball -> !ball.cube.isAlive() || ball.cube.level() != level);
			return balls;
		}
		balls = new ArrayList<>();
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY() - 64, footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (SulfurCube cube : level.getEntitiesOfClass(SulfurCube.class, area, cube -> cube.isAlive() && cube.entityTags().contains(TAG))) {
			balls.add(new Ball(cube, owner(cube)));
		}
		held.put(arena.id, balls);
		return balls;
	}

	/** The one ball of a game played with one, made in the middle where there is none. */
	static @Nullable Ball single(ServerLevel level, Arena arena, ItemStack block) {
		List<Ball> balls = of(level, arena);
		if (balls.isEmpty()) return spawn(level, arena, middle(level, arena), block, null);
		for (int extra = balls.size() - 1; extra > 0; extra--) balls.remove(extra).cube.discard();
		return balls.get(0);
	}

	/** A player's own ball, or null where they have none yet. */
	static @Nullable Ball owned(ServerLevel level, Arena arena, UUID owner) {
		for (Ball ball : of(level, arena)) if (owner.equals(ball.owner)) return ball;
		return null;
	}

	static @Nullable Ball spawn(ServerLevel level, Arena arena, Vec3 at, ItemStack block, @Nullable UUID owner) {
		SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.EVENT);
		if (cube == null) return null;
		cube.addTag(TAG);
		if (owner != null) cube.addTag(OWNER + owner);
		cube.setSize(2, true);
		cube.setItemSlot(EquipmentSlot.BODY, block);
		cube.setDropChance(EquipmentSlot.BODY, 0);
		cube.setPersistenceRequired();
		cube.snapTo(at.x, at.y, at.z, 0, 0);
		Ball ball = new Ball(cube, owner);
		// Held before it is added, so the load that follows finds it already the arena's.
		held.computeIfAbsent(arena.id, id -> new ArrayList<>()).add(ball);
		level.addFreshEntity(cube);
		return ball;
	}

	static void remove(Arena arena, Ball ball) {
		ball.cube.discard();
		List<Ball> balls = held.get(arena.id);
		if (balls != null) balls.remove(ball);
	}

	/** The ball set down here, still, and as if just touched. */
	static void place(Ball ball, Vec3 at) {
		ball.cube.setPos(at.x, at.y, at.z);
		ball.cube.setDeltaMovement(Vec3.ZERO);
		ball.cube.resetFallDistance();
		ball.cube.needsSync = true;
		ball.lastTouch = null;
		ball.touchedAt = System.currentTimeMillis();
	}

	/** A ball nobody has touched for a while, away from home, brought back to it. */
	static void rescue(MinecraftServer server, Arena arena, Ball ball, Vec3 home, long now) {
		if (now - ball.touchedAt < IDLE_MILLIS) return;
		boolean away = ball.cube.position().distanceTo(home) > NEAR_HOME;
		UUID last = ball.lastTouch;
		place(ball, home);
		ball.lastTouch = last;
		if (away) Arenas.tellInside(server, arena, "Nobody could get at the ball; it's back in the middle");
	}

	static Vec3 middle(ServerLevel level, Arena arena) {
		Footprint footprint = arena.footprint();
		return Spawns.near(level, arena, (int) Math.floor(footprint.centerX()), (int) Math.floor(footprint.centerZ()), 2);
	}

	/** The block the preset asks for, if a sulfur cube will swallow it; dirt if not. */
	static ItemStack swallowed(String block) {
		Identifier id = Identifier.tryParse(block);
		ItemStack stack = id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
		return stack.is(ItemTags.SULFUR_CUBE_SWALLOWABLE) ? stack : new ItemStack(Items.DIRT);
	}

	private static @Nullable UUID owner(SulfurCube cube) {
		for (String tag : cube.entityTags()) {
			if (!tag.startsWith(OWNER)) continue;
			try {
				return UUID.fromString(tag.substring(OWNER.length()));
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}

	private static @Nullable Ball find(Arena arena, SulfurCube cube) {
		List<Ball> balls = held.get(arena.id);
		if (balls == null) return null;
		for (Ball ball : balls) if (ball.cube == cube) return ball;
		return null;
	}

	// --- Touches ---

	/**
	 * Somebody hit a ball, by hand or with anything shot or thrown ({@code kicked}), or walked into
	 * it. Theirs is the last touch; and where a ball counts for something as it lands, it is
	 * counted here, before the touch is taken from whoever sent it.
	 */
	public static void touched(SulfurCube cube, @Nullable Entity by, boolean kicked) {
		if (!(by instanceof ServerPlayer player) || !cube.entityTags().contains(TAG)) return;
		Arena arena = Arenas.of(player);
		if (arena == null || !played(arena.preset) || arena.phase != Arena.Phase.LIVE) return;
		Ball ball = find(arena, cube);
		Arena.Member member = arena.member(player.getUUID());
		if (ball == null || member == null || member.watching || member.out) return;
		MinecraftServer server = player.level().getServer();
		if (!kicked) {
			Dodgeball.struck(server, arena, ball, player);
			trap(cube, player);
		}
		if (kicked) Golf.stroke(server, arena, ball, player, member);
		ball.lastTouch = player.getUUID();
		ball.touchedAt = System.currentTimeMillis();
	}

	/**
	 * A ball coming at somebody stops against them. The game only nudges a mob that runs into a
	 * player, so without this a fast ball goes straight through whoever stands in its way. Only a
	 * ball moving towards them: one just kicked is moving away from its kicker.
	 */
	private static void trap(SulfurCube cube, ServerPlayer player) {
		Vec3 going = cube.getDeltaMovement();
		Vec3 toward = player.position().subtract(cube.position());
		if (going.x * toward.x + going.z * toward.z <= 0) return;
		cube.setDeltaMovement(going.x * TRAPPED, Math.min(going.y, 0), going.z * TRAPPED);
		cube.needsSync = true;
	}

	/** Whether walking into a ball shoves it: not in golf, where every move of it is a stroke. */
	public static boolean pushable(SulfurCube cube) {
		if (!cube.entityTags().contains(TAG) || !(cube.level() instanceof ServerLevel level)) return true;
		Arena arena = Arenas.at(level, cube.getX(), cube.getZ());
		return arena == null || arena.preset.activeGoal() != Preset.Goal.GOLF;
	}

	/** Who touched it last, if they are still in the fight. */
	static Arena.@Nullable Member lastTouch(Arena arena, Ball ball) {
		Arena.Member member = ball.lastTouch == null ? null : arena.member(ball.lastTouch);
		return member == null || member.watching || member.out || !member.inside ? null : member;
	}

	/**
	 * Whether a ball loading in may stay: only one its arena is playing with. One left in a chunk
	 * that unloaded was replaced, and would otherwise come back as a second ball.
	 */
	public static boolean keeps(Entity ball, ServerLevel level) {
		Arena arena = Arenas.at(level, ball.getX(), ball.getZ());
		if (arena == null || !played(arena.preset)) return false;
		List<Ball> balls = held.get(arena.id);
		if (balls == null || balls.isEmpty()) return true;
		for (Ball known : balls) if (known.cube == ball) return true;
		return false;
	}

	public static void forget(Arena arena) {
		List<Ball> balls = held.remove(arena.id);
		if (balls != null) for (Ball ball : balls) ball.cube.discard();
	}
}
