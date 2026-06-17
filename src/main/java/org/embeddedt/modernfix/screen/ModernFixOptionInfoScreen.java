package org.embeddedt.modernfix.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class ModernFixOptionInfoScreen extends Screen {
    private final Screen lastScreen;
    private final Component description;

    public ModernFixOptionInfoScreen(Screen lastScreen, String optionName) {
        super(Component.literal(optionName));

        this.lastScreen = lastScreen;
        String key = "modernfix.option." + optionName;
        String localized = I18n.get(key);
        if(localized == null || localized.isBlank() || localized.equals(key))
            this.description = Component.translatable("modernfix.option.no_description", optionName);
        else
            this.description = Component.translatable(key);
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(new Button.Builder(CommonComponents.GUI_DONE, (button) -> {
            this.onClose();
        }).pos(this.width / 2 - 100, this.height - 29).size(200, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(lastScreen);
    }

    private void drawMultilineString(GuiGraphicsExtractor guiGraphics, Font fr, Component str, int x, int y) {
        for(FormattedCharSequence s : fr.split(str, this.width - 50)) {
            guiGraphics.text(fr, s, x, y, -1, true);
            y += fr.lineHeight;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTicks);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 8, -1);
        this.drawMultilineString(guiGraphics, this.minecraft.font, description, 10, 50);
    }
}
