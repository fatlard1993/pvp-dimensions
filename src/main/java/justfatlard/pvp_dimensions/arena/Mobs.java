package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.preset.MobKinds;
import justfatlard.pvp_dimensions.mixin.MobAccessor;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.world.Footprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An arena's hostile mobs are its own. The game's spawning is shut out of arenas altogether, and
 * each arena spawns what its preset asks for instead: at night only, or day and night, as many as
 * its rarity allows for the players inside, of the kinds left switched on, somewhere near a
 * player but not in their face.
 *
 * <p>Day and night means undead that would burn in daylight are spawned in a leather cap, which
 * they do not drop. Anything hostile the arena did not spawn itself, by darkness or by egg, is
 * sent away as it loads.
 *
 * <p>Each mob comes as exactly the kind asked for: a zombie grown and on foot, a baby zombie a
 * baby, a jockey on its mount, none of the game's own chance of a baby or a rider on top. A mob
 * that only fights back comes in angry at a player, and is kept that way; a giant, which the game
 * gives no mind at all, is given one that walks and hits.
 */
public final class Mobs {
	private Mobs() {}

	/** The mark on a mob the arena spawned, which is what lets it stay. */
	public static final String OURS = "pvp-dimensions.mob";
	/** The mark on the mount under one of the arena's own: let stay, but not a foe to clear. */
	private static final String MOUNT = "pvp-dimensions.mount";
	/** The mark naming the kind a mob was spawned as, for a hunt after a baby or a rider. */
	public static final String KIND = "pvp-dimensions.kind=";

	private static final RandomSource RANDOM = RandomSource.create();
	private static final int NEAREST = 12;
	private static final int FARTHEST = 28;

	private record Pace(long everyMillis, int perPlayer) {}

	private static Pace pace(Preset.Rarity rarity) {
		return switch (rarity) {
			case RARE -> new Pace(20_000, 2);
			case NORMAL -> new Pace(10_000, 4);
			case COMMON -> new Pace(6_000, 7);
			case SWARM -> new Pace(3_000, 12);
		};
	}

	/**
	 * Whether a mob loading in an arena dimension may stay there. One of the arena's own may, and so
	 * may what one of them brings with it, a slime's halves, an evoker's vexes, a spider's rider,
	 * which arrives right beside it and is marked as the arena's too.
	 */
	public static boolean allowed(Entity entity, ServerLevel level) {
		if (entity.getType().getCategory() != MobCategory.MONSTER) return true;
		if (entity.entityTags().contains(OURS) || entity.entityTags().contains(MOUNT)) return true;
		AABB beside = entity.getBoundingBox().inflate(OFFSPRING_REACH);
		if (level.getEntitiesOfClass(Mob.class, beside, mob -> mob != entity && mob.entityTags().contains(OURS)).isEmpty()) return false;
		entity.addTag(OURS);
		return true;
	}

	/** How far from one of the arena's own mobs what it brings with it turns up. */
	private static final double OFFSPRING_REACH = 4;

