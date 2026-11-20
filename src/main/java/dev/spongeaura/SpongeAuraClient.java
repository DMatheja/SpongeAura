package dev.spongeaura;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spongeaura.SpongeAuraConfig.EfficiencyMode;
import dev.spongeaura.SpongeAuraConfig.PlacementMode;
import dev.spongeaura.SpongeAuraConfig.RotationMode;
import dev.spongeaura.SpongeAuraConfig.WetSpongeBreakMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * SpongeAura v2.3.0 for Minecraft 26.3 (Fabric, Mojang Names, Java 25).
 */
public class SpongeAuraClient implements ClientModInitializer {

    private static final int TINY_POCKET_MAX_SOURCES = 5;
    private static final int MAX_EFFICIENCY_MIN_SOURCES = 48;
    private static final double MAX_EFFICIENCY_MIN_RETENTION = 0.65;
    private static final int PRACTICAL_MIN_SOURCES = 22;
    private static final double PRACTICAL_MIN_RETENTION = 0.50;

    private static final int WATER_TICK_DELAY = 5;
    private static final int MAX_BURST_CAP = 4;
    private static final int BURST_SHORTLIST_LIMIT = 8;

    private static final int PREDICTION_TTL_TICKS = 12;
    private static final int RECENT_PLACE_TTL_TICKS = 6;
    private static final int REFILL_COOLDOWN_TICKS = 4;
    private static final int MAX_SPONGE_ABSORB = 65;
    private static final int MAX_SPONGE_DEPTH = 6;

    private static final Item[] FILLER_PRIORITY = {
            Items.NETHERRACK, Items.DIRT, Items.COBBLESTONE
    };

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final Direction[] WATER_EXPOSURE_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spongeaura", "general"));

    private static KeyMapping toggleKey;
    private static KeyMapping modeKey;
    private static KeyMapping settingsKey;

    private static SpongeAuraConfig config = new SpongeAuraConfig();
    private static SpongeAuraClient instance;

    private boolean enabled = false;
    private boolean notifiedEmpty = false;
    private boolean notifiedNoFiller = false;
    private boolean notifiedNoSafeSpot = false;
    private boolean notifiedOutsideArea = false;
    private boolean triggeredStackJoke = false;
    private boolean internalPlacement = false;
    private long tickCounter = 0;
    private long lastManualInterceptTick = -1;
    private int cooldown = 0;
    private int refillCooldown = 0;

    private BlockPos breakingPos = null;
    private BlockPos netherBreakPos = null;
    private int sessionPlaced = 0;
    private int sessionFilled = 0;
    private int sessionDrained = 0;
    private int sessionBroken = 0;
    private int sessionDried = 0;

    // Locally predicted dried/filled positions: BlockPos.asLong() -> expiration tick
    private final Map<Long, Long> predictedDry = new HashMap<>();
    // Recently placed sponge positions so auto-break waits for absorption to settle
    private final Map<Long, Long> recentlyPlaced = new HashMap<>();

    private record BfsNode(BlockPos pos, int depth) {}
    private record BfsResult(Set<Long> clearedAll, Set<Long> clearedSources) {}
    private record RepairResult(Set<Long> repairedAll, int repairedSources) {}
    private record ValidSpot(BlockPos pos, BlockHitResult hit) {}
    private record RepairCandidate(ValidSpot spot, BfsResult bfs, RepairResult repair, double rawScore) {}

    private enum PlacementKind {
        SPONGE,
        FILL_BLOCK
    }

    private record Candidate(
            PlacementKind kind,
            BlockPos targetPos,
            BlockHitResult hit,
            Set<Long> clearedAll,
            int netSources,
            int tier,
            double score
    ) {}

    private record PlacementSearchResult(Candidate best, boolean sawTinyNeedingFiller) {}

    private enum SpongeReady {
        READY,
        REFILLED_WAIT,
        NONE
    }

    public static SpongeAuraConfig getConfig() {
        return config;
    }

    public static boolean isEnabled() {
        return instance != null && instance.enabled;
    }

