package mpsectors;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.RulesLoadEvent;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.SectorInfo;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.world.Tile;
import mindustry.world.WorldContext;
import mindustry.world.blocks.campaign.Accelerator;
import mindustry.world.blocks.storage.CoreBlock;

/**
 * Loads and saves one campaign sector in a headless process.
 *
 * A sector with a save is loaded through SaveIO.load(). A sector that was never played is generated
 * the way Control.playSector() does it (world.loadSector + logic.play + saveSector), minus the UI.
 *
 * Shared campaign state lives next to the saves:
 *   <campaign>/sectors/<planet>-<id>.msav
 *   <campaign>/info/<planet>-<id>.json
 *   <campaign>/unlocks.json
 */
public class SectorRunner{

    private static java.lang.reflect.Field unlockedField;
    private static final Object unlocksLock = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>
        campaignLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public final String sectorName, planetName;
    public final int sectorId;
    public final Fi campaignSave, campaignInfo, unlocksFile, localSave;

    public volatile boolean loaded;
    public String loadout = "", resources = "";
    /** While a save is being loaded, its research must not be written back to the campaign file. */
    public static volatile boolean suppressUnlockWrites;
    private float lastImportTotal;

    public SectorRunner(String campaignPath, String sector){
        this.sectorName = sector;

        int split = sector.lastIndexOf('-');
        if(split <= 0 || split == sector.length() - 1) throw new IllegalArgumentException("Bad sector name: " + sector);

        planetName = sector.substring(0, split);
        sectorId = Integer.parseInt(sector.substring(split + 1));

        Fi campaign = Core.files.absolute(campaignPath);
        campaignSave = campaign.child("sectors").child(sector + ".msav");
        campaignInfo = campaign.child("info").child(sector + ".json");
        unlocksFile = campaign.child("unlocks.json");
        localSave = Vars.saveDirectory.child("sector-" + sector + ".msav");
    }

    public Sector sector(){
        Planet planet = Vars.content.getByName(ContentType.planet, planetName);
        return planet == null || sectorId < 0 || sectorId >= planet.sectors.size ? null : planet.sectors.get(sectorId);
    }

    public boolean exists(){
        return campaignSave.exists();
    }

