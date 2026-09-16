package justfatlard.pvp_dimensions.command;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * An arena drawn from above and from the side, to a file: for checking what worldgen made
 * without a client. Only registered when the server runs with {@code -Dpvpdimensions.debug=true}.
 */
final class DebugPictures {
	private DebugPictures() {}

	static final boolean ON = Boolean.getBoolean("pvpdimensions.debug");

	static Path draw(ServerLevel level, Arena arena) throws IOException {
		Footprint footprint = arena.footprint();
		int margin = 8;
		int width = footprint.width() + margin * 2;
		for (int cx = (footprint.minX() - margin) >> 4; cx <= (footprint.maxX() + margin) >> 4; cx++) {
			for (int cz = (footprint.minZ() - margin) >> 4; cz <= (footprint.maxZ() + margin) >> 4; cz++) level.getChunk(cx, cz);
		}
		BufferedImage top = new BufferedImage(width, width, BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < width; i++) {
			for (int j = 0; j < width; j++) {
				int x = footprint.minX() - margin + i;
				int z = footprint.minZ() - margin + j;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					top.setRGB(i, j, 0xFF00FF);
					continue;
				}
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				BlockState state = level.getBlockState(pos.set(x, y, z));
				int rgb = state.isAir() ? 0x101018 : state.getMapColor(level, pos).col;
				int shade = Math.max(0, Math.min(40, (y - arena.surfaceY) / 2));
				top.setRGB(i, j, brighten(rgb, shade));
			}
		}

		int height = level.getHeight();
		BufferedImage side = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int z = footprint.minZ() + footprint.width() / 2 + 3;
		for (int i = 0; i < width; i++) {
			int x = footprint.minX() - margin + i;
			for (int y = level.getMinY(); y < level.getMaxY(); y++) {
				BlockState state = level.hasChunk(x >> 4, z >> 4) ? level.getBlockState(pos.set(x, y, z)) : null;
				int rgb = state == null ? 0xFF00FF : state.isAir() ? 0x101018 : state.getMapColor(level, pos) == MapColor.NONE ? 0x8080FF : state.getMapColor(level, pos).col;
				side.setRGB(i, level.getMaxY() - y, rgb);
			}
		}

		Path folder = level.getServer().getServerDirectory().resolve("pvp-debug");
		Files.createDirectories(folder);
		ImageIO.write(top, "png", folder.resolve(arena.id + "-top.png").toFile());
		ImageIO.write(side, "png", folder.resolve(arena.id + "-side.png").toFile());
		return folder;
	}

	private static int brighten(int rgb, int by) {
		int r = Math.min(255, ((rgb >> 16) & 0xFF) + by);
		int g = Math.min(255, ((rgb >> 8) & 0xFF) + by);
		int b = Math.min(255, (rgb & 0xFF) + by);
		return (r << 16) | (g << 8) | b;
	}
}
