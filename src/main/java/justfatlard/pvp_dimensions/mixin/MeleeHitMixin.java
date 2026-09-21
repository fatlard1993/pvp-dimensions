package justfatlard.pvp_dimensions.mixin;

import justfatlard.pvp_dimensions.arena.Arena;
import justfatlard.pvp_dimensions.arena.Arenas;
import justfatlard.pvp_dimensions.arena.Hits;
import justfatlard.pvp_dimensions.preset.Preset;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A swing landing on a player, counted where the arena is boxing.
 *
 * <p>At the head of {@code attack}, which is deliberately before the damage. Vanilla works out the
 * blow and then offers it to {@code hurtOrSimulate}, which is where the arena's pvp rule refuses
 * it; a refusal there makes {@code wasHurt} false and plays the no-damage sound. Counting from the
 * head means the count does not depend on the damage being allowed, so a boxing match can be run
 * with the arena's pvp switched off and nobody able to hurt anybody - which is the whole point of
 * a goal that counts hits rather than harm. Watching {@code AFTER_DAMAGE} instead would have tied
 * the two back together and left that match unplayable.
 *
 * <p>Only a full-strength swing counts. Vanilla's own line for that is an attack strength above
 * 0.9, the same test it uses for crits and sweeps, so the cooldown means something here too and
 * the match is not won by whoever clicks fastest.
 *
 * <p>Nothing is cancelled: whether the blow is allowed to hurt stays entirely {@code Combat}'s
 * business, and this only watches.
 */
@Mixin(Player.class)
public abstract class MeleeHitMixin {

	/** Vanilla's own threshold for a swing that has finished recharging. */
	private static final float FULL_STRENGTH = 0.9F;

	@Inject(method = "attack", at = @At("HEAD"))
	private void pvpDimensions$countTheSwing(Entity target, CallbackInfo callback) {
		Player self = (Player) (Object) this;
		if (!(self instanceof ServerPlayer swinger)) return;
		if (!(self.level() instanceof ServerLevel level)) return;
		if (!(target instanceof ServerPlayer hit)) return;
		// The gate vanilla checks first; without it a swing at somebody who cannot be attacked
		// would score where vanilla would not even have swung.
		if (!target.isAttackable()) return;
		if (self.getAttackStrengthScale(0.5F) <= FULL_STRENGTH) return;

		Arena arena = Arenas.of(hit);
		if (arena == null || !Hits.counts(arena.preset, Preset.HitKind.MELEE) || arena != Arenas.of(swinger)) return;
		Hits.landed(level.getServer(), arena, swinger, hit);
	}
}
