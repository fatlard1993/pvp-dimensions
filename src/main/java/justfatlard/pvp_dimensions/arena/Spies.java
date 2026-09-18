package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.jspecify.annotations.Nullable;

/**
 * Everybody knows where they are but one.
 *
 * <p>The table game this comes from hides a place from one player at the table and lets the rest
 * ask each other about it. That does not survive being moved into Minecraft on its own: a room
 * tells you where you are the moment you open your eyes. So the place is not the room. The room is
 * a blank hall, the place is a card, and the talking is the game - which is why this mode wants
 * voices in it and gives the HUD nothing but the card and the clock.
 *
 * <p>The place the cards name is built, though, out past the wall of the hall. Naming the odd one
 * out opens it, and the round settles the Minecraft way: see the scramble. This class is the hall
 * half - who knows what, and what their card says.
 */
public final class Spies {
	private Spies() {}

	/** A place everyone but one player is told they are standing in, and the parts they play in it. */
	private record Place(String name, List<String> roles) {}

	/**
	 * Where a round can be set.
	 *
	 * <p>Chosen for the questions they afford rather than for being landmarks: every one of them
	 * has work to do, something to be afraid of and somewhere to sleep, because "what do you do
	 * here", "what would kill you here" and "where do you bed down" are the questions a table
	 * actually asks. A place nobody can picture is a place nobody can bluff about.
	 */
	private static final List<Place> PLACES = List.of(
		new Place("the Village", List.of("Librarian", "Blacksmith", "Farmer", "Iron Golem",
			"Wandering Trader", "Night Watch", "Cleric")),
		new Place("the Nether Fortress", List.of("Blaze Wrangler", "Wither Skeleton", "Bridge Builder",
			"Fortress Cook", "Lost Piglin", "Quartz Miner")),
		new Place("the Ocean Monument", List.of("Guardian", "The Elder's Attendant", "Sponge Diver",
			"Prismarine Mason", "Drowned Pilgrim", "Air Carrier")),
		new Place("the Abandoned Mineshaft", List.of("Rail Layer", "Cave Spider Keeper", "Lost Miner",
			"Cart Pusher", "Cobweb Cutter", "Lamp Lighter")),
		new Place("the Stronghold", List.of("Portal Keeper", "Librarian", "Silverfish Exterminator",
			"Stone Mason", "Pilgrim", "Ender Eye Reader")),
		new Place("the Woodland Mansion", List.of("Vindicator", "The Evoker's Apprentice", "Room Sweeper",
			"Totem Carver", "Trapped Explorer", "Window Cleaner")),
		new Place("the End City", List.of("Shulker Herder", "Chorus Farmer", "Elytra Thief",
			"Tower Climber", "Purpur Mason", "Void Watcher")),
		new Place("the Desert Temple", List.of("Tomb Robber", "Trap Defuser", "Sandstone Mason",
			"Lost Nomad", "Trap Setter", "Treasure Counter")),
		new Place("the Ancient City", List.of("Warden's Watch", "Sculk Listener", "Echo Shard Finder",
			"Silent Runner", "Candle Lighter", "Skulk Farmer")),
		new Place("the Bastion", List.of("Piglin Brute", "Gold Hoarder", "Hoglin Rancher",
			"Bridge Guard", "Barter Trader", "Lava Fisher")),
		new Place("the Farm", List.of("Wheat Farmer", "Beekeeper", "Chicken Counter", "Scarecrow",
			"Compost Keeper", "Fence Mender")),
		new Place("the Pillager Outpost", List.of("Lookout", "Cage Keeper", "Crossbow Fletcher",
			"Banner Bearer", "Captive", "Log Hauler"))
	);

	/**
	 * A round in progress, by arena id.
	 *
	 * <p>Kept here rather than on the arena, the way the takeover keeps its counted ground: a round
	 * of this does not outlive a restart in any state worth resuming, since the whole of it is what
	 * players have said out loud since the cards went out.
	 */
	private record Round(Place place, UUID spy, Map<UUID, String> roles) {}