    public static void setEnabled(boolean value) {
        if (instance == null || instance.enabled == value) {
            return;
        }
        instance.enabled = value;
        instance.notifiedEmpty = false;
        instance.notifiedNoFiller = false;
        instance.notifiedNoSafeSpot = false;
        instance.notifiedOutsideArea = false;
        if (value) {
            instance.resetSessionStats();
        } else {
            instance.predictedDry.clear();
            instance.recentlyPlaced.clear();
            Minecraft client = Minecraft.getInstance();
            if ((instance.breakingPos != null || instance.netherBreakPos != null) && client.gameMode != null) {
                client.gameMode.stopDestroyBlock();
            }
            instance.breakingPos = null;
            instance.netherBreakPos = null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.level != null) {
            instance.sendStatus(client.player, client.level, !value);
        }
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = SpongeAuraConfig.load();

        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.spongeaura.toggle", InputConstants.KEY_G, CATEGORY)
        );
        modeKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.spongeaura.mode", InputConstants.KEY_H, CATEGORY)
        );
        settingsKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.spongeaura.settings", InputConstants.KEY_J, CATEGORY)
        );

        SpongeHighlighter.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        UseBlockCallback.EVENT.register(this::onManualUseBlock);
        UseItemCallback.EVENT.register(this::onManualUseItem);
    }

    private InteractionResult onManualUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        return handleManualSpongeAttempt(player, level, hand);
    }

    private InteractionResult onManualUseItem(Player player, Level level, InteractionHand hand) {
        return handleManualSpongeAttempt(player, level, hand);
    }

    private InteractionResult handleManualSpongeAttempt(Player player, Level level, InteractionHand hand) {
        if (!level.isClientSide()
                || !enabled
                || internalPlacement
                || !player.getItemInHand(hand).is(Items.SPONGE)
                || player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        if (level instanceof ClientLevel clientLevel
                && isEvaporatingDimension(clientLevel, player.blockPosition())) {
            return InteractionResult.PASS;
        }

        int areaMargin = (int) Math.ceil(config.reach) + 1;
        if (config.areaLimitEnabled && !config.isNearWorkArea(player.blockPosition(), areaMargin)) {
            return InteractionResult.PASS;
        }

        if (lastManualInterceptTick == tickCounter) {
            return InteractionResult.FAIL;
        }
        lastManualInterceptTick = tickCounter;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.level != null && client.gameMode != null) {
            tryPlaceBestNow(client, client.player, client.level, hand, true);
        }
        return InteractionResult.FAIL;
    }

    private void onEndTick(Minecraft client) {
        tickCounter++;

        if (!predictedDry.isEmpty()) {
            predictedDry.values().removeIf(expireTick -> expireTick <= tickCounter);
        }
        if (!recentlyPlaced.isEmpty()) {
            recentlyPlaced.values().removeIf(expireTick -> expireTick <= tickCounter);
        }
        if (cooldown > 0) {
            cooldown--;
        }
        if (refillCooldown > 0) {
            refillCooldown--;
        }

        if (client.player == null || client.level == null || client.gameMode == null) {
            predictedDry.clear();
            recentlyPlaced.clear();
            breakingPos = null;
            netherBreakPos = null;
            notifiedEmpty = false;
            notifiedNoFiller = false;
            notifiedNoSafeSpot = false;
            notifiedOutsideArea = false;
            resetSessionStats();
            SpongeHighlighter.clear();
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        SpongeHighlighter.onClientTick(client);

        while (settingsKey.consumeClick()) {
            if (client.gui.screen() == null) {
                client.gui.setScreen(new SpongeAuraSettingsScreen(null));
            }
        }

        while (toggleKey.consumeClick()) {
            setEnabled(!enabled);
        }

        while (modeKey.consumeClick()) {
            config.efficiencyMode = (config.efficiencyMode == EfficiencyMode.MAX_EFFICIENCY)
                    ? EfficiencyMode.PRACTICAL
                    : EfficiencyMode.MAX_EFFICIENCY;
            config.save();
            sendModeStatus(player);
        }

        if (!enabled || client.gui.screen() != null) {
            if (breakingPos != null || netherBreakPos != null) {
                client.gameMode.stopDestroyBlock();
                breakingPos = null;
                netherBreakPos = null;
            }
            return;
        }

        if (config.netherAutoDry && isEvaporatingDimension(level, player.blockPosition())) {
            if (breakingPos != null) {
                client.gameMode.stopDestroyBlock();
                breakingPos = null;
            }
            tickNetherDry(client, player, level);
            return;
        } else if (netherBreakPos != null) {
            client.gameMode.stopDestroyBlock();
            netherBreakPos = null;
        }

        int areaMargin = (int) Math.ceil(config.reach) + 1;
        if (config.areaLimitEnabled && !config.isNearWorkArea(player.blockPosition(), areaMargin)) {
            if (breakingPos != null) {
                client.gameMode.stopDestroyBlock();
                breakingPos = null;
            }
            if (!notifiedOutsideArea) {
                player.sendOverlayMessage(Component.literal("§b[SpongeAura] §ePaused — outside work area"));
                notifiedOutsideArea = true;
            }
            return;
        }
        notifiedOutsideArea = false;

        // Continue breaking an already-targeted wet sponge before doing anything else
        if (breakingPos != null) {
            if (!level.getBlockState(breakingPos).is(Blocks.WET_SPONGE)) {
                sessionBroken++;
                spawnBreakParticles(level, breakingPos);
                SpongeHighlighter.requestRescan();
                breakingPos = null;
            } else if (config.wetSpongeBreakMode == WetSpongeBreakMode.OFF
                    || !isWithinReach(player, breakingPos)
                    || !config.isInsideWorkArea(breakingPos)
                    || (config.wetSpongeBreakMode == WetSpongeBreakMode.SAFE && isWetSpongeExposed(level, breakingPos))) {
                client.gameMode.stopDestroyBlock();
                breakingPos = null;
            } else {
                selectBestHotbarTool(player, level.getBlockState(breakingPos));
                Direction face = getClosestFace(player.getEyePosition(), breakingPos);
                if (config.rotationMode != RotationMode.NONE) {
                    applyRotation(player, Vec3.atCenterOf(breakingPos));
                }
                client.gameMode.continueDestroyBlock(breakingPos, face);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                return;
            }
        }

        // Placement / tiny-pocket fill phase
        if (cooldown <= 0) {
            if (tryPlaceBestNow(client, player, level, InteractionHand.MAIN_HAND, false)) {
                return;
            }
        }

        // Smart wet-sponge auto-break phase (runs when no placement occurred this tick)
        if (config.wetSpongeBreakMode != WetSpongeBreakMode.OFF) {
            BlockPos wetTarget = findNearestWetSponge(player, level);
            if (wetTarget != null) {
                selectBestHotbarTool(player, level.getBlockState(wetTarget));
                Direction face = getClosestFace(player.getEyePosition(), wetTarget);
                if (config.rotationMode != RotationMode.NONE) {
                    applyRotation(player, Vec3.atCenterOf(wetTarget));
                }
                if (client.gameMode.startDestroyBlock(wetTarget, face)) {
                    breakingPos = wetTarget;
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                }
            }
        }
    }

    private void tickNetherDry(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (netherBreakPos != null) {
            if (!level.getBlockState(netherBreakPos).is(Blocks.SPONGE)) {
                sessionDried++;
                spawnBreakParticles(level, netherBreakPos);
                SpongeHighlighter.requestRescan();
                netherBreakPos = null;
            } else if (!isWithinReach(player, netherBreakPos)) {
                client.gameMode.stopDestroyBlock();
                netherBreakPos = null;
            } else {
                selectBestHotbarTool(player, Blocks.SPONGE.defaultBlockState());
                Direction face = getClosestFace(player.getEyePosition(), netherBreakPos);
                if (config.rotationMode != RotationMode.NONE) {
                    applyRotation(player, Vec3.atCenterOf(netherBreakPos));
                }
                client.gameMode.continueDestroyBlock(netherBreakPos, face);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                return;
            }
        }

        BlockPos dryPos = findNearestBlock(player, level, Blocks.SPONGE);
        if (dryPos != null) {
            selectBestHotbarTool(player, Blocks.SPONGE.defaultBlockState());
            Direction face = getClosestFace(player.getEyePosition(), dryPos);
            if (config.rotationMode != RotationMode.NONE) {
                applyRotation(player, Vec3.atCenterOf(dryPos));
            }
            if (client.gameMode.startDestroyBlock(dryPos, face)) {
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                if (!level.getBlockState(dryPos).is(Blocks.SPONGE)) {
                    sessionDried++;
                    spawnBreakParticles(level, dryPos);
                    SpongeHighlighter.requestRescan();
                    netherBreakPos = null;
                } else {
                    netherBreakPos = dryPos;
                }
                return;
            }
        }

        if (cooldown > 0) {
            return;
        }

        SpongeReady ready = ensureWetSpongeReady(client, player);
        if (ready == SpongeReady.REFILLED_WAIT) {
            return;
        }
        if (ready != SpongeReady.READY) {
            enabled = false;
            if (sessionDried > 0) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §aNether Dry complete! Dried §f" + sessionDried + " §asponges — pick them up!"
                ));
            } else {
                player.sendOverlayMessage(Component.literal("§b[SpongeAura] §eNo wet sponges in inventory!"));
            }
            return;
        }

        ValidSpot spot = findBestNetherDrySpot(player, level);
        if (spot == null) {
            if (!notifiedNoSafeSpot) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §eStand on a safe solid floor away from lava/fire to dry sponges!"
                ));
                notifiedNoSafeSpot = true;
            }
            return;
        }
        notifiedNoSafeSpot = false;

        if (config.rotationMode != RotationMode.NONE) {
            applyRotation(player, spot.hit().getLocation());
        }

        InteractionResult result;
        internalPlacement = true;
        try {
            result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, spot.hit());
        } finally {
            internalPlacement = false;
        }

        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            spawnSteamParticles(level, spot.pos());
            SpongeHighlighter.requestRescan();
            cooldown = config.placeDelayTicks;
        }
    }

    private boolean tryPlaceBestNow(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            InteractionHand preferredHand,
            boolean fromManual
    ) {
        boolean hasDrySponge = (fromManual && player.getItemInHand(preferredHand).is(Items.SPONGE))
                || hasDrySpongeAvailable(player);
        boolean hasFillerBlock = config.fillTinyWithBlocks && hasFillerBlockAvailable(player);

        if (!hasDrySponge && !hasFillerBlock) {
            if (!notifiedEmpty) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §eNo dry sponges or filler blocks left!"
                ));
                notifiedEmpty = true;
            }
            return false;
        }
        notifiedEmpty = false;

        PlacementSearchResult search = findBestPlacement(player, level, hasDrySponge, hasFillerBlock);
        Candidate best = search.best();
        if (best == null) {
            if (fromManual) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §eNo valid non-refilling spot in reach (Shift+Click to force)"
                ));
            } else if (search.sawTinyNeedingFiller() && !notifiedNoFiller) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §eCarry netherrack/dirt/cobble for 1-5 block water sources"
                ));
                notifiedNoFiller = true;
            }
            return false;
        }
        notifiedNoFiller = false;

        InteractionHand useHand;
        if (best.kind() == PlacementKind.FILL_BLOCK) {
            SpongeReady fillerReady = ensureFillerBlockReady(client, player);
            if (fillerReady == SpongeReady.REFILLED_WAIT) {
                return true;
            }
            if (fillerReady != SpongeReady.READY) {
                return false;
            }
            useHand = InteractionHand.MAIN_HAND;
        } else {
            if (fromManual && player.getItemInHand(preferredHand).is(Items.SPONGE)) {
                useHand = preferredHand;
            } else {
                SpongeReady spongeReady = ensureDrySpongeReady(client, player);
                if (spongeReady == SpongeReady.REFILLED_WAIT) {
                    return true;
                }
                if (spongeReady != SpongeReady.READY) {
                    return false;
                }
                useHand = InteractionHand.MAIN_HAND;
            }
        }

        return executePlacement(client, player, level, useHand, best);
    }

    private boolean executePlacement(
            Minecraft client,
            LocalPlayer player,
            ClientLevel level,
            InteractionHand hand,
            Candidate best
    ) {
        if (config.rotationMode != RotationMode.NONE) {
            applyRotation(player, best.hit().getLocation());
        }

        InteractionResult result;
        internalPlacement = true;
        try {
            result = client.gameMode.useItemOn(player, hand, best.hit());
        } finally {
            internalPlacement = false;
        }

        if (!result.consumesAction()) {
            return false;
        }

        player.swing(hand, SwingAnimation.DEFAULT, false);

        long dryExpire = tickCounter + PREDICTION_TTL_TICKS;
        predictedDry.put(best.targetPos().asLong(), dryExpire);
        if (best.kind() == PlacementKind.SPONGE) {
            for (long clearedPos : best.clearedAll()) {
                predictedDry.put(clearedPos, dryExpire);
            }
            recentlyPlaced.put(best.targetPos().asLong(), tickCounter + RECENT_PLACE_TTL_TICKS);
            sessionPlaced++;
            if (sessionPlaced == 64 && !triggeredStackJoke) {
                triggeredStackJoke = true;
                Component joke = Component.literal("§b[SpongeAura] §dGut gemacht, ein ganzes Stack nass-gemacht :)");
                client.gui.hud.getChat().addClientSystemMessage(joke);
                player.sendOverlayMessage(joke);
            }
            sessionDrained += Math.max(1, best.netSources());
            spawnPlaceParticles(level, best.targetPos());
            SpongeHighlighter.requestRescan();
        } else {
            sessionFilled++;
            sessionDrained++;
            spawnFillParticles(level, best.targetPos());
        }

        cooldown = config.placeDelayTicks;
        return true;
    }

    private void resetSessionStats() {
        sessionPlaced = 0;
        sessionFilled = 0;
        sessionDrained = 0;
        sessionBroken = 0;
        sessionDried = 0;
        triggeredStackJoke = false;
    }

    private String formatModeLabel() {
        return (config.efficiencyMode == EfficiencyMode.MAX_EFFICIENCY)
                ? "§dMax Efficiency (48+)"
                : "§fPractical (22+)";
    }

    private void sendModeStatus(LocalPlayer player) {
        String state = enabled ? "§aON" : "§cOFF";
        String placeLabel = (config.placementMode == PlacementMode.LEGIT) ? "Legit" : "Direct";
        player.sendOverlayMessage(Component.literal(
                "§b[SpongeAura] " + state + " §7| Mode: " + formatModeLabel() + " §7| " + placeLabel
        ));
    }

    private void sendStatus(LocalPlayer player, ClientLevel level, boolean showSummaryIfAny) {
        boolean netherMode = config.netherAutoDry && isEvaporatingDimension(level, player.blockPosition());

        if (!enabled) {
            if (netherMode) {
                if (showSummaryIfAny && sessionDried > 0) {
                    player.sendOverlayMessage(Component.literal(
                            "§b[SpongeAura] §6NETHER DRY §cOFF §7| Dried: §f" + sessionDried
                    ));
                } else {
                    player.sendOverlayMessage(Component.literal("§b[SpongeAura] §6NETHER DRY §cOFF"));
                }
            } else if (showSummaryIfAny && (sessionPlaced > 0 || sessionFilled > 0 || sessionBroken > 0)) {
                player.sendOverlayMessage(Component.literal(
                        "§b[SpongeAura] §cOFF §7| Sponges: §f" + sessionPlaced
                                + " §7| Filled: §f" + sessionFilled
                                + " §7| Drained: §f~" + sessionDrained
                                + " §7| Broken: §f" + sessionBroken
                ));
            } else {
                player.sendOverlayMessage(Component.literal("§b[SpongeAura] §cOFF §7| " + formatModeLabel()));
            }
            return;
        }

        if (netherMode) {
            player.sendOverlayMessage(Component.literal(
                    "§b[SpongeAura] §6NETHER DRY §aON §7| Wet Sponges: §d" + countItem(player, Items.WET_SPONGE)
            ));
            return;
        }

        String modeLabel = formatModeLabel();
        String placeLabel = (config.placementMode == PlacementMode.LEGIT) ? "Legit" : "Direct";
        String areaTag = "";
        if (config.areaLimitEnabled) {
            int areaMargin = (int) Math.ceil(config.reach) + 1;
            areaTag = config.isNearWorkArea(player.blockPosition(), areaMargin)
                    ? " §7| §eArea"
                    : " §7| §cOutside Area";
        }
        int totalSponges = countItem(player, Items.SPONGE);
        if (config.fillTinyWithBlocks) {
            int totalFillers = countFillerBlocks(player);
            player.sendOverlayMessage(Component.literal(
                    "§b[SpongeAura] §aON §7| " + modeLabel + " §7| " + placeLabel
                            + " §7| Sponges: §b" + totalSponges + " §7| Fill: §e" + totalFillers + areaTag
            ));
        } else {
            player.sendOverlayMessage(Component.literal(
                    "§b[SpongeAura] §aON §7| " + modeLabel + " §7| " + placeLabel
                            + " §7| Sponges: §b" + totalSponges + areaTag
            ));
        }
    }

    static boolean isEvaporatingDimension(ClientLevel level, BlockPos pos) {
        return level.dimension().equals(Level.NETHER)
                || Boolean.TRUE.equals(level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos));
    }

    private boolean isFillerBlock(ItemStack stack) {
        return stack.is(Items.NETHERRACK)
                || stack.is(Items.DIRT)
                || stack.is(Items.COBBLESTONE);
    }

    private boolean hasDrySpongeAvailable(LocalPlayer player) {
        int limit = config.autoRefill ? InventoryMenu.INV_SLOT_END : 9;
        for (int slot = 0; slot < limit; slot++) {
            if (player.getInventory().getItem(slot).is(Items.SPONGE)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFillerBlockAvailable(LocalPlayer player) {
        int limit = config.autoRefill ? InventoryMenu.INV_SLOT_END : 9;
        for (int slot = 0; slot < limit; slot++) {
            if (isFillerBlock(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private int countFillerBlocks(LocalPlayer player) {
        int total = 0;
        for (int slot = 0; slot < InventoryMenu.INV_SLOT_END; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isFillerBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private SpongeReady ensureDrySpongeReady(Minecraft client, LocalPlayer player) {
        if (player.getMainHandItem().is(Items.SPONGE)) {
            return SpongeReady.READY;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(Items.SPONGE)) {
                player.getInventory().setSelectedSlot(slot);
                return SpongeReady.READY;
            }
        }

        if (!config.autoRefill
                || client.gameMode == null
                || refillCooldown > 0
                || player.containerMenu != player.inventoryMenu) {
            return SpongeReady.NONE;
        }

        for (int invSlot = InventoryMenu.INV_SLOT_START; invSlot < InventoryMenu.INV_SLOT_END; invSlot++) {
            if (player.getInventory().getItem(invSlot).is(Items.SPONGE)) {
                int hotbarSlot = chooseDryRefillHotbarSlot(player);
                client.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId,
                        invSlot,
                        hotbarSlot,
                        ContainerInput.SWAP,
                        player
                );
                player.getInventory().setSelectedSlot(hotbarSlot);
                refillCooldown = REFILL_COOLDOWN_TICKS;
                return SpongeReady.REFILLED_WAIT;
            }
        }
        return SpongeReady.NONE;
    }

    private SpongeReady ensureFillerBlockReady(Minecraft client, LocalPlayer player) {
        if (isFillerBlock(player.getMainHandItem())) {
            return SpongeReady.READY;
        }
        for (Item preferred : FILLER_PRIORITY) {
            for (int slot = 0; slot < 9; slot++) {
                if (player.getInventory().getItem(slot).is(preferred)) {
                    player.getInventory().setSelectedSlot(slot);
                    return SpongeReady.READY;
                }
            }
        }

        if (!config.autoRefill
                || client.gameMode == null
                || refillCooldown > 0
                || player.containerMenu != player.inventoryMenu) {
            return SpongeReady.NONE;
        }

        for (Item preferred : FILLER_PRIORITY) {
            for (int invSlot = InventoryMenu.INV_SLOT_START; invSlot < InventoryMenu.INV_SLOT_END; invSlot++) {
                if (player.getInventory().getItem(invSlot).is(preferred)) {
                    int hotbarSlot = chooseFillerRefillHotbarSlot(player);
                    client.gameMode.handleContainerInput(
                            player.inventoryMenu.containerId,
                            invSlot,
                            hotbarSlot,
                            ContainerInput.SWAP,
                            player
                    );
                    player.getInventory().setSelectedSlot(hotbarSlot);
                    refillCooldown = REFILL_COOLDOWN_TICKS;
                    return SpongeReady.REFILLED_WAIT;
                }
            }
        }
        return SpongeReady.NONE;
    }

    private SpongeReady ensureWetSpongeReady(Minecraft client, LocalPlayer player) {
        if (player.getMainHandItem().is(Items.WET_SPONGE)) {
            return SpongeReady.READY;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(Items.WET_SPONGE)) {
                player.getInventory().setSelectedSlot(slot);
                return SpongeReady.READY;
            }
        }

        if (!config.autoRefill
                || client.gameMode == null
                || refillCooldown > 0
                || player.containerMenu != player.inventoryMenu) {
            return SpongeReady.NONE;
        }

        for (int invSlot = InventoryMenu.INV_SLOT_START; invSlot < InventoryMenu.INV_SLOT_END; invSlot++) {
            if (player.getInventory().getItem(invSlot).is(Items.WET_SPONGE)) {
                int hotbarSlot = chooseWetRefillHotbarSlot(player);
                client.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId,
                        invSlot,
                        hotbarSlot,
                        ContainerInput.SWAP,
                        player
                );
                player.getInventory().setSelectedSlot(hotbarSlot);
                refillCooldown = REFILL_COOLDOWN_TICKS;
                return SpongeReady.REFILLED_WAIT;
            }
        }
        return SpongeReady.NONE;
    }

    private int chooseDryRefillHotbarSlot(LocalPlayer player) {
        int protectedToolSlot = findBestHotbarToolSlot(player, Blocks.SPONGE.defaultBlockState());
        int current = player.getInventory().getSelectedSlot();

        if (current >= 0 && current < 9 && current != protectedToolSlot
                && player.getInventory().getItem(current).isEmpty()) {
            return current;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).is(Items.WET_SPONGE)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != protectedToolSlot && !isToolItem(stack) && !isFillerBlock(stack)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && !isToolItem(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot) {
                return slot;
            }
        }
        return (current >= 0 && current < 9) ? current : 0;
    }

    private int chooseFillerRefillHotbarSlot(LocalPlayer player) {
        int protectedToolSlot = findBestHotbarToolSlot(player, Blocks.SPONGE.defaultBlockState());
        int current = player.getInventory().getSelectedSlot();

        if (current >= 0 && current < 9 && current != protectedToolSlot
                && player.getInventory().getItem(current).isEmpty()) {
            return current;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).is(Items.WET_SPONGE)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != protectedToolSlot && !stack.is(Items.SPONGE) && !isToolItem(stack)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != protectedToolSlot && !stack.is(Items.SPONGE)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot) {
                return slot;
            }
        }
        return (current >= 0 && current < 9) ? current : 0;
    }

    private int chooseWetRefillHotbarSlot(LocalPlayer player) {
        int protectedToolSlot = findBestHotbarToolSlot(player, Blocks.SPONGE.defaultBlockState());
        int current = player.getInventory().getSelectedSlot();

        if (current >= 0 && current < 9 && current != protectedToolSlot
                && player.getInventory().getItem(current).isEmpty()) {
            return current;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && player.getInventory().getItem(slot).is(Items.SPONGE)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot && !isToolItem(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != protectedToolSlot) {
                return slot;
            }
        }
        return 0;
    }

    private boolean isToolItem(ItemStack stack) {
        return stack.getDestroySpeed(Blocks.SPONGE.defaultBlockState()) > 1.0f
                || stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.0f
                || stack.getDestroySpeed(Blocks.DIRT.defaultBlockState()) > 1.0f;
    }

    private int countItem(LocalPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < InventoryMenu.INV_SLOT_END; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int findBestHotbarToolSlot(LocalPlayer player, BlockState targetState) {
        int current = player.getInventory().getSelectedSlot();
        float bestSpeed = (current >= 0 && current < 9)
                ? player.getInventory().getItem(current).getDestroySpeed(targetState)
                : 1.0f;
        int bestSlot = -1;
        if (bestSpeed > 1.0f && current >= 0 && current < 9) {
            bestSlot = current;
        }

        for (int slot = 0; slot < 9; slot++) {
            float speed = player.getInventory().getItem(slot).getDestroySpeed(targetState);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void selectBestHotbarTool(LocalPlayer player, BlockState targetState) {
        int bestSlot = findBestHotbarToolSlot(player, targetState);
        if (bestSlot != -1 && bestSlot != player.getInventory().getSelectedSlot()) {
            player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    private ValidSpot findBestNetherDrySpot(LocalPlayer player, ClientLevel level) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = BlockPos.containing(eyePos);
        AABB playerBox = player.getBoundingBox();
        double maxDistSqr = config.reach * config.reach;
        int radius = (int) Math.ceil(config.reach);

        Vec3 look = player.getLookAngle();
        Vec3 idealSpot = player.position().add(look.x * 1.8, 0.5, look.z * 1.8);

        ValidSpot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Vec3 posCenter = Vec3.atCenterOf(pos);
                    if (eyePos.distanceToSqr(posCenter) > maxDistSqr) {
                        continue;
                    }

                    AABB blockBox = new AABB(
                            pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0
                    );
                    if (playerBox.intersects(blockBox)) {
                        continue;
                    }

                    if (!level.getBlockState(pos).isAir()) {
                        continue;
                    }

                    if (!isSafeDropFloor(level, pos) || hasHazardNeighbor(level, pos)) {
                        continue;
                    }

                    BlockHitResult hit = findNetherSupportHit(level, pos, eyePos, maxDistSqr);
                    if (hit == null) {
                        continue;
                    }

                    double score = -posCenter.distanceToSqr(idealSpot);
                    if (level.getBlockState(pos.below()).isSolid()) {
                        score += 4.0;
                    }
                    if (pos.getY() == player.getBlockY()) {
                        score += 1.0;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        best = new ValidSpot(pos, hit);
                    }
                }
            }
        }

        return best;
    }

    private boolean isSafeDropFloor(ClientLevel level, BlockPos pos) {
        for (int depth = 1; depth <= 3; depth++) {
            BlockPos below = pos.below(depth);
            BlockState state = level.getBlockState(below);
            if (isHazardBlock(state, level.getFluidState(below))) {
                return false;
            }
            if (state.isSolid()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHazardNeighbor(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (isHazardBlock(level.getBlockState(neighbor), level.getFluidState(neighbor))) {
                return true;
            }
        }
        return false;
    }

    private boolean isHazardBlock(BlockState state, FluidState fluid) {
        return fluid.is(FluidTags.LAVA)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.NETHER_PORTAL);
    }

    private BlockHitResult findNetherSupportHit(ClientLevel level, BlockPos targetPos, Vec3 eyePos, double maxDistSqr) {
        BlockPos floorPos = targetPos.below();
        BlockState floorState = level.getBlockState(floorPos);
        if (!floorState.isAir()
                && floorState.getFluidState().isEmpty()
                && !floorState.canBeReplaced()) {
            Vec3 faceCenter = Vec3.atCenterOf(floorPos).add(0.0, 0.5, 0.0);
            if (eyePos.distanceToSqr(faceCenter) <= maxDistSqr) {
                return new BlockHitResult(faceCenter, Direction.UP, floorPos, false);
            }
        }
        return findLegitSupportHit(level, targetPos, eyePos, maxDistSqr);
    }

    private PlacementSearchResult findBestPlacement(
            LocalPlayer player,
            ClientLevel level,
            boolean hasDrySponge,
            boolean hasFillerBlock
    ) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = BlockPos.containing(eyePos);
        AABB playerBox = player.getBoundingBox();
        double maxDistSqr = config.reach * config.reach;
        int radius = (int) Math.ceil(config.reach);
        boolean legitMode = (config.placementMode == PlacementMode.LEGIT);
        boolean maxEffMode = (config.efficiencyMode == EfficiencyMode.MAX_EFFICIENCY);
        int modeFloor = maxEffMode ? MAX_EFFICIENCY_MIN_SOURCES : PRACTICAL_MIN_SOURCES;
        double minRetention = maxEffMode ? MAX_EFFICIENCY_MIN_RETENTION : PRACTICAL_MIN_RETENTION;

        List<ValidSpot> validSpots = new ArrayList<>();
        List<RepairCandidate> repairShortlist = new ArrayList<>();
        Candidate bestImmediate = null;
        boolean sawTinyNeedingFiller = false;

        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double distSqr = eyePos.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distSqr > maxDistSqr) {
                        continue;
                    }

                    AABB blockBox = new AABB(
                            pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0
                    );
                    if (playerBox.intersects(blockBox)) {
                        continue;
                    }

                    if (!config.isInsideWorkArea(pos)) {
                        continue;
                    }

                    if (isPredictedDry(pos) || !level.getBlockState(pos).canBeReplaced()) {
                        continue;
                    }

                    BlockHitResult hit;
                    if (legitMode) {
                        FluidState fluid = level.getFluidState(pos);
                        if (fluid.is(FluidTags.WATER) && !fluid.isSource()) {
                            continue;
                        }
                        if (!isActiveWaterSource(level, pos, null) && !hasAdjacentActiveSource(level, pos)) {
                            continue;
                        }
                        hit = findLegitSupportHit(level, pos, eyePos, maxDistSqr);
                        if (hit == null) {
                            continue;
                        }
                    } else {
                        if (!isActiveWaterSource(level, pos, null)) {
                            continue;
                        }
                        hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    }

                    ValidSpot spot = new ValidSpot(pos, hit);
                    validSpots.add(spot);

                    BfsResult bfs = simulateSpongeBfs(level, pos, null);
                    int grossSources = bfs.clearedSources().size();
                    if (grossSources <= 0) {
                        continue;
                    }

                    boolean completeCluster = isCompleteWaterCluster(level, bfs.clearedAll());

                    // Branch A: Tiny standalone 1-5 source pocket -> fill with junk blocks instead of wasting a sponge
                    if (completeCluster && grossSources <= TINY_POCKET_MAX_SOURCES) {
                        if (config.fillTinyWithBlocks && hasFillerBlock && isActiveWaterSource(level, pos, null)) {
                            double score = 100.0 + pos.getY() * 0.35 - distSqr * 0.05;
                            bestImmediate = pickBetter(bestImmediate, new Candidate(
                                    PlacementKind.FILL_BLOCK,
                                    pos,
                                    hit,
                                    Set.of(pos.asLong()),
                                    1,
                                    1,
                                    score
                            ));
                        } else if ((!config.fillTinyWithBlocks || !hasFillerBlock) && !maxEffMode && hasDrySponge) {
                            double score = grossSources + pos.getY() * 0.35;
                            bestImmediate = pickBetter(bestImmediate, new Candidate(
                                    PlacementKind.SPONGE,
                                    pos,
                                    hit,
                                    bfs.clearedAll(),
                                    grossSources,
                                    0,
                                    score
                            ));
                        } else if (maxEffMode && config.fillTinyWithBlocks && !hasFillerBlock) {
                            sawTinyNeedingFiller = true;
                        }
                        continue;
                    }

                    if (!hasDrySponge) {
                        continue;
                    }

                    // Branch B: Standalone 6-65 source pocket -> cleanly wiped with zero refill in BOTH modes
                    if (completeCluster) {
                        int tier = (grossSources >= modeFloor) ? 2 : 1;
                        double score = grossSources + pos.getY() * 0.35;
                        bestImmediate = pickBetter(bestImmediate, new Candidate(
                                PlacementKind.SPONGE,
                                pos,
                                hit,
                                bfs.clearedAll(),
                                grossSources,
                                tier,
                                score
                        ));
                        continue;
                    }

                    // Branch C: Large water body (rivers, big water caves, aquifers) -> net permanent + retention check
                    RepairResult repair = simulateWaterRepair(
                            level, Set.of(pos.asLong()), bfs.clearedAll(), bfs.clearedSources()
                    );
                    int netPermanent = grossSources - repair.repairedSources();
                    double retention = (double) netPermanent / grossSources;

                    if (netPermanent >= modeFloor && retention >= minRetention) {
                        double score = netPermanent + (repair.repairedSources() == 0 ? 4.0 : 0.0) + pos.getY() * 0.35;
                        bestImmediate = pickBetter(bestImmediate, new Candidate(
                                PlacementKind.SPONGE,
                                pos,
                                hit,
                                bfs.clearedAll(),
                                netPermanent,
                                2,
                                score
                        ));
                    } else if (grossSources >= modeFloor) {
                        double rawScore = grossSources + pos.getY() * 0.35;
                        repairShortlist.add(new RepairCandidate(spot, bfs, repair, rawScore));
                    }
                }
            }
        }

        // Sub-5-tick burst lookahead for remaining repairable high-yield spots
        int stepTicks = config.placeDelayTicks + 1;
        int maxBurstSponges = hasDrySponge ? Math.min(
                MAX_BURST_CAP,
                Math.min(countItem(player, Items.SPONGE), 1 + ((WATER_TICK_DELAY - 1) / stepTicks))
        ) : 0;

        if (maxBurstSponges >= 2 && !repairShortlist.isEmpty()) {
            repairShortlist.sort(Comparator.comparingDouble(RepairCandidate::rawScore).reversed());
            int checked = 0;
            for (RepairCandidate rc : repairShortlist) {
                if (++checked > BURST_SHORTLIST_LIMIT) {
                    break;
                }
                Candidate burstApproved = evaluateBurstCutOff(
                        level, rc, validSpots, maxBurstSponges, modeFloor, minRetention
                );
                if (burstApproved != null) {
                    bestImmediate = pickBetter(bestImmediate, burstApproved);
                }
            }
        }

        return new PlacementSearchResult(bestImmediate, sawTinyNeedingFiller);
    }

    private boolean isCompleteWaterCluster(ClientLevel level, Set<Long> clearedAll) {
        for (long posKey : clearedAll) {
            BlockPos pos = BlockPos.of(posKey);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (!clearedAll.contains(neighbor.asLong()) && isActiveWaterSource(level, neighbor, null)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Candidate evaluateBurstCutOff(
            ClientLevel level,
            RepairCandidate root,
            List<ValidSpot> validSpots,
            int maxBurstSponges,
            int modeFloor,
            double minRetention
    ) {
        Set<Long> burstPlaced = new HashSet<>();
        burstPlaced.add(root.spot().pos().asLong());

        Set<Long> burstClearedAll = new HashSet<>(root.bfs().clearedAll());
        Set<Long> burstClearedSources = new HashSet<>(root.bfs().clearedSources());
        RepairResult currentRepair = root.repair();
        int rootNet = root.bfs().clearedSources().size() - root.repair().repairedSources();

        for (int k = 2; k <= maxBurstSponges; k++) {
            ValidSpot bestFollowUp = null;
            BfsResult bestFollowBfs = null;
            RepairResult bestFollowRepair = null;
            int bestFollowNet = Integer.MIN_VALUE;

            for (ValidSpot candidate : validSpots) {
                long candKey = candidate.pos().asLong();
                if (burstPlaced.contains(candKey) || burstClearedAll.contains(candKey)) {
                    continue;
                }
                if (!isNearRepairedEdge(candidate.pos(), currentRepair.repairedAll())) {
                    continue;
                }

                BfsResult followBfs = simulateSpongeBfs(level, candidate.pos(), burstClearedAll);
                if (followBfs.clearedSources().isEmpty()) {
                    continue;
                }

                Set<Long> testPlaced = new HashSet<>(burstPlaced);
                testPlaced.add(candKey);
                Set<Long> testClearedAll = new HashSet<>(burstClearedAll);
                testClearedAll.addAll(followBfs.clearedAll());
                Set<Long> testClearedSources = new HashSet<>(burstClearedSources);
                testClearedSources.addAll(followBfs.clearedSources());

                RepairResult testRepair = simulateWaterRepair(level, testPlaced, testClearedAll, testClearedSources);
                int testNet = testClearedSources.size() - testRepair.repairedSources();

                if (testRepair.repairedSources() < currentRepair.repairedSources()
                        || (testRepair.repairedSources() == currentRepair.repairedSources() && testNet > bestFollowNet)) {
                    if (bestFollowRepair == null
                            || testRepair.repairedSources() < bestFollowRepair.repairedSources()
                            || (testRepair.repairedSources() == bestFollowRepair.repairedSources() && testNet > bestFollowNet)) {
                        bestFollowUp = candidate;
                        bestFollowBfs = followBfs;
                        bestFollowRepair = testRepair;
                        bestFollowNet = testNet;
                    }
                }
            }

            if (bestFollowUp == null) {
                break;
            }

            burstPlaced.add(bestFollowUp.pos().asLong());
            burstClearedAll.addAll(bestFollowBfs.clearedAll());
            burstClearedSources.addAll(bestFollowBfs.clearedSources());
            currentRepair = bestFollowRepair;

            double avgPerSponge = (double) bestFollowNet / k;
            double burstRetention = (double) bestFollowNet / burstClearedSources.size();

            if (avgPerSponge >= modeFloor && burstRetention >= minRetention && bestFollowNet > rootNet) {
                double score = avgPerSponge
                        + (currentRepair.repairedSources() == 0 ? 4.0 : 0.0)
                        + root.spot().pos().getY() * 0.35;
                int firstNetEstimate = Math.max(modeFloor, (int) Math.round(avgPerSponge));
                return new Candidate(
                        PlacementKind.SPONGE,
                        root.spot().pos(),
                        root.spot().hit(),
                        root.bfs().clearedAll(),
                        firstNetEstimate,
                        2,
                        score
                );
            }
        }

        return null;
    }

    private boolean isNearRepairedEdge(BlockPos pos, Set<Long> repairedAll) {
        for (long rLong : repairedAll) {
            int dx = Math.abs(pos.getX() - BlockPos.getX(rLong));
            int dy = Math.abs(pos.getY() - BlockPos.getY(rLong));
            int dz = Math.abs(pos.getZ() - BlockPos.getZ(rLong));
            if (dx + dy + dz <= 5) {
                return true;
            }
        }
        return false;
    }

    private Candidate pickBetter(Candidate current, Candidate challenger) {
        if (challenger == null) {
            return current;
        }
        if (current == null) {
            return challenger;
        }
        if (challenger.tier() != current.tier()) {
            return challenger.tier() > current.tier() ? challenger : current;
        }
        return challenger.score() > current.score() ? challenger : current;
    }

    private BlockHitResult findLegitSupportHit(ClientLevel level, BlockPos targetPos, Vec3 eyePos, double maxDistSqr) {
        BlockHitResult bestHit = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (Direction offsetDir : Direction.values()) {
            BlockPos supportPos = targetPos.relative(offsetDir);
            BlockState supportState = level.getBlockState(supportPos);
            if (supportState.isAir()
                    || !supportState.getFluidState().isEmpty()
                    || supportState.canBeReplaced()) {
                continue;
            }

            Direction clickFace = offsetDir.getOpposite();
            Vec3 faceCenter = Vec3.atCenterOf(supportPos).add(clickFace.getUnitVec3().scale(0.5));
            double distSqr = eyePos.distanceToSqr(faceCenter);
            if (distSqr <= maxDistSqr && distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestHit = new BlockHitResult(faceCenter, clickFace, supportPos, false);
            }
        }
        return bestHit;
    }

    private boolean hasAdjacentActiveSource(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (isActiveWaterSource(level, pos.relative(dir), null)) {
                return true;
            }
        }
        return false;
    }

    private BfsResult simulateSpongeBfs(ClientLevel level, BlockPos start, Set<Long> extraDry) {
        Set<Long> clearedAll = new HashSet<>(MAX_SPONGE_ABSORB + 4);
        Set<Long> clearedSources = new HashSet<>(MAX_SPONGE_ABSORB + 4);
        Set<Long> visited = new HashSet<>(MAX_SPONGE_ABSORB + 16);
        Queue<BfsNode> queue = new ArrayDeque<>();

        long startKey = start.asLong();
        visited.add(startKey);
        int bfsCount = 1;

        if (isActiveWater(level, start, extraDry)) {
            clearedAll.add(startKey);
            if (level.getFluidState(start).isSource()) {
                clearedSources.add(startKey);
            }
        }
        queue.add(new BfsNode(start, 0));

        while (!queue.isEmpty() && bfsCount < MAX_SPONGE_ABSORB) {
            BfsNode current = queue.poll();
            if (current.depth() >= MAX_SPONGE_DEPTH) {
                continue;
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = current.pos().relative(dir);
                long nextKey = next.asLong();
                if (visited.contains(nextKey)) {
                    continue;
                }

                if (isActiveWater(level, next, extraDry)) {
                    visited.add(nextKey);
                    bfsCount++;
                    clearedAll.add(nextKey);
                    if (level.getFluidState(next).isSource()) {
                        clearedSources.add(nextKey);
                    }
                    queue.add(new BfsNode(next, current.depth() + 1));
                    if (bfsCount >= MAX_SPONGE_ABSORB) {
                        break;
                    }
                }
            }
        }

        return new BfsResult(clearedAll, clearedSources);
    }

    private RepairResult simulateWaterRepair(
            ClientLevel level,
            Set<Long> placedSponges,
            Set<Long> clearedAll,
            Set<Long> clearedSources
    ) {
        Set<Long> repaired = new HashSet<>();
        boolean changed = true;

        while (changed) {
            changed = false;
            for (long posKey : clearedAll) {
                if (placedSponges.contains(posKey) || repaired.contains(posKey)) {
                    continue;
                }

                BlockPos pos = BlockPos.of(posKey);
                int horizSources = 0;
                for (Direction dir : HORIZONTAL_DIRECTIONS) {
                    if (isSurvivingSource(level, pos.relative(dir), placedSponges, clearedAll, repaired)) {
                        horizSources++;
                    }
                }

                if (horizSources >= 2 && isRepairSupportBelow(level, pos.below(), placedSponges, clearedAll, repaired)) {
                    repaired.add(posKey);
                    changed = true;
                }
            }
        }

        int repairedSources = 0;
        for (long srcKey : clearedSources) {
            if (repaired.contains(srcKey)) {
                repairedSources++;
            }
        }
        return new RepairResult(repaired, repairedSources);
    }

    private boolean isSurvivingSource(
            ClientLevel level,
            BlockPos pos,
            Set<Long> placedSponges,
            Set<Long> clearedAll,
            Set<Long> repaired
    ) {
        long key = pos.asLong();
        if (placedSponges.contains(key)) {
            return false;
        }
        if (repaired.contains(key)) {
            return true;
        }
        if (clearedAll.contains(key)) {
            return false;
        }
        return isActiveWaterSource(level, pos, null);
    }

    private boolean isRepairSupportBelow(
            ClientLevel level,
            BlockPos below,
            Set<Long> placedSponges,
            Set<Long> clearedAll,
            Set<Long> repaired
    ) {
        long belowKey = below.asLong();
        if (placedSponges.contains(belowKey)) {
            return true;
        }
        if (isSurvivingSource(level, below, placedSponges, clearedAll, repaired)) {
            return true;
        }
        return !clearedAll.contains(belowKey) && level.getBlockState(below).isSolid();
    }

    private BlockPos findNearestBlock(LocalPlayer player, ClientLevel level, Block targetBlock) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = BlockPos.containing(eyePos);
        double maxDistSqr = config.reach * config.reach;
        int radius = (int) Math.ceil(config.reach);

        BlockPos nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;

        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double distSqr = eyePos.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distSqr > maxDistSqr || distSqr >= nearestDistSqr) {
                        continue;
                    }
                    if (!level.getBlockState(pos).is(targetBlock)) {
                        continue;
                    }
                    nearest = pos;
                    nearestDistSqr = distSqr;
                }
            }
        }
        return nearest;
    }

    private BlockPos findNearestWetSponge(LocalPlayer player, ClientLevel level) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = BlockPos.containing(eyePos);
        double maxDistSqr = config.reach * config.reach;
        int radius = (int) Math.ceil(config.reach);

        BlockPos nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;

        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double distSqr = eyePos.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distSqr > maxDistSqr || distSqr >= nearestDistSqr) {
                        continue;
                    }

                    if (!config.isInsideWorkArea(pos)) {
                        continue;
                    }

                    Long placedExpire = recentlyPlaced.get(pos.asLong());
                    if (placedExpire != null && placedExpire > tickCounter) {
                        continue;
                    }

                    if (!level.getBlockState(pos).is(Blocks.WET_SPONGE)) {
                        continue;
                    }

                    if (config.wetSpongeBreakMode == WetSpongeBreakMode.SAFE && isWetSpongeExposed(level, pos)) {
                        continue;
                    }

                    nearest = pos;
                    nearestDistSqr = distSqr;
                }
            }
        }
        return nearest;
    }

    private boolean isWetSpongeExposed(ClientLevel level, BlockPos pos) {
        for (Direction dir : WATER_EXPOSURE_FACES) {
            if (level.getFluidState(pos.relative(dir)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinReach(LocalPlayer player, BlockPos pos) {
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= config.reach * config.reach;
    }

    private Direction getClosestFace(Vec3 eyePos, BlockPos pos) {
        Vec3 delta = eyePos.subtract(Vec3.atCenterOf(pos));
        Direction best = Direction.UP;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (Direction dir : Direction.values()) {
            double dot = delta.dot(dir.getUnitVec3());
            if (dot > bestDot) {
                bestDot = dot;
                best = dir;
            }
        }
        return best;
    }

    private void applyRotation(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double horizDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = Mth.clamp((float) (-Mth.atan2(diff.y, horizDist) * (180.0 / Math.PI)), -90.0f, 90.0f);

        if (config.rotationMode == RotationMode.CLIENT) {
            player.setYRot(yaw);
            player.setXRot(pitch);
        } else if (config.rotationMode == RotationMode.SERVER) {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    yaw, pitch, player.onGround(), player.horizontalCollision
            ));
        }
    }

    private void spawnPlaceParticles(ClientLevel level, BlockPos pos) {
        if (!config.particles) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < 6; i++) {
            double ox = (level.getRandom().nextDouble() - 0.5) * 0.9;
            double oy = (level.getRandom().nextDouble() - 0.5) * 0.9;
            double oz = (level.getRandom().nextDouble() - 0.5) * 0.9;
            level.addParticle(ParticleTypes.BUBBLE_POP, cx + ox, cy + oy, cz + oz, 0.0, 0.04, 0.0);
        }
        level.addParticle(ParticleTypes.HAPPY_VILLAGER, cx, cy + 0.65, cz, 0.0, 0.03, 0.0);
    }

    private void spawnFillParticles(ClientLevel level, BlockPos pos) {
        if (!config.particles) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < 3; i++) {
            double ox = (level.getRandom().nextDouble() - 0.5) * 0.6;
            double oy = (level.getRandom().nextDouble() - 0.5) * 0.6;
            double oz = (level.getRandom().nextDouble() - 0.5) * 0.6;
            level.addParticle(ParticleTypes.BUBBLE_POP, cx + ox, cy + oy, cz + oz, 0.0, 0.03, 0.0);
        }
    }

    private void spawnSteamParticles(ClientLevel level, BlockPos pos) {
        if (!config.particles) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.7;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < 4; i++) {
            double ox = (level.getRandom().nextDouble() - 0.5) * 0.6;
            double oz = (level.getRandom().nextDouble() - 0.5) * 0.6;
            level.addParticle(ParticleTypes.CLOUD, cx + ox, cy, cz + oz, 0.0, 0.04, 0.0);
        }
    }

    private void spawnBreakParticles(ClientLevel level, BlockPos pos) {
        if (!config.particles) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < 5; i++) {
            double ox = (level.getRandom().nextDouble() - 0.5) * 0.7;
            double oy = (level.getRandom().nextDouble() - 0.5) * 0.7;
            double oz = (level.getRandom().nextDouble() - 0.5) * 0.7;
            level.addParticle(ParticleTypes.SPLASH, cx + ox, cy + oy, cz + oz, 0.0, 0.05, 0.0);
        }
    }

    private boolean isPredictedDry(BlockPos pos) {
        Long expire = predictedDry.get(pos.asLong());
        return expire != null && expire > tickCounter;
    }

    private boolean isActiveWater(ClientLevel level, BlockPos pos, Set<Long> extraDry) {
        if (isPredictedDry(pos)) {
            return false;
        }
        if (extraDry != null && extraDry.contains(pos.asLong())) {
            return false;
        }
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    private boolean isActiveWaterSource(ClientLevel level, BlockPos pos, Set<Long> extraDry) {
        if (isPredictedDry(pos)) {
            return false;
        }
        if (extraDry != null && extraDry.contains(pos.asLong())) {
            return false;
        }
        FluidState fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && fluid.isSource();
    }
}
