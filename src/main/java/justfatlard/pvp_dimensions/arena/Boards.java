package justfatlard.pvp_dimensions.arena;

import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.TeamColors;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the board games built into the ground share: where a team's board goes, its signs, and the
 * blocks it is drawn in.
 */
final class Boards {
	private Boards() {}

	/**
	 * The first corner of a square board on this team's end of the ground, on its own spoke from the
	 * middle, as far out as leaves {@code behind} blocks to the edge.
	 */
	static BlockPos atEnd(ServerLevel level, Arena arena, int team, int size, int behind) {
		Footprint footprint = arena.footprint();
		int width = footprint.width();
		double radius = Math.max(size, Math.min(width * Preset.GOAL_RING, width / 2.0 - size / 2.0 - behind));
		double angle = arena.preset.ringAngle(team);
		int x = (int) Math.floor(footprint.centerX() + Math.cos(angle) * radius);
		int z = (int) Math.floor(footprint.centerZ() + Math.sin(angle) * radius);
		int y = Builds.ground(level, arena, x, z);
		return new BlockPos(x - size / 2, y, z - size / 2);
	}

	/** The first corner of a board this wide and deep, set in the middle of the ground. */
	static BlockPos inMiddle(ServerLevel level, Arena arena, int wide, int deep) {
		Footprint footprint = arena.footprint();
		int x = (int) Math.floor(footprint.centerX());
		int z = (int) Math.floor(footprint.centerZ());
		return new BlockPos(x - wide / 2, Builds.ground(level, arena, x, z), z - deep / 2);
	}

	/** A sign standing here, turned by {@code rotation} sixteenths, the words on both faces. */
	static void sign(ServerLevel level, BlockPos pos, String words, int rotation) {
		level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), Block.UPDATE_ALL);
		if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
			for (SignTextSlot face : SignTextSlot.values()) {
				sign.setText(sign.getText(face).asMutable().setLine(1, Component.literal(words).withStyle(ChatFormatting.BOLD)).asImmutable(), face);
			}
			sign.setWaxed(true);
		}
	}

	static BlockState block(String id) {
		return Terrain.block("minecraft:" + id, Blocks.STONE.defaultBlockState());
	}

	static BlockState rim(int team) {
		return Terrain.block(TeamColors.of(team).concrete(), Blocks.STONE.defaultBlockState());
	}

	/** A title for everyone inside, in this team's colour. */
	static void title(MinecraftServer server, Arena arena, int team, String big, String small) {
		for (Arena.Member member : arena.inside()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(small).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(big)
				.withStyle(style -> style.withColor(TeamColors.of(team).team().textColor()))));
		}
	}

	/** A title only for this team's players, in its colour. */
	static void titleTeam(MinecraftServer server, Arena arena, int team, String big, String small) {
		for (Arena.Member member : arena.inside()) {
			if (member.team != team) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(small).withStyle(ChatFormatting.YELLOW)));
			player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(big)
				.withStyle(style -> style.withColor(TeamColors.of(team).team().textColor()))));
		}
	}

	/** The member a player is in this arena as, if they are playing it and not watching. */
	static Arena.@org.jspecify.annotations.Nullable Member playing(Arena arena, ServerPlayer player) {
		Arena.Member member = arena.member(player.getUUID());
		return member == null || member.watching || member.out ? null : member;
	}
}
