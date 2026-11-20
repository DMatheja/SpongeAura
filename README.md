# SpongeAura

> **Smart perimeter-prep sponge automation for Minecraft 26.3 (Fabric · Java 25)**  
> *by David Matheja — David Industries™*

SpongeAura automates draining rivers, water caves, aquifers, and stray pockets without wasting sponges on self-refilling ocean holes, and auto-dries wet sponges in the Nether.

---

## Quick Install

1. Install **Fabric Loader** (`>=0.19.3`) and **Fabric API** for **Minecraft 26.3** (e.g., via Modrinth App).
2. *(Optional)* Install **Mod Menu** to open settings from the Mods list.
3. Download **`spongeaura-2.2.2.jar`** (do **not** use `-sources.jar`) and drop it into your instance's `mods/` folder.
4. Launch Minecraft.

---

## Controls

All keybinds can be changed under **Options → Controls → Key Binds → SpongeAura**.

| Key | Action |
| :---: | :--- |
| **`G`** | Toggle SpongeAura **ON / OFF** *(Drains water in Overworld/End · Auto-Dries in Nether)* |
| **`H`** | Switch Efficiency Mode (**Max Efficiency** ↔ **Practical**) |
| **`J`** | Open **SpongeAura Settings** GUI |
| **`Shift + Right-Click`** | Bypass manual placement takeover and force a raw sponge placement |

---

## Features

- **Source-Only Targeting & Anti-Refill Physics**
  - Strictly targets water **source** blocks (never wastes sponges on flowing water).
  - Simulates Vanilla's 65-block / depth-6 BFS plus Minecraft's 5-tick infinite-water repair rule, plus a sub-5-tick burst check to cut off regenerating edges.
  - **Two Efficiency Modes (`H`):**
    - **`Max Efficiency`**: `48+` net permanent sources/sponge (`65%+` kept dry) on large bodies + full wipes of standalone `6–65` pockets.
    - **`Practical`** *(default)*: `22+` net permanent sources/sponge (`50%+` kept dry) on large bodies + full wipes of standalone `6–65` pockets.
- **Tiny-Source Auto-Fill (`1–5` sources)**
  - Automatically places **Netherrack → Dirt → Cobblestone** into tiny `1–5` source pockets instead of wasting sponges (`Fill Tiny (1-5)` in Settings).
- **Manual Placement Takeover**
  - Right-clicking while holding a dry sponge redirects the click to SpongeAura's current best non-refilling spot (hold `Shift` to force raw placement).
- **Nether Auto-Dry**
  - Press **`G`** in the Nether (or any water-evaporating dimension) to automatically place wet sponges onto safe nearby floor tiles (avoiding lava, fire, magma, portals, and cliffs), instamine them with your fastest tool (e.g., Efficiency V Netherite Hoe), and stop automatically when done.
- **Through-Wall Highlighting**
  - Outlines **dry sponges** in **pink** and **wet sponges** in **purple** (both placed blocks and dropped items) through walls.
- **Quality of Life**
  - `Direct` or `Legit` (adjacent solid block face) placement, `None`/`Server`/`Client` rotations, tool-safe `Auto-Refill` from main inventory, `Safe`/`Always`/`Off` wet-sponge auto-breaking, persistent `config/spongeaura.json`, and Mod Menu support.

---

## Building from Source

Requires **Java 25 JDK**. No pre-installed Gradle is needed — use the included Gradle Wrapper:

**Windows (PowerShell / CMD):**
```powershell
.\gradlew.bat clean jar
```

**Linux / macOS:**
```bash
./gradlew clean jar
```

The compiled mod will be in `build/libs/spongeaura-2.2.2.jar`.