    public boolean load(){
        Sector sector = sector();
        if(sector == null){
            Log.err("[mp-sectors] no such sector: @", sectorName);
            return false;
        }

        boolean previousSuppress = suppressUnlockWrites;
        suppressUnlockWrites = true;

        try{
            boolean hasSave = campaignSave.exists();

            //Campaign metadata is loaded BEFORE the save: it is the only home of the core cargo
            //(Building.write does not persist it), and the load path applies it once the save is
            //known to be valid. Instances have a fresh settings.bin, so there is no local copy.
            boolean infoUsable = false;
            if(campaignInfo.exists()){
                try{
                    SectorInfo parsed = JsonIO.read(SectorInfo.class, campaignInfo.readString());
                    //a neutral placeholder from verifyCampaign() must not overwrite the live
                    //world's wave rules; treat it as missing and recover from the save instead
                    if(parsed != null && (parsed.hasCore || parsed.wave != 0)){
                        sector.info = parsed;
                        infoUsable = true;
                    }
                }catch(Throwable t){
                    Log.err("[mp-sectors] could not read sector info", t);
                }
            }

            //a sector whose core is gone (lost, or a save made with content mods that are not
            //loaded here) must be generated again with the client's launch loadout.
            //SectorInfo.hasCore defaults to true, so a missing/unreadable info must not count:
            //without this test the save is loaded as empty metadata and Logic clears the core.
            boolean fromSave = hasSave && infoUsable && sector.info.hasCore;

            //metadata may be lost (crash between the save and info write, a bad copy, a manual
            //cleanup). The save itself is still valid; load it once and rebuild the metadata
            //instead of regenerating the sector and overwriting the player's base.
            boolean recovered = false;
            if(hasSave && !fromSave && !infoUsable){
                recovered = recoverFromSave();
                fromSave = recovered;
            }

            //a lost sector still has a save with the old buildings' plans (the B key ghosts);
            //read them before regenerating so the base can be rebuilt
            Seq<mindustry.game.Teams.BlockPlan> lostPlans = (hasSave && !fromSave) ? readLostPlans() : null;

            //true when the campaign info predates the save beside it and must not be applied
            boolean infoStale = false;

            if(fromSave){
                if(!recovered){
                    copy(campaignSave, localSave);
                    SaveMeta meta = readMeta(localSave);
                    infoStale = isInfoStale(sector, meta);

                    //load without letting Logic's SaveLoadEvent call SectorInfo.write(): that
                    //pours the campaign info over the freshly loaded world (core, wave, rules)
                    loadSave(sector, localSave);

                    //readRules only fills the sector in when the save carries a rules string;
                    //SectorInfo.write() below dereferences it, so guarantee one here
                    if(Vars.state.rules.sector == null) Vars.state.rules.sector = sector;

                    //a save without a sector is a built-in map that a broken instance rotated into
                    if(Vars.state.teams.playerCores().isEmpty()
                        || (meta != null && meta.rules != null && meta.rules.sector == null)){
                        Log.warn("[mp-sectors] sector @ has no core or is not a campaign map, marking it as unowned", sectorName);
                        markLost();
                        return false;
                    }

                    if(infoStale){
                        Log.warn("[mp-sectors] sector @ info is older than its save (wave @ < @), keeping the save",
                            sectorName, sector.info.wave, meta.wave);
                        //core cargo lives only in the campaign info (Building.write does not
                        //persist it), so apply the stock while the save keeps its wave rules
                        applyStock(sector.info);
                    }else{
                        //vanilla: the info is authoritative for core items and the wave rules
                        sector.info.write();
                    }
                }

                if(Vars.state.teams.playerCores().isEmpty()){
                    Log.warn("[mp-sectors] sector @ has no core or is not a campaign map, marking it as unowned", sectorName);
                    markLost();
                    return false;
                }
            }else{
                //never played: generate it like the campaign does on a free launch.
                //Vars.control is null on a dedicated server, so save through SaveIO directly.
                //reset first: a failed load of another save leaves stale rules (attack mode, waves)
                Vars.logic.reset();
                if(sector.preset != null) sector.preset.quietUnlock();

                //apply the loadout chosen by the client before the map generator places the core
                applyLaunchLoadout();

                Vars.world.loadSector(sector);
                Vars.state.rules.sector = sector;
                Vars.logic.play();

                if(lostPlans != null && !lostPlans.isEmpty()){
                    var teamData = Vars.state.rules.defaultTeam.data();
                    int carried = 0;
                    for(mindustry.game.Teams.BlockPlan plan : lostPlans){
                        var build = Vars.world.build(plan.x, plan.y);
                        if(build != null && build.block == plan.block && build.tileX() == plan.x && build.tileY() == plan.y && build.team != Vars.state.rules.waveTeam){
                            continue;
                        }
                        teamData.plans.add(plan);
                        carried++;
                    }
                    if(carried > 0) Log.info("[mp-sectors] carried @ plans into regenerated sector @", carried, sectorName);
                }

                SaveIO.save(localSave);

                //publish the generated save and its info as one unit: a snapshot must not catch
                //a new save next to the old (or missing) metadata
                withCampaignLock(campaignSave.parent().parent(), () -> {
                    //publish the generated save at once: the first autosave may be a minute away,
                    //and a crash before it would leave info.hasCore set with no campaign save
                    copy(localSave, campaignSave);

                    //SaveIO updated sector.info from the freshly placed core; publish it so the map
                    //immediately knows this sector has a base again
                    campaignInfo.parent().mkdirs();
                    writeAtomic(campaignInfo, JsonIO.write(sector.info));
                });
                Log.info("[mp-sectors] generated a new sector @", sectorName);
            }

            //a save copied under a different name still has to run as the configured sector
            if(Vars.state.rules.sector != null && Vars.state.rules.sector != sector){
                Log.warn("[mp-sectors] save was made for @, running it as @", Vars.state.rules.sector, sector);
            }
            Vars.state.rules.sector = sector;
            Vars.state.rules.planet = sector.planet;
            Vars.state.rules.cloudColor = sector.planet.landCloudColor;

            //modded campaign saves often ship with reactor explosions disabled; the campaign uses vanilla
            if(!Vars.state.rules.reactorExplosions){
                Log.info("[mp-sectors] sector @ had reactor explosions disabled; enabling", sectorName);
                Vars.state.rules.reactorExplosions = true;
            }

            if(fromSave){
                //do not call logic.play() here: it clears core items and resets wave timers.
                //Vanilla just resumes the loaded save, so keep the world and apply campaign waves.
                //A stale info must not overwrite the newer save's wave rules; a recovered sector
                //has no info at all and keeps the rules read from its save.
                if(!recovered && !infoStale){
                    Vars.state.rules.waves = sector.info.waves;
                    Vars.state.rules.winWave = sector.info.winWave;
                }

                //the suppressed SaveLoadEvent handler normally enforces these campaign flags
                Vars.state.rules.coreIncinerates = true;
                Vars.state.rules.canGameOver = true;
                Vars.state.rules.allowEditRules = false;

                Events.fire(new RulesLoadEvent(Vars.state.rules, true));
                Vars.state.set(GameState.State.playing);
            }

            if(fromSave){
                //the save is the source of truth for core contents; rebuild the campaign info
                //so a crash between the save and its info write is repaired on the next load
                withCampaignLock(campaignInfo.parent().parent(), () -> {
                    try{
                        sector.info.prepare(sector);
                        campaignInfo.parent().mkdirs();
                        writeAtomic(campaignInfo, JsonIO.write(sector.info));
                    }catch(Throwable t){
                        Log.err("[mp-sectors] could not refresh sector info for @", sectorName, t);
                    }
                });
            }

            refreshImportRates();
            applyUnlocks();
            loaded = true;
            return true;
        }catch(Throwable t){
            Log.err("[mp-sectors] failed to load sector @", sectorName);
            Log.err(t);
            return false;
        }finally{
            suppressUnlockWrites = previousSuppress;
        }
    }

