package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Gateways;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing but players crosses the edge of an arena that keeps inventories apart, whatever moves
 * it: a companion following its owner home, a whistle, another mod's teleport. What lives in such
 * an arena stays in it, and nothing from outside is brought in.
 *
 * <p>Here rather than on each thing that moves entities, because this is the one place every
 * move between dimensions goes through for anything that is not a player. Arenas share their
 * dimensions, so the same holds between one arena and the next: a pet snapping to its owner does
 * not jump from one plot to another, or out into the empty ground between them.
 */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "teleportCrossDimension", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$stayOnYourSide(ServerLevel oldLevel, ServerLevel newLevel, TeleportTransition transition,
			CallbackInfoReturnable<Entity> cir) {
		if (!Gateways.mayCross((Entity) (Object) this, oldLevel, newLevel, transition)) cir.setReturnValue(null);
	}

	@Inject(method = "snapTo(DDDFF)V", at = @At("HEAD"), cancellable = true)
	private void pvpDimensions$stayInYourArena(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
		if (!Gateways.maySnap((Entity) (Object) this, x, z)) ci.cancel();
	}
}
