package justfatlard.pvp_dimensions.onboarding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pvp_dimensions.PvpDimensions;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which demos a player has been offered, stepped into and finished, by the id of the mod each
 * belongs to.
 *
 * <p>The facts are kept rather than a single "played" mark, because what counts as played is the
 * demo's to say and can change under a player: a demo that adds a finish in an update reads as
 * unplayed for everyone who only walked through it, which is what a new finish means.
 */
public record Progress(Set<String> offered, Set<String> entered, Set<String> finished) {
	public static final Progress NONE = new Progress(Set.of(), Set.of(), Set.of());

	private static final Codec<Set<String>> IDS = Codec.STRING.listOf().xmap(Set::copyOf, List::copyOf);

	public static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		IDS.optionalFieldOf("offered", Set.of()).forGetter(Progress::offered),
		IDS.optionalFieldOf("entered", Set.of()).forGetter(Progress::entered),
		IDS.optionalFieldOf("finished", Set.of()).forGetter(Progress::finished)
	).apply(instance, Progress::new));

	public static final AttachmentType<Progress> ATTACHMENT = AttachmentRegistry.<Progress>builder()
		.persistent(CODEC)
		.copyOnDeath()
		.buildAndRegister(PvpDimensions.id("onboarding"));

	/** Loads the class, which is what registers the attachment; it must happen at init. */
	public static void init() {}

	public static Progress of(ServerPlayer player) {
		return player.getAttachedOrElse(ATTACHMENT, NONE);
	}

	public boolean played(Demo demo) {
		return demo.finishes() ? finished.contains(demo.id()) : entered.contains(demo.id());
	}

	public static void offer(ServerPlayer player, Set<String> ids) {
		Progress now = of(player);
		player.setAttached(ATTACHMENT, new Progress(with(now.offered, ids), now.entered, now.finished));
	}

	public static void enter(ServerPlayer player, String id) {
		Progress now = of(player);
		player.setAttached(ATTACHMENT, new Progress(with(now.offered, Set.of(id)), with(now.entered, Set.of(id)), now.finished));
	}

	public static void finish(ServerPlayer player, String id) {
		Progress now = of(player);
		player.setAttached(ATTACHMENT, new Progress(now.offered, with(now.entered, Set.of(id)), with(now.finished, Set.of(id))));
	}

	private static Set<String> with(Set<String> ids, Set<String> more) {
		Set<String> out = new HashSet<>(ids);
		out.addAll(more);
		return Set.copyOf(out);
	}
}
