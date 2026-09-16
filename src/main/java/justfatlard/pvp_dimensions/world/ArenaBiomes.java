package justfatlard.pvp_dimensions.world;

import java.util.stream.Stream;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.jspecify.annotations.Nullable;

/**
 * One biome per arena, the one its preset chose, whatever the climate noise would have said.
 * Terrain still takes its shape from the noise, so a desert arena has dunes and hills, but every
 * block of it is desert: sand on top, cacti and all.
 *
 * <p>{@code biomes} is every biome an arena in this dimension may choose, which is also what the
 * game plans features and structures around; everywhere outside an arena is {@code fallback}.
 */
public final class ArenaBiomes extends BiomeSource {
	public static final MapCodec<ArenaBiomes> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Biome.LIST_CODEC.fieldOf("biomes").forGetter(source -> source.biomes),
		Biome.CODEC.fieldOf("fallback").forGetter(source -> source.fallback)
	).apply(instance, ArenaBiomes::new));

	private final HolderSet<Biome> biomes;
	private final Holder<Biome> fallback;
	private volatile @Nullable ResourceKey<Level> dimension;

	public ArenaBiomes(HolderSet<Biome> biomes, Holder<Biome> fallback) {
		this.biomes = biomes;
		this.fallback = fallback;
	}

	void bind(ResourceKey<Level> dimension) {
		this.dimension = dimension;
	}

	public HolderSet<Biome> choosable() {
		return biomes;
	}

	public Holder<Biome> fallback() {
		return fallback;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(biomes.stream(), Stream.of(fallback)).distinct();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return (quartX, quartY, quartZ) -> {
			Footprint footprint = Footprints.at(dimension, quartX >> 2, quartZ >> 2);
			return footprint == null ? fallback : footprint.terrain().biome();
		};
	}
}