    /**
     * Loads the save just to see whether it still has a player core. Used when the sector info is
     * missing or unreadable: without this a perfectly good save would be regenerated and lost.
     *
     * The load must not apply SectorInfo.write(): the world in memory is the one the sector runs
     * from, and the empty default info would clear its core before the callback sees it.
     */
    private boolean recoverFromSave(){
        try{
            copy(campaignSave, localSave);

            Sector sector = sector();
            if(sector == null) return false;

            //the custom load context forces this sector into the rules, so a built-in map (a
            //broken instance rotated into one) has to be rejected from the save header instead
            SaveMeta meta = readMeta(localSave);
            if(meta != null && meta.rules != null && meta.rules.sector == null){
                return false;
            }

            loadSave(sector, localSave);

            if(Vars.state.teams.playerCores().isEmpty() || Vars.state.rules.sector == null){
                return false;
            }

            Log.info("[mp-sectors] recovered sector info for @ from its save", sectorName);
            return true;
        }catch(Throwable t){
            Log.err("[mp-sectors] could not recover sector info for @", sectorName, t);
            return false;
        }
    }

    /** Reads the destroyed buildings' plans from a lost sector's save, before regenerating it. */
    private Seq<mindustry.game.Teams.BlockPlan> readLostPlans(){
        try{
            copy(campaignSave, localSave);

            Sector sector = sector();
            if(sector == null) return null;

            loadSave(sector, localSave);

            if(Vars.state.rules.sector != sector) return null;

            Seq<mindustry.game.Teams.BlockPlan> plans = new Seq<>();
            for(mindustry.game.Teams.BlockPlan plan : Vars.state.rules.defaultTeam.data().plans){
                plans.add(plan);
            }
            return plans;
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read plans from @", sectorName, t);
            return null;
        }
    }

    /**
     * Loads a sector save without the vanilla SaveLoadEvent behaviour: Logic would call
     * SectorInfo.write() on the freshly loaded world, copying campaign metadata (core cargo, wave,
     * wave rules) over the save. The caller applies the info itself once it knows the save is valid.
     */
    private static void loadSave(Sector sector, Fi file){
        SaveIO.load(file, new SectorLoadContext(Vars.world.context, sector));
    }

