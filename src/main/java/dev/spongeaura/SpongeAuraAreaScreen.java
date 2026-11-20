package dev.spongeaura;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SpongeAuraAreaScreen extends Screen {

    private final Screen parent;
    private boolean localBounded;
    private String errorMessage = null;

    private CycleButton<Boolean> areaModeButton;
    private EditBox x1Box;
    private EditBox z1Box;
    private EditBox x2Box;
    private EditBox z2Box;

    public SpongeAuraAreaScreen(Screen parent) {
        super(Component.literal("SpongeAura Work Area"));
        this.parent = parent;
        this.localBounded = SpongeAuraClient.getConfig().areaLimitEnabled;
    }

    @Override
    protected void init() {
        SpongeAuraConfig cfg = SpongeAuraClient.getConfig();
        int leftX = this.width / 2 - 157;
        boolean hasPlayer = (this.minecraft != null && this.minecraft.player != null);

        this.areaModeButton = this.addRenderableWidget(
                CycleButton.booleanBuilder(Component.literal("Bounded (X/Z)"), Component.literal("Always On"), localBounded)
                        .withTooltip(b -> Tooltip.create(Component.literal(
                                "Bounded restricts SpongeAura horizontally to X/Z bounds (all Y levels). Always On works everywhere.")))
                        .create(leftX, 40, 314, 20, Component.literal("Area Mode"),
                                (btn, val) -> {
                                    this.localBounded = val;
                                    this.errorMessage = null;
                                })
        );

        String prevX1 = (x1Box != null) ? x1Box.getValue() : String.valueOf(cfg.areaX1);
        String prevZ1 = (z1Box != null) ? z1Box.getValue() : String.valueOf(cfg.areaZ1);
        String prevX2 = (x2Box != null) ? x2Box.getValue() : String.valueOf(cfg.areaX2);
        String prevZ2 = (z2Box != null) ? z2Box.getValue() : String.valueOf(cfg.areaZ2);

        this.x1Box = createCoordBox(leftX, 74, "X1", prevX1);
        this.z1Box = createCoordBox(leftX + 80, 74, "Z1", prevZ1);
        Button corner1Btn = this.addRenderableWidget(
                Button.builder(Component.literal("Set Corner 1 (Here)"), btn -> {
                            if (this.minecraft != null && this.minecraft.player != null) {
                                x1Box.setValue(String.valueOf(this.minecraft.player.getBlockX()));
                                z1Box.setValue(String.valueOf(this.minecraft.player.getBlockZ()));
                                setBoundedState(true);
                                this.errorMessage = null;
                            }
                        })
                        .bounds(leftX + 162, 74, 152, 20)
                        .tooltip(Tooltip.create(Component.literal("Sets Corner 1 X/Z to your current block position.")))
                        .build()
        );
        corner1Btn.active = hasPlayer;

        this.x2Box = createCoordBox(leftX, 108, "X2", prevX2);
        this.z2Box = createCoordBox(leftX + 80, 108, "Z2", prevZ2);
        Button corner2Btn = this.addRenderableWidget(
                Button.builder(Component.literal("Set Corner 2 (Here)"), btn -> {
                            if (this.minecraft != null && this.minecraft.player != null) {
                                x2Box.setValue(String.valueOf(this.minecraft.player.getBlockX()));
                                z2Box.setValue(String.valueOf(this.minecraft.player.getBlockZ()));
                                setBoundedState(true);
                                this.errorMessage = null;
                            }
                        })
                        .bounds(leftX + 162, 108, 152, 20)
                        .tooltip(Tooltip.create(Component.literal("Sets Corner 2 X/Z to your current block position.")))
                        .build()
        );
        corner2Btn.active = hasPlayer;

        Button center128Btn = this.addRenderableWidget(
                Button.builder(Component.literal("Center ±128 (Here)"), btn -> applyCenterPreset(128))
                        .bounds(leftX, 150, 152, 20)
                        .tooltip(Tooltip.create(Component.literal("Sets a 257×257 X/Z box centered on you and enables Bounded.")))
                        .build()
        );
        center128Btn.active = hasPlayer;

        Button center64Btn = this.addRenderableWidget(
                Button.builder(Component.literal("Center ±64 (Here)"), btn -> applyCenterPreset(64))
                        .bounds(leftX + 162, 150, 152, 20)
                        .tooltip(Tooltip.create(Component.literal("Sets a 129×129 X/Z box centered on you and enables Bounded.")))
                        .build()
        );
        center64Btn.active = hasPlayer;

        int footerY = Math.max(182, this.height - 28);
        this.addRenderableWidget(
                Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                        .bounds(leftX, footerY, 152, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(Component.literal("Save & Back"), btn -> saveAndClose())
                        .bounds(leftX + 162, footerY, 152, 20)
                        .build()
        );
    }

    private EditBox createCoordBox(int x, int y, String label, String value) {
        EditBox box = new EditBox(this.font, x, y, 72, 20, Component.literal(label));
        box.setMaxLength(12);
        box.setHint(Component.literal(label));
        box.setValue(value);
        box.setResponder(s -> this.errorMessage = null);
        return this.addRenderableWidget(box);
    }

    private void applyCenterPreset(int radius) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        int px = this.minecraft.player.getBlockX();
        int pz = this.minecraft.player.getBlockZ();
        x1Box.setValue(String.valueOf(px - radius));
        z1Box.setValue(String.valueOf(pz - radius));
        x2Box.setValue(String.valueOf(px + radius));
        z2Box.setValue(String.valueOf(pz + radius));
        setBoundedState(true);
        this.errorMessage = null;
    }

    private void setBoundedState(boolean bounded) {
        this.localBounded = bounded;
        if (this.areaModeButton != null) {
            this.areaModeButton.setValue(bounded);
        }
    }

    private void saveAndClose() {
        try {
            int x1 = Integer.parseInt(x1Box.getValue().trim());
            int z1 = Integer.parseInt(z1Box.getValue().trim());
            int x2 = Integer.parseInt(x2Box.getValue().trim());
            int z2 = Integer.parseInt(z2Box.getValue().trim());

            SpongeAuraConfig cfg = SpongeAuraClient.getConfig();
            cfg.areaLimitEnabled = localBounded;
            cfg.areaX1 = x1;
            cfg.areaZ1 = z1;
            cfg.areaX2 = x2;
            cfg.areaZ2 = z2;
            cfg.save();

            this.minecraft.gui.setScreen(this.parent);
        } catch (NumberFormatException e) {
            this.errorMessage = "§cPlease enter valid integer coordinates.";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int leftX = this.width / 2 - 157;

        guiGraphics.centeredText(this.font, "§b§lWork Area Bounds §8(Horizontal X / Z)", this.width / 2, 10, 0xFFFFFFFF);
        guiGraphics.centeredText(
                this.font,
                "No vertical (Y) limit — protects your base from accidental draining",
                this.width / 2,
                22,
                0xFFAAAAAA
        );

        guiGraphics.text(this.font, "X1", leftX, 63, 0xFFCCCCCC, true);
        guiGraphics.text(this.font, "Z1", leftX + 80, 63, 0xFFCCCCCC, true);
        guiGraphics.text(this.font, "X2", leftX, 97, 0xFFCCCCCC, true);
        guiGraphics.text(this.font, "Z2", leftX + 80, 97, 0xFFCCCCCC, true);

        String summary = buildSummaryText();
        guiGraphics.centeredText(this.font, summary, this.width / 2, 134, 0xFFFFFFFF);
    }

    private String buildSummaryText() {
        if (errorMessage != null) {
            return errorMessage;
        }
        try {
            int x1 = Integer.parseInt(x1Box.getValue().trim());
            int z1 = Integer.parseInt(z1Box.getValue().trim());
            int x2 = Integer.parseInt(x2Box.getValue().trim());
            int z2 = Integer.parseInt(z2Box.getValue().trim());
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);
            long w = (long) maxX - minX + 1L;
            long d = (long) maxZ - minZ + 1L;
            if (localBounded) {
                return "§aActive Area: §fX [" + minX + " .. " + maxX + "]  Z [" + minZ + " .. " + maxZ
                        + "] §7(" + w + "×" + d + ", all Y)";
            }
            return "§7Saved Bounds: X [" + minX + " .. " + maxX + "]  Z [" + minZ + " .. " + maxZ
                    + "] §8(currently Always On)";
        } catch (NumberFormatException e) {
            return "§cEnter valid integer coordinates";
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
