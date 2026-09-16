package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.ArenaBiomes;
import justfatlard.pvp_dimensions.world.ArenaGenerator;
import justfatlard.pvp_dimensions.world.Divisions;
import justfatlard.pvp_dimensions.world.SavedTerrains;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** An arena's preset and placement, as the {@link Terrain} worldgen reads. */
public final class Terrains {
	private Terrains() {}

	public static Terrain of(Arena arena) {
		Preset preset = arena.preset;
		boolean saved = preset.source == Preset.Source.SAVED;
		Terrain.Kind kind = saved ? Terrain.Kind.EMPTY : preset.shape == Preset.Shape.FLAT ? Terrain.Kind.FLAT : Terrain.Kind.NOISE;

		String biomeId = saved ? SavedTerrains.biomeOf(preset.saved) : preset.biome;
		Holder<Biome> biome = biome(arena, biomeId);

		boolean pie = !saved && preset.divisionShape == Preset.DivisionShape.PIE && preset.divisions > 1;
		int[] grid = saved || pie ? new int[] {1, 1} : Divisions.grid(Math.max(1, preset.divisions));
		int slices = pie ? preset.divisions : 0;
		BlockState plain = Terrain.block(preset.divisionBlock, Blocks.GLASS.defaultBlockState());
		List<BlockState> cellWalls = new ArrayList<>();
		for (int cell = 0; cell < (pie ? slices : grid[0] * grid[1]); cell++) {
			if (preset.teamsOn() && preset.teamWalls) {
				cellWalls.add(Terrain.block(preset.teamWallBlock(cell % preset.teams), plain));
			} else {
				cellWalls.add(plain);
			}
		}

		return new Terrain(
			kind,
			biome,
			saved ? 0 : preset.depth,
			preset.shape == Preset.Shape.NATURAL || preset.flatFeatures,
			saved ? Terrain.Natural.INSTANCE : Terrain.Palette.of(preset),
			saved ? Preset.Bedrock.OFF : preset.bedrock,
			arena.wallTop,
			arena.wallTop,
			grid[0],
			grid[1],
			slices,
			List.copyOf(cellWalls),
			!saved && preset.structures,
			!preset.roofed());
	}

	/**
	 * The chosen biome, if the arena's dimension can have it; otherwise that dimension's own
	 * default, since a biome it was not built for has no features planned and would fail to grow.
	 */
	private static Holder<Biome> biome(Arena arena, String id) {
		MinecraftServer server = PvpDimensions.server();
		if (server == null) throw new IllegalStateException("Arena terrain asked for with no server");
		var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		ServerLevel level = server.getLevel(arena.dimension);
		ArenaBiomes source = level != null && level.getChunkSource().getGenerator() instanceof ArenaGenerator generator
			&& generator.getBiomeSource() instanceof ArenaBiomes arenaBiomes ? arenaBiomes : null;

		Identifier parsed = Identifier.tryParse(id);
		if (parsed != null) {
			var found = biomes.get(ResourceKey.create(Registries.BIOME, parsed));
			if (found.isPresent() && (source == null || source.choosable().contains(found.get()))) return found.get();
		}
		if (source != null) return source.fallback();
		return biomes.getOrThrow(Biomes.PLAINS);
	}

	/** The dimension a preset's arenas are made in. */
	public static ResourceKey<net.minecraft.world.level.Level> dimensionOf(Preset preset) {
		if (preset.source == Preset.Source.SAVED) {
			ResourceKey<net.minecraft.world.level.Level> recorded = SavedTerrains.dimensionOf(preset.saved);
			if (recorded != null) return recorded;
		}
		return Places.forWorld(preset.world);
	}

	public static String defaultBiome(Preset preset) {
		return Fields.defaultBiome(preset.world);
	}
}