    /** @return the save header, or null when it cannot be read. */
    private static @Nullable SaveMeta readMeta(Fi file){
        try{
            return SaveIO.getMeta(file);
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read the save header of @", file.name(), t);
            return null;
        }
    }

    /**
     * A crash between SaveIO.save() and the info write leaves the previous info beside a newer
     * save; applying it would roll the sector back (and persist the rollback on the next save).
     */
    private boolean isInfoStale(Sector sector, @Nullable SaveMeta meta){
        if(meta == null) return false;
        if(sector.info.wave < meta.wave) return true;

        //same wave: the timestamps still tell which file is newer
        long infoTime = campaignInfo.lastModified(), saveTime = campaignSave.lastModified();
        return infoTime > 0 && saveTime > 0 && infoTime < saveTime;
    }

    /** Applies the campaign info's core stock, leaving the loaded save's wave/rules untouched. */
    private static void applyStock(SectorInfo info){
        var core = Vars.state.rules.defaultTeam.core();
        if(core == null) return;

        core.items.clear();
        info.items.each((item, amount) -> core.items.add(item, Math.min(Math.max(amount, 0), core.storageCapacity)));
    }

    /**
     * Delegates to the real world context but reports a map. SaveIO only skips the
     * SaveLoadEvent's SectorInfo.write() when the context says "map", which is what keeps the
     * loaded save intact; the sector is still reported so the save's rules know their sector.
     */
    private static class SectorLoadContext implements WorldContext{
        private final WorldContext base;
        private final Sector sector;

        SectorLoadContext(WorldContext base, Sector sector){
            this.base = base;
            this.sector = sector;
        }

        @Override public Tile tile(int index){ return base.tile(index); }
        @Override public void resize(int width, int height){ base.resize(width, height); }
        @Override public Tile create(int x, int y, int width, int height, int floor){ return base.create(x, y, width, height, floor); }
        @Override public boolean isGenerating(){ return base.isGenerating(); }
        @Override public void begin(){ base.begin(); }
        @Override public void end(){ base.end(); }
        @Override public void onReadBuilding(){ base.onReadBuilding(); }
        @Override public void onReadTileData(){ base.onReadTileData(); }
        @Override public Sector getSector(){ return sector; }
        @Override public boolean isMap(){ return true; }
    }