	private static final Map<String, Round> rounds = new HashMap<>();

	/** Draw a place, draw the one who is not told it, and deal the rest their parts. */
	public static void begin(MinecraftServer server, Arena arena) {
		List<Arena.Member> playing = new ArrayList<>(arena.members.values());
		if (playing.isEmpty()) return;

		var random = server.overworld().getRandom();
		Place place = PLACES.get(random.nextInt(PLACES.size()));

		List<String> roles = new ArrayList<>(place.roles());
		Collections.shuffle(roles, new java.util.Random(random.nextLong()));

		UUID spy = playing.get(random.nextInt(playing.size())).id;
		Map<UUID, String> dealt = new LinkedHashMap<>();
		int next = 0;
		for (Arena.Member member : playing) {
			if (member.id.equals(spy)) continue;
			// More players than parts is a bigger table than the place was written for; the parts
			// come round again rather than anybody standing there with a blank card.
			dealt.put(member.id, roles.get(next++ % roles.size()));
		}

		rounds.put(arena.id, new Round(place, spy, dealt));
		for (Arena.Member member : playing) deal(server, arena, member.id);
		seat(server, arena);
	}

	/** Tell one player what they hold, as plainly as the card would. */
	private static void deal(MinecraftServer server, Arena arena, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null) return;
		Round round = rounds.get(arena.id);
		if (round == null) return;

