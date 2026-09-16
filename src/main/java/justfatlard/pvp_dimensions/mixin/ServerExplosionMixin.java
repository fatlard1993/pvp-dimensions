package justfatlard.pvp_dimensions.mixin;

import java.util.ArrayList;
import java.util.List;
import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Goals;
import justfatlard.pvp_dimensions.arena.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A blast in an arena leaves standing what nobody may break there: a wall that holds until it
 * falls, a team's chest, a flag chest, the hill.
 */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {
	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
	private void pvpDimensions$sparing(CallbackInfoReturnable<List<BlockPos>> cir) {
		ServerLevel level = ((ServerExplosion) (Object) this).level();
		if (!Places.isArena(level.dimension())) return;
		List<BlockPos> blown = new ArrayList<>(cir.getReturnValue());
		if (blown.removeIf(pos -> {
			Arena arena = Arenas.at(level, pos.getX(), pos.getZ());
			return arena != null && Goals.protectedBlock(arena, pos, null);
		})) cir.setReturnValue(blown);
	}
}