	public static void tick(MinecraftServer server, ServerLevel level, Arena arena, long now, java.util.Map<String, Long> last) {
		Preset preset = arena.preset;
		Preset.MobTime when = preset.activeMobs();
		if (when == Preset.MobTime.OFF) return;
		if (when == Preset.MobTime.NIGHT && !level.isDarkOutside()) return;
		Pace pace = pace(preset.mobRarity);
		long previous = last.getOrDefault(arena.id, 0L);
		if (now - previous < pace.everyMillis()) return;
		last.put(arena.id, now);

		List<ServerPlayer> players = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null && player.level() == level && !player.isSpectator()) players.add(player);
		}
		if (players.isEmpty()) return;

		// Each kind as many times over as its level: a common kind turns up six times as often as a rare one.
		List<String> kinds = new ArrayList<>();
		for (String id : MobKinds.of(preset.world)) {
			int weight = switch (preset.mobLevel(id)) {
				case OFF -> 0;
				case RARE -> 1;
				case NORMAL -> 3;
				case COMMON -> 6;
			};
			for (int i = 0; i < weight; i++) kinds.add(id);
		}
		// The kind a hunt is for spawns whatever its level says, and makes up half of what spawns.
		String hunted = preset.activeGoal() == Preset.Goal.MOBS && MobKinds.of(preset.world).contains(preset.mobGoalKind) ? preset.mobGoalKind : null;
		// A hunt for any mob with every kind switched off hunts all of them rather than nothing.
		if (kinds.isEmpty() && hunted == null && preset.activeGoal() == Preset.Goal.MOBS) kinds.addAll(MobKinds.of(preset.world));
		if (kinds.isEmpty() && hunted == null) return;

		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		int ours = level.getEntitiesOfClass(Mob.class, area, mob -> mob.entityTags().contains(OURS)).size();
		int room = Math.min(60, pace.perPlayer() * players.size()) - ours;
		for (int spawned = 0; spawned < Math.min(room, 1 + players.size() / 2); spawned++) {
			ServerPlayer near = players.get(RANDOM.nextInt(players.size()));
			String kind = hunted != null && (kinds.isEmpty() || RANDOM.nextBoolean()) ? hunted : kinds.get(RANDOM.nextInt(kinds.size()));
			spawnNear(level, arena, near, kind, players, false);
		}
	}

	/** One of the kind somewhere near a player but not on top of anyone; false where no spot was found. */
	static boolean spawnNear(ServerLevel level, Arena arena, ServerPlayer near, String kind, List<ServerPlayer> players, boolean stays) {
		for (int attempt = 0; attempt < 8; attempt++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double distance = NEAREST + RANDOM.nextDouble() * (FARTHEST - NEAREST);
			int x = (int) Math.floor(near.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(near.getZ() + Math.sin(angle) * distance);
			Vec3 spot = Spawns.standAt(level, arena, x, z);
			if (spot == null) continue;
			boolean crowded = false;
			for (ServerPlayer player : players) {
				if (player.position().distanceToSqr(spot) < NEAREST * NEAREST) crowded = true;
			}
			if (crowded) continue;
			spawnAt(level, kind, spot, stays, near);
			return true;
		}
		return false;
	}

	/**
	 * One of the arena's own, of this kind, here, on its mount if it has one, and angry at
	 * {@code foe} if it only fights back. One that {@code stays} never wanders off and despawns,
	 * which a wave's must not, or the wave could never be cleared.
	 */
	static void spawnAt(ServerLevel level, String kind, Vec3 spot, boolean stays, @Nullable ServerPlayer foe) {
		MobKinds.Variant variant = MobKinds.variant(kind);
		Entity rider = create(level, variant != null ? variant.rider() : kind, spot);
		if (rider == null) return;
		rider.addTag(OURS);
		rider.addTag(KIND + kind);
		if (rider instanceof Mob mob) {
			prepare(level, mob, spot, variant != null && variant.baby(), stays);
			if (level.isBrightOutside() && mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
				mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
				mob.setDropChance(EquipmentSlot.HEAD, 0);
			}
		}
		Entity root = rider;
		if (variant != null && variant.mount() != null) {
			Entity mount = create(level, variant.mount(), spot);
			if (mount != null) {
				if (mount instanceof Mob mob) prepare(level, mob, spot, false, stays);
				if (mount instanceof AbstractHorse horse) horse.setTamed(true);
				// A hostile mount, a spider, is a foe in its own right; a chicken or a horse isn't.
				mount.addTag(mount.getType().getCategory() == MobCategory.MONSTER ? OURS : MOUNT);
				rider.startRiding(mount, true, false);
				root = mount;
			}
		}
		level.addFreshEntityWithPassengers(root);
		if (foe != null) anger(rider, foe);
	}

	private static @Nullable Entity create(ServerLevel level, String id, Vec3 spot) {
		Identifier parsed = Identifier.tryParse(id);
		EntityType<?> type = parsed == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).orElse(null);
		Entity entity = type == null ? null : type.create(level, EntitySpawnReason.NATURAL);
		if (entity != null) entity.snapTo(spot.x, spot.y, spot.z, RANDOM.nextFloat() * 360, 0);
		return entity;
	}

	/**
	 * Readied as the game readies a natural spawn, then made exactly the kind asked for: grown or a
	 * baby, and alone, with none of the game's chance of a chicken under it or a skeleton on top.
	 */
	private static void prepare(ServerLevel level, Mob mob, Vec3 spot, boolean baby, boolean stays) {
		SpawnGroupData data = mob instanceof Zombie ? new Zombie.ZombieGroupData(baby, false) : null;
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot)), EntitySpawnReason.NATURAL, data);
		mob.setBaby(baby);
		Entity vehicle = mob.getVehicle();
		if (vehicle != null) {
			mob.stopRiding();
			vehicle.discard();
		}
		for (Entity passenger : List.copyOf(mob.getPassengers())) {
			passenger.stopRiding();
			passenger.discard();
		}
		if (stays) mob.setPersistenceRequired();
	}

	/** Angry at a player, for a mob that would otherwise only fight back. Endermen keep their own rules. */
	static void anger(Entity entity, ServerPlayer foe) {
		if (!(entity instanceof NeutralMob neutral) || entity instanceof Enderman || !(entity instanceof Mob mob)) return;
		neutral.setPersistentAngerTarget(EntityReference.of(foe));
		neutral.startPersistentAngerTimer();
		mob.setTarget(foe);
	}

	/** Whether both are on the arena's side, mobs, mounts or the horde: the same side, which never fights itself. */
	public static boolean sameSide(Entity one, Entity other) {
		return arenaSide(one) && arenaSide(other);
	}

	private static boolean arenaSide(Entity entity) {
		var tags = entity.entityTags();
		return tags.contains(OURS) || tags.contains(MOUNT) || tags.contains(Horde.TAG);
	}

	/**
	 * Every so often, the arena's mobs are set on the players again: one that has picked another
	 * of the arena's own to fight by a way round {@link Mob#setTarget}, as piglins and hoglins do,
	 * lets it be; and one that only fights back is made angry again at the nearest player fighting,
	 * since anger in the game runs out after half a minute.
	 */
	public static void turnOnPlayers(MinecraftServer server, ServerLevel level, Arena arena) {
		List<ServerPlayer> players = new ArrayList<>();
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player != null && player.level() == level && !player.isSpectator() && player.isAlive()) players.add(player);
		}
		if (players.isEmpty()) return;
		Footprint footprint = arena.footprint();
		AABB area = new AABB(footprint.minX(), level.getMinY(), footprint.minZ(), footprint.maxX(), level.getMaxY(), footprint.maxZ());
		for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob -> mob.entityTags().contains(OURS))) {
			if (mob.getTarget() != null && sameSide(mob, mob.getTarget())) {
				mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
				mob.setTarget(null);
			}
			if (!(mob instanceof NeutralMob neutral) || mob instanceof Enderman) continue;
			if (neutral.isAngry() && mob.getTarget() != null && mob.getTarget().isAlive()) continue;
			ServerPlayer nearest = players.get(0);
			for (ServerPlayer player : players) {
				if (player.distanceToSqr(mob) < nearest.distanceToSqr(mob)) nearest = player;
			}
			anger(mob, nearest);
		}
	}

	/**
	 * One of the arena's own loaded, freshly spawned or back from disk: a giant is given a mind,
	 * which the game leaves it without and which doesn't keep across a save.
	 */
	public static void loaded(Entity entity) {
		if (!(entity instanceof Giant giant) || !giant.entityTags().contains(OURS)) return;
		MobAccessor mind = (MobAccessor) giant;
		mind.pvpDimensions$goals().addGoal(1, new GiantSmash(giant));
		mind.pvpDimensions$goals().addGoal(2, new WaterAvoidingRandomStrollGoal(giant, 0.5));
		mind.pvpDimensions$goals().addGoal(3, new LookAtPlayerGoal(giant, Player.class, 16));
		mind.pvpDimensions$targets().addGoal(1, new NearestAttackableTargetGoal<>(giant, Player.class, true));
	}
}
