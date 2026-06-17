package org.embeddedt.modernfix.common.mixin.perf.dynamic_resources;

import net.minecraft.client.resources.model.sprite.TextureSlots;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureSlots.class)
@ClientOnlyMixin
public class MixinTextureSlots {
    /**
     * @author coredex-source
     * @reason Return false instead of throwing StringIndexOutOfBoundsException to prevent resource reload from crashing when model points to empty texture string.
     */
    @Inject(method = "isTextureReference", at = @At("HEAD"), cancellable = true)
    private static void mfix$handleEmptyTextureReference(String string, CallbackInfoReturnable<Boolean> cir) {
        if (string == null || string.isEmpty()) {
            cir.setReturnValue(false);
        }
    }
}
