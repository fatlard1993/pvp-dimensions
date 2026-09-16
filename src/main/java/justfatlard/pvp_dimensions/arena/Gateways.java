package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Footprints;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** What the mixins ask, answered here so the mixins stay one line each. */
public final class Gateways {
	private Gateways() {}

	/** A portal's answer: {@code decided} false leaves it to vanilla; a null trip goes nowhere. */
	public record Answer(boolean decided, @Nullable TeleportTransition trip) {
		static final Answer VANILLA = new Answer(false, null);
		static final Answer NOWHERE = new Answer(true, null);
	}

	/**
	 * Only players go through: an item thrown into a gate would be carried into an arena that keeps
	 * inventories apart, and one thrown into an exit carried out of it.
	 */
	public static Answer destination(ServerLevel level, Entity entity, BlockPos pos) {
		String gate = Portals.gateAt(level, pos);
		if (gate != null) {
			if (!(entity instanceof ServerPlayer player)) return Answer.NOWHERE;
			Arena arena = Arenas.get(level.getServer(), gate);
			if (arena == null || !arena.open() && arena.phase != Arena.Phase.GENERATING) return Answer.NOWHERE;
			// The frame is its own invitation: whoever walks through it is in.
			arena.invited.add(player.getUUID());
			return new Answer(true, Travel.enterByGate(player, arena, pos));
		}
		if (!Places.isArena(level.dimension())) return Answer.VANILLA;
		String exit = Portals.exitAt(level, pos);
		if (exit != null && entity instanceof ServerPlayer player) {
			Arena arena = Arenas.of(player);
			if (arena != null && arena.id.equals(exit)) return new Answer(true, Travel.leave(player, arena, Travel.Why.PORTAL));
		}
		return Answer.NOWHERE;
	}

	/**
	 * Whether a non-player may be moved between these dimensions: not out of an arena that keeps
	 * inventories apart, nor into one, nor into the empty ground between arenas.
	 */
	public static boolean mayCross(Entity entity, ServerLevel from, ServerLevel to, TeleportTransition trip) {
		if (entity instanceof Player) return true;
		if (Places.isArena(from.dimension())) {
			Arena arena = Arenas.at(from, entity.getX(), entity.getZ());
			if (arena == null || arena.preset.isolated()) return false;
		}
		if (Places.isArena(to.dimension())) {
			Arena arena = Arenas.at(to, trip.position().x, trip.position().z);
			if (arena == null || arena.preset.isolated()) return false;
		}
		return true;
	}

	/**
	 * Whether a non-player already in the world may be put straight down at another spot of an
	 * arena dimension: only within the arena it is in. Anything new, still being placed, is free.
	 */
	public static boolean maySnap(Entity entity, double x, double z) {
		if (entity instanceof Player || entity.tickCount == 0) return true;
		if (!(entity.level() instanceof ServerLevel level) || !Places.isArena(level.dimension())) return true;
		Footprint from = Footprints.atBlock(level.dimension(), entity.getX(), entity.getZ());
		Footprint to = Footprints.atBlock(level.dimension(), x, z);
		if (from == null && to == null) return true;
		return from != null && to != null && from.arena().equals(to.arena());
	}

	/**
	 * An arena that lets things pass lets its pets out as it ends, rather than taking them down with
	 * its ground: each to its owner, or to where a dying owner will wake, or to spawn while its owner
	 * is away. An arena that keeps things apart keeps its pets too, the same as anything else won
	 * inside it.
	 */
	public static void petsHome(MinecraftServer server, Arena arena) {
		if (arena.preset.isolated()) return;
		ServerLevel level = Arenas.level(server, arena);
		if (level == null) return;
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (Mob pet : level.getEntitiesOfClass(Mob.class, area, mob -> mob instanceof OwnableEntity owned && owned.getOwnerReference() != null)) {
			ServerPlayer owner = server.getPlayerList().getPlayer(((OwnableEntity) pet).getOwnerReference().getUUID());
			TeleportTransition trip;
			if (owner == null || Places.isArena(owner.level().dimension()) && Visit.of(owner) == null) {
				trip = Travel.spawnTrip(server);
			} else if (Visit.of(owner) instanceof Visit visit) {
				trip = Travel.homeTrip(server, visit.home());
			} else {
				trip = new TeleportTransition(owner.level(), owner.position(), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
			}
			pet.teleport(new TeleportTransition(trip.newLevel(), trip.position(), Vec3.ZERO, pet.getYRot(), pet.getXRot(), TeleportTransition.DO_NOTHING));
		}
	}

	public static void borderFor(ServerPlayer player, ServerLevel level) {
		if (!Places.isArena(level.dimension())) return;
		Arena arena = Arenas.of(player);
		if (arena != null && arena.dimension.equals(level.dimension())) Borders.send(player, arena);
	}
}
