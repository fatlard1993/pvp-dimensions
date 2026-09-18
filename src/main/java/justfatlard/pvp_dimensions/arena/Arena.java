package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.mojang.serialization.Codec;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import justfatlard.pvp_dimensions.world.Footprint;
import justfatlard.pvp_dimensions.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * One running arena: the preset it was started from, copied, where it stands, who is in it, and
 * where it is in its life. Kept in the world's saved data, so an arena outlives a restart.
 */
public final class Arena {
	public enum Phase { GENERATING, LOBBY, LIVE, ENDED }

	/** Somebody who has been in this arena, and how they have done. */
	public static final class Member {
		public final UUID id;
		public String name;
		public int team = -1;
		public int kills;
		public int deaths;
		public int streak;
		/** Mobs killed, the ones that count toward a goal of mob kills. */
		public int mobKills;
		public @Nullable UUID lastKiller;
		public boolean inside;
		public boolean watching;
		/** Lives left, for a goal with lives; -1 before the first is counted. */
		public int lives = -1;
		/** Out of lives, or on a team whose base has fallen: watching until the end, or one of the horde. */
		public boolean out;
		/** Out, and back as one of the horde. */
		public boolean zombie;
		/** Seconds held on the hill. */
		public int held;
		/** What they paid to come in, until it is spent, shared out or handed back. */
		public ItemList fee = new ItemList();
		/** Whether they have paid to come in; nobody pays twice for one arena. */
		public boolean paid;
		/** Which loadout they picked: 0, the entry kit, until they pick. */
		public int loadout;
		/** What this round has been worth to them, where the goal keeps a score of its own. */
		public int points;

		Member(UUID id, String name) {
			this.id = id;
			this.name = name;
		}

		CompoundTag save() {
			CompoundTag tag = new CompoundTag();
			tag.store("id", UUIDUtil.CODEC, id);
			tag.putString("name", name);
			tag.putInt("team", team);
			tag.putInt("kills", kills);
			tag.putInt("deaths", deaths);
			tag.putInt("streak", streak);
			tag.putInt("mob_kills", mobKills);
			if (lastKiller != null) tag.store("last_killer", UUIDUtil.CODEC, lastKiller);
			tag.putBoolean("inside", inside);
			tag.putBoolean("watching", watching);
			tag.putInt("lives", lives);
			tag.putBoolean("out", out);
			tag.putBoolean("zombie", zombie);
			tag.putInt("held", held);
			tag.putBoolean("paid", paid);
			tag.putInt("loadout", loadout);
			tag.putInt("points", points);
			if (!fee.isEmpty()) tag.store("fee", Presets.ITEMS, fee);
			return tag;
		}

		static Optional<Member> load(CompoundTag tag) {
			return tag.read("id", UUIDUtil.CODEC).map(id -> {
				Member member = new Member(id, tag.getStringOr("name", "?"));
				member.team = tag.getIntOr("team", -1);
				member.kills = tag.getIntOr("kills", 0);
				member.deaths = tag.getIntOr("deaths", 0);
				member.streak = tag.getIntOr("streak", 0);
				member.mobKills = tag.getIntOr("mob_kills", 0);
				member.lastKiller = tag.read("last_killer", UUIDUtil.CODEC).orElse(null);
				member.inside = tag.getBooleanOr("inside", false);
				member.watching = tag.getBooleanOr("watching", false);
				member.lives = tag.getIntOr("lives", -1);
				member.out = tag.getBooleanOr("out", false);
				member.zombie = tag.getBooleanOr("zombie", false);
				member.held = tag.getIntOr("held", 0);
				member.paid = tag.getBooleanOr("paid", false);
				member.loadout = tag.getIntOr("loadout", 0);
				member.points = tag.getIntOr("points", 0);
				member.fee = tag.read("fee", Presets.ITEMS).orElseGet(ItemList::new);
				return member;
			});
		}
	}

	public final String id;
	public final int number;
	public final Preset preset;
	public final String presetId;
	public final UUID host;
	public final String hostName;
	public final ResourceKey<Level> dimension;
	public final int plot;
	public final int minChunkX;
	public final int minChunkZ;
	public final int chunks;
	/** What the ground looks like where people stand: spawns, the lobby and the ceiling are set from it. */
	public final int surfaceY;
	public final int highestY;
	public final int wallTop;
	public final int lobbyY;