		if (id.equals(round.spy())) {
			Say.to(player, ChatFormatting.RED + "You do not know where you are.");
			Say.to(player, "Everyone else does. Ask about it without giving away that you cannot.");
			return;
		}
		Say.to(player, "You are in " + ChatFormatting.AQUA + round.place().name() + ChatFormatting.RESET + ".");
		Say.to(player, "You are the " + ChatFormatting.AQUA + round.roles().get(id) + ChatFormatting.RESET
			+ ". One of you does not know the place: find them.");
	}

	/**
	 * The line under a player's own name on the HUD: their card, and nobody else's.
	 *
	 * <p>The status line is per player already, which is the whole reason the card needs no screen
	 * of its own - and a card you can glance at all round is better than one shown once at the
	 * start, because forgetting your own part is exactly the slip that gets an innocent hunted.
	 */
	public static @Nullable String card(Arena arena, UUID id) {
		Round round = rounds.get(arena.id);
		if (round == null) return null;
		if (id.equals(round.spy())) return ChatFormatting.RED + "You do not know where you are";
		return round.place().name() + " - " + round.roles().get(id);
	}

	/** Whether this player is the one who was not told, for the scramble to settle on. */
	public static boolean odd(Arena arena, UUID id) {
		Round round = rounds.get(arena.id);
		return round != null && round.spy().equals(id);
	}

	/** The place a round was set in, for naming it once the round is over. */
	public static @Nullable String placeOf(Arena arena) {
		Round round = rounds.get(arena.id);
		return round == null ? null : round.place().name();
	}

	/**
	 * The clock ran out with nobody named: the one who did not know has sat at the table for a
	 * whole round without being found, which is the win the table game gives them.
	 */
	public static boolean timeUp(MinecraftServer server, Arena arena) {
		Round round = rounds.get(arena.id);
		if (round == null) return false;
		Arena.Member spy = arena.members.get(round.spy());
		if (spy == null) return false;
		Goals.winPlayer(server, arena, spy, "nobody found the one who did not know; it was " + round.place().name());
		return true;
	}

	// --- The scramble ---

	/**
	 * How long a call stands before it lapses, and how long the window stays open once it carries.
	 *
	 * <p>Twenty seconds to answer a call is long enough to think and short enough that nobody can
	 * stall a round out by refusing to vote. Thirty seconds of open window is the number to feel
	 * out in play: long enough for the accused to make a real run of it, short enough that the
	 * talking is still the game.
	 */
	private static final long CALL_MILLIS = 20_000L;
	private static final long WINDOW_MILLIS = 30_000L;

	/** What a second of staying alive is worth to whoever the room named. */
	private static final int SURVIVED_PER_SECOND = 2;
	/** What a kill is worth before the doubling: the sign is what the roles decide. */
	private static final int KILL = 20;
	/** What the room lynching one of its own is worth to the one who talked them into it. */
	private static final int MISDIRECTED = 40;

	/** A name put up, and who has agreed to it so far. */
	private record Call(UUID accused, String accusedName, long closesAt, Set<UUID> agreed) {}

	/** The window: who the room named, and when it shuts. */
	private record Hunt(UUID target, long endsAt, long openedAt) {}

	private static final Map<String, Call> calls = new HashMap<>();
	private static final Map<String, Hunt> hunts = new HashMap<>();

	/** Whether blades are out in this arena, which is the only time they are in this mode. */
	public static boolean open(Arena arena) {
		return hunts.containsKey(arena.id);
	}

	/** Who the room named, for the scoring to tell a lynching from a murder. */
	private static @Nullable UUID target(Arena arena) {
		Hunt hunt = hunts.get(arena.id);
		return hunt == null ? null : hunt.target();
	}

	/** Put a name up. Anybody may, once there is nothing else running. */
	public static void accuse(MinecraftServer server, Arena arena, ServerPlayer caller, ServerPlayer accused) {
		if (rounds.get(arena.id) == null) {
			Say.to(caller, "Nothing to accuse anybody of: this arena is not playing the odd one out");
			return;
		}
		if (open(arena)) {
			Say.to(caller, "Blades are already out");
			return;
		}
		if (calls.containsKey(arena.id)) {
			Say.to(caller, "A name is already up; say /pvp agree if you want it");
			return;
		}
		Arena.Member member = arena.member(accused.getUUID());
		if (member == null || !member.inside || member.out) {
			Say.to(caller, accused.getGameProfile().name() + " is not in this round");
			return;
		}
		calls.put(arena.id, new Call(accused.getUUID(), accused.getGameProfile().name(),
			System.currentTimeMillis() + CALL_MILLIS, new HashSet<>(Set.of(caller.getUUID()))));
		Arenas.tellInside(server, arena, caller.getGameProfile().name() + " says it is "
			+ accused.getGameProfile().name() + ". Say /pvp agree to take the blame with them.");
	}

	/** Agree to the name up. Enough of the room agreeing is what opens the window. */
	public static void agree(MinecraftServer server, Arena arena, ServerPlayer voter) {
		Call call = calls.get(arena.id);
		if (call == null) {
			Say.to(voter, "No name is up");
			return;
		}
		if (voter.getUUID().equals(call.accused())) {
			Say.to(voter, "Not your own");
			return;
		}
		if (!call.agreed().add(voter.getUUID())) {
			Say.to(voter, "You have said so already");
			return;
		}
		int standing = standing(arena);
		int needed = standing / 2 + 1;
		if (call.agreed().size() < needed) {
			Arenas.tellInside(server, arena, call.agreed().size() + " of " + needed + " for "
				+ call.accusedName());
			return;
		}
		calls.remove(arena.id);
		begin(server, arena, call);
	}

	/** Everyone still standing in the round: what a majority is counted out of. */
	private static int standing(Arena arena) {
		int count = 0;
		for (Arena.Member member : arena.members.values()) if (member.inside && !member.out && !member.watching) count++;
		return count;
	}

	/** The window opens. No head start: a scramble is the point. */
	private static void begin(MinecraftServer server, Arena arena, Call call) {
		long now = System.currentTimeMillis();
		hunts.put(arena.id, new Hunt(call.accused(), now + WINDOW_MILLIS, now));
		standAll(server, arena);
		Arenas.tellInside(server, arena, "The room has named " + call.accusedName()
			+ ". Blades are out - on anybody.");
		ServerPlayer accused = server.getPlayerList().getPlayer(call.accused());
		if (accused != null) Say.to(accused, ChatFormatting.RED + "They have named you. Stay alive.");
	}

	/**
	 * A death while the blades are out.
	 *
	 * <p>Everything this mode scores happens here. The sign of a kill is what the two roles
	 * disagree about and nothing else: killing the one who was not told is worth something to
	 * anybody who knew the place, and killing somebody who knew it is worth the same to the one
	 * who did not. Either way it doubles when the victim is not the name the room put up, because
	 * going off the room's own call is a read somebody is backing with their own money.
	 *
	 * <p>The room is told who fell and whether they knew the place. It is never told who swung.
	 * That is the whole of the shady half: the fact is in the world, where people were standing and
	 * what they were holding, and not in the chat log.
	 */
	public static void died(MinecraftServer server, Arena arena, ServerPlayer victim, @Nullable ServerPlayer killer) {
		Round round = rounds.get(arena.id);
		if (round == null || !open(arena)) return;

		Arena.Member lost = arena.member(victim.getUUID());
		if (lost == null) return;
		boolean victimKnew = !round.spy().equals(victim.getUUID());
		boolean onTarget = victim.getUUID().equals(target(arena));

		if (killer != null && !killer.getUUID().equals(victim.getUUID())) {
			Arena.Member hand = arena.member(killer.getUUID());
			if (hand != null) {
				boolean killerKnew = !round.spy().equals(killer.getUUID());
				// One line covers all three ways this can happen, because the rule is the same
				// rule: taking somebody of the other sort pays, taking one of your own costs. A
				// knower taking the one who did not know is paid; a knower taking a knower is
				// charged; the one who did not know is paid for every knower, which is everybody
				// they can reach. Doubled off the room's own call, either way round: going by your
				// own read rather than the vote is a read you are backing with your own money.
				int worth = victimKnew == killerKnew ? -KILL : KILL;
				hand.points += worth * (onTarget ? 1 : 2);
			}
		}

		// The room's own call, carried out on somebody who knew the place: that is the work of
		// whoever talked them into it, and it pays whether or not they lifted a finger.
		if (onTarget && victimKnew) {
			Arena.Member spy = arena.members.get(round.spy());
			if (spy != null) spy.points += MISDIRECTED;
		}

		Arenas.tellInside(server, arena, lost.name + " is down. "
			+ (victimKnew ? "They knew the place." : ChatFormatting.RED + "They did not know the place."));

		lost.out = true;
		hunts.remove(arena.id);

		if (!victimKnew) {
			Goals.winTogether(server, arena, "the one who did not know is down; it was " + round.place().name());
			return;
		}
		if (knowersLeft(arena, round) == 0) {
			Arena.Member spy = arena.members.get(round.spy());
			if (spy != null) Goals.winPlayer(server, arena, spy, "everybody who knew the place is down");
		}
	}

	private static int knowersLeft(Arena arena, Round round) {
		int count = 0;
		for (Arena.Member member : arena.members.values()) {
			if (member.id.equals(round.spy()) || member.out || !member.inside) continue;
			count++;
		}
		return count;
	}

	/** Lapse a call nobody took up, shut a window whose time is done, and pay for staying alive. */
	public static void tick(MinecraftServer server, Arena arena, long now) {
		// Sitting is not a state a player can be trusted to stay in: shift gets anybody off a
		// seat, and a round where one person is walking about while the rest are sat down is the
		// round giving itself away. Whoever stood up sits back down.
		if (rounds.containsKey(arena.id) && !open(arena)) seat(server, arena);

		Call call = calls.get(arena.id);
		if (call != null && now >= call.closesAt()) {
			calls.remove(arena.id);
			Arenas.tellInside(server, arena, "Nobody else would say it was " + call.accusedName());
		}
		Hunt hunt = hunts.get(arena.id);
		if (hunt == null || now < hunt.endsAt()) return;

		hunts.remove(arena.id);
		Arena.Member survivor = arena.members.get(hunt.target());
		if (survivor != null) {
			survivor.points += (int) ((hunt.endsAt() - hunt.openedAt()) / 1000L) * SURVIVED_PER_SECOND;
		}
		// Nothing is revealed about somebody who lived through it: surviving a scramble is not
		// evidence, and a round where it was would be a round with one question in it.
		Arenas.tellInside(server, arena, "Blades away. "
			+ (survivor == null ? "" : survivor.name + " is still standing."));
		seat(server, arena);
	}

	// --- Sitting for the talk ---

	/**
	 * Everybody stays put while the talking is on.
	 *
	 * <p>The table game has everyone round a table, and nothing about standing in a Minecraft room
	 * says that: people wander, climb the walls and are halfway across the hall by the third
	 * question. So for as long as the blades are down, every player sits on a seat of their own
	 * and cannot walk off it - a bed without the bed, which is what fatlard asked for and what a
	 * bed cannot be here, since two of the three arena worlds treat one as a bomb.
	 *
	 * <p>A seat is an armour stand nobody can see, with no gravity and no hitbox, which is the
	 * oldest trick in the book and the only one that holds a player still without fighting their
	 * client for it. Anything that clamps a position instead ends up in a tug of war the player
	 * feels as rubber-banding, and the round would look broken rather than seated.
	 */
	private static final Map<String, Map<UUID, Integer>> seats = new HashMap<>();

	/** Sit everyone who is not sitting, and let go of everyone who should not be. */
	private static void seat(MinecraftServer server, Arena arena) {
		Map<UUID, Integer> theirs = seats.computeIfAbsent(arena.id, id -> new HashMap<>());
		boolean talking = !open(arena);
		for (Arena.Member member : arena.members.values()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member.id);
			if (player == null) continue;
			boolean shouldSit = talking && member.inside && !member.out && !member.watching;
			if (!shouldSit) {
				stand(player, theirs.remove(member.id));
				continue;
			}
			// Riding anything at all is enough: a player on a seat stays on it whoever put them
			// there, and re-seating one who is already sitting would drop them a block each time.
			if (player.getVehicle() != null) continue;
			// No marker flag: it is private in this version, so the seat keeps a hitbox. It is
			// invisible, unhittable and inside the player sitting on it, which is near enough.
			ArmorStand seat = new ArmorStand(player.level(), player.getX(), player.getY(), player.getZ());
			seat.setInvisible(true);
			seat.setNoGravity(true);
			seat.setPermanentlyInvulnerable(true);
			seat.setSilent(true);
			player.level().addFreshEntity(seat);
			player.startRiding(seat, true, true);
			theirs.put(member.id, seat.getId());
		}
	}

	/** Let one player up, and take their seat away with them. */
	private static void stand(ServerPlayer player, @Nullable Integer seatId) {
		Entity vehicle = player.getVehicle();
		if (vehicle instanceof ArmorStand) {
			player.stopRiding();
			vehicle.discard();
		}
		if (seatId == null) return;
		Entity left = ((ServerLevel) player.level()).getEntity(seatId);
		if (left instanceof ArmorStand) left.discard();
	}

	/** Everyone up, seats gone: the round is over or the blades are out. */
	private static void standAll(MinecraftServer server, Arena arena) {
		Map<UUID, Integer> theirs = seats.remove(arena.id);
		if (theirs == null) return;
		for (Map.Entry<UUID, Integer> sat : theirs.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(sat.getKey());
			if (player != null) stand(player, sat.getValue());
		}
	}

	/** A round is over: its cards mean nothing now. */
	public static void clear(Arena arena) {
		seats.remove(arena.id);
		rounds.remove(arena.id);
		calls.remove(arena.id);
		hunts.remove(arena.id);
	}
}
