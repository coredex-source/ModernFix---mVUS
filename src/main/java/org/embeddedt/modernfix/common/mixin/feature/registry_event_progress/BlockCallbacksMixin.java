package org.embeddedt.modernfix.common.mixin.feature.registry_event_progress;

import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(targets = {"net/neoforged/neoforge/registries/NeoForgeRegistryCallbacks$BlockCallbacks"})
public class BlockCallbacksMixin {
    @Shadow @Final @Mutable
    private Set<Block> addedBlocks;

    @Unique
    private ProgressMeter modernfix$bakeProgress;

    /**
     * @author embeddedt
     * @reason Use an ordered set to make the baking order more predictable for users watching the splash screen
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void useOrderedSet(CallbackInfo ci) {
        this.addedBlocks = new ReferenceLinkedOpenHashSet<>(this.addedBlocks);
    }

    @Inject(method = "onBake", at = @At("HEAD"))
    private void startBakeProgress(CallbackInfo ci) {
        this.modernfix$bakeProgress = StartupNotificationManager.prependProgressBar("Build blockstate caches", addedBlocks.size());
    }

    @Inject(method = "lambda$onBake$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getStateDefinition()Lnet/minecraft/world/level/block/state/StateDefinition;", ordinal = 0))
    private void showBakeProgressPerBlock(CallbackInfo ci, @Local(ordinal = 0) Block block) {
        var id = block.builtInRegistryHolder().getKey().identifier();
        this.modernfix$bakeProgress.label("Build blockstate caches - " + id.toString());
        this.modernfix$bakeProgress.increment();
    }

    @Inject(method = "lambda$onBake$0", at = @At(value = "INVOKE", target = "Ljava/util/Set;clear()V", ordinal = 0))
    private void stopBakeProgress(CallbackInfo ci) {
        this.modernfix$bakeProgress.complete();
        this.modernfix$bakeProgress = null;
    }
}
