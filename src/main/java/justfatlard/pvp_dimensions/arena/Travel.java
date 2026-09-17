package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import justfatlard.pvp_dimensions.Access;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.ItemList;
import justfatlard.pvp_dimensions.preset.Kit;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.ui.ItemSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Going in and coming out, with everything that has to happen on the way: what a player carries
 * put aside or let through, a team and a game mode given and taken back, and home remembered.
 *
 * <p>Every way in and out comes through here: invitations, lit frames, exit portals, the leave
 * command, a death with nowhere to respawn inside, the arena ending, and a player logging back in
 * to an arena that finished while they were away. Each builds the trip and hands it to whatever
 * moves the player, since a portal does its own moving.
 */
public final class Travel {
	private Travel() {}

	public enum Why { COMMAND, PORTAL, ENDED, DEATH, LOGIN, MOVED }

	// --- In ---

	/** From an invitation or a command: checked, then taken straight in. */
	public static void join(ServerPlayer player, Arena arena) {
		if (arena.phase == Arena.Phase.ENDED) {
			Say.to(player, arena.title() + " is over");
			return;
		}
		if (!arena.invitedOrOpen(player.getUUID()) && !Access.admin(player)) {
			Say.to(player, arena.title() + " is by invitation");
			return;
		}
		if (arena.phase == Arena.Phase.GENERATING) {
			Arenas.queue(arena, player);
			Say.to(player, arena.title() + " is still being made; you'll be taken in when it's ready");
			return;
		}
		TeleportTransition trip = enter(player, arena, Visit.Home.of(player));
		if (trip != null) player.teleport(trip);
	}

	/**
	 * Everything on the way in, short of moving: the visit written, the pack put aside if the
	 * arena keeps inventories apart, a team and game mode given. Null, with the reason said, when
	 * the player may not go in.
	 */
	public static @Nullable TeleportTransition enter(ServerPlayer player, Arena arena, Visit.Home home) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = Arenas.level(server, arena);
		if (level == null || !arena.open()) return null;
		Visit existing = Visit.of(player);
		if (existing != null) {
			if (!existing.arena().equals(arena.id)) Say.to(player, "You're already in an arena; /pvp leave first");
			return null;
		}
		if (ItemSessions.editing(player)) {
			Say.to(player, "Finish editing items first: /pvp done");
			return null;
		}
		if (player.isDeadOrDying()) return null;

		Preset preset = arena.preset;
		boolean admin = Access.admin(player);
		Arena.Member before = arena.member(player.getUUID());
		boolean late = arena.phase == Arena.Phase.LIVE && before == null;
		if (late && preset.lateJoin == Preset.LateJoin.DENY && !admin) {
			Say.to(player, arena.title() + " has started and takes no late arrivals");
			return null;
		}
		boolean watching = late && preset.lateJoin == Preset.LateJoin.SPECTATE && !admin;
		boolean pays = !watching && !preset.entryFee.isEmpty() && (before == null || !before.paid);
		if (pays) {
			String shortOf = Fees.shortOf(player, preset.entryFee);
			if (shortOf != null) {
				Say.to(player, "It costs " + preset.entryFee.describe(6) + " to join " + arena.title() + "; you're short " + shortOf);
				return null;
			}
		}
		ItemList paid = pays ? Fees.take(player, preset.entryFee) : null;

		Optional<Kit> stash = preset.isolated() ? Optional.of(Kit.of(player)) : Optional.empty();
		Optional<Visit.Experience> experience = preset.isolated() ? Optional.of(Visit.Experience.of(player)) : Optional.empty();
		Optional<List<AddedSlots.Held>> added = preset.isolated() ? Optional.of(AddedSlots.take(player)) : Optional.empty();
		Visit.set(player, new Visit(arena.id, home, stash, experience, Optional.empty(), Optional.of(player.gameMode().getName()), added));
		if (preset.isolated()) {
			player.getInventory().clearContent();
			Visit.Experience.clear(player);
			player.removeAllEffects();
			player.containerMenu.broadcastChanges();
		}

		Arena.Member member = arena.join(player.getUUID(), player.getGameProfile().name());
		member.inside = true;
		member.watching = watching;
		if (paid != null) {
			member.fee = paid;
			member.paid = true;
		}
		if (preset.teamsOn() && !watching) Teams.put(player, arena, member, member.team >= 0 ? member.team : Teams.smallest(arena));
		else if (!watching) Teams.hide(player, arena);
		Arenas.vault(server).touch();
		Arenas.wake(server, arena);

