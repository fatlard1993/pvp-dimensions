package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Hits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A thrown thing landing on a player, counted where the arena is counting them.
 *
 * <p>On {@link Projectile} rather than on snowballs, because the goal does not care what was
 * thrown: every throwable in the game runs its own hit through {@code super.onHitEntity}, so a
 * snowball, an egg and a handful of somebody's poop all arrive here the same way, and so will
 * whatever gets added next.
 *
 * <p>Nothing is cancelled. A snowball does no damage to a player anyway, and the ones that do are
 * still the arena's business to allow or refuse through {@code Combat} - this only watches.
 */
@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {

	@Inject(method = "onHitEntity", at = @At("HEAD"))
	private void pvpDimensions$countTheHit(EntityHitResult hitResult, CallbackInfo callback) {
		Projectile self = (Projectile) (Object) this;
		if (!(self.level() instanceof ServerLevel level)) return;
		if (!(hitResult.getEntity() instanceof ServerPlayer hit)) return;

		Entity owner = self.getOwner();
		if (!(owner instanceof ServerPlayer thrower)) return;

		Arena arena = Arenas.of(hit);
		if (arena == null || !Hits.counting(arena) || arena != Arenas.of(thrower)) return;
		Hits.landed(level.getServer(), arena, thrower, hit);
	}
}
