package justfatlard.pvp_dimensions.mixin;

import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lit TNT's owner, which the game only sets for whoever lit it by hand: the rest is the placer's. */
@Mixin(PrimedTnt.class)
public interface PrimedTntAccessor {
	@Accessor("owner")
	void pvpDimensions$setOwner(EntityReference<LivingEntity> owner);
}
