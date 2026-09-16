package justfatlard.pvp_dimensions.arena;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.preset.Kit;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.ui.PickScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Kits to pick from, where a preset has more than one: the entry kit, which is the first and what
 * anyone who doesn't pick gets, and the preset's others, each with its own respawn.
 *
 * <p>The pick comes up on the way in, in the waiting room or on arrival, and after each death
 * where the preset says. Picked within a few seconds of spawning, the kit is swapped there and
 * then; later, it is the kit for the next spawn.
 */
public final class Loadouts {
	private Loadouts() {}

	/** How long after spawning a new pick swaps the kit in hand. */
	private static final long SWAP_MILLIS = 15_000L;

	/** What each player was last handed, and when: what a swap takes back, and whether it still may. */
	private record Handed(Kit kit, long at) {}

	private static final Map<UUID, Handed> handed = new ConcurrentHashMap<>();

	/**
	 * The member's kit on the way in, or on a respawn what their loadout respawns with: nothing,
	 * the kit again, or a pack of its own.
	 */
	public static void equip(ServerPlayer player, Arena arena, Arena.Member member, boolean entering) {
		Preset preset = arena.preset;
		int pick = member.loadout;
		Kit kit;
		if (entering) {
			kit = preset.loadoutKit(pick);
		} else {
			kit = switch (preset.loadoutRespawn(pick)) {
				case NONE -> null;
				case ENTRY -> preset.loadoutKit(pick);
				case CUSTOM -> preset.loadoutRespawnCustom(pick);
			};
			if (kit != null) player.getInventory().clearContent();
		}
		if (kit != null) {
			kit.give(player);
			handed.put(player.getUUID(), new Handed(kit.copy(), System.currentTimeMillis()));
		}
	}

	/** The pick put in front of a player: the menu on a Pandorical client, a row of chat buttons on any other. */
	public static void offer(ServerPlayer player, Arena arena) {
		boolean teams = PickScreen.teamsToPick(arena);
		if (!arena.preset.picksLoadout() && !teams) return;
		if (PandoricalApi.isAvailable(player)) {
			PickScreen.show(player, arena);
			return;
		}
		if (teams) {
			MutableComponent line = Say.line("Pick a team: ");
			for (int team = 0; team < arena.preset.teams; team++) {
				String name = justfatlard.pvp_dimensions.preset.TeamColors.of(team).name();
				line.append(Say.button(name, "/pvp team " + name.toLowerCase(java.util.Locale.ROOT))).append(Component.literal(" "));
			}
			Say.to(player, line);
		}
		if (arena.preset.picksLoadout()) {
			MutableComponent line = Say.line("Pick a loadout: ");
			for (int i = 0; i < arena.preset.loadoutCount(); i++) {
				line.append(Say.button(arena.preset.loadoutName(i), "/pvp loadout " + (i + 1))).append(Component.literal(" "));
			}
			Say.to(player, line);
		}
	}

	/** A player's pick, swapped in now if they only just spawned, or kept for their next spawn. */
	public static void pick(ServerPlayer player, Arena arena, Arena.Member member, int index) {
		Preset preset = arena.preset;
		if (index < 0 || index >= preset.loadoutCount()) return;
		if (full(arena, index, member)) {
			Say.to(player, preset.loadoutName(index) + " is taken: " + capWords(arena, index));
			return;
		}
		boolean changed = member.loadout != index;
		member.loadout = index;
		String name = preset.loadoutName(index);
		if (arena.phase != Arena.Phase.LIVE) {
			Say.to(player, "You'll start with " + name);
			return;
		}
		Handed last = handed.get(player.getUUID());
		boolean fresh = last != null && System.currentTimeMillis() - last.at() <= SWAP_MILLIS && !member.zombie && !member.watching;
		if (!changed) {
			Say.to(player, "You have " + name);
		} else if (fresh) {
			takeBack(player, last.kit());
			Kit kit = preset.loadoutKit(index);
			kit.give(player);
			handed.put(player.getUUID(), new Handed(kit.copy(), last.at()));
			player.containerMenu.broadcastChanges();
			Say.to(player, "Swapped for " + name);
		} else {
			Say.to(player, "You'll have " + name + " next time you come back");
		}
	}

	/** Whether this player was handed their kit only moments ago, so a new pick swaps it. */
	public static boolean fresh(ServerPlayer player) {
		Handed last = handed.get(player.getUUID());
		return last != null && System.currentTimeMillis() - last.at() <= SWAP_MILLIS;
	}

	/**
	 * Who besides this member has the loadout now, as far as they can know it: their own team's
	 * where caps count each team apart, everyone's where they count the match.
	 */
	public static java.util.List<Arena.Member> holders(Arena arena, int index, Arena.Member member) {
		java.util.List<Arena.Member> holders = new java.util.ArrayList<>();
		for (Arena.Member other : arena.members.values()) {
			if (other == member || !other.inside || other.watching || other.zombie || other.loadout != index) continue;
			if (perTeam(arena) && other.team != member.team) continue;
			holders.add(other);
		}
		return holders;
	}

	public static int taken(Arena arena, int index, Arena.Member member) {
		return holders(arena, index, member).size();
	}

	private static boolean perTeam(Arena arena) {
		return arena.preset.teamsOn() && arena.preset.capsPerTeam;
	}

	/** Whether the loadout's cap is reached without this member. */
	public static boolean full(Arena arena, int index, Arena.Member member) {
		int cap = arena.preset.loadoutCap(index);
		return cap > 0 && taken(arena, index, member) >= cap;
	}

	public static String capWords(Arena arena, int index) {
		int cap = arena.preset.loadoutCap(index);
		return cap <= 0 ? "" : cap + " at most" + (perTeam(arena) ? " per team" : "");
	}

	/** Out of the pack, what the last kit put there and is still there as it was handed over. */
	private static void takeBack(ServerPlayer player, Kit kit) {
		Inventory inventory = player.getInventory();
		for (ItemStack given : kit.stacks()) {
			int left = given.getCount();
			for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!ItemStack.isSameItemSameComponents(stack, given)) continue;
				int taken = Math.min(left, stack.getCount());
				stack.shrink(taken);
				left -= taken;
			}
		}
	}

	/** A loadout's picture: the first thing in its kit. */
	public static String icon(Preset preset, int index) {
		Kit kit = preset.loadoutKit(index);
		return kit.isEmpty() ? "minecraft:barrier" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(kit.stacks().get(0).getItem()).toString();
	}

	/** What a loadout respawns with, in words. */
	public static String respawnWords(Preset preset, int index) {
		if (!preset.respawnsInside()) return "";
		return switch (preset.loadoutRespawn(index)) {
			case NONE -> "Comes back with nothing";
			case ENTRY -> "Comes back with the same";
			case CUSTOM -> "Comes back with " + preset.loadoutRespawnCustom(index).describe(4);
		};
	}

	public static void forget(@Nullable UUID player) {
		if (player != null) handed.remove(player);
	}
}
