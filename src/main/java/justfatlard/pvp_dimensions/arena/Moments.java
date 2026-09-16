package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * The moments of a fight worth a cheer: kills in quick succession, a long shot, a teammate
 * avenged, a carrier brought down, and the things that aren't kills at all: an assist, a capture,
 * the hill taken and held, a wave cleared, a big mob felled, a long life. Each is announced, with
 * a title for whoever earned it, and pays whatever the preset gives it.
 *
 * <p>What it takes to notice them lives here and nowhere else, and is forgotten with the arena.
 */
public final class Moments {
	private Moments() {}

	/** Kills this close together are a double, then a triple. */
	private static final long BURST_MILLIS = 10_000L;
	/** A hit this recent, before somebody else's kill, is an assist. */
	private static final long ASSIST_MILLIS = 10_000L;
	/** A teammate's killer, killed within this, is avenged. */
	private static final long AVENGE_MILLIS = 20_000L;
	private static final double LONG_SHOT_BLOCKS = 40;
	private static final long SURVIVOR_MILLIS = 5 * 60_000L;

	/** The biggest first: which kill a title goes to when one earns several. */
	private static final List<Preset.KillType> HEADLINE = List.of(
		Preset.KillType.TRIPLE_KILL, Preset.KillType.STREAK_10, Preset.KillType.DOUBLE_KILL, Preset.KillType.STREAK_5,
		Preset.KillType.CARRIER, Preset.KillType.AVENGER, Preset.KillType.LONG_SHOT, Preset.KillType.TRAP,
		Preset.KillType.SHUTDOWN, Preset.KillType.STREAK_3, Preset.KillType.REVENGE, Preset.KillType.FIRST_BLOOD);

	private static final Set<EntityType<?>> BIG = Set.of(EntityTypes.GIANT, EntityTypes.WARDEN, EntityTypes.RAVAGER,
		EntityTypes.EVOKER, EntityTypes.ELDER_GUARDIAN, EntityTypes.WITHER, EntityTypes.ENDER_DRAGON);

	private record Burst(long at, int count) {}
	private record Victim(UUID id, long at) {}
	private record Life(long since, int paid) {}

	/** Each player's last kill and how many came close before it. */
	private static final Map<UUID, Burst> bursts = new ConcurrentHashMap<>();
	/** Who each player last killed, and when: what an avenger avenges. */
	private static final Map<UUID, Victim> lastVictim = new ConcurrentHashMap<>();
	/** Everyone each player was hurt by lately, and when. */
	private static final Map<UUID, Map<UUID, Long>> hurtBy = new ConcurrentHashMap<>();
	/** Players who went down with a flag on them, as the flag went home. */
	private static final Map<UUID, Long> carriers = new ConcurrentHashMap<>();
	/** Who last carried each team's flag, by arena and flag. */
	private static final Map<String, Map<Integer, UUID>> flagCarriers = new ConcurrentHashMap<>();
	/** How long each player has been alive, and how many survivor moments that has paid. */
	private static final Map<UUID, Life> lives = new ConcurrentHashMap<>();
	/** How many have fallen in each arena, and how many had when its wave was sent. */
	private static final Map<String, Integer> fallen = new ConcurrentHashMap<>();
	private static final Map<String, Integer> fallenAtWave = new ConcurrentHashMap<>();
	/** The side last to take each arena's hill, so stepping off and on again takes nothing. */
	private static final Map<String, String> hillTakenBy = new ConcurrentHashMap<>();

	// ---- kills ----

