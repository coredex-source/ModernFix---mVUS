package org.embeddedt.modernfix.common.mixin.perf.thread_priorities;

import net.minecraft.client.Minecraft;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
@ClientOnlyMixin
public class IntegratedServerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void adjustServerPriority(Thread serverThread, Minecraft minecraft, LevelStorageSource.LevelStorageAccess storageSource, PackRepository packRepository, WorldStem worldStem, Services services, LevelLoadListener levelLoadListener, CallbackInfo ci) {
        int pri = 4;
        serverThread.setPriority(pri);
    }
}
