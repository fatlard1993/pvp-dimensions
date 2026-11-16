package justfatlard.pvp_dimensions.gametest;

import static justfatlard.pvp_dimensions.gametest.Tables.check;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.preset.Kinds;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

/**
 * A saved terrain, saved a chunk a tick and laid down a slice at a time: each chunk's file is the
 * one a structure template saved from the world would be, and the ground laid from it is the
 * template block for block, and the same as the game laying the template whole.
 */
public final class SavedGroundLays implements FabricClientGameTest {
	private static final String NAME = "lay-test";
	private static final int CHUNKS = 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			Tables.start(server, connection, Kinds.DEATHMATCH, preset -> {
				preset.pvp = false;
				preset.shape = Preset.Shape.FLAT;
				preset.size = CHUNKS;
			});
			server.runOnServer(SavedGroundLays::build);
			context.waitTicks(20);

			AtomicInteger saved = new AtomicInteger(-2);
			server.runOnServer(s -> {
				Arena arena = Tables.arena(s);
				check(arena.chunks == CHUNKS, "the arena is " + arena.chunks + " chunks across, not " + CHUNKS);
				SavedTerrains.save(Arenas.level(s, arena), arena.footprint(), NAME, "minecraft:plains",
					arena.surfaceY, arena.highestY, arena.wallTop, saved::set);
			});
			server.waitFor(s -> saved.get() != -2, 1200);

