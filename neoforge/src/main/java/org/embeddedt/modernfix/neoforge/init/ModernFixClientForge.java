package org.embeddedt.modernfix.neoforge.init;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.ModernFixClient;
import org.embeddedt.modernfix.screen.ModernFixConfigScreen;

public class ModernFixClientForge {
    private static ModernFixClient commonMod;
    public static KeyMapping.Category MODERNFIX_KEYS = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ModernFix.MODID, "key.modernfix"));

    public ModernFixClientForge(ModContainer modContainer, IEventBus modBus) {
        commonMod = new ModernFixClient();
        modBus.addListener(this::keyBindRegister);
        modBus.addListener(this::onClientSetup);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, (mc, screen) -> new ModernFixConfigScreen(screen));
    }

    private KeyMapping configKey;

    private void keyBindRegister(RegisterKeyMappingsEvent event) {
        configKey = new KeyMapping("key.modernfix.config", KeyConflictContext.UNIVERSAL, InputConstants.UNKNOWN, MODERNFIX_KEYS);
        event.register(configKey);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if(false) {
            event.enqueueWork(() -> {
                ModLoader.addLoadingIssue(ModLoadingIssue.warning("modernfix.connectedness_dynresoruces"));
            });
        }
    }

    @SubscribeEvent
    public void onConfigKey(ClientTickEvent.Pre event) {
        if(configKey != null && configKey.consumeClick()) {
            Minecraft.getInstance().setScreen(new ModernFixConfigScreen(Minecraft.getInstance().screen));
        }
    }

    @SubscribeEvent
    public void onDisconnect(LevelEvent.Unload event) {
        if(event.getLevel().isClientSide()) {
            DebugScreenOverlay overlay = Minecraft.getInstance().getDebugOverlay();
            Minecraft.getInstance().execute(overlay::clearChunkCache);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartedEvent event) {
        commonMod.onServerStarted(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderTickEnd(RenderFrameEvent.Post event) {
        commonMod.onRenderTickEnd();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTags(TagsUpdatedEvent e) {
        commonMod.onTagsUpdated();
    }
}
