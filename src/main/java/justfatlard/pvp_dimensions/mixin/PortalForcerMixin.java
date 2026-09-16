package justfatlard.pvp_dimensions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import justfatlard.pvp_dimensions.arena.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A lit gate is not somewhere a trip from the nether comes out: arriving in one would put a
 * traveller a step from an arena they never asked to join.
 */
@Mixin(PortalForcer.class)
public class PortalForcerMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@WrapOperation(method = "findClosestPortalPosition", at = @At(value = "INVOKE",
		target = "Ljava/util/stream/Stream;min(Ljava/util/Comparator;)Ljava/util/Optional;"))
	private Optional<BlockPos> pvpDimensions$notThroughGates(Stream<BlockPos> candidates, Comparator<? super BlockPos> nearest,
			Operation<Optional<BlockPos>> original) {
		return original.call(candidates.filter(pos -> Portals.gateAt(level, pos) == null), nearest);
	}
}
