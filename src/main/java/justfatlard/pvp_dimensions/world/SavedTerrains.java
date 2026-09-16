package justfatlard.pvp_dimensions.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.arena.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
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
	 * Copy an arena's blocks into a new saved terrain, a chunk a slice, and write the files off
	 * the server thread. {@code done} hears the count of chunks written, or -1 on failure.
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

		int height = level.getHeight();
		AtomicInteger next = new AtomicInteger();
		int total = footprint.chunks() * footprint.chunks();
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
				Util.ioPool().execute(() -> {
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
				});
				return true;
			}
			int i = index % footprint.chunks();
			int j = index / footprint.chunks();
			BlockPos origin = new BlockPos(footprint.minX() + i * 16, level.getMinY(), footprint.minZ() + j * 16);
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(level, origin, new Vec3i(16, height, 16), true, SKIPPED);
			CompoundTag tag = template.save(new CompoundTag());
			Path file = folder.resolve(i + "_" + j + ".nbt");
			Util.ioPool().execute(() -> {
				try {
					NbtIo.writeCompressed(tag, file);
				} catch (IOException e) {
					PvpDimensions.LOGGER.error("Saved terrain chunk {} could not be written", file, e);
				}
			});
			return false;
		});
	}

	/** Lay a saved terrain into an arena's empty plot, a chunk a slice. */
	public static void paste(ServerLevel level, Footprint footprint, String name, Runnable done) {
		Meta meta = known.get(name);
		if (root == null || meta == null) {
			done.run();
			return;
		}
		Path folder = root.resolve(meta.name());
		int chunks = Math.min(meta.chunks(), footprint.chunks());
		AtomicInteger next = new AtomicInteger();
		RandomSource random = RandomSource.create();
		Jobs.add(() -> {
			int index = next.getAndIncrement();
			if (index >= chunks * chunks) {
				done.run();
				return true;
			}
			int i = index % chunks;
			int j = index / chunks;
			Path file = folder.resolve(i + "_" + j + ".nbt");
			if (!Files.exists(file)) return false;
			try {
				CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
				StructureTemplate template = new StructureTemplate();
				template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
				BlockPos origin = new BlockPos(footprint.minX() + i * 16, level.getMinY(), footprint.minZ() + j * 16);
				template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), random, Block.UPDATE_CLIENTS);
			} catch (IOException e) {
				PvpDimensions.LOGGER.error("Saved terrain chunk {} could not be read", file, e);
			}
			return false;
		});
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
