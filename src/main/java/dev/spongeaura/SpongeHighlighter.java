package dev.spongeaura;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders through-wall pink (dry) and purple (wet) outlines for placed sponge blocks
 * and dropped sponge items using Minecraft 26.3's native always-on-top Gizmos.
 */
public final class SpongeHighlighter {

    private static final GizmoStyle DRY_STYLE = GizmoStyle.strokeAndFill(0xFFFF55CC, 2.5f, 0x45FF55CC);
    private static final GizmoStyle WET_STYLE = GizmoStyle.strokeAndFill(0xFFAA44FF, 2.5f, 0x45AA44FF);

    private static final int SCAN_CHUNK_RADIUS = 4;
    private static final int SCAN_INTERVAL_TICKS = 10;

    private record SpongeMarker(BlockPos pos, boolean wet) {}

    private static volatile List<SpongeMarker> cachedBlocks = List.of();
    private static int scanTicker = 0;
    private static boolean rescanRequested = true;

    private SpongeHighlighter() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(SpongeHighlighter::onBeforeGizmos);
    }

    public static void requestRescan() {
        rescanRequested = true;
    }

    public static void clear() {
        cachedBlocks = List.of();
        scanTicker = 0;
        rescanRequested = true;
    }

    public static void onClientTick(Minecraft client) {
        if (!SpongeAuraClient.getConfig().highlightSponges
                || client.level == null
                || client.player == null) {
            if (!cachedBlocks.isEmpty()) {
                clear();
            }
            return;
        }

        if (!rescanRequested && ++scanTicker < SCAN_INTERVAL_TICKS) {
            return;
        }
        scanTicker = 0;
        rescanRequested = false;

        ClientLevel level = client.level;
        ChunkPos center = client.player.chunkPosition();
        int centerX = center.x();
        int centerZ = center.z();

        List<SpongeMarker> found = new ArrayList<>();
        for (int cx = centerX - SCAN_CHUNK_RADIUS; cx <= centerX + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = centerZ - SCAN_CHUNK_RADIUS; cz <= centerZ + SCAN_CHUNK_RADIUS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                LevelChunkSection[] sections = chunk.getSections();
                int minSectionY = chunk.getMinSectionY();

                for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                    LevelChunkSection section = sections[sectionIdx];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    if (!section.maybeHas(s -> s.is(Blocks.SPONGE) || s.is(Blocks.WET_SPONGE))) {
                        continue;
                    }

                    int baseX = SectionPos.sectionToBlockCoord(cx);
                    int baseY = SectionPos.sectionToBlockCoord(minSectionY + sectionIdx);
                    int baseZ = SectionPos.sectionToBlockCoord(cz);

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                boolean dry = state.is(Blocks.SPONGE);
                                boolean wet = !dry && state.is(Blocks.WET_SPONGE);
                                if (dry || wet) {
                                    found.add(new SpongeMarker(
                                            new BlockPos(baseX + x, baseY + y, baseZ + z),
                                            wet
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }

        cachedBlocks = found;
    }

    private static void onBeforeGizmos(LevelRenderContext context) {
        if (!SpongeAuraClient.getConfig().highlightSponges) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<SpongeMarker> markers = cachedBlocks;

        try (Gizmos.TemporaryCollection ignored = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
            for (SpongeMarker marker : markers) {
                BlockState liveState = level.getBlockState(marker.pos());
                GizmoStyle style;
                if (liveState.is(Blocks.SPONGE)) {
                    style = DRY_STYLE;
                } else if (liveState.is(Blocks.WET_SPONGE)) {
                    style = WET_STYLE;
                } else {
                    continue;
                }
                Gizmos.cuboid(marker.pos(), 0.004f, style).setAlwaysOnTop();
            }

            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity itemEntity)) {
                    continue;
                }
                ItemStack stack = itemEntity.getItem();
                boolean dry = stack.is(Items.SPONGE);
                boolean wet = !dry && stack.is(Items.WET_SPONGE);
                if (!dry && !wet) {
                    continue;
                }

                Vec3 interpPos = itemEntity.getPosition(partialTick);
                Vec3 delta = interpPos.subtract(itemEntity.position());
                AABB box = itemEntity.getBoundingBox().move(delta).inflate(0.08);
                Gizmos.cuboid(box, wet ? WET_STYLE : DRY_STYLE).setAlwaysOnTop();
            }
        }
    }
}
