package org.embeddedt.modernfix.common.mixin.perf.dynamic_resources;

import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@ClientOnlyMixin
@Mixin(ModelDiscovery.ModelWrapper.class)
public interface ModelWrapperInvoker {
    @Invoker("<init>")
    static ModelDiscovery.ModelWrapper mfix$invokeCtor(Identifier identifier, UnbakedModel unbakedModel, boolean bl) {
        throw new RuntimeException("Invoker didn't work?");
    }
}
