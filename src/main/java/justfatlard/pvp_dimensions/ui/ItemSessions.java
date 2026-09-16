package justfatlard.pvp_dimensions.ui;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.Say;
import justfatlard.pvp_dimensions.arena.Visit;
import justfatlard.pvp_dimensions.preset.Field;
import justfatlard.pvp_dimensions.preset.Fields;
import justfatlard.pvp_dimensions.preset.Kit;
import justfatlard.pvp_dimensions.preset.Preset;
import justfatlard.pvp_dimensions.preset.Presets;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Item lists are made by holding the items. An admin who edits a kit has their own pack put aside
 * and the kit laid out in its place; they arrange it as a player should receive it, armour on,
 * sword in the first slot, from the creative menu or from chests, and say done. What they are
 * holding then is the kit, and their own pack comes back.
 *
 * <p>The pack put aside is kept on the player, in their save, like an arena visit's: a crash or a
 * disconnect in the middle of editing loses nothing.
 */
public final class ItemSessions {
	private ItemSessions() {}

	public record Editing(String preset, String field, Kit stash) {
		static final Codec<Editing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("preset").forGetter(Editing::preset),
			Codec.STRING.fieldOf("field").forGetter(Editing::field),
			Kit.CODEC.fieldOf("stash").forGetter(Editing::stash)
		).apply(instance, Editing::new));
	}

	public static final AttachmentType<Editing> ATTACHMENT = AttachmentRegistry.<Editing>builder()
		.persistent(Editing.CODEC)
		.copyOnDeath()
		.buildAndRegister(PvpDimensions.id("editing"));

	public static void init() {}

	public static boolean editing(ServerPlayer player) {
		return player.getAttached(ATTACHMENT) != null;
	}

	public static @Nullable Editing of(ServerPlayer player) {
		return player.getAttached(ATTACHMENT);
	}

	public static void start(ServerPlayer player, String presetId, String fieldKey) {
		Preset preset = Presets.get(presetId);
		if (preset == null) {
			Say.to(player, "No preset called " + presetId);
			return;
		}
		if (!(Fields.find(preset, fieldKey) instanceof Field.Items field)) {
			Say.to(player, "That isn't a list of items");
			return;
		}
		if (Visit.of(player) != null) {
			Say.to(player, "Leave the arena first; kits are made outside");
			return;
		}
		if (editing(player)) {
			Say.to(player, "You're already editing; finish that first");
			return;
		}
		player.setAttached(ATTACHMENT, new Editing(presetId, fieldKey, Kit.of(player)));
		player.getInventory().clearContent();
		field.asKit(preset).give(player);
		player.containerMenu.broadcastChanges();
		Menus.closeScreen(player);

		String how = field.laidOut
			? "Lay it out as a player should get it: armour worn, first slot in hand."
			: "Hold whatever should be in the list; where it sits doesn't matter.";
		player.sendSystemMessage(Say.line("Editing " + field.label + " for " + preset.name + ". " + how + " ")
			.append(Say.button("Done", "/pvp done")).append(" ").append(Say.button("Cancel", "/pvp cancel")));
	}

	/** What they hold becomes the list; their own pack comes back. */
	public static void done(ServerPlayer player) {
		Editing editing = of(player);
		if (editing == null) {
			Say.to(player, "You aren't editing any items");
			return;
		}
		Preset preset = Presets.get(editing.preset());
		Field found = preset == null ? null : Fields.find(preset, editing.field());
		if (found instanceof Field.Items field) {
			field.store(preset, Kit.of(player));
			Presets.save(editing.preset(), preset);
			Say.to(player, field.label + " saved: " + field.display(preset));
		} else {
			Say.to(player, "That preset or list is gone; nothing was saved");
		}
		restore(player, editing);
		if (preset != null) Menus.edit(player, editing.preset(), found != null ? found.section : Field.Section.GEAR);
	}

	public static void cancel(ServerPlayer player) {
		Editing editing = of(player);
		if (editing == null) {
			Say.to(player, "You aren't editing any items");
			return;
		}
		restore(player, editing);
		Say.to(player, "Nothing changed");
	}

	private static void restore(ServerPlayer player, Editing editing) {
		player.getInventory().clearContent();
		editing.stash().give(player);
		player.containerMenu.broadcastChanges();
		player.removeAttached(ATTACHMENT);
	}

	public static void loggedIn(ServerPlayer player) {
		Editing editing = of(player);
		if (editing == null) return;
		player.sendSystemMessage(Say.line("You were in the middle of editing items; your own pack is put aside. ")
			.append(Say.button("Done", "/pvp done")).append(" ").append(Say.button("Cancel", "/pvp cancel")));
	}
}
