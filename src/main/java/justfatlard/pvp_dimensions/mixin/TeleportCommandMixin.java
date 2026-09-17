package justfatlard.pvp_dimensions.mixin;

import java.util.Set;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import justfatlard.pvp_dimensions.arena.Travel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LookAt;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An arena with a door on it is not entered by {@code /tp}.
 *
 * <p>The command is failed outright rather than allowed and undone: whoever typed it is the one
 * who can do something about it, and a command that says "Teleported BennyW2020" while nothing
 * happens is worse than one that says why not.
 */
@Mixin(TeleportCommand.class)
public class TeleportCommandMixin {

	private static final DynamicCommandExceptionType UNINVITED =
		new DynamicCommandExceptionType(reason -> Component.literal(String.valueOf(reason)));

	@Inject(method = "performTeleport", at = @At("HEAD"))
	private static void pvpDimensions$refuseUninvited(CommandSourceStack source, Entity victim, ServerLevel level,
			double x, double y, double z, Set<Relative> relatives, float yRot, float xRot, @Nullable LookAt lookAt,
			CallbackInfo callback) throws CommandSyntaxException {
		if (!(victim instanceof ServerPlayer player)) return;
		// x and z are the destination itself: the command works out its own relative offsets after
		// this point, from these same absolute numbers.
		String refused = Travel.uninvited(player, level, x, z);
		if (refused != null) throw UNINVITED.create(refused);
	}
}
