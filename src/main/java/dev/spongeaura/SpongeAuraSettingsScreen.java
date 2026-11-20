package dev.spongeaura;

import dev.spongeaura.SpongeAuraConfig.EfficiencyMode;
import dev.spongeaura.SpongeAuraConfig.PlacementMode;
import dev.spongeaura.SpongeAuraConfig.RotationMode;
import dev.spongeaura.SpongeAuraConfig.WetSpongeBreakMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public class SpongeAuraSettingsScreen extends Screen {

    private static final String BRAND_TEXT = "✦ by David Matheja - David Industries™ ✦";
    private static final int[] BRAND_PALETTE = {
            0xFFD166, // Warm Gold
            0x55FFFF, // Aqua
            0xFF55CC, // Sponge Pink
            0xAA55FF  // Wet Purple
    };

    private final Screen parent;

    public SpongeAuraSettingsScreen(Screen parent) {
        super(Component.literal("SpongeAura Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SpongeAuraConfig cfg = SpongeAuraClient.getConfig();

        int colW = 152;
        int gap = 10;
        int rowH = (this.height < 280) ? 19 : 20;
        int rowStep = (this.height < 280) ? 21 : 23;
        int leftX = this.width / 2 - colW - gap / 2;
        int rightX = this.width / 2 + gap / 2;
        int startY = Math.max(47, Math.min(62, (this.height - rowStep * 6 - 44) / 2 + 18));

        // Row 0: Enabled & Efficiency Mode
        this.addRenderableWidget(
                CycleButton.onOffBuilder(SpongeAuraClient.isEnabled())
                        .withTooltip(v -> Tooltip.create(Component.literal("Turns SpongeAura on or off (Key: G).")))
                        .create(leftX, startY, colW, rowH, Component.literal("SpongeAura"),
                                (btn, value) -> SpongeAuraClient.setEnabled(value))
        );

        this.addRenderableWidget(
                CycleButton.<EfficiencyMode>builder(EfficiencyMode::displayName, cfg.efficiencyMode)
                        .withValues(EfficiencyMode.values())
                        .withTooltip(mode -> Tooltip.create(mode.tooltip()))
                        .create(rightX, startY, colW, rowH, Component.literal("Efficiency"),
                                (btn, value) -> {
                                    cfg.efficiencyMode = value;
                                    cfg.save();
                                })
        );

        // Row 1: Nether Dry & Through-Wall Highlight
        int y1 = startY + rowStep;
        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.netherAutoDry)
                        .withTooltip(v -> Tooltip.create(Component.literal(
                                "In the Nether (or any evaporating dimension), pressing G automatically places wet sponges and breaks the dried sponges with your best tool.")))
                        .create(leftX, y1, colW, rowH, Component.literal("Nether Dry"),
                                (btn, value) -> {
                                    cfg.netherAutoDry = value;
                                    cfg.save();
                                })
        );

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.highlightSponges)
                        .withTooltip(v -> Tooltip.create(Component.literal(
                                "Highlights dry (pink) and wet (purple) sponge blocks and dropped items through walls.")))
                        .create(rightX, y1, colW, rowH, Component.literal("Highlight"),
                                (btn, value) -> {
                                    cfg.highlightSponges = value;
                                    cfg.save();
                                    if (value) {
                                        SpongeHighlighter.requestRescan();
                                    } else {
                                        SpongeHighlighter.clear();
                                    }
                                })
        );

        // Row 2: Break Wet & Placement Mode
        int y2 = startY + rowStep * 2;
        this.addRenderableWidget(
                CycleButton.<WetSpongeBreakMode>builder(WetSpongeBreakMode::displayName, cfg.wetSpongeBreakMode)
                        .withValues(WetSpongeBreakMode.values())
                        .withTooltip(mode -> Tooltip.create(mode.tooltip()))
                        .create(leftX, y2, colW, rowH, Component.literal("Break Wet"),
                                (btn, value) -> {
                                    cfg.wetSpongeBreakMode = value;
                                    cfg.save();
                                })
        );

        this.addRenderableWidget(
                CycleButton.<PlacementMode>builder(PlacementMode::displayName, cfg.placementMode)
                        .withValues(PlacementMode.values())
                        .withTooltip(mode -> Tooltip.create(mode.tooltip()))
                        .create(rightX, y2, colW, rowH, Component.literal("Placement"),
                                (btn, value) -> {
                                    cfg.placementMode = value;
                                    cfg.save();
                                })
        );

        // Row 3: Rotation Mode & Auto-Refill
        int y3 = startY + rowStep * 3;
        this.addRenderableWidget(
                CycleButton.<RotationMode>builder(RotationMode::displayName, cfg.rotationMode)
                        .withValues(RotationMode.values())
                        .withTooltip(mode -> Tooltip.create(mode.tooltip()))
                        .create(leftX, y3, colW, rowH, Component.literal("Rotation"),
                                (btn, value) -> {
                                    cfg.rotationMode = value;
                                    cfg.save();
                                })
        );

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.autoRefill)
                        .withTooltip(v -> Tooltip.create(Component.literal(
                                "Automatically swaps sponges or filler blocks from your main inventory into the hotbar while protecting tools.")))
                        .create(rightX, y3, colW, rowH, Component.literal("Auto-Refill"),
                                (btn, value) -> {
                                    cfg.autoRefill = value;
                                    cfg.save();
                                })
        );

        // Row 4: Reach & Place Delay Sliders
        int y4 = startY + rowStep * 4;
        ReachSlider reachSlider = new ReachSlider(leftX, y4, colW, rowH, cfg);
        reachSlider.setTooltip(Tooltip.create(Component.literal(
                "Maximum distance from your eyes to target blocks (2.5 - 5.5 blocks).")));
        this.addRenderableWidget(reachSlider);

        DelaySlider delaySlider = new DelaySlider(rightX, y4, colW, rowH, cfg);
        delaySlider.setTooltip(Tooltip.create(Component.literal(
                "Cooldown ticks between placements (0 = fastest for bursting water repair and Nether drying).")));
        this.addRenderableWidget(delaySlider);

        // Row 5: Fill Tiny (1-5) & Particles
        int y5 = startY + rowStep * 5;
        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.fillTinyWithBlocks)
                        .withTooltip(v -> Tooltip.create(Component.literal(
                                "Uses netherrack, dirt, or cobblestone instead of sponges to clear tiny 1-5 block water sources.")))
                        .create(leftX, y5, colW, rowH, Component.literal("Fill Tiny (1-5)"),
                                (btn, value) -> {
                                    cfg.fillTinyWithBlocks = value;
                                    cfg.save();
                                })
        );

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.particles)
                        .withTooltip(v -> Tooltip.create(Component.literal(
                                "Spawns subtle client-side particles when placing, breaking, filling, or drying sponges.")))
                        .create(rightX, y5, colW, rowH, Component.literal("Particles"),
                                (btn, value) -> {
                                    cfg.particles = value;
                                    cfg.save();
                                })
        );

        // Footer: Reset Defaults, Work Area & Done
        int footerY = Math.max(startY + rowStep * 6 + 6, this.height - 26);
        this.addRenderableWidget(
                Button.builder(Component.literal("Reset Defaults"), btn -> {
                            cfg.resetDefaults();
                            SpongeHighlighter.requestRescan();
                            this.minecraft.gui.setScreen(new SpongeAuraSettingsScreen(this.parent));
                        })
                        .bounds(leftX, footerY, 98, rowH)
                        .tooltip(Tooltip.create(Component.literal("Restore all settings to recommended defaults.")))
                        .build()
        );

        String areaLabel = cfg.areaLimitEnabled ? "Work Area: Bounded" : "Work Area: Always";
        String areaTip = cfg.areaLimitEnabled
                ? "Bounded to X [" + cfg.areaMinX() + ".." + cfg.areaMaxX() + "], Z ["
                        + cfg.areaMinZ() + ".." + cfg.areaMaxZ() + "] (all Y levels). Click to edit."
                : "Currently Always On (works everywhere). Click to configure horizontal X/Z bounds.";
        this.addRenderableWidget(
                Button.builder(Component.literal(areaLabel), btn ->
                                this.minecraft.gui.setScreen(new SpongeAuraAreaScreen(this)))
                        .bounds(leftX + 104, footerY, 106, rowH)
                        .tooltip(Tooltip.create(Component.literal(areaTip)))
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Done"), btn -> this.onClose())
                        .bounds(leftX + 216, footerY, 98, rowH)
                        .build()
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, "§b§lSpongeAura Settings §8v2.3.0", this.width / 2, 6, 0xFFFFFFFF);

        renderBrandBanner(guiGraphics, mouseX, mouseY);

        String stateText = SpongeAuraClient.isEnabled() ? "§aActive" : "§cInactive";
        String subtitle = "Status: " + stateText + "  §8|  §7G Toggle · H Mode · J Settings";
        guiGraphics.centeredText(this.font, subtitle, this.width / 2, 34, 0xFFAAAAAA);
    }

    private void renderBrandBanner(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int textW = this.font.width(BRAND_TEXT);
        int pillW = textW + 14;
        int pillH = 14;
        int pillX = this.width / 2 - pillW / 2;
        int pillY = 17;

        boolean hovered = mouseX >= pillX && mouseX <= pillX + pillW
                && mouseY >= pillY && mouseY <= pillY + pillH;

        double phase = (System.currentTimeMillis() % 60000L) / 850.0;
        int borderRgb = samplePalette(phase);
        int borderColor = (hovered ? 0xFF000000 : 0xB8000000) | borderRgb;
        int bgColor = hovered ? 0xE01A1E33 : 0xCC121420;

        guiGraphics.fill(pillX + 1, pillY + 1, pillX + pillW - 1, pillY + pillH - 1, bgColor);
        guiGraphics.fill(pillX, pillY, pillX + pillW, pillY + 1, borderColor);
        guiGraphics.fill(pillX, pillY + pillH - 1, pillX + pillW, pillY + pillH, borderColor);
        guiGraphics.fill(pillX, pillY + 1, pillX + 1, pillY + pillH - 1, borderColor);
        guiGraphics.fill(pillX + pillW - 1, pillY + 1, pillX + pillW, pillY + pillH - 1, borderColor);

        int drawX = this.width / 2 - textW / 2;
        int drawY = pillY + 3;
        for (int i = 0; i < BRAND_TEXT.length(); i++) {
            String ch = BRAND_TEXT.substring(i, i + 1);
            int argb = 0xFF000000 | samplePalette(phase - i * 0.14);
            guiGraphics.text(this.font, ch, drawX, drawY, argb, true);
            drawX += this.font.width(ch);
        }

        if (hovered) {
            guiGraphics.setTooltipForNextFrame(
                    this.font,
                    Component.literal("Engineered by David Matheja — David Industries™ (v2.3.0)"),
                    mouseX,
                    mouseY
            );
        }
    }

    private static int samplePalette(double t) {
        int len = BRAND_PALETTE.length;
        double wrapped = ((t % len) + len) % len;
        int idx = (int) wrapped;
        double frac = wrapped - idx;

        int c0 = BRAND_PALETTE[idx];
        int c1 = BRAND_PALETTE[(idx + 1) % len];

        int r = (int) Math.round(((c0 >> 16) & 0xFF) * (1.0 - frac) + ((c1 >> 16) & 0xFF) * frac);
        int g = (int) Math.round(((c0 >> 8) & 0xFF) * (1.0 - frac) + ((c1 >> 8) & 0xFF) * frac);
        int b = (int) Math.round((c0 & 0xFF) * (1.0 - frac) + (c1 & 0xFF) * frac);
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public void onClose() {
        SpongeAuraClient.getConfig().save();
        this.minecraft.gui.setScreen(this.parent);
    }

    private static class ReachSlider extends AbstractSliderButton {
        private static final double MIN = 2.5;
        private static final double MAX = 5.5;
        private final SpongeAuraConfig cfg;

        ReachSlider(int x, int y, int w, int h, SpongeAuraConfig cfg) {
            super(x, y, w, h, Component.empty(), (cfg.reach - MIN) / (MAX - MIN));
            this.cfg = cfg;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double val = Math.round((MIN + this.value * (MAX - MIN)) * 10.0) / 10.0;
            this.setMessage(Component.literal(String.format(Locale.ROOT, "Reach: %.1f", val)));
        }

        @Override
        protected void applyValue() {
            cfg.reach = Math.round((MIN + this.value * (MAX - MIN)) * 10.0) / 10.0;
            cfg.save();
        }
    }

    private static class DelaySlider extends AbstractSliderButton {
        private static final int MIN = 0;
        private static final int MAX = 10;
        private final SpongeAuraConfig cfg;

        DelaySlider(int x, int y, int w, int h, SpongeAuraConfig cfg) {
            super(x, y, w, h, Component.empty(), (double) (cfg.placeDelayTicks - MIN) / (MAX - MIN));
            this.cfg = cfg;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = (int) Math.round(MIN + this.value * (MAX - MIN));
            this.setMessage(Component.literal("Place Delay: " + val + "t"));
        }

        @Override
        protected void applyValue() {
            cfg.placeDelayTicks = (int) Math.round(MIN + this.value * (MAX - MIN));
            cfg.save();
        }
    }
}
