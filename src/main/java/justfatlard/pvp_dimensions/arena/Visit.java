package justfatlard.pvp_dimensions.arena;

import java.util.List;
import java.util.Optional;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import justfatlard.pvp_dimensions.PvpDimensions;
import justfatlard.pvp_dimensions.preset.Kit;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A player's time in an arena, kept on the player: which arena, where they came from, and what
 * they were carrying when they went in, added slots included.
 *
 * <p>On the player rather than in the arena's records because the two must never disagree about
 * a pack: the player's own save file holds the stash and the inventory side by side, written
 * together, so a crash can lose neither and double neither. It rides through deaths too, since a
 * death in an arena is how a lot of visits end.
 */
public record Visit(String arena, Home home, Optional<Kit> stash, Optional<Experience> experience,
		Optional<String> team, Optional<String> gameMode, Optional<List<AddedSlots.Held>> added) {

	public record Experience(int levels, float progress, int total) {
		static final Codec<Experience> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("levels").forGetter(Experience::levels),
			Codec.FLOAT.fieldOf("progress").forGetter(Experience::progress),
			Codec.INT.fieldOf("total").forGetter(Experience::total)
		).apply(instance, Experience::new));

		public static Experience of(ServerPlayer player) {
			return new Experience(player.experienceLevel, player.experienceProgress, player.totalExperience);
		}

		public void restore(ServerPlayer player) {
			player.experienceLevel = levels;
			player.experienceProgress = progress;
			player.totalExperience = total;
			player.setExperienceLevels(levels);
			player.setExperiencePoints((int) (progress * player.getXpNeededForNextLevel()));
		}

		public static void clear(ServerPlayer player) {
			new Experience(0, 0, 0).restore(player);
		}
	}

	/** Where to put somebody back. */
	public record Home(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Home::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(Home::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Home::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Home::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(Home::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Home::pitch)
		).apply(instance, Home::new));

		public static Home of(ServerPlayer player) {
			return new Home(player.level().dimension(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		}
	}

	public static final Codec<Visit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("arena").forGetter(Visit::arena),
		Home.CODEC.fieldOf("home").forGetter(Visit::home),
		Kit.CODEC.optionalFieldOf("stash").forGetter(Visit::stash),
		Experience.CODEC.optionalFieldOf("experience").forGetter(Visit::experience),
		Codec.STRING.optionalFieldOf("team").forGetter(Visit::team),
		Codec.STRING.optionalFieldOf("game_mode").forGetter(Visit::gameMode),
		// Absent on visits begun before added slots were put aside, whose slots are then left alone.
		AddedSlots.Held.CODEC.listOf().optionalFieldOf("added").forGetter(Visit::added)
	).apply(instance, Visit::new));

	public static final AttachmentType<Visit> ATTACHMENT = AttachmentRegistry.<Visit>builder()
		.persistent(CODEC)
		.copyOnDeath()
		.buildAndRegister(PvpDimensions.id("visit"));

	/** Loads the class, which is what registers the attachment; it must happen at init. */
	public static void init() {}

	public static @Nullable Visit of(ServerPlayer player) {
		return player.getAttached(ATTACHMENT);
	}

	public static void set(ServerPlayer player, @Nullable Visit visit) {
		if (visit == null) player.removeAttached(ATTACHMENT);
		else player.setAttached(ATTACHMENT, visit);
	}

	public Visit withTeam(@Nullable String previous) {
		return new Visit(arena, home, stash, experience, Optional.ofNullable(previous), gameMode, added);
	}
}
