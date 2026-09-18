package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fighting: who may hurt whom, who is credited with a kill and what it earns them, and where a
 * death comes back.
 */
public final class Combat {
	private Combat() {}

	/**
	 * Whether this player may hurt other players, or null to leave it to the server's own rule.
	 * Inside an arena it is the arena's rule and nothing else: never in the waiting room, always in
	 * the fight, whatever the server's pvp setting says for the rest of the world.
	 */
	public static @Nullable Boolean pvpAllowed(ServerPlayer player) {
		if (!Places.isArena(player.level().dimension())) return null;
		Arena arena = Arenas.of(player);
		if (arena == null) return null;
		Arena.Member member = arena.member(player.getUUID());
		if (member == null || member.watching) return false;
		// The odd one out is played with blades down until the room names somebody; then they are
		// out for everybody at once, which is what makes a scramble out of an execution.
		if (Spies.open(arena)) return arena.phase == Arena.Phase.LIVE;
		return arena.phase == Arena.Phase.LIVE && arena.preset.pvp;
	}

	/** Whether one player may hurt another where the horde decides it; null to leave it to the rule above. */
	public static @Nullable Boolean canHarm(ServerPlayer player, net.minecraft.world.entity.player.Player other) {
		if (!Places.isArena(player.level().dimension())) return null;
		Arena arena = Arenas.of(player);
		if (arena == null || arena.phase != Arena.Phase.LIVE || !arena.preset.hordeOn()) return null;
		return Horde.mayHarm(player, other);
	}

