package org.embeddedt.modernfix.common.mixin.devenv;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Minecraft.class)
@ClientOnlyMixin
public class MinecraftMixin {
    /**
     * @author embeddedt
     * @reason avoid exception stacktrace being printed in dev
     */
    @Overwrite
    private static UserApiService createUserApiService(MinecraftServicesDiscoveryService servicesDiscoveryService, GameConfig arg) {
        return UserApiService.OFFLINE;
    }
}
