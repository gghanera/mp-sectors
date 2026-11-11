[English](README.md) | [Русский](README.ru.md) | [Releases](https://github.com/gghanera/mp-sectors/releases)

# MP Sectors — a multi-sector Mindustry campaign

One coordinator plus up to **5 simultaneously loaded sectors** and **10 players**.
Players join the coordinator, open the sector map and land wherever the campaign allows.
Each sector runs as its own headless Mindustry server, so the world keeps running while
players are elsewhere.

This repository contains everything needed to build and run the setup:

| Component | Path | What it is |
|---|---|---|
| **mp-sectors** | `src/`, `mod.hjson` | Server mod: coordinator, sector instances, snapshots, research sync, chat relay |
| **mp-campaign** | `client/` | Client mod: sector map, landing, snapshots, Time Control, cross-sector chat |
| **any-research** | `any-research/` | Client/server bridge that lets every player research on a dedicated server |
| **Coordinator / launcher** | `mp-sectors.ps1`, `client/src/.../ServerProcess.java` | Starts the home sector and spawns sector servers on demand |
| **Docs** | `docs/` | Full Russian description and changelog |

The official Mindustry server/client jars are **not** committed. `fetch.ps1` downloads them
from the official Anuken/Mindustry releases.

---

## Requirements

- **Windows** (the launcher and scripts are PowerShell; the Java mods themselves are portable).
- **PowerShell 7** (`pwsh`).
- **JDK 17 or newer** on the server. The Steam-bundled JRE is trimmed and cannot run a
  dedicated server; the launcher looks for `JAVA_HOME` or `java` in `PATH`.
- Mindustry **build 160.x** (the mods target 160; min game version 160).
- For remote play: **Radmin VPN / ZeroTier / Tailscale**, or port forwarding. Steam
  networking is experimental and disabled by default.

---

## Quick start

### 1. Server

```powershell
git clone https://github.com/gghanera/mp-sectors.git
cd mp-sectors

.\fetch.ps1                 # downloads libs\server-release.jar
.\build.ps1                 # builds build\mp-sectors.jar
.\mp-sectors.ps1 coordinator
```

The coordinator prints:

```
coordinator ready: game 127.0.0.1:6567, http 127.0.0.1:8090
```

Players connect to `IP:6567` like to a normal Mindustry server. The coordinator hosts the
**home sector**; other sectors are started on demand.

Stop it with:

```powershell
.\mp-sectors.ps1 coordinator-stop
```

### 2. Clients

Every player copies into `%APPDATA%\Mindustry\mods` (Steam: `...\saves\mods`):

- `mods\mp-campaign.jar` — sector map, landing, snapshots, chat;
- `client-mods\any-research.jar` — research on a dedicated server.

Then restart Mindustry and join `IP:6567`.

> `any-research.jar` is **client-side only**. Do not put it into the server mods folder,
> otherwise its command is registered twice.

### 3. In-game launcher (optional)

Instead of PowerShell, open **Main menu → “Сервер кампании”** (bottom-left) or press `F9`.
The launcher writes `coordinator/config/mp-coordinator.json`, copies the mods, starts the
server jar on the game JRE and shows status. It also has a **“Карта”** button that opens the
server's campaign map before you connect.

### 4. One-file server installer

Download **`Install-MP-Sectors.cmd`** from the repository root (or the release assets) and
run it on the server PC. It:

- downloads the latest release and installs the server into `.\MP-Sectors`;
- puts `mp-campaign.jar` and `any-research.jar` next to itself for the players;
- checks/installs Java 17+ and PowerShell 7 if needed;
- creates `start-server.cmd`, `stop-server.cmd`, firewall rules and a desktop shortcut.

It does **not** include a campaign. Copy your `campaign` folder into
`MP-Sectors\campaign` and run `start-server.cmd`.

---

## How it works

```
player ── TCP ──> coordinator (home sector, game port 6567)
                       │
                       ├── HTTP API :8090  (sector list, landing, snapshots, chat, history)
                       │
                       ├── instance 6570  sector A
                       ├── instance 6571  sector B
                       ├── instance 6572  sector C
                       └── ...
```

- The coordinator is a normal headless Mindustry process with the **mp-sectors** mod.
- It hosts the home sector and keeps the campaign folder (`campaign/`): sector saves,
  `SectorInfo`, research, home, notes, history.
- When a player lands on another sector, the coordinator starts a headless server for it
  (up to `maxSectors`, including the home), waits for the port, and tells the client where
  to connect.
- All instances share the same campaign folder. Research is merged live through
  `campaign/unlocks.json`; launch costs and accelerator requests travel through
  `campaign/requests/`.
- An empty instance saves and shuts down after `idleStopSeconds`.

---

## Features

### Sector map and landing

- `N` (or pause → planet map) opens the campaign map with the coordinator's data.
- Sectors are gated by the campaign: starting sector, neighbouring story sectors, numbered
  sectors after a better core, other planets only via an existing base.
- Landing on a sector without a base opens the vanilla core loadout dialog; the schematic
  and resources cost is deducted from the source sector.
- Lost/broken sectors are detected (no core / not a campaign map), saved, marked unowned and
  regenerated on the next landing. **Destroyed-building plans** (the `B` ghosts) are carried
  into the regenerated sector.
- Normal landing and the Interplanetary Accelerator play the vanilla launch animation.
- Thorium reactors explode like vanilla: `reactorExplosions` is forced on when a sector loads.

### Campaign snapshots

- The whole campaign is copied every 10 minutes to `F:\MP-Sectors\backups` (configurable
  with `snapshotDir`), keeping the newest **50**.
- **Daily** snapshots: the first snapshot of a day is also stored in `backups\daily\<date>`
  and kept for **7 days**.
- **Before-restore** snapshot: every rollback first creates `before-restore_<date>` so a bad
  restore can be undone.
- Startup **integrity check**: each snapshot must contain a readable `.msav`; broken ones are
  marked “повреждён” and cannot be restored.
- Manual snapshot: `Ctrl+S` (moderators) or the “Создать снимок” button; `mpsnapshot` in the
  coordinator console.

### Cross-sector chat

Players in different sectors see each other's chat:

- each sector server forwards `PlayerChatEvent` to the coordinator (`POST /chat`);
- clients poll `GET /chat?since=<id>` every 2 seconds and add messages from other sectors
  to the normal chat as `[sector] Name: text`;
- messages from your own sector are skipped (vanilla already shows them);
- the buffer is 200 lines in memory; commands are not relayed; the server word filter is
  applied before relaying.

### Moderators

Moderators can pause, change speed, abandon sectors and manage snapshots. The list is
`campaign\moderators.json` (default `["GGhanera"]`):

```json
["GGhanera", "IgorPank"]
```

- manage it from the server console: `mpmod add <name>`, `mpmod remove <name>`, `mpmod list`;
- editing the file by hand is also supported; changes are picked up without a restart;
- the coordinator and every instance share the file; permissions are checked on the client
  and on the server;
- regular players cannot pause, change speed, abandon a sector or restore snapshots.

### Admin panel (moderators)

`mp-campaign` 2.1.3+ adds an in-game admin panel: press **F10** (also in the main menu,
where the player entity does not exist yet) or use the **«Админка»** button on the campaign
map. It talks to the coordinator's `POST /admin` and checks the player name against
`campaign\moderators.json`.

From the panel a moderator can:

- **pause / resume** the home sector and every running sector at once;
- set the campaign **speed** to `x0.5`, `x1`, `x2` or `x4`;
- create a **snapshot** without leaving the game;
- **stop** a running sector (it is saved first, players are disconnected);
- **kick** a player from the home or any running sector;
- **add or remove moderators**;
- read the last 150 lines of the coordinator or a sector **log**.

Pause, speed and kick are relayed through `campaign\requests`: the coordinator writes a command
and each instance reads it within ~2 seconds, so the panel does not need direct access to the
sector ports. Moderator actions are written to `campaign\history.json`.

The HTTP API still has no token, so the panel is meant for a trusted network
(Radmin/VPN/LAN). Do not expose port `8090` to the internet.

### Map UI

- **Sector stats**: wave, core items/capacity, production, imports and exports per minute.
- **Filters**: All / Bases / Attacked / Allowed, with a clickable sector list.
- **Legend**: base, attacked, not unlocked, “you are here”.
- **Names and notes**: “Заметка” stores a custom sector name and note in
  `campaign\notes.json`.
- **History**: landings, captures, losses, home changes, launch pads, snapshots, restores and
  notes in `campaign\history.json`.

### Other campaign features

- **Research sync**: `any-research` lets any player research on a dedicated server; unlocks
  are merged into `campaign/unlocks.json` and applied live in all running sectors.
- **Launch pads**: “Направить пусковые площадки” points every pad on the planet at the
  selected sector; running instances pick the change up in ~2 seconds.
- **Time Control** (`sk7725timecontrol`) is mirrored onto the server: the speed slider
  changes the world for everyone in that sector.
- **Pause** (`Space` / `/pause`), **pause all** (`/pauseall` / `/resumeall`), **abandon**
  (`/abandon` or the pause-menu button), and the vanilla **Interplanetary Accelerator** are
  supported. Pause-all freezes the home and every running sector and blocks landing on new
  sectors until `/resumeall`; returning to the home lobby is still allowed.
- **Two windows on one PC** for testing: duplicate clients are allowed by default.

### Co-op tools (2.2.0)

- **`/who` and «Кто где»**: `/who` prints who is in which sector; the map's «Кто где» button
  opens the same list. Names come from the coordinator's live player count.
- **Chat survives restarts**: the last 200 lines are stored in `campaign/chat.json` and
  reloaded by the coordinator (and after a snapshot restore). `@nick` mentions are highlighted
  and play a sound; each player is limited to 5 cross-sector lines per 10 seconds.
- **`/speedall <x>`**: change the speed of the home and every running sector at once.
- **`/snapshot` in chat**: a moderator can create a campaign snapshot without Ctrl+S.
- **Snapshot comments** (`Комментарий` in the snapshot window) and **per-sector restore**
  (`Сектор...`): restore one non-home sector from a snapshot; the home is still restored with
  the full campaign.
- **Campaign-wide bans**: `/ban <name>`, `/unban <name>`, `/bans`; the list lives in
  `campaign/bans.json` and applies to the home and every sector.
- **Watchdog**: if an instance crashes, the coordinator writes it to chat/history and restarts
  it, up to 3 times in 10 minutes.
- **Global player limit**: direct connections to an instance port are now counted; if the
  campaign stays over `maxPlayers` for 5 seconds, extra players are kicked.
- **Version check**: the coordinator reports `serverVersion` and `campaignVersion`; the client
  shows «Обнови mp-campaign» if the versions differ. The coordinator also checks GitHub
  Releases once a day and logs a newer version (no auto-download; disable with
  `"updateCheck": false`).

---

## Configuration

### Coordinator (`coordinator/config/mp-coordinator.json`)

| Field | Default | Meaning |
|---|---|---|
| `campaign` | — | Absolute path to the campaign folder |
| `httpPort` | `8090` | HTTP API port |
| `gamePort` | `6567` | Home sector game port |
| `maxSectors` | `5` | Loaded sectors including home |
| `maxPlayers` | `10` | Total players |
| `playerLimit` | `5` | Per-sector player limit |
| `instanceBasePort` | `6570` | First port for sector instances |
| `idleStopSeconds` | `60` | Stop an empty instance after this |
| `autosaveSeconds` | `60` | Home autosave interval |
| `snapshotDir` | `F:\MP-Sectors\backups` | Snapshot folder (empty = `backups` next to campaign) |
| `updateCheck` | `true` | Check GitHub Releases once a day and log a newer version |
| `memoryMb` | `1024` | JVM heap per instance |
| `allowDuplicateClients` | `true` | Allow two windows from one PC |
| `serverJar`, `javaPath` | — | Server jar and JDK paths |

### Campaign files

```
campaign/
  sectors/<planet>-<id>.msav   sector saves
  info/<planet>-<id>.json      SectorInfo
  home.json                    current home sector
  unlocks.json                 shared research
  moderators.json              moderator names
  bans.json                    campaign-wide bans
  chat.json                    last 200 chat lines
  paused.flag                  campaign-wide pause marker
  notes.json                   custom sector names/notes
  history.json                 campaign log
  requests/                    transient launch/accelerator requests
```

Back up `campaign/` — it is the whole campaign.

---

## Commands

**In-game client**

| Key / command | Action |
|---|---|
| `N` | Sector map |
| `F9` | Campaign server launcher |
| `Space` | Pause / resume (moderators) |
| `Ctrl+S` | Snapshot now (moderators) |
| `Escape → “Покинуть”` | Abandon sector (moderators) |

**Chat commands**

| Command | Action |
|---|---|
| `/pause` | Pause / resume (moderators) |
| `/pauseall` | Pause the whole campaign and block landings (moderators) |
| `/resumeall` | Resume the whole campaign (moderators) |
| `/speed <x>` | Game speed, e.g. `4` or `0.5` (moderators) |
| `/speedall <x>` | Speed of the whole campaign (moderators) |
| `/snapshot` | Create a campaign snapshot (moderators) |
| `/who` | Who is in which sector |
| `/ban <name>`, `/unban <name>`, `/bans` | Campaign-wide bans (moderators) |
| `/abandon` | Abandon sector (moderators) |
| `/anyresearch ...` | Client half of Any Research |

**Server console**

| Command | Action |
|---|---|
| `mpsave` | Save the current sector |
| `mpsnapshot` | Snapshot the campaign now |
| `mpmod add\|remove\|list` | Manage moderators |
| `anyresearch <name>` | Research a node (admin) |
| `pause`, `pauseall`, `resumeall`, `speed <x>`, `speedall <x>`, `abandon` | Campaign controls |
| `who` | Who is in which sector |
| `mpsnapshot` (snapshots), `ban`/`unban` (vanilla console) | See the chat commands for shared bans |

---

## Building from source

```powershell
# server mod
.\fetch.ps1
.\build.ps1                  # build\mp-sectors.jar

# client mod (needs the desktop jar)
.\fetch.ps1 -Client          # client\libs\Mindustry.jar
cd client
.\build.ps1                  # client\build\mp-campaign.jar

# any-research (client-side)
cd ..\any-research
.\build.ps1 -GameJar ..\client\libs\Mindustry.jar
                             # any-research\build\any-research.jar
```

The build scripts use `javac --release 17` and `jar` from the JDK.

Create a player-facing zip (mods + scripts + docs):

```powershell
.\package.ps1 -Version 2.2.3     # dist\MP-Sectors-2.2.3.zip
```

Smoke-test the moderator admin endpoint (starts a throwaway coordinator on a temp copy of
the campaign):

```powershell
.\test-admin.ps1
```

---

## Networking

- Recommended: **Radmin VPN / ZeroTier / Tailscale**. Players connect to the host's virtual IP.
- Or forward TCP+UDP `6567` and `6570–6574`, plus HTTP `8090` for the map.
- The Windows firewall must allow inbound `8090` and the game ports; Radmin is often treated
  as a “public” network.
- **Steam networking is off by default.** The Steam provider breaks the dedicated-server
  handshake; use TCP instead. `mp-sectors.ps1` enables Steam only with `-Steam`.
- Steam saves are not touched; the campaign is a separate `campaign/` folder.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Server exits right after start | The Steam JRE is trimmed. Install JDK 17+ and set `JAVA_HOME` or the **Java** field in the launcher. |
| “Не удалось запустить сектор” | The save has no core or is not a campaign map; land again to regenerate it. Plans are preserved. |
| Map does not open / “Координатор недоступен” | Check `http://IP:8090/status`, the firewall and the **Адрес** button. |
| Client and server mod versions differ | Update both `mp-campaign.jar` and `mp-sectors.jar`; the client mod is visible and version-checked. |
| Chat from other sectors is missing | All players need `mp-campaign.jar` 2.1.1+; the server needs the updated `mp-sectors.jar`. |
| Snapshot marked “повреждён” | The copy is incomplete; use another snapshot. The coordinator logs the check at startup. |

---

## Links

- [Latest release](https://github.com/gghanera/mp-sectors/releases/latest)
- [Русский README](README.ru.md)
- [Full Russian description and changelog](docs/MP-Sectors-полное-описание.txt)
- [Any Research README (English)](any-research/README.en.md)
- [Any Research README (Russian)](any-research/README.md)
- [Client mod README](client/README.md)

## License

No license has been chosen yet. If you plan to redistribute or accept contributions, add one
(for example MIT) before publishing.
