package org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation;

import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = {
        "net/minecraft/world/level/levelgen/material/condition/BiomeCondition$1",
        "net/minecraft/world/level/levelgen/material/condition/StoneDepthCondition$1",
        "net/minecraft/world/level/levelgen/material/condition/VerticalGradientCondition$1",
        "net/minecraft/world/level/levelgen/material/condition/WaterCondition$1",
        "net/minecraft/world/level/levelgen/material/condition/YCondition$1",
})
public abstract class SurfaceRulesMixin extends MaterialRuleContext.LazyYCondition {
    protected SurfaceRulesMixin(MaterialRuleContext context) {
        super(context);
    }

    /**
     * @author VoidsongDragonfly
     * @reason Replacing Vanilla's use of {@link MaterialRuleContext.LazyYCondition LazyYCondition} that causes performance
     * detriments due to unused caching behavior. The `lastUpdateY` field is updated every time the block position
     * changes (making the cache useful only within a single block), and the targeted condition objects are not interned
     * (meaning there is no caching happening anyway, as each instance uses its own cache).
     *
     */
    @Override
    public boolean test() {
        return compute();
    }
}
