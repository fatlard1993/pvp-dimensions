package justfatlard.pvp_dimensions.arena;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pvp_dimensions.mixin.PrimedTntAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * A trap's kill is its maker's. Every block put down in a live arena remembers who put it there;
 * a death nobody dealt, from a block the victim was in or standing on, spikes, a cactus, magma,
 * a berry bush, a campfire, is credited to whoever placed that block. TNT set off by redstone or
 * fire rather than by hand is lit in its placer's name, so its blast is theirs as the game counts
 * it. Your own trap, or your teammate's, credits nobody.
 */
public final class Traps {
	private Traps() {}

	/** Who put each block down, by arena and position. */
	private static final Map<String, Map<Long, UUID>> placed = new ConcurrentHashMap<>();

	public static void placed(ServerLevel level, BlockPos pos, ServerPlayer player) {
		if (!Places.isArena(level.dimension())) return;
		Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
		if (arena == null || arena.phase != Arena.Phase.LIVE) return;
		placed.computeIfAbsent(arena.id, id -> new ConcurrentHashMap<>()).put(pos.asLong(), player.getUUID());
	}

	/** TNT lit by anything but a hand, given its placer as its owner, where it has one. */
	public static void primed(PrimedTnt tnt, ServerLevel level) {
		if (tnt.getOwner() != null) return;
		Arena arena = Arenas.at(level, tnt.getX(), tnt.getZ());
		if (arena == null) return;
		UUID placer = placer(arena, tnt.blockPosition());
		ServerPlayer player = placer == null ? null : level.getServer().getPlayerList().getPlayer(placer);
		if (player != null) ((PrimedTntAccessor) tnt).pvpDimensions$setOwner(EntityReference.of(player));
	}

	private static @Nullable UUID placer(Arena arena, BlockPos pos) {
		Map<Long, UUID> byPos = placed.get(arena.id);
		return byPos == null ? null : byPos.get(pos.asLong());
	}

	/**
	 * Whoever laid the trap this player died in: a block they were inside or standing on, placed
	 * by somebody else and not a teammate. Null where no such block is.
	 */
	public static @Nullable ServerPlayer trapper(MinecraftServer server, Arena arena, ServerPlayer victim) {
		Arena.Member lost = arena.member(victim.getUUID());
		AABB box = victim.getBoundingBox().inflate(0.05).expandTowards(0, -0.6, 0);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			UUID placer = placer(arena, pos);
			if (placer == null || placer.equals(victim.getUUID())) continue;
			Arena.Member maker = arena.member(placer);
			if (maker != null && lost != null && arena.preset.teamsOn() && maker.team == lost.team) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(placer);
			if (player != null) return player;
		}
		return null;
	}

	public static void forget(Arena arena) {
		placed.remove(arena.id);
	}
}
