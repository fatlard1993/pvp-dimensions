package justfatlard.pvp_dimensions.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Jobs;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Arenas kept block for block: generated, built on by an admin, saved, and laid down again the
 * same every time. Kept with the world, a chunk to a file, since they are made from its blocks.
 *
 * <p>Air is not kept, the arena's own exit portals are not kept (a new arena lights its own), and
 * players are not kept; everything else is, chests and what is in them, armour stands, signs.
 */
public final class SavedTerrains {
	private SavedTerrains() {}

	public record Meta(String name, ResourceKey<Level> dimension, int chunks, String biome, int surfaceY, int highestY, int wallTop) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Block> SKIPPED = List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.NETHER_PORTAL);
	private static final Map<String, Meta> known = new TreeMap<>();
	private static @Nullable Path root;

	public static void load(MinecraftServer server) {
		root = server.getWorldPath(LevelResource.ROOT).resolve("pvp-dimensions").resolve("terrain");
		known.clear();
		try {
			Files.createDirectories(root);
			try (Stream<Path> folders = Files.list(root)) {
				for (Path folder : folders.filter(Files::isDirectory).toList()) {
					Path meta = folder.resolve("meta.json");
					if (!Files.exists(meta)) continue;
					try {
						JsonObject json = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
						Meta read = new Meta(folder.getFileName().toString(),
							ResourceKey.create(Registries.DIMENSION, Identifier.parse(json.get("dimension").getAsString())),
							json.get("chunks").getAsInt(), json.get("biome").getAsString(),
							json.get("surface_y").getAsInt(), json.get("highest_y").getAsInt(), json.get("wall_top").getAsInt());
						known.put(read.name(), read);
					} catch (Exception e) {
						PvpDimensions.LOGGER.error("Saved terrain {} could not be read", folder, e);
					}
				}
			}
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Saved terrains could not be listed", e);
		}
	}

	public static List<String> names() {
		return new ArrayList<>(known.keySet());
	}

	public static @Nullable Meta meta(String name) {
		return known.get(name);
	}

	public static String biomeOf(String name) {
		Meta meta = known.get(name);
		return meta != null ? meta.biome() : "minecraft:plains";
	}

	public static @Nullable ResourceKey<Level> dimensionOf(String name) {
		Meta meta = known.get(name);
		return meta != null ? meta.dimension() : null;
	}

	/** Where a saved terrain of this name is kept, or would be; null before the server has started. */
	public static @Nullable Path folder(String name) {
		return root == null ? null : root.resolve(clean(name));
	}

	public static String clean(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
	}

	/**
	 * Copy an arena's blocks into a new saved terrain and write the files off the server thread.
	 * Each chunk is copied whole in one tick, which is only a copy of its block arrays, so that a
	 * door or anything else standing in it is never caught half changed; turning that copy into a
	 * file happens off the server thread. {@code done} hears the count of chunks written once every
	 * file is down, or -1 on failure.
	 */
	public static void save(ServerLevel level, Footprint footprint, String name, String biome, int surfaceY, int highestY, int wallTop,
			java.util.function.IntConsumer done) {
		if (root == null) {
			done.accept(-1);
			return;
		}
		String clean = clean(name);
		Path folder = root.resolve(clean);
		try {
			if (Files.exists(folder)) {
				try (Stream<Path> old = Files.walk(folder)) {
					old.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
				}
			}
			Files.createDirectories(folder);
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Saved terrain {} could not be started", clean, e);
			done.accept(-1);
			return;
		}

		AtomicInteger next = new AtomicInteger();
		int total = footprint.chunks() * footprint.chunks();
		List<CompletableFuture<Void>> writing = new ArrayList<>(total);
		Jobs.add(() -> {
			int index = next.getAndIncrement();
			if (index >= total) {
				Meta meta = new Meta(clean, level.dimension(), footprint.chunks(), biome, surfaceY, highestY, wallTop);
				JsonObject json = new JsonObject();
				json.addProperty("dimension", meta.dimension().identifier().toString());
				json.addProperty("chunks", meta.chunks());
				json.addProperty("biome", meta.biome());
				json.addProperty("surface_y", meta.surfaceY());
				json.addProperty("highest_y", meta.highestY());
				json.addProperty("wall_top", meta.wallTop());
				CompletableFuture.allOf(writing.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
					try {
						Files.writeString(folder.resolve("meta.json"), GSON.toJson(json), StandardCharsets.UTF_8);
						level.getServer().execute(() -> {
							known.put(clean, meta);
							done.accept(total);
						});
					} catch (IOException e) {
						PvpDimensions.LOGGER.error("Saved terrain {} could not be finished", clean, e);
						level.getServer().execute(() -> done.accept(-1));
					}
				}, Util.ioPool());
				return true;
			}
			int i = index % footprint.chunks();
			int j = index / footprint.chunks();
			Snapshot snapshot = Snapshot.of(level, footprint.minChunkX() + i, footprint.minChunkZ() + j);
			Path file = folder.resolve(i + "_" + j + ".nbt");
			writing.add(CompletableFuture.supplyAsync(snapshot::toTag, Util.backgroundExecutor()).thenAcceptAsync(tag -> {
				try {
					NbtIo.writeCompressed(tag, file);
				} catch (IOException e) {
					PvpDimensions.LOGGER.error("Saved terrain chunk {} could not be written", file, e);
				}
			}, Util.ioPool()).exceptionally(e -> {
				PvpDimensions.LOGGER.error("Saved terrain chunk {} could not be made", file, e);
				return null;
			}));
			return false;
		});
	}

	/**
	 * One chunk column as it stood when it was saved: its block arrays copied, its block entities
	 * and entities written out. {@link #toTag} makes from it the same file a structure template
	 * saved from the world would make, block for block and in the same order.
	 */
	private record Snapshot(List<@Nullable PalettedContainer<BlockState>> sections, Map<BlockPos, CompoundTag> blockEntities,
			List<StructureEntityInfo> entities, int height) {

		static Snapshot of(ServerLevel level, int chunkX, int chunkZ) {
			LevelChunk chunk = level.getChunk(chunkX, chunkZ);
			BlockPos origin = new BlockPos(chunkX << 4, level.getMinY(), chunkZ << 4);
			List<@Nullable PalettedContainer<BlockState>> sections = new ArrayList<>();
			for (LevelChunkSection section : chunk.getSections()) sections.add(section.hasOnlyAir() ? null : section.getStates().copy());
			Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
			List<StructureEntityInfo> entities = new ArrayList<>();
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(PvpDimensions.LOGGER)) {
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
					blockEntity.saveWithId(output);
					blockEntities.put(blockEntity.getBlockPos().subtract(origin), output.buildResult());
				}
				BlockPos far = origin.offset(15, level.getHeight() - 1, 15);
				for (Entity entity : level.getEntitiesOfClass(Entity.class, AABB.encapsulatingFullBlocks(origin, far), found -> !(found instanceof Player))) {
					Vec3 pos = new Vec3(entity.getX() - origin.getX(), entity.getY() - origin.getY(), entity.getZ() - origin.getZ());
					TagValueOutput output = TagValueOutput.createWithContext(reporter.forChild(entity.problemPath()), entity.registryAccess());
					entity.save(output);
					BlockPos blockPos = entity instanceof Painting painting ? painting.getPos().subtract(origin) : BlockPos.containing(pos);
					entities.add(new StructureEntityInfo(pos, blockPos, output.buildResult().copy()));
				}
			}
			return new Snapshot(sections, blockEntities, entities, level.getHeight());
		}

		CompoundTag toTag() {
			List<StructureBlockInfo> blocks = new ArrayList<>();
			for (int section = 0; section < sections.size(); section++) {
				PalettedContainer<BlockState> states = sections.get(section);
				if (states == null) continue;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState state = states.get(x, y, z);
							if (SKIPPED.contains(state.getBlock())) continue;
							BlockPos pos = new BlockPos(x, section * 16 + y, z);
							blocks.add(new StructureBlockInfo(pos, state, blockEntities.get(pos)));
						}
					}
				}
			}
			blocks.sort(Comparator.<StructureBlockInfo>comparingInt(SavedTerrains::kind)
				.thenComparingInt(info -> info.pos().getY()).thenComparingInt(info -> info.pos().getX()).thenComparingInt(info -> info.pos().getZ()));

			Map<BlockState, Integer> ids = new java.util.LinkedHashMap<>();
			ListTag blockList = new ListTag();
			for (StructureBlockInfo info : blocks) {
				CompoundTag block = new CompoundTag();
				block.put("pos", ints(info.pos().getX(), info.pos().getY(), info.pos().getZ()));
				block.putInt("state", ids.computeIfAbsent(info.state(), state -> ids.size()));
				if (info.nbt() != null) block.put("nbt", info.nbt());
				blockList.add(block);
			}
			ListTag palette = new ListTag();
			for (BlockState state : ids.keySet()) palette.add(NbtUtils.writeBlockState(state));
			ListTag entityList = new ListTag();
			for (StructureEntityInfo info : entities) {
				CompoundTag entity = new CompoundTag();
				ListTag pos = new ListTag();
				pos.add(DoubleTag.valueOf(info.pos.x));
				pos.add(DoubleTag.valueOf(info.pos.y));
				pos.add(DoubleTag.valueOf(info.pos.z));
				entity.put("pos", pos);
				entity.put("blockPos", ints(info.blockPos.getX(), info.blockPos.getY(), info.blockPos.getZ()));
				entity.put("nbt", info.nbt);
				entityList.add(entity);
			}
			CompoundTag tag = new CompoundTag();
			tag.put("blocks", blockList);
			tag.put("palette", palette);
			tag.put("entities", entityList);
			tag.put("size", ints(16, height, 16));
			return NbtUtils.addCurrentDataVersion(tag);
		}

		private static ListTag ints(int... values) {
			ListTag list = new ListTag();
			for (int value : values) list.add(IntTag.valueOf(value));
			return list;
		}
	}

	/**
	 * Where a block falls in a structure template's order: whole blocks first, then the rest, then
	 * those with a block entity, which is the order the game lays them down in too.
	 */
	private static int kind(StructureBlockInfo info) {
		if (info.nbt() != null) return 2;
		BlockState state = info.state();
		return !state.getBlock().hasDynamicShape() && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 0 : 1;
	}

	/** Chunks of a saved terrain read and unpacked ahead of the one being laid down. */
	private static final int READ_AHEAD = 4;

	/**
	 * Lay a saved terrain into an arena's empty plot. Each chunk's file is read and unpacked off
	 * the server thread, a few ahead of the one being laid, and each chunk is laid a slice at a
	 * time, so no one tick takes more than its share; see {@link Laying}.
	 */
	public static void paste(ServerLevel level, Footprint footprint, String name, Runnable done) {
		Meta meta = known.get(name);
		if (root == null || meta == null) {
			done.run();
			return;
		}
		Path folder = root.resolve(meta.name());
		int chunks = Math.min(meta.chunks(), footprint.chunks());
		int total = chunks * chunks;
		var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		java.util.ArrayDeque<CompletableFuture<@Nullable Piece>> reading = new java.util.ArrayDeque<>();
		AtomicInteger read = new AtomicInteger();
		AtomicInteger next = new AtomicInteger();
		RandomSource random = RandomSource.create();
		Laying[] laying = new Laying[1];
		Jobs.add(() -> {
			while (reading.size() < READ_AHEAD && read.get() < total) {
				int index = read.getAndIncrement();
				Path file = folder.resolve(index % chunks + "_" + index / chunks + ".nbt");
				reading.add(CompletableFuture.supplyAsync(() -> read(blocks, file), Util.ioPool()));
			}
			if (laying[0] != null) {
				if (laying[0].step()) laying[0] = null;
				return false;
			}
			int index = next.getAndIncrement();
			if (index >= total) {
				done.run();
				return true;
			}
			Piece piece = reading.poll().join();
			if (piece == null) return false;
			BlockPos origin = new BlockPos(footprint.minX() + index % chunks * 16, level.getMinY(), footprint.minZ() + index / chunks * 16);
			laying[0] = new Laying(level, piece, origin, random);
			return false;
		});
	}

	/**
	 * One chunk of a saved terrain, unpacked, its blocks in the order they are laid: a layer at a
	 * time from the bottom up, and within a layer in the template's own order.
	 */
	private record Piece(List<StructureBlockInfo> blocks, List<StructureEntityInfo> entities) {}

	/** One chunk of a saved terrain, unpacked; null where it has no file, the file can't be read, or it holds nothing. */
	private static @Nullable Piece read(net.minecraft.core.HolderGetter<Block> blocks, Path file) {
		if (!Files.exists(file)) return null;
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Saved terrain chunk {} could not be read", file, e);
			return null;
		}
		ListTag size = tag.getListOrEmpty("size");
		if (size.getIntOr(0, 0) < 1 || size.getIntOr(1, 0) < 1 || size.getIntOr(2, 0) < 1) return null;
		ListTag paletteTag = tag.getList("palettes").map(palettes -> palettes.getListOrEmpty(0)).orElseGet(() -> tag.getListOrEmpty("palette"));
		List<BlockState> palette = new ArrayList<>(paletteTag.size());
		for (int i = 0; i < paletteTag.size(); i++) palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i)));
		List<StructureBlockInfo> infos = new ArrayList<>(tag.getListOrEmpty("blocks").compoundStream().map(block -> {
			ListTag pos = block.getListOrEmpty("pos");
			int id = block.getIntOr("state", 0);
			BlockState state = id >= 0 && id < palette.size() ? palette.get(id) : Blocks.AIR.defaultBlockState();
			return new StructureBlockInfo(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)), state, block.getCompound("nbt").orElse(null));
		}).toList());
		infos.sort(Comparator.<StructureBlockInfo>comparingInt(info -> info.pos().getY()).thenComparingInt(SavedTerrains::kind)
			.thenComparingInt(info -> info.pos().getX()).thenComparingInt(info -> info.pos().getZ()));
		List<StructureEntityInfo> entities = new ArrayList<>();
		tag.getListOrEmpty("entities").compoundStream().forEach(entity -> {
			ListTag pos = entity.getListOrEmpty("pos");
			ListTag blockPos = entity.getListOrEmpty("blockPos");
			entity.getCompound("nbt").ifPresent(nbt -> entities.add(new StructureEntityInfo(
				new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0)),
				new BlockPos(blockPos.getIntOr(0, 0), blockPos.getIntOr(1, 0), blockPos.getIntOr(2, 0)), nbt)));
		});
		if (infos.isEmpty() && entities.isEmpty()) return null;
		return new Piece(infos, entities);
	}

	/**
	 * One chunk being laid, the way a structure template lays itself down, only spread over ticks:
	 * its blocks a layer a slice, their shapes fitted to each other and their neighbours told, and
	 * its entities last.
	 *
	 * <p>Layers go from the bottom up, each whole in one slice, so that between ticks everything
	 * below the next layer is down: sand never finds itself over air that is still to come, nor
	 * water a gap beside it.
	 */
	private static final class Laying {
		private static final int PER_SLICE = 256;
		private static final int ENTITIES_PER_SLICE = 16;
		private static final Direction[] FILL_FROM = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

		private enum Stage { BLOCKS, FLUIDS, EDGES, NEIGHBOURS, ENTITIES }

		private final ServerLevel level;
		private final Piece piece;
		private final BlockPos origin;
		private final RandomSource random;
		/** Indexes into the piece's blocks of those that were set, in the order they were. */
		private final IntArrayList placed = new IntArrayList();
		private final LongOpenHashSet placedAt = new LongOpenHashSet();
		private final java.util.Set<BlockPos> lockedFluids = new java.util.HashSet<>();
		private final List<BlockPos> toFill = new ArrayList<>();
		private java.util.@Nullable Iterator<BlockPos> filling;
		private boolean filled = true;
		private @Nullable Stage stage = Stage.BLOCKS;
		private int next;

		Laying(ServerLevel level, Piece piece, BlockPos origin, RandomSource random) {
			this.level = level;
			this.piece = piece;
			this.origin = origin;
			this.random = random;
		}

		/** One slice. True once the chunk is laid. */
		boolean step() {
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(PvpDimensions.LOGGER)) {
				if (stage == Stage.BLOCKS) layer(reporter);
				else if (stage == Stage.FLUIDS) fill();
				else if (stage == Stage.EDGES) edges();
				else if (stage == Stage.NEIGHBOURS) neighbours();
				else if (stage == Stage.ENTITIES) entities(reporter);
			}
			return stage == null;
		}

		private void then(@Nullable Stage after) {
			stage = after;
			next = 0;
		}

		private void layer(ProblemReporter reporter) {
			List<StructureBlockInfo> blocks = piece.blocks();
			if (next >= blocks.size()) {
				then(Stage.FLUIDS);
				return;
			}
			int y = blocks.get(next).pos().getY();
			for (; next < blocks.size() && blocks.get(next).pos().getY() == y; next++) place(next, reporter);
		}

		private void place(int index, ProblemReporter reporter) {
			StructureBlockInfo info = piece.blocks().get(index);
			BlockPos pos = origin.offset(info.pos());
			FluidState before = level.getFluidState(pos);
			BlockState state = info.state();
			if (info.nbt() != null) level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_INVISIBLE);
			if (!level.setBlock(pos, state, Block.UPDATE_CLIENTS)) return;
			placed.add(index);
			placedAt.add(pos.asLong());
			if (info.nbt() != null) {
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (blockEntity != null) {
					CompoundTag nbt = info.nbt().copy();
					if (blockEntity instanceof RandomizableContainer) nbt.putLong("LootTableSeed", random.nextLong());
					blockEntity.loadWithComponents(TagValueInput.create(reporter.forChild(blockEntity.problemPath()), level.registryAccess(), nbt));
				}
			}
			if (state.getFluidState().isSource()) {
				lockedFluids.add(pos);
			} else if (state.getBlock() instanceof LiquidBlockContainer container) {
				container.placeLiquid(level, pos, state, before);
				if (!before.isSource()) toFill.add(pos);
			}
		}

		/** Blocks that hold water filled from a source beside them that this chunk didn't lay, round after round while any takes. */
		private void fill() {
			if (filling == null) {
				if (!filled || toFill.isEmpty()) {
					then(Stage.EDGES);
					return;
				}
				filling = toFill.iterator();
				filled = false;
			}
			for (int n = 0; n < PER_SLICE && filling.hasNext(); n++) {
				BlockPos pos = filling.next();
				FluidState toPlace = level.getFluidState(pos);
				for (int i = 0; i < FILL_FROM.length && !toPlace.isSource(); i++) {
					BlockPos beside = pos.relative(FILL_FROM[i]);
					FluidState fluid = level.getFluidState(beside);
					if (fluid.isSource() && !lockedFluids.contains(beside)) toPlace = fluid;
				}
				if (toPlace.isSource()) {
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof LiquidBlockContainer container) {
						container.placeLiquid(level, pos, state, toPlace);
						filled = true;
						filling.remove();
					}
				}
			}
			if (!filling.hasNext()) filling = null;
		}

		/** Every face of what was laid that meets something it didn't lay: both sides fitted to each other. */
		private void edges() {
			BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
			int end = Math.min(placed.size(), next + PER_SLICE);
			for (; next < end; next++) {
				BlockPos pos = origin.offset(piece.blocks().get(placed.getInt(next)).pos());
				for (Direction direction : Direction.values()) {
					beside.setWithOffset(pos, direction);
					if (placedAt.contains(beside.asLong())) continue;
					BlockState state = level.getBlockState(pos);
					BlockState neighbour = level.getBlockState(beside);
					BlockState fitted = state.updateShape(level, level, pos, direction, beside, neighbour, level.getRandom());
					if (state != fitted) level.setBlock(pos, fitted, Block.UPDATE_CLIENTS);
					BlockState neighbourFitted = neighbour.updateShape(level, level, beside, direction.getOpposite(), pos, fitted, level.getRandom());
					if (neighbour != neighbourFitted) level.setBlock(beside, neighbourFitted, Block.UPDATE_CLIENTS);
				}
			}
			if (next >= placed.size()) then(Stage.NEIGHBOURS);
		}

		private void neighbours() {
			int end = Math.min(placed.size(), next + PER_SLICE);
			for (; next < end; next++) {
				StructureBlockInfo info = piece.blocks().get(placed.getInt(next));
				BlockPos pos = origin.offset(info.pos());
				BlockState state = level.getBlockState(pos);
				BlockState fitted = Block.updateFromNeighbourShapes(state, level, pos);
				if (state != fitted) level.setBlock(pos, fitted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				level.updateNeighborsAt(pos, fitted.getBlock());
				if (info.nbt() != null) {
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (blockEntity != null) blockEntity.setChanged();
				}
			}
			if (next >= placed.size()) then(Stage.ENTITIES);
		}

		private void entities(ProblemReporter reporter) {
			List<StructureEntityInfo> entities = piece.entities();
			int end = Math.min(entities.size(), next + ENTITIES_PER_SLICE);
			for (; next < end; next++) {
				StructureEntityInfo info = entities.get(next);
				CompoundTag tag = info.nbt.copy();
				Vec3 pos = info.pos.add(origin.getX(), origin.getY(), origin.getZ());
				ListTag posTag = new ListTag();
				posTag.add(DoubleTag.valueOf(pos.x));
				posTag.add(DoubleTag.valueOf(pos.y));
				posTag.add(DoubleTag.valueOf(pos.z));
				tag.put("Pos", posTag);
				tag.remove("UUID");
				if (tag.contains("block_pos")) tag.store("block_pos", BlockPos.CODEC, info.blockPos.offset(origin));
				Optional<Entity> made;
				try {
					made = EntityType.create(TagValueInput.create(reporter, level.registryAccess(), tag), level,
						new EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false));
				} catch (Exception e) {
					made = Optional.empty();
				}
				made.ifPresent(entity -> {
					float yaw = entity.rotate(Rotation.NONE) + entity.mirror(Mirror.NONE) - entity.getYRot();
					entity.snapTo(pos.x, pos.y, pos.z, yaw, entity.getXRot());
					entity.setYBodyRot(yaw);
					entity.setYHeadRot(yaw);
					level.addFreshEntityWithPassengers(entity);
				});
			}
			if (next >= entities.size()) then(null);
		}
	}

	public static boolean delete(String name) {
		Meta meta = known.remove(name);
		if (meta == null || root == null) return false;
		try (Stream<Path> files = Files.walk(root.resolve(meta.name()))) {
			files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Saved terrain {} could not be deleted", name, e);
		}
		return true;
	}
}