			server.runOnServer(s -> {
				check(saved.get() == CHUNKS * CHUNKS, "the terrain was not saved: " + saved.get());
				Arena arena = Tables.arena(s);
				ServerLevel level = Arenas.level(s, arena);
				for (int i = 0; i < CHUNKS; i++) {
					for (int j = 0; j < CHUNKS; j++) {
						BlockPos origin = new BlockPos(arena.footprint().minX() + i * 16, level.getMinY(), arena.footprint().minZ() + j * 16);
						StructureTemplate template = new StructureTemplate();
						template.fillFromWorld(level, origin, new Vec3i(16, level.getHeight(), 16), true,
							java.util.List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.NETHER_PORTAL));
						CompoundTag theirs = template.save(new CompoundTag());
						CompoundTag ours = file(i, j);
						for (String key : java.util.List.of("size", "palette", "blocks")) {
							check(theirs.get(key).equals(ours.get(key)), "chunk " + i + "," + j + "'s saved " + key + " is not what a structure template saves");
						}
						check(theirs.getListOrEmpty("entities").size() == ours.getListOrEmpty("entities").size(),
							"chunk " + i + "," + j + " saved " + ours.getListOrEmpty("entities").size() + " entities, a structure template "
								+ theirs.getListOrEmpty("entities").size());
					}
				}
			});

			// Two empty spots of the arena dimension, far from any plot: one for the game to lay the
			// template whole, one for the saved terrain to be laid a slice at a time.
			int[] spots = server.computeOnServer(s -> {
				Arena arena = Tables.arena(s);
				ServerLevel level = Arenas.level(s, arena);
				int[] chunks = {arena.minChunkX + 64, arena.minChunkZ, arena.minChunkX + 128, arena.minChunkZ};
				for (int k = 0; k < chunks.length; k += 2) {
					for (int i = 0; i < CHUNKS; i++) {
						for (int j = 0; j < CHUNKS; j++) level.setChunkForced(chunks[k] + i, chunks[k + 1] + j, true);
					}
				}
				return chunks;
			});
			server.waitFor(s -> {
				ServerLevel level = Arenas.level(s, Tables.arena(s));
				for (int k = 0; k < spots.length; k += 2) {
					for (int i = 0; i < CHUNKS; i++) {
						for (int j = 0; j < CHUNKS; j++) {
							if (level.getChunkSource().getChunkNow(spots[k] + i, spots[k + 1] + j) == null) return false;
						}
					}
				}
				return true;
			}, 600);

			AtomicBoolean laid = new AtomicBoolean();
			server.runOnServer(s -> {
				Arena arena = Tables.arena(s);
				ServerLevel level = Arenas.level(s, arena);
				var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
				for (int i = 0; i < CHUNKS; i++) {
					for (int j = 0; j < CHUNKS; j++) {
						StructureTemplate template = new StructureTemplate();
						template.load(blocks, file(i, j));
						BlockPos origin = new BlockPos((spots[0] + i) << 4, level.getMinY(), (spots[1] + j) << 4);
						template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), RandomSource.create(), Block.UPDATE_CLIENTS);
					}
				}
				SavedTerrains.paste(level, new Footprint("lay-test-b", level.dimension(), spots[2], spots[3], CHUNKS, arena.terrain()),
					NAME, () -> laid.set(true));
			});
			server.waitFor(s -> laid.get(), 2400);

			server.runOnServer(s -> {
				Arena arena = Tables.arena(s);
				ServerLevel level = Arenas.level(s, arena);
				var lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
				int compared = 0;
				for (int i = 0; i < CHUNKS; i++) {
					for (int j = 0; j < CHUNKS; j++) {
						Map<BlockPos, BlockState> expected = new HashMap<>();
						CompoundTag file = file(i, j);
						var palette = file.getListOrEmpty("palette");
						for (var block : file.getListOrEmpty("blocks").compoundStream().toList()) {
							var pos = block.getListOrEmpty("pos");
							expected.put(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)),
								net.minecraft.nbt.NbtUtils.readBlockState(lookup, palette.getCompoundOrEmpty(block.getIntOr("state", 0))));
						}
						BlockPos whole = new BlockPos((spots[0] + i) << 4, level.getMinY(), (spots[1] + j) << 4);
						BlockPos sliced = new BlockPos((spots[2] + i) << 4, level.getMinY(), (spots[3] + j) << 4);
						for (int y = 0; y < level.getHeight(); y++) {
							for (int x = 0; x < 16; x++) {
								for (int z = 0; z < 16; z++) {
									BlockPos rel = new BlockPos(x, y, z);
									BlockState want = expected.getOrDefault(rel, Blocks.AIR.defaultBlockState());
									BlockState got = level.getBlockState(sliced.offset(rel));
									BlockState game = level.getBlockState(whole.offset(rel));
									check(got.equals(want), "laid " + got + " where the template has " + want + ", chunk " + i + "," + j + " at " + rel);
									check(got.equals(game), "laid " + got + " where the game laying it whole made " + game + ", chunk " + i + "," + j + " at " + rel);
									if (!want.isAir()) compared++;
									BlockEntity mine = level.getBlockEntity(sliced.offset(rel));
									BlockEntity its = level.getBlockEntity(whole.offset(rel));
									check((mine == null) == (its == null), "a block entity laid at one and not the other, chunk " + i + "," + j + " at " + rel);
									if (mine != null) {
										check(mine.saveWithoutMetadata(level.registryAccess()).equals(its.saveWithoutMetadata(level.registryAccess())),
											"the block entity laid at " + rel + " in chunk " + i + "," + j + " holds something else than the game's");
									}
								}
							}
						}
					}
				}
				check(compared > 1000, "only " + compared + " blocks were compared");

				BlockPos chest = arena.footprint().center(0);
				BlockPos at = new BlockPos(chest.getX() - arena.footprint().minX() + (spots[2] << 4), 0, chest.getZ() - arena.footprint().minZ() + (spots[3] << 4));
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
				check(level.getBlockEntity(new BlockPos(at.getX(), top - 1, at.getZ())) instanceof ChestBlockEntity laidChest
					&& laidChest.countItem(Items.DIAMOND) == 5, "the chest laid down does not hold its five diamonds");
				for (int k = 0; k < spots.length; k += 2) {
					AABB area = new AABB(spots[k] << 4, level.getMinY(), spots[k + 1] << 4, (spots[k] + CHUNKS) << 4, level.getMaxY(), (spots[k + 1] + CHUNKS) << 4);
					int stands = level.getEntities(EntityTypes.ARMOR_STAND, area, entity -> true).size();
					check(stands == 1, stands + " armour stands were laid, not one");
				}
				for (int k = 0; k < spots.length; k += 2) {
					for (int i = 0; i < CHUNKS; i++) {
						for (int j = 0; j < CHUNKS; j++) level.setChunkForced(spots[k] + i, spots[k + 1] + j, false);
					}
				}
			});
		}
	}

	/**
	 * Some of everything laying has to get right, beside the flat ground: a fence across a chunk
	 * edge, a chest with something in it, sand on a slab, water in a hole, a torch on a wall, and an
	 * armour stand.
	 */
	private static void build(MinecraftServer server) {
		Arena arena = Tables.arena(server);
		ServerLevel level = Arenas.level(server, arena);
		Footprint plot = arena.footprint();
		BlockPos middle = plot.center(0);
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, middle.getX(), middle.getZ());
		BlockPos chest = new BlockPos(middle.getX(), top, middle.getZ());
		level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		if (level.getBlockEntity(chest) instanceof ChestBlockEntity box) box.setItem(0, new ItemStack(Items.DIAMOND, 5));

		int edge = plot.minX() + 16;
		for (int x = edge - 3; x <= edge + 3; x++) level.setBlock(new BlockPos(x, top, plot.minZ() + 4), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);

		BlockPos slab = new BlockPos(plot.minX() + 6, top, plot.minZ() + 8);
		level.setBlock(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Block.UPDATE_ALL);
		level.setBlock(slab.above(), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);

		level.setBlock(new BlockPos(plot.minX() + 9, top - 1, plot.minZ() + 8), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

		BlockPos stone = new BlockPos(plot.minX() + 5, top, plot.minZ() + 11);
		level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(stone.east(), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST), Block.UPDATE_ALL);

		ArmorStand stand = new ArmorStand(level, plot.minX() + 10.5, top, plot.minZ() + 11.5);
		level.addFreshEntity(stand);
	}

	private static CompoundTag file(int i, int j) {
		Path folder = SavedTerrains.folder(NAME);
		try {
			return NbtIo.readCompressed(folder.resolve(i + "_" + j + ".nbt"), NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new AssertionError("saved chunk " + i + "," + j + " could not be read", e);
		}
	}
}