	/** A player hurt by another: remembered, for an assist if somebody else finishes them. */
	public static void hurt(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer victim) || !(source.getEntity() instanceof ServerPlayer by) || by == victim) return;
		if (!Places.isArena(victim.level().dimension())) return;
		hurtBy.computeIfAbsent(victim.getUUID(), id -> new ConcurrentHashMap<>()).put(by.getUUID(), System.currentTimeMillis());
	}

	/** A flag carrier about to go down: remembered a moment, for whoever gets the kill. */
	static void carrierFalling(ServerPlayer player) {
		carriers.put(player.getUUID(), System.currentTimeMillis());
	}

	/** A player on this team's side carrying another's flag: the one a capture is credited to. */
	static void carrying(Arena arena, int flag, Arena.Member member) {
		flagCarriers.computeIfAbsent(arena.id, id -> new ConcurrentHashMap<>()).put(flag, member.id);
	}

	/** Whoever last carried this flag, if they are on the capturing side. */
	static Arena.@Nullable Member capturedBy(Arena arena, int flag, int team) {
		Map<Integer, UUID> byFlag = flagCarriers.get(arena.id);
		UUID id = byFlag == null ? null : byFlag.remove(flag);
		Arena.Member member = id == null ? null : arena.member(id);
		return member != null && member.team == team ? member : null;
	}

	/** Somebody in the fight fell, by any hand or none: the end of their life and of a flawless wave. */
	static void fell(Arena arena, Arena.Member member) {
		fallen.merge(arena.id, 1, Integer::sum);
		lives.remove(member.id);
	}

	/**
	 * The kill moments this kill earns besides the old ones: a burst, a long shot, a trap, an avenged
	 * teammate, a carrier brought down.
	 */
	static List<Preset.KillType> kill(Arena arena, ServerPlayer killer, Arena.Member won, ServerPlayer victim, Arena.Member lost,
			DamageSource source, boolean trap) {
		long now = System.currentTimeMillis();
		List<Preset.KillType> earned = new ArrayList<>();
		Burst last = bursts.get(killer.getUUID());
		int count = last != null && now - last.at() <= BURST_MILLIS ? last.count() + 1 : 1;
		bursts.put(killer.getUUID(), new Burst(now, count));
		if (count == 2) earned.add(Preset.KillType.DOUBLE_KILL);
		else if (count >= 3) earned.add(Preset.KillType.TRIPLE_KILL);

		if (trap) earned.add(Preset.KillType.TRAP);
		else if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile
				&& killer.distanceTo(victim) >= LONG_SHOT_BLOCKS) earned.add(Preset.KillType.LONG_SHOT);

		Victim avenged = lastVictim.remove(victim.getUUID());
		if (avenged != null && arena.preset.teamsOn() && now - avenged.at() <= AVENGE_MILLIS && !avenged.id().equals(killer.getUUID())) {
			Arena.Member fallenMate = arena.member(avenged.id());
			if (fallenMate != null && fallenMate.team == won.team) earned.add(Preset.KillType.AVENGER);
		}
		lastVictim.put(killer.getUUID(), new Victim(victim.getUUID(), now));

		Long carrying = carriers.remove(victim.getUUID());
		if (carrying != null && now - carrying <= 1_000L) earned.add(Preset.KillType.CARRIER);
		return earned;
	}

	/** Everyone besides the killer who hurt the dead lately and wasn't on their side: each paid an assist. */
	static List<ServerPlayer> assists(MinecraftServer server, Arena arena, ServerPlayer victim, Arena.Member lost, @Nullable ServerPlayer killer) {
		Map<UUID, Long> hits = hurtBy.remove(victim.getUUID());
		carriers.remove(victim.getUUID());
		List<ServerPlayer> helped = new ArrayList<>();
		if (hits == null || killer == null) return helped;
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Long> hit : hits.entrySet()) {
			if (hit.getKey().equals(killer.getUUID()) || now - hit.getValue() > ASSIST_MILLIS) continue;
			Arena.Member helper = arena.member(hit.getKey());
			if (helper == null || !helper.inside || helper.watching || helper.zombie) continue;
			if (arena.preset.teamsOn() && helper.team == lost.team) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(helper.id);
			if (player == null || player.level() != victim.level()) continue;
			helped.add(player);
			pay(arena, player, Preset.Feat.ASSIST);
			Say.bar(player, "Assist on " + lost.name);
			ding(player, false);
		}
		return helped;
	}

	/** The title for a kill's biggest moment, where it has one worth a title. */
	static void headline(ServerPlayer killer, List<Preset.KillType> earned, Arena.Member lost, ServerPlayer victim) {
		for (Preset.KillType type : HEADLINE) {
			if (!earned.contains(type)) continue;
			String under = switch (type) {
				case LONG_SHOT -> Math.round(killer.distanceTo(victim)) + " blocks, on " + lost.name;
				case SHUTDOWN -> lost.name + "'s streak is over";
				default -> "on " + lost.name;
			};
			title(killer, Fields.killTypeLabel(type) + "!", under);
			ding(killer, type == Preset.KillType.TRIPLE_KILL || type == Preset.KillType.STREAK_10 || type == Preset.KillType.STREAK_5);
			return;
		}
	}

	// ---- feats ----

	/** A big mob down, where one of the fight's players brought it down. */
	static void mobKilled(MinecraftServer server, Arena arena, ServerPlayer killer, LivingEntity mob) {
		if (!BIG.contains(mob.getType())) return;
		String name = mob.getType().getDescription().getString();
		pay(arena, killer, Preset.Feat.BIG_GAME);
		title(killer, "Big game!", "You brought down " + article(name));
		ding(killer, true);
		Arenas.tellInside(server, arena, killer.getGameProfile().name() + " brought down " + article(name) + "!");
	}

	/** A flag home in a chest of the capturing side: the carrier's moment. */
	static void captured(Arena arena, ServerPlayer carrier) {
		pay(arena, carrier, Preset.Feat.CAPTURE);
		title(carrier, "Flag captured!", "Brought home by you");
		ding(carrier, true);
	}

	/** The hill held by a side, a second on: taken, if it is a new side, and paid each full minute. */
	static void hill(MinecraftServer server, Arena arena, String side, List<Arena.Member> on, int seconds) {
		boolean taken = !side.equals(hillTakenBy.put(arena.id, side));
		for (Arena.Member member : on) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			if (taken) {
				pay(arena, player, Preset.Feat.HILL_TAKEN);
				title(player, "Hill taken!", "Hold it");
				ding(player, false);
			}
			if (seconds > 0 && seconds % 60 == 0) {
				pay(arena, player, Preset.Feat.HILL_MINUTE);
				Say.bar(player, (seconds / 60 == 1 ? "A minute" : seconds / 60 + " minutes") + " on the hill");
				ding(player, false);
			}
		}
	}

	/** A hill moved somewhere new: whoever gets there first has taken it, whoever held the old. */
	static void hillMoved(Arena arena) {
		hillTakenBy.remove(arena.id);
	}

	static void waveSent(Arena arena) {
		fallenAtWave.put(arena.id, fallen.getOrDefault(arena.id, 0));
	}

	/** A wave cleared: paid to everyone still fighting, and on top, if nobody fell, flawless. */
	static void waveCleared(MinecraftServer server, Arena arena, int number) {
		boolean flawless = fallen.getOrDefault(arena.id, 0).equals(fallenAtWave.getOrDefault(arena.id, -1));
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || !player.isAlive()) continue;
			pay(arena, player, Preset.Feat.WAVE_CLEARED);
			if (flawless) {
				pay(arena, player, Preset.Feat.FLAWLESS_WAVE);
				title(player, "Flawless!", "Nobody fell in wave " + number);
				ding(player, true);
			}
		}
		if (flawless) Arenas.tellInside(server, arena, "A flawless wave: nobody fell");
	}

	/** Once a second in a live arena: every five minutes somebody has stayed alive, their moment. */
	static void tick(MinecraftServer server, Arena arena, long now) {
		for (Arena.Member member : arena.fighting()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null || !player.isAlive() || player.isSpectator()) continue;
			Life life = lives.computeIfAbsent(member.id, id -> new Life(now, 0));
			int due = (int) ((now - life.since()) / SURVIVOR_MILLIS);
			if (due <= life.paid()) continue;
			lives.put(member.id, new Life(life.since(), due));
			pay(arena, player, Preset.Feat.SURVIVOR);
			String minutes = due * SURVIVOR_MILLIS / 60_000L + " minutes";
			Say.bar(player, "Survivor: " + minutes + " without dying");
			ding(player, false);
			Arenas.tellInside(server, arena, member.name + " has survived " + minutes);
		}
	}

	// ---- saying it ----

	private static void pay(Arena arena, ServerPlayer player, Preset.Feat feat) {
		ItemList reward = arena.preset.featRewards.get(feat);
		if (reward == null || reward.isEmpty()) return;
		reward.give(player);
		player.containerMenu.broadcastChanges();
	}

	private static void title(ServerPlayer player, String words, String under) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(4, 30, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(under).withStyle(ChatFormatting.YELLOW)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(words).withStyle(ChatFormatting.GOLD)));
	}

	/** A sound for the one who earned it: a chime, or a fanfare for the big ones. */
	private static void ding(ServerPlayer player, boolean big) {
		SoundEvent sound = big ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP;
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
			net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.PLAYERS,
			player.getX(), player.getY(), player.getZ(), big ? 0.6F : 0.5F, big ? 1F : 1.6F, player.getRandom().nextLong()));
	}

	private static String article(String name) {
		return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name;
	}

	public static void forget(@Nullable UUID player) {
		if (player == null) return;
		bursts.remove(player);
		lastVictim.remove(player);
		hurtBy.remove(player);
		carriers.remove(player);
		lives.remove(player);
	}

	public static void forget(Arena arena) {
		for (UUID id : arena.members.keySet()) forget(id);
		flagCarriers.remove(arena.id);
		fallen.remove(arena.id);
		fallenAtWave.remove(arena.id);
		hillTakenBy.remove(arena.id);
	}
}
