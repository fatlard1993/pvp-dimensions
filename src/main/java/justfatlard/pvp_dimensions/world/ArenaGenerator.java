package justfatlard.pvp_dimensions.world;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's noise generator, asked for ground only inside an arena.
 *
 * <p>Wraps the game's generator rather than extending it, since that class is final. The game
 * only builds a level's noise state for its own generator class and hands everything else a blank
 * one, so this keeps its own, made from the same settings and the level's seed, and passes that
 * to the generator it wraps in place of whatever it was given.
 *
 * <p>Outside every arena a chunk is left empty: no noise, no surface, no trees. Inside, the ground
 * is vanilla's and then {@link Shaper}'s, or laid flat, or left for a saved terrain to be pasted
 * into, as the arena's {@link Terrain} says.
 *
 * <p>Flat ground is vanilla's too: a second generator with the same settings but for one number,
 * the ground's shape, which is a level surface at {@link #FLAT_TOP}. Everything the game lays on
 * ground by biome, sand on a desert, sulfur and cinnabar in sulfur caves, lays itself on the flat
 * the same way, with no list here to keep up with the game's biomes.
 */
public final class ArenaGenerator extends ChunkGenerator {
	public static final MapCodec<ArenaGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
		NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.settings)
	).apply(instance, instance.stable(ArenaGenerator::new)));

	/** Where a flat arena's surface is. */
	public static final int FLAT_TOP = 64;

	private final Holder<NoiseGeneratorSettings> settings;
	private final NoiseBasedChunkGenerator noise;
	private final Holder<NoiseGeneratorSettings> flatSettings;
	private final NoiseBasedChunkGenerator flat;
	private volatile @Nullable ResourceKey<Level> dimension;
	private volatile @Nullable RandomState randomState;
	private volatile @Nullable RandomState flatRandomState;

	public ArenaGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
		super(biomeSource);
		this.settings = settings;
		this.noise = new NoiseBasedChunkGenerator(biomeSource, settings);
		this.flatSettings = Holder.direct(flattened(settings.value()));
		this.flat = new NoiseBasedChunkGenerator(biomeSource, flatSettings);
	}

	/** The same settings, with the ground's shape a level surface and no underground water. */
	private static NoiseGeneratorSettings flattened(NoiseGeneratorSettings settings) {
		NoiseRouter router = settings.noiseRouter();
		NoiseRouter level = new NoiseRouter(router.temperature(), router.vegetation(), router.continents(), router.erosion(),
			router.depth(), router.ridges(), DensityFunctions.constant(FLAT_TOP),
			DensityFunctions.yClampedGradient(FLAT_TOP, FLAT_TOP + 1, 1.0F, -1.0F));
		return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(), settings.defaultFluid(), level,
			settings.materialRule(), settings.spawnTarget(), settings.seaLevel(), settings.disableMobGeneration(), Optional.empty(),
			settings.useLegacyRandomSource(), settings.debugFunctions());
	}

	/** Which dimension this generator builds, learned as the level loads. */
	public void bind(ResourceKey<Level> dimension) {
		this.dimension = dimension;
		if (biomeSource instanceof ArenaBiomes biomes) biomes.bind(dimension);
	}

	public @Nullable ResourceKey<Level> dimension() {
		return dimension;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	/**
	 * Where the noise state is made, since this is the first the generator hears of the seed. The
	 * level's registries are not handed over here, so they come from the server, which exists by
	 * the time any level is being built.
	 */
	@Override
	public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState given, long seed) {
		MinecraftServer server = PvpDimensions.server();
		if (server != null) {
			RegistryAccess registries = server.registryAccess();
			this.randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
			this.flatRandomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, flatSettings.value());
		} else {
			PvpDimensions.LOGGER.error("Arena generator built with no server to read noise from; arenas will be flat");
			this.randomState = given;
			this.flatRandomState = given;
		}
		return super.createState(structureSets, ours(given), seed);
	}

	private RandomState ours(RandomState given) {
		RandomState mine = randomState;
		return mine != null ? mine : given;
	}

	private @Nullable Footprint footprint(ChunkAccess chunk) {
		return Footprints.at(dimension, chunk.getPos().x(), chunk.getPos().z());
	}

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState given, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		return super.createBiomes(ours(given), blender, structureManager, chunk);
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState given,
			StructureManager structureManager, BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion,
			Set<Holder<Biome>> possibleBiomes) {
		Footprint footprint = footprint(chunk);
		if (footprint == null) return CompletableFuture.completedFuture(chunk);
		return switch (footprint.terrain().kind()) {
			case NOISE -> noise.buildTerrain(chunk, blender, ours(given), structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
				.thenApply(built -> {
					Shaper.terrain(built, footprint);
					return built;
				});
			case FLAT -> {
				RandomState level = flatRandomState != null ? flatRandomState : ours(given);
				yield flat.buildTerrain(chunk, blender, level, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
					.thenApply(built -> {
						Shaper.heal(built);
						Shaper.terrain(built, footprint);
						return built;
					});
			}
			case EMPTY -> CompletableFuture.completedFuture(chunk);
		};
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		Footprint footprint = footprint(chunk);
		if (footprint == null) return;
		Terrain terrain = footprint.terrain();
		if (terrain.kind() == Terrain.Kind.NOISE || terrain.kind() == Terrain.Kind.FLAT && terrain.features()) {
			super.applyBiomeDecoration(level, chunk, structureManager);
		}
		if (footprint.terrain().kind() != Terrain.Kind.EMPTY) Shaper.settle(chunk, footprint);
	}

	@Override
	public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
			ChunkAccess centerChunk, StructureTemplateManager templates, ResourceKey<Level> level) {
		Footprint footprint = footprint(centerChunk);
		if (footprint == null || !footprint.terrain().structures() || footprint.terrain().kind() != Terrain.Kind.NOISE) return;
		super.createStructures(registryAccess, state, structureManager, centerChunk, templates, level);
	}

	@Override
	public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess centerChunk) {
		Footprint footprint = footprint(centerChunk);
		if (footprint == null || !footprint.terrain().structures()) return;
		super.createReferences(level, structureManager, centerChunk);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		Footprint footprint = Footprints.at(dimension, region.getCenter().x(), region.getCenter().z());
		if (footprint != null && footprint.terrain().kind() != Terrain.Kind.EMPTY) noise.spawnOriginalMobs(region);
	}

	@Override
	public int getGenDepth() {
		return noise.getGenDepth();
	}

	@Override
	public int getSeaLevel() {
		return noise.getSeaLevel();
	}

	@Override
	public int getMinY() {
		return noise.getMinY();
	}

	/** The noise's height, arena or not: what an arena is placed by, before any of it exists. */
	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState given) {
		return noise.getBaseHeight(x, z, type, heightAccessor, ours(given));
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState given) {
		return noise.getBaseColumn(x, z, heightAccessor, ours(given));
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState given, BlockPos feetPos, SamplerContext samplerContext) {
		noise.addDebugScreenInfo(result, ours(given), feetPos, samplerContext);
	}

	/** The noise state this generator actually uses, for surveying ground before it is built. */
	public RandomState randomState(RandomState given) {
		return ours(given);
	}
}
