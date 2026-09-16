package justfatlard.pvp_dimensions.arena;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Setting a player down safely at the end of a trip home, where everything they own comes back to
 * them. The server gives a chunk it has not loaded no collision, so a player put down in one falls
 * through it, and a trip home comes in a busy tick, with everyone in an ending arena sent at once.
 *
 * <p>So the ground there is loaded before they arrive; a spot with no room to stand is traded for
 * the nearest above it that has some, and one with nothing under it for the ground below; and for
 * a few seconds after, anyone who drops out of the world anyway is put back where they landed.
 */
public final class Landing {
	private Landing() {}

	private static final EntityDimensions PLAYER = EntityTypes.PLAYER.getDimensions();
	private static final long WATCH_MILLIS = 10_000L;
	/** How far up a spot with something in the way may move to find room. */
	private static final int ROOM_ABOVE = 8;

	private record Watch(ResourceKey<Level> level, Vec3 spot, long until) {}

	private static final Map<UUID, Watch> watched = new HashMap<>();

	/** The trip to this spot, made safe; null where there is nowhere safe to stand near it. */
	public static @Nullable TeleportTransition to(ServerLevel level, Vec3 wanted, float yaw, float pitch,
			TeleportTransition.PostTeleportTransition after) {
		Vec3 spot = safe(level, wanted);
		if (spot == null) return null;
		return new TeleportTransition(level, spot, Vec3.ZERO, yaw, pitch, Set.of(), after.then(entity -> {
			if (entity instanceof ServerPlayer player) watched.put(player.getUUID(), new Watch(level.dimension(), spot, System.currentTimeMillis() + WATCH_MILLIS));
		}));
	}

	/**
	 * Where to stand at or near {@code wanted}: loaded, clear of anything solid, and on the first
	 * thing below that holds a player up, or on the water, which does too. Null over the void.
	 */
	static @Nullable Vec3 safe(ServerLevel level, Vec3 wanted) {
		level.getChunk(SectionPos.blockToSectionCoord(wanted.x), SectionPos.blockToSectionCoord(wanted.z));
		Vec3 spot = wanted;
		for (int up = 1; !fits(level, spot); up++) {
			if (up > ROOM_ABOVE) return null;
			spot = new Vec3(wanted.x, Math.floor(wanted.y) + up, wanted.z);
		}
		BlockPos.MutableBlockPos pos = BlockPos.containing(spot).mutable();
		for (; pos.getY() >= level.getMinY(); pos.move(Direction.DOWN)) {
			if (!level.getFluidState(pos).isEmpty()) {
				Vec3 surface = new Vec3(spot.x, pos.getY() + 1, spot.z);
				return surface.y >= spot.y || !fits(level, surface) ? spot : surface;
			}
			VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (shape.isEmpty()) continue;
			double top = pos.getY() + shape.max(Direction.Axis.Y);
			if (top > spot.y) continue;
			Vec3 standing = new Vec3(spot.x, top, spot.z);
			return fits(level, standing) ? standing : spot;
		}
		return null;
	}

	private static boolean fits(ServerLevel level, Vec3 at) {
		return level.noCollision(PLAYER.makeBoundingBox(at));
	}

	/** Anyone just set down who has dropped out of the world anyway goes back where they landed. */
	public static void tick(MinecraftServer server) {
		if (watched.isEmpty()) return;
		long now = System.currentTimeMillis();
		watched.entrySet().removeIf(entry -> {
			Watch watch = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (now > watch.until() || player == null || !player.isAlive() || !player.level().dimension().equals(watch.level())) return true;
			ServerLevel level = player.level();
			if (player.getY() >= level.getMinY()) return false;
			PvpDimensions.LOGGER.warn("{} fell out of the world just after coming home; put back at {}", player.getGameProfile().name(), watch.spot());
			player.resetFallDistance();
			player.setDeltaMovement(Vec3.ZERO);
			player.teleportTo(level, watch.spot().x, watch.spot().y, watch.spot().z, Set.of(), player.getYRot(), player.getXRot(), true);
			return false;
		});
	}
}