	/** Nothing hurts anyone in the waiting room: it has glass walls and a long way down. */
	public static boolean allowDamage(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player) || !Places.isArena(player.level().dimension())) return true;
		Arena arena = Arenas.of(player);
		return arena == null || arena.phase != Arena.Phase.LOBBY;
	}

	/** Where a death in an arena that keeps its dead comes back; null to let the game decide. */
	public static @Nullable TeleportTransition respawn(ServerPlayer player, TeleportTransition.PostTeleportTransition after) {
		Visit visit = Visit.of(player);
		if (visit != null) {
			Arena any = Arenas.get(player.level().getServer(), visit.arena());
			if (any == null || any.phase == Arena.Phase.ENDED) return Travel.homeTrip(player.level().getServer(), visit.home());
		}
		Arena arena = Arenas.of(player);
		if (arena == null || !player.level().dimension().equals(arena.dimension)) return null;
		boolean inLobby = arena.phase == Arena.Phase.LOBBY;
		if (!inLobby && !(arena.phase == Arena.Phase.LIVE && arena.preset.respawnsInside())) return null;
		ServerLevel level = player.level();
		Arena.Member member = arena.member(player.getUUID());
		boolean zombie = member != null && member.out && arena.preset.hordeOn();
		Vec3 spot = inLobby && arena.lobbyStanding ? Lobby.arrival(arena)
			: zombie ? Spawns.random(level, arena) : Spawns.forPlayer(level, arena, member != null ? member.team : -1);
		return new TeleportTransition(level, spot, Vec3.ZERO, player.getYRot(), 0, Set.of(), after);
	}

	/**
	 * Back from the dead. Inside, the respawn kit; outside, because the arena does not keep its
	 * dead, the visit is over and their own pack comes back.
	 */
	public static void respawned(ServerPlayer player) {
		Visit visit = Visit.of(player);
		if (visit == null) return;
		Arena arena = Arenas.get(player.level().getServer(), visit.arena());
		if (arena != null && arena.phase != Arena.Phase.ENDED && player.level().dimension().equals(arena.dimension)) {
			Arena.Member member = arena.member(player.getUUID());
			if (member != null && member.out) Goals.respawnedOut(player, arena, member);
			else if (arena.phase == Arena.Phase.LIVE) {
				Travel.equip(player, arena, false);
				if (arena.preset.loadoutRepick) Loadouts.offer(player, arena);
			}
			Borders.send(player, arena);
			Fog.show(player, arena);
			return;
		}
		boolean ended = arena == null || arena.phase == Arena.Phase.ENDED;
		Travel.leave(player, arena, ended ? Travel.Why.ENDED : Travel.Why.DEATH);
		if (arena != null) Say.to(player, (ended ? arena.title() + " is over" : "Out of " + arena.title()) + ". What you had before is back with you");
	}

	/** A mob died: if a player in the fight took one of the arena's own down, it pays and counts toward a hunt. */
	public static void mobDied(LivingEntity mob) {
		if (!(mob.level() instanceof ServerLevel level) || !Places.isArena(level.dimension())) return;
		if (!(mob.getKillCredit() instanceof ServerPlayer killer)) return;
		Arena arena = Arenas.of(killer);
		if (arena == null || arena.phase != Arena.Phase.LIVE || !killer.level().dimension().equals(arena.dimension)) return;
		Arena.Member member = arena.member(killer.getUUID());
		if (member == null || member.out) return;
		if (mob.entityTags().contains(Mobs.OURS)) arena.preset.mobKillReward.give(killer);
		Moments.mobKilled(level.getServer(), arena, killer, mob);
		Goals.mobKilled(level.getServer(), arena, member, mob);
	}

	/** A player died: counted, and whoever killed them rewarded, with a word to everyone inside. */
	public static void died(ServerPlayer victim, DamageSource source) {
		Arena arena = Arenas.of(victim);
		if (arena == null || arena.phase != Arena.Phase.LIVE || !victim.level().dimension().equals(arena.dimension)) return;
		MinecraftServer server = victim.level().getServer();
		Arena.Member lost = arena.member(victim.getUUID());
		if (lost == null) return;
		LivingEntity by = victim.getKillCredit();
		if (lost.zombie) {
			if (by instanceof ServerPlayer hunter) Arenas.tellInside(server, arena, hunter.getGameProfile().name() + " put down " + lost.name);
			return;
		}
		Moments.fell(arena, lost);
		if (Horde.is(by)) {
			Moments.assists(server, arena, victim, lost, null);
			Goals.died(arena, lost);
			Arenas.tellInside(server, arena, lost.name + " was caught by the horde");
			Arenas.vault(server).touch();
			return;
		}
		int streakEnded = lost.streak;
		lost.deaths++;
		lost.streak = 0;
		Goals.died(arena, lost);

		LivingEntity credit = victim.getKillCredit();
		// Nobody struck the blow: a trap somebody laid may have.
		ServerPlayer trapper = !(credit instanceof ServerPlayer) || credit == victim ? Traps.trapper(server, arena, victim) : null;
		if (trapper != null) credit = trapper;
		if (!(credit instanceof ServerPlayer killer) || killer == victim) {
			Moments.assists(server, arena, victim, lost, null);
			if (Spies.open(arena)) Spies.died(server, arena, victim, null);
			Arenas.vault(server).touch();
			return;
		}
		Arena.Member won = arena.member(killer.getUUID());
		if (won == null || !won.inside || arena != Arenas.of(killer)) {
			Moments.assists(server, arena, victim, lost, null);
			return;
		}

		won.kills++;
		won.streak++;
		List<Preset.KillType> earned = new ArrayList<>();
		if (!arena.firstBlood) {
			arena.firstBlood = true;
			earned.add(Preset.KillType.FIRST_BLOOD);
		}
		if (trapper != null) {
			// A trap is its own kind of kill: no bonus for up close, from range or a knock.
		} else if (source.getDirectEntity() instanceof Projectile) earned.add(Preset.KillType.RANGED);
		else if (source.getEntity() == killer) earned.add(Preset.KillType.MELEE);
		else earned.add(Preset.KillType.KNOCKOUT);
		if (killer.getUUID().equals(won.lastKiller)) earned.add(Preset.KillType.REVENGE);
		if (streakEnded >= 3) earned.add(Preset.KillType.SHUTDOWN);
		if (won.streak == 3) earned.add(Preset.KillType.STREAK_3);
		if (won.streak == 5) earned.add(Preset.KillType.STREAK_5);
		if (won.streak == 10) earned.add(Preset.KillType.STREAK_10);
		earned.addAll(Moments.kill(arena, killer, won, victim, lost, source, trapper != null));
		lost.lastKiller = killer.getUUID();
		List<ServerPlayer> helped = Moments.assists(server, arena, victim, lost, killer);

		arena.preset.killReward.give(killer);
		List<String> bonuses = new ArrayList<>();
		for (Preset.KillType type : earned) {
			ItemList bonus = arena.preset.killBonus.get(type);
			if (bonus != null && !bonus.isEmpty()) bonus.give(killer);
			if (type != Preset.KillType.MELEE && type != Preset.KillType.RANGED && type != Preset.KillType.TRAP) bonuses.add(Fields.killTypeLabel(type).toLowerCase());
		}
		killer.containerMenu.broadcastChanges();
		Moments.headline(killer, earned, lost, victim);

		if (Spies.open(arena)) {
			// Who swung is the one thing the room does not get told; Spies says the rest.
			Spies.died(server, arena, victim, killer);
			Arenas.vault(server).touch();
			return;
		}
		String line = killer.getGameProfile().name() + (trapper != null ? "'s trap got " : " took out ") + victim.getGameProfile().name()
			+ (bonuses.isEmpty() ? "" : " (" + String.join(", ", bonuses) + ")")
			+ (helped.isEmpty() ? "" : ", with help from " + String.join(" and ", helped.stream().map(p -> p.getGameProfile().name()).toList()));
		Arenas.tellInside(server, arena, line);
		Say.bar(killer, won.kills + (won.kills == 1 ? " kill" : " kills") + (won.streak > 1 ? ", " + won.streak + " in a row" : ""));
		Arenas.vault(server).touch();
		Goals.killed(server, arena, won);
	}
}
