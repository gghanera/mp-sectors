[English](README.en.md) | [Русский](README.md)

# Any Research (Mindustry mod, build 160.x)

Lets **any player who has this mod** research tech-tree nodes on a multiplayer server, not
just the host. Players without the mod can still play, they just cannot research. Research
follows the **vanilla tech tree rules**: node objectives are checked, parent nodes must be
researched, and the cost is deducted from the campaign stockpile.

## Installation

- **Client:** `%APPDATA%\Mindustry\mods\any-research.jar` (Steam: `...\saves\mods`).
- **Standalone dedicated server:** `<server folder>/config/mods/any-research.jar`.
- **In MP-Sectors:** do **not** install it on the server — the server half is already built
  into `mp-sectors` (otherwise the command is registered twice). The client jar is enough.

## Usage

- A guest with the mod clicks a tech-tree node. The server answers in chat with one of:
  - `Researched: <name> (spent: copper 25)` — the cost was deducted;
  - `Not enough resources: silicon 40/120, graphite 0/50` — what is missing;
  - `Objectives not met: research lead, capture sector frozenForest` — the node is still locked;
  - `Research first: <name>` — a parent node is missing.
- The host researches as usual; the mod does not interfere.
- Server console: `anyresearch <name>` (same as a player click) and
  `anyresearch-status [name]` (diagnostics).

## Rules

As in the vanilla tree (`ui/dialogs/ResearchDialog.java`):

- **Node objectives** (`TechNode.objectives`) must all be completed. This is what keeps
  items (obtained by mining) and sectors (obtained by landing) locked.
- **Parent research**: the parent chain must be researched.
- **Cost**: deducted from the same stockpile the game shows — the planet's bases first, the
  current sector last. If something is missing, nothing is deducted and the research is
  cancelled. The code verifies that the stockpile actually decreased, so a node cannot be
  opened for free.

## Known limitations

- Payment is all-at-once; partial progress is not supported. The host's already paid part
  (`TechNode.finishedRequirements`) is taken into account, so guests pay only the remainder.
- The “mod required” gate is a constant inside the jar, not an anti-cheat.
- The mod targets build 160.x.

## Building

```powershell
cd any-research
.\build.ps1 -GameJar ..\client\libs\Mindustry.jar
```

Requires JDK 17+ and the desktop `Mindustry.jar`. If it is in `client\libs`, the path can be
omitted.