	public Phase phase = Phase.GENERATING;
	public final long createdAt;
	public long liveAt;
	public long endsAt;
	public long lobbyEndsAt;
	public final Map<UUID, Member> members = new LinkedHashMap<>();
	public final Set<UUID> invited = new HashSet<>();
	public boolean openToAll;
	public final List<GlobalPos> gates = new ArrayList<>();
	public final List<BlockPos> exits = new ArrayList<>();
	public boolean lobbyStanding;
	public boolean wallsDown;
	public boolean firstBlood;
	/** Winners not yet out, whose prize is handed over on the way home. */
	public final Set<UUID> winners = new HashSet<>();
	/** Which of the preset's prizes the win drew; -1 until a win has drawn one. */
	public int prize = -1;

	/** Each team's score toward the goal: flags captured. */
	public final Map<Integer, Integer> scores = new java.util.HashMap<>();
	/** Each team's flag's number: bumped whenever a new one is made, so the one it replaces no longer counts. */
	public final Map<Integer, Integer> flagNumbers = new java.util.HashMap<>();
	/** Each team's shared lives, for a goal with a pool. */
	public final Map<Integer, Integer> pools = new java.util.HashMap<>();
	/** Where each team's base stands: its flag chest, its cube, or its bank. */
	public final List<BlockPos> bases = new ArrayList<>();
	/** Where each team's building stands: the middle of its floor. */
	public final List<BlockPos> hearts = new ArrayList<>();
	/** Each team's chest, by where it stands: painted the team's colour, and kept from other teams where the preset says. */
	public final Map<BlockPos, Integer> teamChests = new java.util.HashMap<>();
	/** How many of the preset's game mode changes have happened, and been warned of. */
	public int modesChanged;
	public int modesWarned;
	/** The hill, or a race's finish: the beacon in the middle of its pad; null where there is none. */
	public @Nullable BlockPos marker;
	/** When the hill next moves; 0 for never. */
	public long markerMovesAt;
	/** What is owed to players not yet home, besides a prize: their share of the fees, or their own fee back. */
	public final Map<UUID, ItemList> owed = new java.util.HashMap<>();
	/** Teams whose base has fallen. */
	public final Set<Integer> fallen = new HashSet<>();
	/** Somebody has won; the arena closes at this time, after a moment to take it in. */
	public long closesAt;

	public int pinataIndex;
	public long nextPinataAt;
	public final List<BlockPos> pinatas = new ArrayList<>();
	public long pinataMovesAt;

	/** The wave on now or last sent, as an index into the preset's; -1 before the first. */
	public int waveIndex = -1;
	/** Waves sent so far, counting repeats: what the players are told this one is. */
	public int waveNumber;
	/** When the next wave comes; 0 while one is on. */
	public long nextWaveAt;
	public long waveStartedAt;
	/** No more waves: the last is done and the preset sends no more. */
	public boolean wavesOver;

	private transient @Nullable Footprint footprint;

	public Arena(String id, int number, String presetId, Preset preset, UUID host, String hostName, ResourceKey<Level> dimension,
			int plot, int minChunkX, int minChunkZ, int chunks, int surfaceY, int highestY, int wallTop, int lobbyY, long createdAt) {
		this.id = id;
		this.number = number;
		this.presetId = presetId;
		this.preset = preset;
		this.host = host;
		this.hostName = hostName;
		this.dimension = dimension;
		this.plot = plot;
		this.minChunkX = minChunkX;
		this.minChunkZ = minChunkZ;
		this.chunks = chunks;
		this.surfaceY = surfaceY;
		this.highestY = highestY;
		this.wallTop = wallTop;
		this.lobbyY = lobbyY;
		this.createdAt = createdAt;
	}

	/** "Skirmish #3", the name players see. */
	public String title() {
		return preset.name + " #" + number;
	}

	/** What the win drew, or null before a win or where there was nothing to draw. */
	public @Nullable ItemList prizeItems() {
		return prize >= 0 && prize < preset.prizes.size() ? preset.prizes.get(prize) : null;
	}

	public Footprint footprint() {
		if (footprint == null) footprint = new Footprint(id, dimension, minChunkX, minChunkZ, chunks, Terrains.of(this));
		return footprint;
	}

	public Terrain terrain() {
		return footprint().terrain();
	}

	public boolean open() {
		return phase == Phase.LOBBY || phase == Phase.LIVE;
	}

	public @Nullable Member member(UUID player) {
		return members.get(player);
	}

	public Member join(UUID player, String name) {
		Member member = members.computeIfAbsent(player, id -> new Member(id, name));
		member.name = name;
		return member;
	}

	public List<Member> inside() {
		return members.values().stream().filter(member -> member.inside).toList();
	}

	/** Everyone still in the fight: inside, not watching, not out. */
	public List<Member> fighting() {
		return members.values().stream().filter(member -> member.inside && !member.watching && !member.out).toList();
	}

