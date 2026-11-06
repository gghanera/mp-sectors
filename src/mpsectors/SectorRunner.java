package mpsectors;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
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
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.Planet;
import mindustry.type.Sector;
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

    public final String sectorName, planetName;
    public final int sectorId;
    public final Fi campaignSave, campaignInfo, unlocksFile, localSave;

    public volatile boolean loaded;
    public String loadout = "", resources = "";
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

        try{
            boolean hasSave = campaignSave.exists();

            //Campaign metadata must be loaded BEFORE SaveIO.load: its SaveLoadEvent calls
            //SectorInfo.write(), which copies info.items into the core. Instances have a fresh
            //settings.bin, so without this the core would be cleared to an empty info.
            if(campaignInfo.exists()){
                try{
                    sector.info = JsonIO.read(SectorInfo.class, campaignInfo.readString());
                }catch(Throwable t){
                    Log.err("[mp-sectors] could not read sector info", t);
                }
            }

            //a sector whose core is gone (lost, or a save made with content mods that are not
            //loaded here) must be generated again with the client's launch loadout
            boolean fromSave = hasSave && sector.info.hasCore;

            //a lost sector still has a save with the old buildings' plans (the B key ghosts);
            //read them before regenerating so the base can be rebuilt
            Seq<mindustry.game.Teams.BlockPlan> lostPlans = (hasSave && !fromSave) ? readLostPlans() : null;

            if(fromSave){
                copy(campaignSave, localSave);
                SaveIO.load(localSave);

                //a save without a sector is a built-in map that a broken instance rotated into
                if(Vars.state.teams.playerCores().isEmpty() || Vars.state.rules.sector == null){
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

                //SaveIO updated sector.info from the freshly placed core; publish it so the map
                //immediately knows this sector has a base again
                campaignInfo.parent().mkdirs();
                writeAtomic(campaignInfo, JsonIO.write(sector.info));
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
                Vars.state.rules.waves = sector.info.waves;
                Vars.state.rules.winWave = sector.info.winWave;
                Events.fire(new RulesLoadEvent(Vars.state.rules, true));
                Vars.state.set(GameState.State.playing);
            }

            refreshImportRates();
            applyUnlocks();
            loaded = true;
            return true;
        }catch(Throwable t){
            Log.err("[mp-sectors] failed to load sector @", sectorName);
            Log.err(t);
            return false;
        }
    }

    /** Reads the destroyed buildings' plans from a lost sector's save, before regenerating it. */
    private Seq<mindustry.game.Teams.BlockPlan> readLostPlans(){
        try{
            copy(campaignSave, localSave);
            SaveIO.load(localSave);

            Sector sector = sector();
            if(sector == null || Vars.state.rules.sector != sector) return null;

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
        try{
            campaignInfo.parent().mkdirs();
            writeAtomic(campaignInfo, JsonIO.write(sector.info));
        }catch(Throwable t){
            Log.err("[mp-sectors] could not update sector info for @", sectorName, t);
        }
    }

    /** Marks a won sector as captured: it keeps its core and stops counting as attacked. */
    public synchronized void markCaptured(){
        Sector sector = sector();
        if(sector == null) return;

        sector.info.hasCore = true;
        sector.info.waves = false;
        sector.info.attack = false;
        sector.info.wasCaptured = true;
        try{
            campaignInfo.parent().mkdirs();
            writeAtomic(campaignInfo, JsonIO.write(sector.info));
        }catch(Throwable t){
            Log.err("[mp-sectors] could not mark sector @ as captured", sectorName, t);
        }
    }

    public synchronized void save(){
        if(!loaded) return;

        try{
            SaveIO.save(localSave);
            copy(localSave, campaignSave);
            campaignInfo.parent().mkdirs();
            writeAtomic(campaignInfo, JsonIO.write(sector().info));
            writeUnlocks();
            Log.info("[mp-sectors] saved sector @.", sectorName);
        }catch(Throwable t){
            Log.err("[mp-sectors] save failed for @", sectorName, t);
        }
    }

    /** Campaign research lives in local settings, which a fresh server instance does not have. */
    public void applyUnlocks(){
        ObjectSet<String> names = new ObjectSet<>();

        if(unlocksFile.exists()){
            try{
                String[] stored = JsonIO.read(String[].class, unlocksFile.readString());
                if(stored != null){
                    for(String name : stored) names.add(name);
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read unlocks.json", t);
            }
        }

        if(Vars.state.rules != null){
            for(UnlockableContent content : Vars.state.rules.researched){
                names.add(content.name);
            }
        }

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
            Log.info("[mp-sectors] relocked @ research entries after restore", locked);
        }
    }

    public void writeUnlocks(){
        writeUnlocks(unlocksFile);
    }

    /**
     * Writes the union of this process's unlocks and whatever is already in the campaign file.
     * Merging (instead of overwriting) is what keeps live research from several instances from
     * being lost, and the sorted output keeps the file byte-stable when nothing changed.
     */
    public static void writeUnlocks(Fi file){
        if(file == null) return;

        try{
            String json = unlocksJson(file);
            String old = file.exists() ? file.readString().trim() : "";
            if(!old.equals(json)){
                file.parent().mkdirs();
                writeAtomic(file, json);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write unlocks.json", t);
        }
    }

    public static String unlocksJson(Fi file){
        Seq<String> names = new Seq<>();

        if(file != null && file.exists()){
            try{
                String[] stored = JsonIO.read(String[].class, file.readString());
                if(stored != null){
                    for(String name : stored){
                        if(name != null && !names.contains(name)) names.add(name);
                    }
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read unlocks.json", t);
            }
        }

        for(ContentType type : ContentType.all){
            for(Content content : Vars.content.getBy(type)){
                if(content instanceof UnlockableContent u && u.unlocked() && !u.alwaysUnlocked && !names.contains(u.name)){
                    names.add(u.name);
                }
            }
        }

        names.sort();

        StringBuilder out = new StringBuilder("[");
        for(int i = 0; i < names.size; i++){
            if(i > 0) out.append(',');
            out.append('"').append(names.get(i)).append('"');
        }
        out.append(']');
        return out.toString();
    }

    public static void copy(Fi from, Fi to){
        to.parent().mkdirs();

        //write a temp file first: a crash between delete and copy would lose the destination
        Fi tmp = to.parent().child(to.name() + "." + System.nanoTime() + ".tmp");
        if(tmp.exists()) tmp.delete();
        from.copyTo(tmp);

        try{
            java.nio.file.Files.move(tmp.file().toPath(), to.file().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Throwable t){
            if(to.exists()) to.delete();
            tmp.moveTo(to);
        }
    }

    /** Writes through a temp file: a power cut must not leave a half-written json. */
    public static void writeAtomic(Fi file, String text){
        file.parent().mkdirs();

        //unique temp: several processes write unlocks.json / sector info at the same time
        Fi tmp = file.parent().child(file.name() + "." + System.nanoTime() + ".tmp");
        tmp.writeString(text);

        try{
            java.nio.file.Files.move(tmp.file().toPath(), file.file().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(Throwable t){
            if(file.exists()) file.delete();
            tmp.moveTo(file);
        }
    }
}



