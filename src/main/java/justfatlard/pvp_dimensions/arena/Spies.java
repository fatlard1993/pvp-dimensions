package justfatlard.pvp_dimensions.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import justfatlard.pvp_dimensions.Say;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

	/** A round is over: its cards mean nothing now. */
	public static void clear(Arena arena) {
		rounds.remove(arena.id);
	}
}