		GameType mode;
		if (watching) mode = GameType.SPECTATOR;
		else if (admin) mode = player.gameMode();
		else if (arena.phase == Arena.Phase.LOBBY) mode = GameType.ADVENTURE;
		else mode = playMode(arena);
		if (!watching && preset.activeGoal() == Preset.Goal.DESTRUCTION && member.team >= 0 && arena.fallen.contains(member.team)) {
			member.out = true;
			mode = GameType.SPECTATOR;
		}
		player.setGameMode(mode);

		Vec3 destination;
		if (arena.phase == Arena.Phase.LOBBY && arena.lobbyStanding) destination = Lobby.arrival(arena);
		else if (watching) destination = Vec3.atBottomCenterOf(arena.footprint().center(arena.highestY + 8));
		else destination = Spawns.forPlayer(level, arena, member.team);

		boolean equip = arena.phase == Arena.Phase.LIVE && !watching;
		boolean zombie = member.zombie && preset.hordeOn() && arena.phase == Arena.Phase.LIVE;
		return new TeleportTransition(level, destination, Vec3.ZERO, player.getYRot(), 0, Set.of(), entity -> {
			if (!(entity instanceof ServerPlayer arrived)) return;
			if (equip) equip(arrived, arena, true);
			if (zombie) Horde.rise(arrived, arena, member);
			Borders.send(arrived, arena);
			Fog.show(arrived, arena);
			Say.to(arrived, welcome(arena, watching));
			if (paid != null) Say.to(arrived, "You paid " + paid.describe(6) + " to come in"
				+ (preset.feesToWinners ? "; the winners share everyone's" : ""));
			if (!watching && !zombie) Loadouts.offer(arrived, arena);
		});
	}

	/** Through a lit frame: in, and home is a step back out of the frame they walked into. */
	public static @Nullable TeleportTransition enterByGate(ServerPlayer player, Arena arena, BlockPos portal) {
		if (arena.phase == Arena.Phase.GENERATING) {
			Say.bar(player, arena.title() + " is still being made");
			return null;
		}
		if (!arena.open()) return null;
		ServerLevel level = player.level();
		return enter(player, arena, Portals.homeFromGate(level, portal, player.position(), player.getYRot(), player.getXRot()));
	}

	private static String welcome(Arena arena, boolean watching) {
		if (watching) return "You're watching " + arena.title() + ". /pvp leave to go home";
		String out = arena.preset.exitCommand ? " /pvp leave takes you home." : arena.preset.exitPortal ? " An exit portal takes you home." : "";
		if (arena.phase == Arena.Phase.LOBBY && arena.preset.waitingRoom) {
			String pick = arena.preset.teamsOn() ? " Stand on a colour to pick a team." : "";
			return "Welcome to " + arena.title() + "'s waiting room." + pick + out;
		}
		return "Welcome to " + arena.title() + "." + out;
	}

	/** Set down on the fighting ground, when the waiting room gives way to the fight. */
	public static void setDown(ServerPlayer player, Arena arena, Arena.Member member) {
		ServerLevel level = Arenas.level(player.level().getServer(), arena);
		if (level == null) return;
		if (!Access.admin(player)) player.setGameMode(playMode(arena));
		Vec3 spot = Spawns.forPlayer(level, arena, member.team);
		player.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), player.getYRot(), 0, true);
		equip(player, arena, true);
		Borders.send(player, arena);
	}

	/**
	 * Onto another team just after arriving in a live fight, where that keeps the teams even: no
	 * more on it than on the smallest. Put down at the new team's spawn, with a compass for it.
	 * What went wrong, or null.
	 */
	public static @Nullable String changeTeam(ServerPlayer player, Arena arena, Arena.Member member, int team) {
		if (arena.phase != Arena.Phase.LIVE || !arena.preset.teamsOn() || team < 0 || team >= arena.preset.teams) return "No team to change to";
		if (member.team == team) return null;
		if (!Loadouts.fresh(player)) return "Teams are picked in the moments after you arrive";
		if (!Teams.open(arena, team, member)) return "Too many on " + justfatlard.pvp_dimensions.preset.TeamColors.of(team).name() + " already";
		ServerLevel level = Arenas.level(player.level().getServer(), arena);
		if (level == null) return "The arena isn't there";
		Teams.put(player, arena, member, team);
		Vec3 spot = Spawns.forPlayer(level, arena, team);
		player.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), player.getYRot(), 0, true);
		GoalCompass.give(player, arena, member);
		Say.to(player, "You're on " + justfatlard.pvp_dimensions.preset.TeamColors.of(team).name());
		return null;
	}

	/** Adventure only where the goal needs no digging or building. */
	public static GameType playMode(Arena arena) {
		Preset preset = arena.preset;
		return switch (ModeChanges.current(arena)) {
			case CREATIVE -> GameType.CREATIVE;
			case ADVENTURE -> preset.needsSurvival() ? GameType.SURVIVAL : GameType.ADVENTURE;
			case SURVIVAL -> GameType.SURVIVAL;
		};
	}

	/** The entry kit, or on a respawn whatever the preset says a respawn gets; healed and fed either way. */
	public static void equip(ServerPlayer player, Arena arena, boolean entering) {
		Preset preset = arena.preset;
		Arena.Member member = arena.member(player.getUUID());
		if (member != null) {
			Loadouts.equip(player, arena, member, entering);
		} else if (entering) {
			preset.entryKit.give(player);
		} else if (preset.respawnKit != Preset.RespawnKit.NONE) {
			player.getInventory().clearContent();
			(preset.respawnKit == Preset.RespawnKit.CUSTOM ? preset.respawnCustom : preset.entryKit).give(player);
		}
		if (member != null) {
			Goals.supply(player, arena, member);
			GoalCompass.give(player, arena, member);
		}
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5);
		player.containerMenu.broadcastChanges();
	}

	// --- Out ---

	/** Out and home now: the leave command, the arena ending. */
	public static void goHome(ServerPlayer player, @Nullable Arena arena, Why why) {
		TeleportTransition trip = leave(player, arena, why);
		if (trip != null) player.teleport(trip);
	}

	/**
	 * Everything on the way out, short of moving: the arena's pack traded back for the one put
	 * aside, keeping whatever the preset lets out and a winner's prize, team and game mode handed
	 * back. The trip home, or null where the player is already somewhere else.
	 */
	public static @Nullable TeleportTransition leave(ServerPlayer player, @Nullable Arena arena, Why why) {
		Visit visit = Visit.of(player);
		if (visit == null) return null;
		MinecraftServer server = player.level().getServer();
		Preset preset = arena != null ? arena.preset : null;
		boolean winner = arena != null && arena.winners.remove(player.getUUID());
		ItemList prize = winner ? arena.prizeItems() : null;
		Horde.leave(player);
		GoalCompass.strip(player);
		Arena.Member leaving = arena != null ? arena.member(player.getUUID()) : null;
		if (leaving != null) Fees.leftEarly(arena, leaving);
		ItemList owed = arena != null ? arena.owed.remove(player.getUUID()) : null;

		if (visit.stash().isPresent()) {
			List<ItemStack> carried = new ArrayList<>(Kit.of(player).stacks());
			if (visit.added().isPresent()) {
				for (AddedSlots.Held held : AddedSlots.take(player)) carried.add(held.stack());
			}
			List<ItemStack> keep = new ArrayList<>();
			if (preset != null && !preset.creative()) {
				for (ItemStack stack : carried) {
					if (winner && preset.winnersKeepPack || preset.rewardsOut() && preset.rewards.allows(stack.getItem())) keep.add(stack);
				}
			}
			if (prize != null) keep.addAll(prize.stacks());
			if (owed != null) keep.addAll(owed.stacks());
			player.getInventory().clearContent();
			visit.stash().get().give(player);
			visit.added().ifPresent(held -> AddedSlots.putBack(player, held));
			for (ItemStack stack : keep) ItemList.giveStack(player, stack);
			visit.experience().ifPresent(experience -> experience.restore(player));
			player.removeAllEffects();
		} else {
			if (prize != null) prize.give(player);
			if (owed != null) owed.give(player);
		}
		if (prize != null) Say.to(player, "Your prize from " + arena.title() + ": " + prize.describe(8));
		if (owed != null) Say.to(player, "From " + arena.title() + "'s entry fees: " + owed.describe(8));
		Fog.show(player, null);
		justfatlard.pvp_dimensions.ui.GameHud.hide(player);
		player.containerMenu.broadcastChanges();

		GameType before = visit.gameMode().map(name -> GameType.byName(name, GameType.SURVIVAL)).orElse(GameType.SURVIVAL);
		if (player.gameMode() != before) player.setGameMode(before);
		Teams.restore(player, visit.team().orElse(null));
		Visit.set(player, null);

		if (arena != null) {
			Arena.Member member = arena.member(player.getUUID());
			if (member != null) {
				member.inside = false;
				member.watching = false;
			}
			Arenas.unbar(arena, player);
			Arenas.vault(server).touch();
			Arenas.forgetIfSettled(server, arena);
		}

		// Only someone still standing in an arena dimension needs taking home; a respawn or
		// another mod's teleport has already put them somewhere else.
		return Places.isArena(player.level().dimension()) ? home(server, visit.home()) : null;
	}

	/** The trip home, for a respawn that must come out there. */
	public static TeleportTransition homeTrip(MinecraftServer server, Visit.Home home) {
		return home(server, home);
	}

	/** The world's spawn, for anyone whose own home is gone, unknown, or nowhere safe to stand. */
	public static TeleportTransition spawnTrip(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Vec3 spawn = Vec3.atBottomCenterOf(level.getRespawnData().pos());
		TeleportTransition.PostTeleportTransition told = entity -> {
			if (entity instanceof ServerPlayer player) Say.to(player, "Home");
		};
		TeleportTransition trip = Landing.to(level, spawn, 0, 0, told);
		return trip != null ? trip : new TeleportTransition(level, spawn, Vec3.ZERO, 0, 0, Set.of(), told);
	}

	/** Back where they came from, set down safely, or at spawn where that is gone or over the void. */
	private static TeleportTransition home(MinecraftServer server, Visit.Home home) {
		ServerLevel level = server.getLevel(home.dimension());
		if (level == null || Places.isArena(home.dimension())) return spawnTrip(server);
		TeleportTransition trip = Landing.to(level, new Vec3(home.x(), home.y(), home.z()), home.yaw(), home.pitch(), TeleportTransition.DO_NOTHING);
		return trip != null ? trip : spawnTrip(server);
	}

	// --- Loose ends ---

	/**
	 * Logging in. A visit to an arena that ended while they were away, or that no longer exists,
	 * ends now, and they are put back where they came from. Anyone standing in an arena dimension
	 * with no visit at all is put at spawn: there is nothing under them.
	 *
	 * <p>Someone back on the death screen is handed nothing: whatever goes into a body is lost when
	 * it respawns. Their visit ends at the respawn instead.
	 */
	public static void loggedIn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		boolean dead = player.isDeadOrDying();
		Visit visit = Visit.of(player);
		if (visit != null) {
			Arena arena = Arenas.get(server, visit.arena());
			if (arena == null || arena.phase == Arena.Phase.ENDED || !player.level().dimension().equals(arena.dimension)) {
				if (!dead) {
					TeleportTransition trip = leave(player, arena, Why.LOGIN);
					if (trip != null) player.teleport(trip);
				}
				if (arena != null) Say.to(player, arena.title() + " ended while you were away");
			} else {
				Arena.Member member = arena.join(player.getUUID(), player.getGameProfile().name());
				member.inside = true;
				Arenas.wake(server, arena);
				Fog.show(player, arena);
				if (!dead && !arena.footprint().containsBlock(player.getX(), player.getZ())) {
					ServerLevel level = player.level();
					Vec3 back = arena.lobbyStanding ? Lobby.arrival(arena) : Spawns.forPlayer(level, arena, member.team);
					player.teleportTo(level, back.x, back.y, back.z, Set.of(), player.getYRot(), 0, true);
				}
				Borders.send(player, arena);
			}
		} else if (!dead && Places.isArena(player.level().dimension())
				&& (!Access.admin(player) || Arenas.at(player.level(), player.getX(), player.getZ()) == null)) {
			// Nobody is left standing where no arena is: that ground is gone, or about to be.
			player.teleport(home(server, new Visit.Home(Places.OVERWORLD, 0, 0, 0, 0, 0)));
		}
	}

	private static final java.util.Map<java.util.UUID, Long> strayed = new java.util.HashMap<>();

	/**
	 * Anyone whose visit says they are in an arena but who has been somewhere else for a few
	 * seconds has left it, whatever took them: their own things come back. Every way out this
	 * mod knows of ends the visit itself; this is for the ways it does not.
	 */
	public static void sweep(MinecraftServer server) {
		long now = System.currentTimeMillis();
		java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Visit visit = Visit.of(player);
			if (visit == null || player.isDeadOrDying()) continue;
			Arena arena = Arenas.get(server, visit.arena());
			if (arena != null && arena.phase != Arena.Phase.ENDED && player.level().dimension().equals(arena.dimension)) continue;
			seen.add(player.getUUID());
			long since = strayed.computeIfAbsent(player.getUUID(), id -> now);
			if (now - since < 3000) continue;
			strayed.remove(player.getUUID());
			TeleportTransition trip = leave(player, arena, Why.MOVED);
			if (trip != null) player.teleport(trip);
		}
		strayed.keySet().retainAll(seen);
	}

	/**
	 * Why a teleport may not set this player down here, or null where it may.
	 *
	 * <p>An arena that puts what a player carries aside at the door, or charges to come in, is
	 * entered through {@link #enter}: that is where the stash is taken, the fee paid, a team and a
	 * game mode given, and it is the only way any of that happens. A teleport steps around the lot
	 * and leaves a player standing in the arena holding their own things where nobody else has
	 * theirs, owing an entry nobody collected. So the teleport is refused and the door named, which
	 * is worth more to whoever typed it than the silent undoing {@link #changedLevel} would do a
	 * tick later.
	 *
	 * <p>Admins are not stopped: somebody has to be able to get in and build. Nor is anyone who
	 * already has a visit, which includes every player on their way in through the front door,
	 * since {@link #enter} sets the visit before the trip.
	 */
	public static @Nullable String uninvited(ServerPlayer player, ServerLevel level, double x, double z) {
		if (!Places.isArena(level.dimension())) return null;
		if (Visit.of(player) != null || Access.admin(player)) return null;

		String who = player.getGameProfile().name();
		Arena arena = Arenas.at(level, x, z);
		if (arena == null) {
			return who + " has no arena to be put down in: arena ground is entered by invitation or through a lit frame";
		}

		String fee = "charges " + arena.preset.entryFee.describe(6) + " at the door";
		String door = arena.preset.isolated()
			? arena.preset.entryFee.isEmpty() ? "puts what a player carries aside at the door"
				: "puts what a player carries aside and " + fee
			: arena.preset.entryFee.isEmpty() ? null : fee;
		return who + " has no invitation to " + arena.title() + (door == null ? "" : ", which " + door)
			+ ". Ask them in instead: /pvp invite " + who + " " + arena.id;
	}

	/**
	 * Moved between dimensions by something that is not this mod: an op's teleport, another
	 * mod's home command. Out of an arena that way still counts as leaving it; into one with no
	 * visit is not allowed for anyone but an admin.
	 */
	public static void changedLevel(ServerPlayer player, ServerLevel from, ServerLevel to) {
		Visit visit = Visit.of(player);
		if (visit != null) {
			Arena arena = Arenas.get(player.level().getServer(), visit.arena());
			if (arena != null && from.dimension().equals(arena.dimension) && !to.dimension().equals(arena.dimension)) {
				leave(player, arena, Why.MOVED);
			}
			return;
		}
		if (Places.isArena(to.dimension()) && !Access.admin(player)) {
			Say.to(player, "Arenas are entered by invitation or through a lit frame");
			sendHomeNextTick(player);
		}
	}

	/**
	 * Take a player home on the tick after this one, never during the teleport that brought them.
	 *
	 * <p>This event fires from the middle of {@code ServerPlayer.teleport}, which finishes by
	 * calling {@code teleportSpectators} against the level the player just left: every player still
	 * standing there whose camera is themselves - which is every player who is not spectating
	 * something - gets sent along the same trip. Turning a player around from in here puts them
	 * back in that old level before that runs, so the tail of the outer teleport picks them up and
	 * sends them into the arena again, which fires this again, with no stack ever unwinding. It
	 * ends as a StackOverflowError in the tick loop; it took the server down on 2026-09-17.
	 *
	 * <p>A tick later the outer teleport is over and there is nothing left to re-enter. The player
	 * is looked up afresh because they may have logged out or died in between.
	 */
	private static void sendHomeNextTick(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		java.util.UUID id = player.getUUID();
		server.execute(() -> {
			ServerPlayer now = server.getPlayerList().getPlayer(id);
			if (now == null || !Places.isArena(now.level().dimension()) || Visit.of(now) != null) return;
			now.teleport(home(server, new Visit.Home(Places.OVERWORLD, 0, 0, 0, 0, 0)));
		});
	}
}
