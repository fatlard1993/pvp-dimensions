package justfatlard.pvp_dimensions;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Two tiers. An admin does everything: makes and edits presets, starts any arena from anything,
 * ends anyone's. A user starts arenas from the presets admins have saved for them and ends their
 * own. Ops are admins always; anyone else is whatever an op has granted them, kept on the player
 * and carried through deaths, or whatever a permissions mod says of the nodes
 * {@code pvp-dimensions-justfatlard:admin} and {@code :user}.
 *
 * <p>Nobody needs a tier to play: an invitation or a lit frame is the way in, and the way out
 * is open to whoever is inside.
 */
public final class Access {
	private Access() {}

	public enum Tier {
		NONE, USER, ADMIN;

		public boolean atLeast(Tier other) {
			return ordinal() >= other.ordinal();
		}
	}

	public static final AttachmentType<String> GRANTED = AttachmentRegistry.<String>builder()
		.persistent(Codec.STRING)
		.copyOnDeath()
		.buildAndRegister(PvpDimensions.id("tier"));

	/** Loads the class, which is what registers the attachment; it must happen at init. */
	static void init() {}

	public static Tier of(ServerPlayer player) {
		if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return Tier.ADMIN;
		if (player.checkPermission(PvpDimensions.id("admin"), false)) return Tier.ADMIN;
		Tier granted = granted(player);
		if (granted == Tier.ADMIN) return Tier.ADMIN;
		if (granted == Tier.USER || player.checkPermission(PvpDimensions.id("user"), false)) return Tier.USER;
		return Tier.NONE;
	}

	public static Tier of(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player != null) return of(player);
		return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) ? Tier.ADMIN : Tier.NONE;
	}

	public static boolean admin(ServerPlayer player) {
		return of(player) == Tier.ADMIN;
	}

	public static Tier granted(ServerPlayer player) {
		String tier = player.getAttached(GRANTED);
		if (tier == null) return Tier.NONE;
		try {
			return Tier.valueOf(tier);
		} catch (IllegalArgumentException e) {
			return Tier.NONE;
		}
	}

	public static void grant(ServerPlayer player, Tier tier) {
		if (tier == Tier.NONE) player.removeAttached(GRANTED);
		else player.setAttached(GRANTED, tier.name());
	}
}
