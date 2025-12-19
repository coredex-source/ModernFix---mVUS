package org.embeddedt.modernfix.screen;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.ModernFixClient;
import org.embeddedt.modernfix.common.mixin.feature.branding.DebugScreenEntriesInvoker;
import org.jetbrains.annotations.Nullable;

public class ModernFixDebugScreen {
    public static final Identifier MODERNFIX_GROUP = Identifier.fromNamespaceAndPath(ModernFix.MODID, "modernfix_info");
    public static Identifier MODERNFIX_ENTRY = DebugScreenEntriesInvoker.mfix$register(
            MODERNFIX_GROUP,
            new ModernFixDebugEntry()
    );

    static class ModernFixDebugEntry implements DebugScreenEntry {
        @Override
        public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            displayer.addToGroup(MODERNFIX_GROUP, ModernFixClient.INSTANCE.brandingString);
        }

        @Override
        public boolean isAllowed(boolean reducedDebugInfo) {
            return true;
        }
    }
}
