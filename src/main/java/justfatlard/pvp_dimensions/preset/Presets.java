package justfatlard.pvp_dimensions.preset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import org.jspecify.annotations.Nullable;

/**
 * The saved presets, one file each in {@code config/pvp-dimensions/presets}: a server's, not a
 * world's, so a good arena survives a new map and can be copied to another server by hand.
 *
 * <p>Items are written with the game's own codec, enchantments and all, which needs the server's
 * registries; so presets load once the server has started and not before.
 */
public final class Presets {
	private Presets() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<String, Preset> byId = new LinkedHashMap<>();
	private static HolderLookup.@Nullable Provider registries;

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve("pvp-dimensions").resolve("presets");
	}

	public static void load(HolderLookup.Provider lookup) {
		registries = lookup;
		byId.clear();
		Path folder = folder();
		try {
			Files.createDirectories(folder);
			try (Stream<Path> files = Files.list(folder)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
					String id = file.getFileName().toString().replaceFirst("\\.json$", "");
					try {
						byId.put(id, read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()));
					} catch (Exception e) {
						PvpDimensions.LOGGER.error("Preset {} could not be read and was skipped", file, e);
					}
				}
			}
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Presets could not be listed", e);
		}
		if (byId.isEmpty()) {
			Preset first = new Preset();
			first.name = "Skirmish";
			save(null, first);
		}
	}

	public static List<Map.Entry<String, Preset>> all() {
		List<Map.Entry<String, Preset>> list = new ArrayList<>(byId.entrySet());
		list.sort(Comparator.comparing(entry -> entry.getValue().name.toLowerCase(Locale.ROOT)));
		return list;
	}

	public static @Nullable Preset get(String id) {
		return byId.get(id);
	}

	public static List<String> ids() {
		return all().stream().map(Map.Entry::getKey).toList();
	}

	/**
	 * Save under the id it was opened from, or a fresh one made from its name.
	 *
	 * @return the id it is saved under
	 */
	public static String save(@Nullable String id, Preset preset) {
		String key = id != null ? id : freshId(preset.name);
		byId.put(key, copy(preset));
		try {
			Files.createDirectories(folder());
			Files.writeString(folder().resolve(key + ".json"), GSON.toJson(write(preset)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Preset {} could not be saved", key, e);
		}
		return key;
	}

	public static boolean delete(String id) {
		if (byId.remove(id) == null) return false;
		try {
			Files.deleteIfExists(folder().resolve(id + ".json"));
		} catch (IOException e) {
			PvpDimensions.LOGGER.error("Preset {} could not be deleted", id, e);
		}
		return true;
	}

	private static String freshId(String name) {
		String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (base.isEmpty()) base = "preset";
		String id = base;
		for (int n = 2; byId.containsKey(id); n++) id = base + "_" + n;
		return id;
	}

	/** A deep copy, by way of the saved form, so nothing is shared with the original. */
	public static Preset copy(Preset preset) {
		return read(write(preset));
	}

	// --- The file ---

	private static RegistryOps<JsonElement> ops() {
		if (registries == null) throw new IllegalStateException("Presets used before the server started");
		return registries.createSerializationContext(JsonOps.INSTANCE);
	}

	public static JsonObject write(Preset p) {
		JsonObject json = new JsonObject();
		json.addProperty("name", p.name);
		json.addProperty("icon", p.icon);
		json.addProperty("for_users", p.forUsers);
		JsonArray adjustable = new JsonArray();
		p.adjustable.forEach(adjustable::add);
		json.add("adjustable", adjustable);

		json.addProperty("traversal", name(p.traversal));
		json.add("rewards", encode(ItemList.CODEC, p.rewards));
		json.add("entry_kit", encode(Kit.CODEC, p.entryKit));
		json.addProperty("death_capture", p.deathCapture);
		json.addProperty("respawn_kit", name(p.respawnKit));
		json.add("respawn_custom", encode(Kit.CODEC, p.respawnCustom));
		json.addProperty("first_loadout", p.firstLoadout);
		JsonArray loadouts = new JsonArray();
		for (Preset.Loadout loadout : p.loadouts) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", loadout.name);
			entry.add("kit", encode(Kit.CODEC, loadout.kit));
			entry.addProperty("respawn", name(loadout.respawn));
			entry.add("respawn_custom", encode(Kit.CODEC, loadout.respawnCustom));
			entry.addProperty("cap", loadout.cap);
			loadouts.add(entry);
		}
		json.add("loadouts", loadouts);
		json.addProperty("loadout_repick", p.loadoutRepick);
		json.addProperty("caps_per_team", p.capsPerTeam);
		json.add("kill_reward", encode(ItemList.CODEC, p.killReward));
		json.add("mob_kill_reward", encode(ItemList.CODEC, p.mobKillReward));
		JsonObject bonus = new JsonObject();
		p.killBonus.forEach((type, list) -> { if (!list.isEmpty()) bonus.add(name(type), encode(ItemList.CODEC, list)); });
		json.add("kill_bonus", bonus);
		JsonObject feats = new JsonObject();
		p.featRewards.forEach((feat, list) -> { if (!list.isEmpty()) feats.add(name(feat), encode(ItemList.CODEC, list)); });
		json.add("feat_rewards", feats);

		json.addProperty("mode", name(p.mode));
		json.addProperty("teams", p.teams);
		json.addProperty("friendly_fire", p.friendlyFire);
		json.addProperty("pvp", p.pvp);
		json.addProperty("waiting_room", p.waitingRoom);
		json.addProperty("late_join", name(p.lateJoin));
		json.addProperty("game_mode", name(p.gameMode));
		json.addProperty("spawn", name(p.spawn));
		json.addProperty("name_tags", name(p.nameTags));
		json.addProperty("exit_portal", p.exitPortal);
		json.addProperty("exit_command", p.exitCommand);
		json.addProperty("death_compasses", p.deathCompasses);
		json.addProperty("head_lock", p.headLock);
		json.addProperty("last_side", p.endWhenOneSideLeft);

		json.addProperty("goal", name(p.goal));
		json.addProperty("kill_target", p.killTarget);
		json.addProperty("lives", name(p.lives));
		json.addProperty("lives_count", p.livesCount);
		json.addProperty("mob_goal_kind", p.mobGoalKind);
		json.addProperty("mob_goal_count", p.mobGoalCount);
		json.addProperty("mob_goal_scope", name(p.mobGoalScope));
		json.addProperty("captures", p.captures);
		json.addProperty("takeover_percent", p.takeoverPercent);
		json.addProperty("base_size", p.baseSize);
		JsonArray prizes = new JsonArray();
		for (ItemList prize : p.prizes) prizes.add(encode(ItemList.CODEC, prize));
		json.add("prizes", prizes);
		json.addProperty("winners_keep_pack", p.winnersKeepPack);
		json.addProperty("hill_place", name(p.hillPlace));
		json.addProperty("hill_moves", p.hillMoves);
		json.addProperty("hill_target", p.hillTarget);
		json.add("bank_values", encode(ItemList.CODEC, p.bankValues));
		json.addProperty("bank_target", p.bankTarget);
		json.addProperty("finish_place", name(p.finishPlace));
		json.addProperty("finish_style", name(p.finishStyle));
		json.add("entry_fee", encode(ItemList.CODEC, p.entryFee));
		json.addProperty("fees_to_winners", p.feesToWinners);
		json.addProperty("goal_compass", p.goalCompass);
		json.addProperty("horde", p.horde);
		json.add("horde_kit", encode(Kit.CODEC, p.hordeKit));

		json.addProperty("mobs", name(p.mobs));
		json.addProperty("mob_rarity", name(p.mobRarity));
		json.addProperty("mob_style", name(p.mobStyle));
		JsonArray waves = new JsonArray();
		for (Preset.Wave wave : p.waves) {
			JsonArray mobs = new JsonArray();
			for (Preset.WaveMob mob : wave.mobs) {
				JsonObject entry = new JsonObject();
				entry.addProperty("kind", mob.kind());
				entry.addProperty("per_player", mob.tenths() / 10.0);
				mobs.add(entry);
			}
			JsonObject entry = new JsonObject();
			entry.add("mobs", mobs);
			waves.add(entry);
		}
		json.add("waves", waves);
		json.addProperty("wave_break", p.waveBreak);
		json.addProperty("wave_limit", p.waveLimit);
		json.addProperty("after_waves", name(p.afterWaves));
		json.addProperty("mob_default", name(p.mobDefault));
		JsonObject mobLevels = new JsonObject();
		p.mobLevels.forEach((kind, level) -> mobLevels.addProperty(kind, name(level)));
		json.add("mob_levels", mobLevels);

		json.addProperty("life", p.lifeMinutes);
		json.addProperty("border_shrink", p.borderShrink);
		json.addProperty("weather", name(p.weather));
		json.addProperty("weather_later", name(p.weatherLater));
		json.addProperty("weather_after", p.weatherAfter);

		json.addProperty("source", name(p.source));
		json.addProperty("saved", p.saved);
		json.addProperty("size", p.size);
		json.addProperty("world", name(p.world));
		json.addProperty("shape", name(p.shape));
		json.addProperty("biome", p.biome);
		json.addProperty("depth", p.depth);
		json.addProperty("material", name(p.material));
		json.addProperty("single", p.single);
		JsonObject swaps = new JsonObject();
		p.swaps.forEach(swaps::addProperty);
		json.add("swaps", swaps);
		JsonArray layers = new JsonArray();
		for (Preset.Layer layer : p.layers) {
			JsonObject entry = new JsonObject();
			entry.addProperty("block", layer.block());
			entry.addProperty("percent", layer.percent());
			layers.add(entry);
		}
		json.add("layers", layers);
		json.addProperty("bedrock", name(p.bedrock));
		json.addProperty("structures", p.structures);
		json.addProperty("flat_features", p.flatFeatures);
		json.addProperty("divisions", p.divisions);
		json.addProperty("division_shape", name(p.divisionShape));
		json.addProperty("division_block", p.divisionBlock);
		json.addProperty("team_walls", p.teamWalls);
		json.addProperty("team_bases", name(p.teamBases));
		json.addProperty("fog", p.fog);
		json.addProperty("chest_access", name(p.chestAccess));
		JsonObject walls = new JsonObject();
		p.teamWallBlocks.forEach((team, block) -> walls.addProperty(String.valueOf(team), block));
		json.add("team_wall_blocks", walls);
		json.addProperty("walls_fall", p.wallsFall);
		json.addProperty("walls_hold", p.wallsHold);
		JsonArray changes = new JsonArray();
		for (Preset.ModeChange change : p.modeChanges) {
			JsonObject entry = new JsonObject();
			entry.addProperty("minute", change.minute);
			entry.addProperty("mode", name(change.mode));
			entry.addProperty("warn", change.warn);
			entry.addProperty("kits", change.kits);
			changes.add(entry);
		}
		json.add("mode_changes", changes);

		json.addProperty("pinata", name(p.pinata));
		json.addProperty("pinata_minutes", p.pinataMinutes);
		json.addProperty("pinata_regardless", p.pinataRegardless);
		json.addProperty("pinata_count", p.pinataCount);
		json.addProperty("pinata_place", name(p.pinataPlace));
		JsonArray loot = new JsonArray();
		for (Preset.PinataLoot entry : p.pinataLoot) {
			JsonObject item = new JsonObject();
			item.addProperty("hits", entry.hits);
			item.add("items", encode(ItemList.CODEC, entry.items));
			loot.add(item);
		}
		json.add("pinata_loot", loot);
		return json;
	}

	/** Anything missing or unreadable keeps the default, so an old file still opens. */
	public static Preset read(JsonObject json) {
		Preset p = new Preset();
		string(json, "name", v -> p.name = v);
		string(json, "icon", v -> p.icon = v);
		bool(json, "for_users", v -> p.forUsers = v);
		p.adjustable = new java.util.TreeSet<>();
		if (json.has("adjustable") && json.get("adjustable").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("adjustable")) {
				if (Fields.adjustableKeys().contains(element.getAsString())) p.adjustable.add(element.getAsString());
			}
		}

		p.traversal = choice(json, "traversal", Preset.Traversal.class, p.traversal);
		p.rewards = decode(json, "rewards", ItemList.CODEC, p.rewards);
		p.entryKit = decode(json, "entry_kit", Kit.CODEC, p.entryKit);
		bool(json, "death_capture", v -> p.deathCapture = v);
		p.respawnKit = choice(json, "respawn_kit", Preset.RespawnKit.class, p.respawnKit);
		p.respawnCustom = decode(json, "respawn_custom", Kit.CODEC, p.respawnCustom);
		string(json, "first_loadout", v -> p.firstLoadout = v);
		if (json.has("loadouts") && json.get("loadouts").isJsonArray()) {
			p.loadouts = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("loadouts")) {
				if (!element.isJsonObject()) continue;
				JsonObject entry = element.getAsJsonObject();
				Preset.Loadout loadout = new Preset.Loadout();
				string(entry, "name", v -> loadout.name = v);
				loadout.kit = decode(entry, "kit", Kit.CODEC, loadout.kit);
				loadout.respawn = choice(entry, "respawn", Preset.RespawnKit.class, loadout.respawn);
				loadout.respawnCustom = decode(entry, "respawn_custom", Kit.CODEC, loadout.respawnCustom);
				integer(entry, "cap", v -> loadout.cap = Math.max(0, v));
				p.loadouts.add(loadout);
			}
		}
		bool(json, "loadout_repick", v -> p.loadoutRepick = v);
		bool(json, "caps_per_team", v -> p.capsPerTeam = v);
		p.killReward = decode(json, "kill_reward", ItemList.CODEC, p.killReward);
		p.mobKillReward = decode(json, "mob_kill_reward", ItemList.CODEC, p.mobKillReward);
		p.featRewards = new EnumMap<>(Preset.Feat.class);
		if (json.has("feat_rewards") && json.get("feat_rewards").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("feat_rewards").entrySet()) {
				Preset.Feat feat = parse(Preset.Feat.class, entry.getKey());
				if (feat != null) p.featRewards.put(feat, decodeElement(entry.getValue(), ItemList.CODEC, new ItemList()));
			}
		}
		p.killBonus = new EnumMap<>(Preset.KillType.class);
		if (json.has("kill_bonus") && json.get("kill_bonus").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("kill_bonus").entrySet()) {
				Preset.KillType type = parse(Preset.KillType.class, entry.getKey());
				if (type != null) p.killBonus.put(type, decodeElement(entry.getValue(), ItemList.CODEC, new ItemList()));
			}
		}

		p.mode = choice(json, "mode", Preset.Mode.class, p.mode);
		integer(json, "teams", v -> p.teams = Math.max(2, Math.min(TeamColors.MAX, v)));
		bool(json, "friendly_fire", v -> p.friendlyFire = v);
		bool(json, "pvp", v -> p.pvp = v);
		bool(json, "waiting_room", v -> p.waitingRoom = v);
		p.lateJoin = choice(json, "late_join", Preset.LateJoin.class, p.lateJoin);
		p.gameMode = choice(json, "game_mode", Preset.GameRule.class, p.gameMode);
		p.spawn = choice(json, "spawn", Preset.Spawn.class, p.spawn);
		p.nameTags = choice(json, "name_tags", Preset.NameTags.class, p.nameTags);
		bool(json, "exit_portal", v -> p.exitPortal = v);
		bool(json, "exit_command", v -> p.exitCommand = v);
		bool(json, "death_compasses", v -> p.deathCompasses = v);
		integer(json, "head_lock", v -> p.headLock = Math.max(-1, v));
		bool(json, "last_side", v -> p.endWhenOneSideLeft = v);

		p.goal = choice(json, "goal", Preset.Goal.class, p.goal);
		integer(json, "kill_target", v -> p.killTarget = Math.max(0, v));
		p.lives = choice(json, "lives", Preset.Lives.class, p.lives);
		integer(json, "lives_count", v -> p.livesCount = Math.max(1, v));
		string(json, "mob_goal_kind", v -> p.mobGoalKind = v);
		integer(json, "mob_goal_count", v -> p.mobGoalCount = Math.max(1, v));
		p.mobGoalScope = choice(json, "mob_goal_scope", Preset.Scope.class, p.mobGoalScope);
		integer(json, "captures", v -> p.captures = Math.max(1, v));
		integer(json, "takeover_percent", v -> p.takeoverPercent = Math.max(0, Math.min(100, v)));
		integer(json, "base_size", v -> p.baseSize = Math.max(1, Math.min(7, v)));
		bool(json, "winners_keep_pack", v -> p.winnersKeepPack = v);
		p.hillPlace = choice(json, "hill_place", Preset.MarkerPlace.class, p.hillPlace);
		integer(json, "hill_moves", v -> p.hillMoves = Math.max(0, v));
		integer(json, "hill_target", v -> p.hillTarget = Math.max(0, v));
		p.bankValues = decode(json, "bank_values", ItemList.CODEC, p.bankValues);
		integer(json, "bank_target", v -> p.bankTarget = Math.max(0, v));
		p.finishPlace = choice(json, "finish_place", Preset.MarkerPlace.class, p.finishPlace);
		p.finishStyle = choice(json, "finish_style", Preset.FinishStyle.class, p.finishStyle);
		p.entryFee = decode(json, "entry_fee", ItemList.CODEC, p.entryFee);
		bool(json, "fees_to_winners", v -> p.feesToWinners = v);
		bool(json, "goal_compass", v -> p.goalCompass = v);
		bool(json, "horde", v -> p.horde = v);
		p.hordeKit = decode(json, "horde_kit", Kit.CODEC, p.hordeKit);
		if (json.has("prizes") && json.get("prizes").isJsonArray()) {
			p.prizes = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("prizes")) p.prizes.add(decodeElement(element, ItemList.CODEC, new ItemList()));
		} else {
			// Presets from before there could be more than one prize kept theirs as one list.
			ItemList only = decode(json, "winner_items", ItemList.CODEC, new ItemList());
			if (!only.isEmpty()) p.prizes = new ArrayList<>(List.of(only));
		}

		p.mobs = choice(json, "mobs", Preset.MobTime.class, p.mobs);
		p.mobRarity = choice(json, "mob_rarity", Preset.Rarity.class, p.mobRarity);
		p.mobStyle = choice(json, "mob_style", Preset.MobStyle.class, p.mobStyle);
		if (json.has("waves") && json.get("waves").isJsonArray()) {
			p.waves = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("waves")) {
				Preset.Wave wave = new Preset.Wave();
				JsonObject entry = element.getAsJsonObject();
				if (entry.has("mobs") && entry.get("mobs").isJsonArray()) {
					for (JsonElement mob : entry.getAsJsonArray("mobs")) {
						JsonObject fields = mob.getAsJsonObject();
						if (!fields.has("kind")) continue;
						int tenths = fields.has("per_player") ? (int) Math.round(fields.get("per_player").getAsDouble() * 10) : 10;
						wave.mobs.add(new Preset.WaveMob(fields.get("kind").getAsString(), Math.max(1, tenths)));
					}
				}
				p.waves.add(wave);
			}
		}
		integer(json, "wave_break", v -> p.waveBreak = Math.max(1, v));
		integer(json, "wave_limit", v -> p.waveLimit = Math.max(0, v));
		p.afterWaves = choice(json, "after_waves", Preset.AfterWaves.class, p.afterWaves);
		// Presets from before kinds could start off had every kind not named at normal.
		p.mobDefault = choice(json, "mob_default", Preset.MobLevel.class, Preset.MobLevel.NORMAL);
		p.mobLevels = new java.util.TreeMap<>();
		if (json.has("mob_levels") && json.get("mob_levels").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("mob_levels").entrySet()) {
				Preset.MobLevel level = parse(Preset.MobLevel.class, entry.getValue().getAsString());
				if (level != null) p.setMobLevel(entry.getKey(), level);
			}
		} else if (json.has("mobs_off") && json.get("mobs_off").isJsonArray()) {
			// Before each kind had a level it was only on or off.
			for (JsonElement element : json.getAsJsonArray("mobs_off")) p.mobLevels.put(element.getAsString(), Preset.MobLevel.OFF);
		}

		integer(json, "life", v -> p.lifeMinutes = Math.max(0, v));
		integer(json, "border_shrink", v -> p.borderShrink = Math.max(0, Math.min(90, v)));
		p.weather = choice(json, "weather", Preset.Weather.class, p.weather);
		p.weatherLater = choice(json, "weather_later", Preset.Weather.class, p.weatherLater);
		integer(json, "weather_after", v -> p.weatherAfter = Math.max(1, v));

		p.source = choice(json, "source", Preset.Source.class, p.source);
		string(json, "saved", v -> p.saved = v);
		integer(json, "size", v -> p.size = Math.max(2, Math.min(Fields.MAX_SIZE, v)));
		if (json.has("world")) {
			p.world = choice(json, "world", Preset.World.class, p.world);
			p.shape = choice(json, "shape", Preset.Shape.class, p.shape);
		} else if (json.has("shape")) {
			// Before the world and the shape were two settings, flatland was a fourth world.
			String old = json.get("shape").getAsString();
			p.shape = old.equals("flat") ? Preset.Shape.FLAT : Preset.Shape.NATURAL;
			Preset.World world = parse(Preset.World.class, old);
			p.world = world != null ? world : Preset.World.OVERWORLD;
		}
		string(json, "biome", v -> p.biome = v);
		integer(json, "depth", v -> p.depth = Math.max(0, v));
		p.material = choice(json, "material", Preset.Material.class, p.material);
		string(json, "single", v -> p.single = v);
		p.swaps = new LinkedHashMap<>();
		if (json.has("swaps") && json.get("swaps").isJsonObject()) {
			json.getAsJsonObject("swaps").entrySet().forEach(entry -> p.swaps.put(entry.getKey(), entry.getValue().getAsString()));
		}
		if (json.has("layers") && json.get("layers").isJsonArray()) {
			p.layers = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("layers")) {
				JsonObject layer = element.getAsJsonObject();
				p.layers.add(new Preset.Layer(layer.get("block").getAsString(), layer.get("percent").getAsInt()));
			}
			if (p.layers.isEmpty()) p.layers.add(new Preset.Layer("minecraft:stone", 100));
		}
		p.bedrock = choice(json, "bedrock", Preset.Bedrock.class, p.bedrock);
		bool(json, "structures", v -> p.structures = v);
		bool(json, "flat_features", v -> p.flatFeatures = v);
		integer(json, "divisions", v -> p.divisions = Math.max(1, Math.min(9, v)));
		// Presets from before slices were divided into a grid, and an arena running from one was built so.
		p.divisionShape = choice(json, "division_shape", Preset.DivisionShape.class, Preset.DivisionShape.GRID);
		string(json, "division_block", v -> p.divisionBlock = v);
		bool(json, "team_walls", v -> p.teamWalls = v);
		p.teamBases = choice(json, "team_bases", Preset.BaseStyle.class, p.teamBases);
		integer(json, "fog", v -> p.fog = Math.max(0, Math.min(32, v)));
		p.chestAccess = choice(json, "chest_access", Preset.ChestAccess.class, p.chestAccess);
		p.teamWallBlocks = new LinkedHashMap<>();
		if (json.has("team_wall_blocks") && json.get("team_wall_blocks").isJsonObject()) {
			json.getAsJsonObject("team_wall_blocks").entrySet().forEach(entry -> {
				try {
					p.teamWallBlocks.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
				} catch (NumberFormatException ignored) {
				}
			});
		}
		integer(json, "walls_fall", v -> p.wallsFall = Math.max(0, v));
		bool(json, "walls_hold", v -> p.wallsHold = v);
		if (json.has("mode_changes") && json.get("mode_changes").isJsonArray()) {
			p.modeChanges = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("mode_changes")) {
				if (!element.isJsonObject()) continue;
				JsonObject entry = element.getAsJsonObject();
				Preset.ModeChange change = new Preset.ModeChange();
				integer(entry, "minute", v -> change.minute = Math.max(1, v));
				change.mode = choice(entry, "mode", Preset.GameRule.class, change.mode);
				bool(entry, "warn", v -> change.warn = v);
				bool(entry, "kits", v -> change.kits = v);
				p.modeChanges.add(change);
			}
		}

		p.pinata = choice(json, "pinata", Preset.PinataMode.class, p.pinata);
		integer(json, "pinata_minutes", v -> p.pinataMinutes = Math.max(1, v));
		bool(json, "pinata_regardless", v -> p.pinataRegardless = v);
		integer(json, "pinata_count", v -> p.pinataCount = Math.max(1, Math.min(5, v)));
		p.pinataPlace = choice(json, "pinata_place", Preset.PinataPlace.class, p.pinataPlace);
		if (json.has("pinata_loot") && json.get("pinata_loot").isJsonArray()) {
			p.pinataLoot = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray("pinata_loot")) {
				JsonObject entry = element.getAsJsonObject();
				Preset.PinataLoot loot = new Preset.PinataLoot();
				if (entry.has("hits")) loot.hits = Math.max(1, entry.get("hits").getAsInt());
				loot.items = decodeElement(entry.get("items"), ItemList.CODEC, new ItemList());
				p.pinataLoot.add(loot);
			}
			if (p.pinataLoot.isEmpty()) p.pinataLoot.add(new Preset.PinataLoot());
		}
		return p;
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	private static <E extends Enum<E>> @Nullable E parse(Class<E> type, String value) {
		try {
			return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <E extends Enum<E>> E choice(JsonObject json, String key, Class<E> type, E fallback) {
		if (!json.has(key)) return fallback;
		E value = parse(type, json.get(key).getAsString());
		return value != null ? value : fallback;
	}

	private static void string(JsonObject json, String key, Consumer<String> put) {
		if (json.has(key) && json.get(key).isJsonPrimitive()) put.accept(json.get(key).getAsString());
	}

	private static void bool(JsonObject json, String key, Consumer<Boolean> put) {
		if (json.has(key) && json.get(key).isJsonPrimitive()) put.accept(json.get(key).getAsBoolean());
	}

	private static void integer(JsonObject json, String key, Consumer<Integer> put) {
		if (json.has(key) && json.get(key).isJsonPrimitive()) put.accept(json.get(key).getAsInt());
	}

	private static <T> JsonElement encode(Codec<T> codec, T value) {
		return codec.encodeStart(ops(), value).getOrThrow();
	}

	private static <T> T decode(JsonObject json, String key, Codec<T> codec, T fallback) {
		return json.has(key) ? decodeElement(json.get(key), codec, fallback) : fallback;
	}

	private static <T> T decodeElement(@Nullable JsonElement element, Codec<T> codec, T fallback) {
		if (element == null) return fallback;
		return codec.parse(ops(), element).resultOrPartial(error ->
			PvpDimensions.LOGGER.warn("Part of a preset could not be read: {}", error)).orElse(fallback);
	}

	/**
	 * For the arena's own copy, kept in the world's saved data: as the same text a preset file
	 * holds, since a round trip through the world's format would turn every switch into a number.
	 */
	public static final Codec<Preset> CODEC = Codec.STRING.xmap(
		text -> read(JsonParser.parseString(text).getAsJsonObject()),
		preset -> GSON.toJson(write(preset)));

	/** An item list kept in the world's saved data, as text for the same reason. */
	public static final Codec<ItemList> ITEMS = Codec.STRING.xmap(
		text -> decodeElement(JsonParser.parseString(text), ItemList.CODEC, new ItemList()),
		items -> GSON.toJson(encode(ItemList.CODEC, items)));
}
