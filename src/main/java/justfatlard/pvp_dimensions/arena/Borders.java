package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;

/**
 * Each arena's edge, as a world border drawn for its players alone.
 *
 * <p>Arenas share their dimension, and a dimension has one border, so the border each player sees
 * is sent to them and them only, sized to their arena. That is what the client walls them in with;
 * the server checks the same edge itself, since a client can be made to ignore its border.
 */
public final class Borders {
	private Borders() {}

	/** How far past the edge a player may stray before being put back, for lag's sake. */
	private static final double SLACK = 1.5;

	/** The border's width now, closing in over the arena's life when the preset says so. */
	public static double size(Arena arena, long now) {
		double full = arena.chunks * 16;
		if (arena.preset.borderShrink <= 0 || arena.endsAt <= 0 || arena.liveAt <= 0 || arena.phase != Arena.Phase.LIVE) return full;
		double finalSize = full * (100 - arena.preset.borderShrink) / 100.0;
		double progress = Math.max(0, Math.min(1, (now - arena.liveAt) / (double) (arena.endsAt - arena.liveAt)));
		return full + (finalSize - full) * progress;
	}

	public static void send(ServerPlayer player, Arena arena) {
		long now = System.currentTimeMillis();
		Footprint footprint = arena.footprint();
		WorldBorder border = new WorldBorder();
		border.setCenter(footprint.centerX(), footprint.centerZ());
		double current = size(arena, now);
		double finalSize = arena.chunks * 16 * (100 - arena.preset.borderShrink) / 100.0;
		long remainingTicks = arena.endsAt > now ? (arena.endsAt - now) / 50 : 0;
		if (arena.phase == Arena.Phase.LIVE && arena.preset.borderShrink > 0 && remainingTicks > 0) {
			border.lerpSizeBetween(current, finalSize, remainingTicks, player.level().getGameTime());
		} else {
			border.setSize(current);
		}
		border.setWarningBlocks(3);
		player.connection.send(new ClientboundInitializeBorderPacket(border));
	}

	/** Back to the border the dimension really has, for someone on their way out. */
	public static void reset(ServerPlayer player, ServerLevel level) {
		player.connection.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
	}

	/** Anyone past their arena's edge is set down just inside it. */
	public static void enforce(ServerLevel level, Arena arena, ServerPlayer player) {
		Footprint footprint = arena.footprint();
		double half = size(arena, System.currentTimeMillis()) / 2;
		double minX = footprint.centerX() - half;
		double maxX = footprint.centerX() + half;
		double minZ = footprint.centerZ() - half;
		double maxZ = footprint.centerZ() + half;
		double x = player.getX();
		double z = player.getZ();
		if (x >= minX - SLACK && x <= maxX + SLACK && z >= minZ - SLACK && z <= maxZ + SLACK) return;

		double inX = Math.max(minX + 2, Math.min(maxX - 2, x));
		double inZ = Math.max(minZ + 2, Math.min(maxZ - 2, z));
		Vec3 spot = Spawns.standAt(level, arena, (int) Math.floor(inX), (int) Math.floor(inZ));
		if (spot == null) spot = Spawns.near(level, arena, (int) Math.floor(inX), (int) Math.floor(inZ), 8);
		player.teleportTo(level, spot.x, spot.y, spot.z, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
	}
}