	public int teamSize(int team) {
		int count = 0;
		for (Member member : members.values()) {
			if (member.inside && member.team == team) count++;
		}
		return count;
	}

	public boolean invitedOrOpen(UUID player) {
		return openToAll || invited.contains(player) || player.equals(host) || members.containsKey(player);
	}

	// --- Saved ---

	public static final Codec<Arena> CODEC = CompoundTag.CODEC.xmap(Arena::load, Arena::save);
	private static final Codec<Map<Integer, Integer>> SCORES = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(
		stored -> {
			Map<Integer, Integer> map = new java.util.HashMap<>();
			stored.forEach((key, value) -> map.put(Integer.parseInt(key), value));
			return map;
		},
		map -> {
			Map<String, Integer> stored = new java.util.HashMap<>();
			map.forEach((key, value) -> stored.put(String.valueOf(key), value));
			return stored;
		});

	private CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.putInt("number", number);
		tag.putString("preset_id", presetId);
		tag.store("preset", Presets.CODEC, preset);
		tag.store("host", UUIDUtil.CODEC, host);
		tag.putString("host_name", hostName);
		tag.store("dimension", Level.RESOURCE_KEY_CODEC, dimension);
		tag.putInt("plot", plot);
		tag.putInt("min_chunk_x", minChunkX);
		tag.putInt("min_chunk_z", minChunkZ);
		tag.putInt("chunks", chunks);
		tag.putInt("surface_y", surfaceY);
		tag.putInt("highest_y", highestY);
		tag.putInt("wall_top", wallTop);
		tag.putInt("lobby_y", lobbyY);
		tag.putString("phase", phase.name());
		tag.putLong("created_at", createdAt);
		tag.putLong("live_at", liveAt);
		tag.putLong("ends_at", endsAt);
		tag.putLong("lobby_ends_at", lobbyEndsAt);
		ListTag memberList = new ListTag();
		for (Member member : members.values()) memberList.add(member.save());
		tag.put("members", memberList);
		tag.store("invited", UUIDUtil.CODEC_SET, invited);
		tag.putBoolean("open_to_all", openToAll);
		tag.store("gates", GlobalPos.CODEC.listOf(), gates);
		tag.store("exits", BlockPos.CODEC.listOf(), exits);
		tag.putBoolean("lobby_standing", lobbyStanding);
		tag.putBoolean("walls_down", wallsDown);
		tag.putBoolean("first_blood", firstBlood);
		tag.store("winners", UUIDUtil.CODEC_SET, winners);
		tag.putInt("prize", prize);
		tag.putInt("pinata_index", pinataIndex);
		tag.putLong("next_pinata_at", nextPinataAt);
		tag.store("pinatas", BlockPos.CODEC.listOf(), pinatas);
		tag.putLong("pinata_moves_at", pinataMovesAt);
		tag.putInt("wave_index", waveIndex);
		tag.putInt("wave_number", waveNumber);
		tag.putLong("next_wave_at", nextWaveAt);
		tag.putLong("wave_started_at", waveStartedAt);
		tag.putBoolean("waves_over", wavesOver);
		tag.store("scores", SCORES, scores);
		tag.store("flag_numbers", SCORES, flagNumbers);
		tag.store("pools", SCORES, pools);
		tag.store("bases", BlockPos.CODEC.listOf(), bases);
		tag.store("hearts", BlockPos.CODEC.listOf(), hearts);
		ListTag chests = new ListTag();
		teamChests.forEach((pos, team) -> {
			CompoundTag chest = new CompoundTag();
			chest.store("pos", BlockPos.CODEC, pos);
			chest.putInt("team", team);
			chests.add(chest);
		});
		tag.put("team_chests", chests);
		if (marker != null) tag.store("marker", BlockPos.CODEC, marker);
		tag.putLong("marker_moves_at", markerMovesAt);
		tag.putInt("modes_changed", modesChanged);
		tag.putInt("modes_warned", modesWarned);
		ListTag owing = new ListTag();
		owed.forEach((player, items) -> {
			CompoundTag entry = new CompoundTag();
			entry.store("player", UUIDUtil.CODEC, player);
			entry.store("items", Presets.ITEMS, items);
			owing.add(entry);
		});
		tag.put("owed", owing);
		tag.store("fallen", Codec.INT.listOf(), List.copyOf(fallen));
		tag.putLong("closes_at", closesAt);
		return tag;
	}

	private static Arena load(CompoundTag tag) {
		Arena arena = new Arena(
			tag.getStringOr("id", "?"),
			tag.getIntOr("number", 0),
			tag.getStringOr("preset_id", ""),
			tag.read("preset", Presets.CODEC).orElseGet(Preset::new),
			tag.read("host", UUIDUtil.CODEC).orElse(new UUID(0, 0)),
			tag.getStringOr("host_name", "?"),
			tag.read("dimension", Level.RESOURCE_KEY_CODEC).orElse(Places.OVERWORLD),
			tag.getIntOr("plot", 0),
			tag.getIntOr("min_chunk_x", 0),
			tag.getIntOr("min_chunk_z", 0),
			tag.getIntOr("chunks", 4),
			tag.getIntOr("surface_y", 64),
			tag.getIntOr("highest_y", 64),
			tag.getIntOr("wall_top", 319),
			tag.getIntOr("lobby_y", 100),
			tag.getLongOr("created_at", 0));
		try {
			arena.phase = Phase.valueOf(tag.getStringOr("phase", Phase.ENDED.name()));
		} catch (IllegalArgumentException e) {
			arena.phase = Phase.ENDED;
		}
		arena.liveAt = tag.getLongOr("live_at", 0);
		arena.endsAt = tag.getLongOr("ends_at", 0);
		arena.lobbyEndsAt = tag.getLongOr("lobby_ends_at", 0);
		for (Tag entry : tag.getListOrEmpty("members")) {
			if (entry instanceof CompoundTag compound) Member.load(compound).ifPresent(member -> arena.members.put(member.id, member));
		}
		arena.invited.addAll(tag.read("invited", UUIDUtil.CODEC_SET).orElse(Set.of()));
		arena.openToAll = tag.getBooleanOr("open_to_all", false);
		arena.gates.addAll(tag.read("gates", GlobalPos.CODEC.listOf()).orElse(List.of()));
		arena.exits.addAll(tag.read("exits", BlockPos.CODEC.listOf()).orElse(List.of()));
		arena.lobbyStanding = tag.getBooleanOr("lobby_standing", false);
		arena.wallsDown = tag.getBooleanOr("walls_down", false);
		arena.firstBlood = tag.getBooleanOr("first_blood", false);
		arena.winners.addAll(tag.read("winners", UUIDUtil.CODEC_SET).orElse(Set.of()));
		arena.prize = tag.getIntOr("prize", -1);
		arena.pinataIndex = tag.getIntOr("pinata_index", 0);
		arena.nextPinataAt = tag.getLongOr("next_pinata_at", 0);
		arena.pinatas.addAll(tag.read("pinatas", BlockPos.CODEC.listOf()).orElse(List.of()));
		arena.pinataMovesAt = tag.getLongOr("pinata_moves_at", 0);
		arena.waveIndex = tag.getIntOr("wave_index", -1);
		arena.waveNumber = tag.getIntOr("wave_number", 0);
		arena.nextWaveAt = tag.getLongOr("next_wave_at", 0);
		arena.waveStartedAt = tag.getLongOr("wave_started_at", 0);
		arena.wavesOver = tag.getBooleanOr("waves_over", false);
		arena.scores.putAll(tag.read("scores", SCORES).orElse(Map.of()));
		arena.flagNumbers.putAll(tag.read("flag_numbers", SCORES).orElse(Map.of()));
		arena.pools.putAll(tag.read("pools", SCORES).orElse(Map.of()));
		arena.bases.addAll(tag.read("bases", BlockPos.CODEC.listOf()).orElse(List.of()));
		arena.hearts.addAll(tag.read("hearts", BlockPos.CODEC.listOf()).orElse(List.of()));
		for (Tag entry : tag.getListOrEmpty("team_chests")) {
			if (entry instanceof CompoundTag chest) chest.read("pos", BlockPos.CODEC).ifPresent(pos -> arena.teamChests.put(pos, chest.getIntOr("team", 0)));
		}
		arena.marker = tag.read("marker", BlockPos.CODEC).orElse(null);
		arena.markerMovesAt = tag.getLongOr("marker_moves_at", 0);
		arena.modesChanged = tag.getIntOr("modes_changed", 0);
		arena.modesWarned = tag.getIntOr("modes_warned", 0);
		for (Tag entry : tag.getListOrEmpty("owed")) {
			if (entry instanceof CompoundTag owing) {
				owing.read("player", UUIDUtil.CODEC).ifPresent(player ->
					arena.owed.put(player, owing.read("items", Presets.ITEMS).orElseGet(ItemList::new)));
			}
		}
		arena.fallen.addAll(tag.read("fallen", Codec.INT.listOf()).orElse(List.of()));
		arena.closesAt = tag.getLongOr("closes_at", 0);
		return arena;
	}
}
