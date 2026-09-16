package justfatlard.pvp_dimensions.preset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The hostile mobs an arena can spawn: all of them, in any world, each with its own level from off
 * to common, the ones that belong to the arena's own sky listed first. Only kinds the game actually
 * has are offered.
 *
 * <p>A kind is a mob as the arena spawns it, and some are not one of the game's own: a baby zombie,
 * a skeleton on a skeleton horse, a wolf that comes in already angry. Those are {@link Variant}s,
 * listed beside the mob they're made from, and the plain kind is then only ever the grown one on
 * foot, so the mix is the preset's.
 */
public final class MobKinds {
	private MobKinds() {}

	private static final String OURS = "pvp-dimensions-justfatlard:";

	/**
	 * A kind made of the game's own: {@code rider}, a baby where {@code baby} says so, on
	 * {@code mount} where there is one.
	 *
	 * @param icon  the picture the editor shows for it
	 * @param small whether that picture is drawn small, for a baby
	 */
	public record Variant(String id, String name, String rider, boolean baby, @Nullable String mount, String icon, boolean small) {}

	private static final Map<String, Variant> VARIANTS = new LinkedHashMap<>();

	private static void variant(String path, String name, String rider, boolean baby, @Nullable String mount, String icon, boolean small) {
		VARIANTS.put(OURS + path, new Variant(OURS + path, name, rider, baby, mount, icon, small));
	}

	/** A mob that only fights back, sent in already angry: one of the game's neutral mobs. */
	private static void angry(String path, String name, String mob) {
		variant(path, name, mob, false, null, mob + "_spawn_egg", false);
	}

	static {
		variant("baby_zombie", "Baby zombie", "minecraft:zombie", true, null, "minecraft:zombie_spawn_egg", true);
		variant("baby_husk", "Baby husk", "minecraft:husk", true, null, "minecraft:husk_spawn_egg", true);
		variant("baby_drowned", "Baby drowned", "minecraft:drowned", true, null, "minecraft:drowned_spawn_egg", true);
		variant("baby_zombified_piglin", "Baby zombified piglin", "minecraft:zombified_piglin", true, null, "minecraft:zombified_piglin_spawn_egg", true);
		variant("chicken_jockey", "Chicken jockey", "minecraft:zombie", true, "minecraft:chicken", "minecraft:chicken_spawn_egg", false);
		variant("spider_jockey", "Spider jockey", "minecraft:skeleton", false, "minecraft:spider", "minecraft:spider_spawn_egg", false);
		variant("skeleton_horseman", "Skeleton horseman", "minecraft:skeleton", false, "minecraft:skeleton_horse", "minecraft:skeleton_horse_spawn_egg", false);
		variant("zombie_horseman", "Zombie horseman", "minecraft:zombie", false, "minecraft:zombie_horse", "minecraft:zombie_horse_spawn_egg", false);
		variant("camel_husk_rider", "Husk on a camel", "minecraft:husk", false, "minecraft:camel_husk", "minecraft:camel_husk_spawn_egg", false);
		angry("angry_wolf", "Angry wolf", "minecraft:wolf");
		angry("angry_bee", "Angry bee", "minecraft:bee");
		angry("angry_iron_golem", "Angry iron golem", "minecraft:iron_golem");
		angry("angry_polar_bear", "Angry polar bear", "minecraft:polar_bear");
	}

	private static final List<String> OVERWORLD = List.of(
		"minecraft:zombie", OURS + "baby_zombie", OURS + "chicken_jockey", OURS + "zombie_horseman",
		"minecraft:husk", OURS + "baby_husk", OURS + "camel_husk_rider", "minecraft:drowned", OURS + "baby_drowned",
		"minecraft:zombie_villager", "minecraft:giant", "minecraft:skeleton", OURS + "skeleton_horseman", "minecraft:stray",
		"minecraft:bogged", "minecraft:parched", "minecraft:creeper", "minecraft:spider", OURS + "spider_jockey",
		"minecraft:cave_spider", "minecraft:silverfish", "minecraft:enderman", "minecraft:witch", "minecraft:slime",
		"minecraft:sulfur_cube", "minecraft:phantom", "minecraft:breeze", "minecraft:pillager", "minecraft:vindicator",
		"minecraft:evoker", "minecraft:illusioner", "minecraft:ravager", "minecraft:warden",
		OURS + "angry_wolf", OURS + "angry_bee", OURS + "angry_iron_golem", OURS + "angry_polar_bear");

	private static final List<String> NETHER = List.of(
		"minecraft:zombified_piglin", OURS + "baby_zombified_piglin", "minecraft:piglin", "minecraft:piglin_brute",
		"minecraft:hoglin", "minecraft:zoglin", "minecraft:ghast", "minecraft:magma_cube", "minecraft:blaze",
		"minecraft:wither_skeleton", "minecraft:skeleton");

	private static final List<String> END = List.of("minecraft:enderman", "minecraft:endermite", "minecraft:shulker");

	/** Every kind, whatever the world: the ones that belong to this one first, then the rest. */
	public static List<String> of(Preset.World world) {
		List<String> wanted = new ArrayList<>(switch (world) {
			case OVERWORLD -> OVERWORLD;
			case NETHER -> NETHER;
			case END -> END;
		});
		for (List<String> other : List.of(OVERWORLD, NETHER, END)) wanted.addAll(other);
		java.util.Set<String> present = new java.util.LinkedHashSet<>();
		for (String id : wanted) {
			if (exists(id)) present.add(id);
		}
		return new ArrayList<>(present);
	}

	/** The variant this kind is, or null for one of the game's own. */
	public static @Nullable Variant variant(String id) {
		return VARIANTS.get(id);
	}

	/** Whether the game has everything the kind is made of. */
	private static boolean exists(String id) {
		Variant variant = variant(id);
		if (variant != null) return entityExists(variant.rider()) && (variant.mount() == null || entityExists(variant.mount()));
		return entityExists(id);
	}

	private static boolean entityExists(String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed != null && BuiltInRegistries.ENTITY_TYPE.containsKey(parsed);
	}

	/** The kind's picture: its spawn egg, a variant's own, or a head or spawner for the few that have none. */
	public static String egg(String id) {
		Variant variant = variant(id);
		if (variant != null) return itemExists(variant.icon()) ? variant.icon() : egg(variant.rider());
		if (id.equals("minecraft:giant")) return "minecraft:zombie_head";
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return "minecraft:spawner";
		String egg = parsed.getNamespace() + ":" + parsed.getPath() + "_spawn_egg";
		return itemExists(egg) ? egg : "minecraft:spawner";
	}

	/** Whether the kind's picture is drawn small: a baby's. */
	public static boolean small(String id) {
		Variant variant = variant(id);
		return variant != null && variant.small();
	}

	/** What rides it, drawn small in the corner of a mounted kind's picture; empty for the rest. */
	public static String badge(String id) {
		Variant variant = variant(id);
		return variant != null && variant.mount() != null ? egg(variant.rider()) : "";
	}

	private static boolean itemExists(String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed != null && BuiltInRegistries.ITEM.containsKey(parsed);
	}

	public static String name(String id) {
		Variant variant = variant(id);
		if (variant != null) return variant.name();
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) return id;
		return Language.getInstance().getOrDefault("entity." + parsed.getNamespace() + "." + parsed.getPath(), parsed.getPath());
	}
}
