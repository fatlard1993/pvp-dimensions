package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blocks each world's ground is mostly made of: the rows a block swap starts with, each
 * becoming itself until an admin says otherwise. Anything else can be added to the list by
 * holding it.
 */
public final class Palettes {
	private Palettes() {}

	private static final List<String> OVERWORLD = List.of(
		"minecraft:grass_block", "minecraft:dirt", "minecraft:stone", "minecraft:deepslate", "minecraft:granite",
		"minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:sand", "minecraft:sandstone",
		"minecraft:gravel", "minecraft:clay", "minecraft:snow_block", "minecraft:water", "minecraft:lava",
		"minecraft:oak_log", "minecraft:oak_leaves");

	private static final List<String> NETHER = List.of(
		"minecraft:netherrack", "minecraft:basalt", "minecraft:blackstone", "minecraft:soul_sand", "minecraft:soul_soil",
		"minecraft:magma_block", "minecraft:crimson_nylium", "minecraft:warped_nylium", "minecraft:nether_wart_block",
		"minecraft:warped_wart_block", "minecraft:glowstone", "minecraft:gravel", "minecraft:lava");

	private static final List<String> END = List.of("minecraft:end_stone", "minecraft:obsidian", "minecraft:chorus_plant");

	public static List<String> defaults(Preset.World world) {
		return switch (world) {
			case OVERWORLD -> OVERWORLD;
			case NETHER -> NETHER;
			case END -> END;
		};
	}

	/** The world's blocks, then whatever else has been given a swap. */
	public static List<String> of(Preset.World world, Map<String, String> swaps) {
		Set<String> all = new LinkedHashSet<>(defaults(world));
		all.addAll(swaps.keySet());
		return new ArrayList<>(all);
	}
}
