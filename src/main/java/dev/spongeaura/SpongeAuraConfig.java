package dev.spongeaura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SpongeAuraConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("spongeaura");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("spongeaura.json");

    public enum EfficiencyMode {
        MAX_EFFICIENCY(
                "Max Efficiency",
                "High efficiency: 48+ net permanent sources/sponge (65%+ kept), full wipes of standalone 6+ pockets, and filler blocks for 1-5 sources."
        ),
        PRACTICAL(
                "Practical",
                "Balanced perimeter clearing: 22+ net permanent sources/sponge (50%+ kept), full wipes of 6+ pockets, and filler blocks for 1-5 sources."
        );

        private final String label;
        private final String description;

        EfficiencyMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public Component displayName() {
            return Component.literal(label);
        }

        public Component tooltip() {
            return Component.literal(description);
        }
    }

    public enum PlacementMode {
        DIRECT("Direct", "Places sponges directly onto water sources (fastest, best for singleplayer/no-anticheat)."),
        LEGIT("Legit", "Only places against solid adjacent block faces (more server/anticheat friendly).");

        private final String label;
        private final String description;

        PlacementMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public Component displayName() {
            return Component.literal(label);
        }

        public Component tooltip() {
            return Component.literal(description);
        }
    }

    public enum RotationMode {
        NONE("None", "Does not rotate your player when placing or breaking sponges."),
        SERVER("Server", "Sends silent rotation packets toward targets without moving your camera."),
        CLIENT("Client", "Visibly aims your camera toward each target.");

        private final String label;
        private final String description;

        RotationMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public Component displayName() {
            return Component.literal(label);
        }

        public Component tooltip() {
            return Component.literal(description);
        }
    }

    public enum WetSpongeBreakMode {
        SAFE("Safe", "Breaks wet sponges only when no water touches their sides or top."),
        ALWAYS("Always", "Breaks any wet sponge in reach (warning: surrounding water may flow back)."),
        OFF("Off", "Leaves wet sponges in place.");

        private final String label;
        private final String description;

        WetSpongeBreakMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public Component displayName() {
            return Component.literal(label);
        }

        public Component tooltip() {
            return Component.literal(description);
        }
    }

    public EfficiencyMode efficiencyMode = EfficiencyMode.PRACTICAL;
    public PlacementMode placementMode = PlacementMode.DIRECT;
    public RotationMode rotationMode = RotationMode.NONE;
    public WetSpongeBreakMode wetSpongeBreakMode = WetSpongeBreakMode.SAFE;

    public double reach = 4.5;
    public int placeDelayTicks = 1;

    public boolean netherAutoDry = true;
    public boolean highlightSponges = true;
    public boolean fillTinyWithBlocks = true;
    public boolean autoRefill = true;
    public boolean particles = true;

    public boolean areaLimitEnabled = false;
    public int areaX1 = -128;
    public int areaZ1 = -128;
    public int areaX2 = 128;
    public int areaZ2 = 128;

    public int areaMinX() {
        return Math.min(areaX1, areaX2);
    }

    public int areaMaxX() {
        return Math.max(areaX1, areaX2);
    }

    public int areaMinZ() {
        return Math.min(areaZ1, areaZ2);
    }

    public int areaMaxZ() {
        return Math.max(areaZ1, areaZ2);
    }

    public boolean isInsideWorkArea(int blockX, int blockZ) {
        if (!areaLimitEnabled) {
            return true;
        }
        return blockX >= areaMinX() && blockX <= areaMaxX()
                && blockZ >= areaMinZ() && blockZ <= areaMaxZ();
    }

    public boolean isInsideWorkArea(BlockPos pos) {
        return isInsideWorkArea(pos.getX(), pos.getZ());
    }

    public boolean isNearWorkArea(BlockPos pos, int margin) {
        if (!areaLimitEnabled) {
            return true;
        }
        return pos.getX() >= areaMinX() - margin && pos.getX() <= areaMaxX() + margin
                && pos.getZ() >= areaMinZ() - margin && pos.getZ() <= areaMaxZ() + margin;
    }

    public static SpongeAuraConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                SpongeAuraConfig loaded = GSON.fromJson(reader, SpongeAuraConfig.class);
                if (loaded != null) {
                    loaded.validate();
                    return loaded;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load SpongeAura config, using defaults", e);
            }
        }
        SpongeAuraConfig fresh = new SpongeAuraConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        validate();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save SpongeAura config", e);
        }
    }

    public void validate() {
        if (efficiencyMode == null) {
            efficiencyMode = EfficiencyMode.PRACTICAL;
        }
        if (placementMode == null) {
            placementMode = PlacementMode.DIRECT;
        }
        if (rotationMode == null) {
            rotationMode = RotationMode.NONE;
        }
        if (wetSpongeBreakMode == null) {
            wetSpongeBreakMode = WetSpongeBreakMode.SAFE;
        }
        reach = Math.max(2.5, Math.min(5.5, Math.round(reach * 10.0) / 10.0));
        placeDelayTicks = Math.max(0, Math.min(10, placeDelayTicks));
    }

    public void resetDefaults() {
        efficiencyMode = EfficiencyMode.PRACTICAL;
        placementMode = PlacementMode.DIRECT;
        rotationMode = RotationMode.NONE;
        wetSpongeBreakMode = WetSpongeBreakMode.SAFE;
        reach = 4.5;
        placeDelayTicks = 1;
        netherAutoDry = true;
        highlightSponges = true;
        fillTinyWithBlocks = true;
        autoRefill = true;
        particles = true;
        areaLimitEnabled = false;
        areaX1 = -128;
        areaZ1 = -128;
        areaX2 = 128;
        areaZ2 = 128;
        save();
    }
}