    private void applyLaunchLoadout(){
        if(loadout != null && !loadout.isEmpty()){
            try{
                Schematic schem = Schematics.readBase64(loadout);
                CoreBlock core = schem == null ? null : schem.findCore();
                if(core != null){
                    Vars.universe.updateLoadout(core, schem);
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read launch loadout", t);
            }
        }

        if(resources != null && !resources.isEmpty()){
            try{
                Vars.universe.updateLaunchResources(parseItems(resources));
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read launch resources", t);
            }
        }
    }

    /** Removes launch cost (the loadout requirements) from the current core. */
    public void deduct(String cost){
        if(cost == null || cost.isEmpty()) return;

        var core = Vars.state.rules.defaultTeam.core();
        if(core == null) return;

        parseItems(cost).each((item, amount) -> core.items.remove(item, Math.min(core.items.get(item), amount)));
        Log.info("[mp-sectors] launch cost @ taken from @", cost, sectorName);
    }

    /** Delivers accelerator cargo to a sector that already had a base (its save ignores the loadout). */
    public void addCargo(String items){
        if(items == null || items.isEmpty()) return;

        var core = Vars.state.rules.defaultTeam.core();
        if(core == null) return;

        //SectorInfo.write() clamps every item to the core's storage capacity on the next load:
        //anything above it would be silently eaten, so clamp it when it is delivered
        int capacity = Math.max(core.storageCapacity, core.block.itemCapacity);

        parseItems(items).each((item, amount) -> {
            int space = Math.max(capacity - core.items.get(item), 0);
            int added = Math.min(amount, space);
            if(added > 0) core.items.add(item, added);
        });
        Log.info("[mp-sectors] accelerator cargo delivered to @: @", sectorName, items);
    }

    /** Consumes the interplanetary accelerator: every stored item, battery power and progress. */
    public void consumeAccelerator(int x, int y){
        var build = Vars.world.build(x, y);
        if(!(build instanceof Accelerator.AcceleratorBuild acc)) return;

        //vanilla moves everything in the accelerator to the launched core; the requirements are the cost
        acc.items.clear();
        acc.power.graph.useBatteries(((Accelerator)acc.block).powerBufferRequirement);
        acc.progress = 0f;
        Log.info("[mp-sectors] accelerator consumed in @", sectorName);
    }

    private static ItemSeq parseItems(String list){
        ItemSeq seq = new ItemSeq();
        if(list == null) return seq;

        for(String pair : list.split(",")){
            int split = pair.indexOf(':');
            if(split <= 0) continue;

            MappableContent content = Vars.content.byName(pair.substring(0, split).trim());
            if(!(content instanceof Item item)) continue;

            try{
                int amount = Integer.parseInt(pair.substring(split + 1).trim());
                if(amount > 0) seq.add(item, amount);
            }catch(NumberFormatException ignored){
            }
        }
        return seq;
    }

    /**
     * Recomputes this sector's import rates from every campaign sector that exports to it.
     * Vanilla's SectorInfo.refreshImportRates() relies on Sector.hasBase(), which is always false
     * on a headless server, so landing pads would never receive anything without this.
     */
    public void refreshImportRates(){
        Sector sector = sector();
        if(sector == null) return;

        float[] rates = new float[Vars.content.items().size];
        Fi dir = campaignInfo.parent();

        if(dir.exists()){
            for(Fi file : dir.list()){
                if(!file.extension().equals("json")) continue;
                String key = file.nameWithoutExtension();
                if(key.equals(sectorName)) continue;

                try{
                    SectorInfo info = JsonIO.read(SectorInfo.class, file.readString());
                    if(info == null || !info.hasCore || info.waves || info.attack || info.destination == null || !info.anyExports()) continue;
                    if(!(info.destination.planet.name + "-" + info.destination.id).equals(sectorName)) continue;

                    info.export.each((item, stat) -> rates[item.id] += stat.mean);
                }catch(Throwable ignored){
                }
            }
        }

        sector.info.importRateCache = rates;

        float total = 0f;
        for(float rate : rates) total += rate;
        if(total > 0f && total != lastImportTotal){
            lastImportTotal = total;
            Log.info("[mp-sectors] import rate for @: @/s", sectorName, total);
        }
    }

    /** Marks the sector as lost so the next landing generates it again. */
    public synchronized void markLost(){
        Sector sector = sector();
        if(sector == null) return;

        sector.info.hasCore = false;
        sector.info.items.clear();
        withCampaignLock(campaignInfo.parent().parent(), () -> {
            try{
                campaignInfo.parent().mkdirs();
                writeAtomic(campaignInfo, JsonIO.write(sector.info));
            }catch(Throwable t){
                Log.err("[mp-sectors] could not update sector info for @", sectorName, t);
            }
        });
    }

    /** Marks a won sector as captured: it keeps its core and stops counting as attacked. */
    public synchronized void markCaptured(){
        Sector sector = sector();
        if(sector == null) return;

        sector.info.hasCore = true;
        sector.info.waves = false;
        sector.info.attack = false;
        sector.info.wasCaptured = true;
        withCampaignLock(campaignInfo.parent().parent(), () -> {
            try{
                campaignInfo.parent().mkdirs();
                writeAtomic(campaignInfo, JsonIO.write(sector.info));
            }catch(Throwable t){
                Log.err("[mp-sectors] could not mark sector @ as captured", sectorName, t);
            }
        });
    }

    /**
     * The start sector stays endless (so it can keep being attacked), but it should still count
     * as captured for the tech tree once the vanilla capture wave has been survived.
     *
     * "Survived" is the vanilla win condition: the wave counter passed the capture wave and no
     * wave team unit is left (Logic.checkGameState uses state.wave >= winWave, enemies == 0 and
     * !spawner.isSpawning()). The current code used to fire at the *start* of the capture wave.
     */
    public synchronized void checkCapture(){
        if(!loaded || Vars.state == null || Vars.state.rules == null || !Vars.state.isCampaign()) return;

        Sector sector = sector();
        if(sector == null || sector.info == null || sector.info.wasCaptured) return;
        if(sector.id != sector.planet.startSector) return;

        int captureWave = sector.preset != null ? sector.preset.captureWave : 0;
        //Erekir's start sector (Onset) has captureWave = 0; use the vanilla default win wave
        //instead of never becoming captured and locking the whole Erekir tech tree
        if(captureWave <= 0) captureWave = 30;

        if(Vars.state.wave < captureWave) return;
        if(Vars.state.enemies > 0) return;
        if(Vars.spawner != null && Vars.spawner.isSpawning()) return;

        sector.info.wasCaptured = true;
        withCampaignLock(campaignInfo.parent().parent(), () -> {
            try{
                campaignInfo.parent().mkdirs();
                writeAtomic(campaignInfo, JsonIO.write(sector.info));
            }catch(Throwable t){
                Log.err("[mp-sectors] could not mark start sector @ as captured", sectorName, t);
            }
        });
        Log.info("[mp-sectors] start sector @ captured after wave @ (waves stay endless)", sectorName, captureWave);
    }

    public synchronized void save(){
        if(!loaded) return;

        Sector sector = sector();
        if(sector == null) return;

        //a start sector running as an instance never reaches the coordinator's capture check;
        //detect it on every autosave (no-op once wasCaptured is set)
        checkCapture();

        try{
            //write the process-local save first; campaign files stay untouched until it is complete
            SaveIO.save(localSave);

            //the live core is authoritative: without prepare() the campaign info keeps the
            //items from the last load, so research/launch costs look unspent on the next load
            sector.info.prepare(sector);

            withCampaignLock(campaignSave.parent().parent(), () -> {
                try{
                    copy(localSave, campaignSave);
                    campaignInfo.parent().mkdirs();
                    writeAtomic(campaignInfo, JsonIO.write(sector.info));
                    Log.info("[mp-sectors] saved sector @.", sectorName);
                }catch(Throwable t){
                    Log.err("[mp-sectors] save failed for @", sectorName, t);
                }
            });
        }catch(Throwable t){
            Log.err("[mp-sectors] save failed for @", sectorName, t);
        }
    }

    /**
     * Campaign research lives in unlocks.json. The file is authoritative once it exists: a sector
     * save may contain research from another campaign (an imported save, a sector-specific rollback
     * to an old snapshot), and merging that back would silently unlock the whole tech tree.
     */
    public void applyUnlocks(){
        ObjectSet<String> names = new ObjectSet<>();
        boolean fileExists = unlocksFile.exists();

        if(fileExists){
            try{
                String[] stored = JsonIO.read(String[].class, unlocksFile.readString());
                if(stored != null){
                    for(String name : stored){
                        if(name != null) names.add(name);
                    }
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read unlocks.json", t);
            }
        }else if(Vars.state.rules != null){
            //a brand-new campaign: adopt the starting sector's research once
            for(UnlockableContent content : Vars.state.rules.researched){
                names.add(content.name);
            }
        }

        //the save may still list research this campaign does not have. Items and liquids present
        //in an old save's core/buildings stay usable in that sector, but must not unlock tech.
        if(Vars.state.rules != null){
            for(UnlockableContent content : Vars.state.rules.researched.copy()){
                boolean resource = content instanceof Item || content instanceof mindustry.type.Liquid;
                if(!resource && !content.alwaysUnlocked && !names.contains(content.name)){
                    Vars.state.rules.researched.remove(content);
                }
            }
        }

        //the process may have unlocked content from the save/previous campaign; make the
        //in-memory tech flags match unlocks.json (empty file = all tech locked)
        if(fileExists) relockTechTo(unlocksFile);

        int unlocked = 0;
        for(String name : names){
            MappableContent content = Vars.content.byName(name);
            if(content instanceof UnlockableContent u && !u.alwaysUnlocked){
                u.unlock();
                //multiplayer clients check state.rules.researched, not the server's content flags
                if(Vars.state.rules != null) Vars.state.rules.researched.add(u);
                unlocked++;
            }
        }

        //seed the file for a fresh campaign; write exactly the adopted set, not every content
        //flag that happened to be unlocked in this process
        if(!fileExists) writeUnlocks(unlocksFile, names);

        StringBuilder dbg = new StringBuilder();
        for(String name : names) dbg.append(name).append(' ');
        Log.info("[mp-sectors] applied @ campaign unlocks (@ entries: @).", unlocked, names.size, dbg);
    }

    /**
     * Locks everything that is not in the given unlock file. Used after a snapshot restore:
     * the process keeps content unlocked in memory, and the next writeUnlocks() would otherwise
     * merge those stale unlocks back into the restored campaign.
     */
    public static void relockTo(Fi file){
        relock(file, false);
    }

    /** Relocks tech content but keeps items/liquids usable (resources in old saves stay usable). */
    public static void relockTechTo(Fi file){
        relock(file, true);
    }

    private static void relock(Fi file, boolean keepResources){
        ObjectSet<String> names = new ObjectSet<>();

        if(file != null && file.exists()){
            try{
                String[] stored = JsonIO.read(String[].class, file.readString());
                if(stored != null){
                    for(String name : stored){
                        if(name != null) names.add(name);
                    }
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read unlocks.json for relock", t);
            }
        }

        try{
            if(unlockedField == null){
                unlockedField = UnlockableContent.class.getDeclaredField("unlocked");
                unlockedField.setAccessible(true);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not access the unlock flag; research will not be rolled back", t);
            return;
        }

        int locked = 0;
        for(ContentType type : ContentType.all){
            for(Content content : Vars.content.getBy(type)){
                if(!(content instanceof UnlockableContent u) || u.alwaysUnlocked || names.contains(u.name)) continue;
                if(keepResources && (u instanceof Item || u instanceof mindustry.type.Liquid)) continue;

                try{
                    if(u.unlocked()){
                        unlockedField.setBoolean(u, false);
                        Core.settings.put(u.name + "-unlocked", false);
                        locked++;
                    }
                }catch(Throwable ignored){
                }
            }
        }

        if(locked > 0){
            //persist the relock: content reads name-unlocked from settings on the next start
            Core.settings.forceSave();
            Log.info("[mp-sectors] relocked @ research entries", locked);
        }
    }

    /** Writes exactly this set, without merging in unrelated content flags. */
    public static void writeUnlocks(Fi file, ObjectSet<String> names){
        if(file == null) return;

        synchronized(unlocksLock){
            try{
                file.parent().mkdirs();
                java.nio.file.Path lockPath = file.parent().child(file.name() + ".lock").file().toPath();
                try(java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockPath,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock ignored = channel.lock()){

                    String json = unlocksJson(names);
                    String old = file.exists() ? file.readString().trim() : "";
                    if(!old.equals(json)) writeAtomic(file, json);
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not seed unlocks.json", t);
            }
        }
    }

    /** Adds one researched content name; used by the research UI and non-item unlock events. */
    public static void addUnlock(Fi file, String name){
        if(file == null || name == null || name.isBlank()) return;

        synchronized(unlocksLock){
            try{
                file.parent().mkdirs();
                java.nio.file.Path lockPath = file.parent().child(file.name() + ".lock").file().toPath();
                try(java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockPath,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock ignored = channel.lock()){

                    ObjectSet<String> names = readUnlocks(file);
                    if(names.contains(name)) return;

                    names.add(name);
                    String json = unlocksJson(names);
                    String old = file.exists() ? file.readString().trim() : "";
                    if(!old.equals(json)) writeAtomic(file, json);
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not add an unlock to unlocks.json", t);
            }
        }
    }

    private static ObjectSet<String> readUnlocks(Fi file){
        ObjectSet<String> names = new ObjectSet<>();
        if(file == null || !file.exists()) return names;

        try{
            String[] stored = JsonIO.read(String[].class, file.readString());
            if(stored != null){
                for(String name : stored){
                    if(name != null) names.add(name);
                }
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read unlocks.json", t);
        }
        return names;
    }

    private static String unlocksJson(ObjectSet<String> set){
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for(String name : set){
            if(name != null && !names.contains(name)) names.add(name);
        }
        names.sort(String::compareTo);

        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < names.size(); i++){
            if(i > 0) out.append(',');
            out.append('"').append(names.get(i)).append('"');
        }
        return out.append(']').toString();
    }

    /**
     * Serializes campaign file updates (a sector save + its info) against snapshots and company
     * archives. Without this a snapshot can copy a new .msav next to the old info (or the reverse)
     * and a restore then shows stale or missing bases. The lock is cross-process: every sector
     * instance and the coordinator use the same campaign folder.
     *
     * @return false when the cross-process lock could not be taken; the action then did NOT run.
     *         Writing without the lock can publish a half-updated save/info pair, so callers must
     *         treat this as a failed write and retry later.
     */
    public static boolean withCampaignLock(Fi campaignRoot, Runnable action){
        if(campaignRoot == null){
            action.run();
            return true;
        }

        String key = campaignRoot.absolutePath();
        java.util.concurrent.locks.ReentrantLock local = campaignLocks.computeIfAbsent(
            key, ignored -> new java.util.concurrent.locks.ReentrantLock());
        local.lock();

        java.nio.channels.FileChannel channel = null;
        java.nio.channels.FileLock lock = null;
        try{
            campaignRoot.mkdirs();
            channel = java.nio.channels.FileChannel.open(campaignRoot.child(".save.lock").file().toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            lock = channel.lock();
        }catch(Exception lockFailed){
            closeQuietly(channel);
            local.unlock();
            //abort instead of writing unlocked: a snapshot can catch the new save with old info
            Log.err("[mp-sectors] campaign lock unavailable, write skipped", lockFailed);
            return false;
        }

        try{
            action.run();
            return true;
        }finally{
            try{
                if(lock != null) lock.release();
            }catch(java.io.IOException ignored){
            }
            closeQuietly(channel);
            local.unlock();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable){
        if(closeable == null) return;
        try{
            closeable.close();
        }catch(java.io.IOException ignored){
        }
    }

    public static void copy(Fi from, Fi to){
        to.parent().mkdirs();

        //write a temp file first: a crash between delete and copy would lose the destination
        Fi tmp = to.parent().child(to.name() + "." + System.nanoTime() + ".tmp");
        if(tmp.exists()) tmp.delete();
        from.copyTo(tmp);

        replaceFile(tmp, to);
    }

    /** Writes through a temp file: a power cut must not leave a half-written json. */
    public static void writeAtomic(Fi file, String text){
        file.parent().mkdirs();

        //unique temp: several processes write unlocks.json / sector info at the same time
        Fi tmp = file.parent().child(file.name() + "." + System.nanoTime() + ".tmp");
        tmp.writeString(text);

        replaceFile(tmp, file);
    }

    /**
     * Moves the temp over the destination. Atomic rename when the filesystem supports it; the
     * portable fallback keeps the old file as a backup until the new one is in place, so a failed
     * move can never leave the destination missing.
     *
     * ponytail: the fallback has a crash window where the destination is at "<name>.<nanotime>.bak"
     * and not at its real path. Closing it needs a journal/dir-fsync; the backup is the manual
     * recovery path and the window only exists on filesystems without atomic rename.
     */
    private static void replaceFile(Fi tmp, Fi to){
        java.nio.file.Path source = tmp.file().toPath();
        java.nio.file.Path target = to.file().toPath();

        try{
            java.nio.file.Files.move(source, target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return;
        }catch(Throwable ignored){
            //fall back to the portable path below
        }

        try{
            java.nio.file.Path backup = null;
            if(java.nio.file.Files.exists(target)){
                backup = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".bak");
                java.nio.file.Files.move(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            try{
                java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }catch(Throwable failed){
                //put the old file back: the destination must never be lost
                if(backup != null){
                    try{
                        java.nio.file.Files.move(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }catch(Throwable ignored){
                    }
                }
                throw failed;
            }

            if(backup != null) java.nio.file.Files.deleteIfExists(backup);
        }catch(java.io.IOException e){
            throw new RuntimeException(e);
        }
    }
}



