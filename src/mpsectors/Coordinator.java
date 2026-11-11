package mpsectors;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.Jval;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.ContentType;
import mindustry.game.SectorInfo;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.JsonIO;
import mindustry.net.Packets.KickReason;
import mindustry.type.Planet;
import mindustry.type.Sector;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The campaign coordinator: hosts the home sector, serves the sector list over HTTP, and starts or
 * stops sector instances on demand.
 *
 * The home sector is "the last sector players left" (campaign/home.json, otherwise the newest save)
 * and any player can change it through POST /home.
 */
public class Coordinator{

    public static class Config{
        public String campaign = "";
        public int httpPort = 8090;
        public int gamePort = 6567;
        public int maxSectors = 5;
        public int maxPlayers = 10;
        public int idleStopSeconds = 60;
        public int autosaveSeconds = 60;
        public int playerLimit = 5;
        public int instanceBasePort = 6570;
        public int memoryMb = 1024;
        /** Where campaign snapshots live; empty means <campaign>/../backups. */
        public String snapshotDir = "";
        /** Query GitHub Releases once a day and log a newer version. */
        public boolean updateCheck = true;
        public boolean allowDuplicateClients = true;
        public String serverJar = "";
        public String javaPath = "";
        public boolean steam;
        public String steamJar = "";
        public String steamNative = "";
    }

    public static class InstanceConfig{
        public String campaign = "";
        public String sector = "";
        public int port = 0;
        public int httpPort = 8090;
        public int playerLimit = 5;
        public int autosaveSeconds = 60;
        public boolean allowDuplicateClients = true;
        public String loadout = "";
        public String resources = "";
        public boolean steam;
        public String steamJar = "";
        public String steamNative = "";
    }

    public static class Home{
        public String sector = "";
    }

    public static class SectorEntry{
        public String planet;
        public int id;
        public String name;
        public boolean hasBase, captured, attacked, allowed, running;
        public int players;
        public String info;
    }

    public static class SectorsResponse{
        public String home;
        public int maxSectors, maxPlayers, players;
        public SectorEntry[] sectors = {};
    }

    public static class LandRequest{
        public String planet = "";
        public int id = -1;
        public String player = "";
        public String fromPlanet = "";
        public int fromId = -1;
        public String loadout = "";
        public String resources = "";
        public String cost = "";
        public boolean accelerator;
        public int tileX = -1, tileY = -1;
    }

    public static class LandResponse{
        public boolean ok;
        public String error;
        public String host;
        public int port;
        public boolean home;
        public String steamHost;
        public long steamId;
    }

    public static class LaunchDeduct{
        public String sector = "";
        public String cost = "";
        public long time;
    }

    public static class AcceleratorRequest{
        public String sector = "";
        public int x = -1, y = -1;
        public long time;
    }

    public static class RestoreRequest{
        public String name = "";
        public String player = "";
        public String sector = "";
        public String comment = "";
    }

    /** One moderator action from the in-game admin panel. */
    public static class AdminRequest{
        public String player = "", action = "", sector = "", name = "", value = "", reason = "";
        public long hours;
    }

    /** A pause/speed/kick command relayed from the coordinator to one sector instance. */
    public static class CommandRequest{
        public String sector = "", action = "", value = "";
        public long time;
    }

    /** One chat line relayed between the sector servers. */
    public static class ChatMessage{
        public long id, time;
        public String player = "", sector = "", message = "";
    }

    public static class ChatPost{
        public String player = "", sector = "", message = "";
    }

    /** One line of the campaign log shown on the map. */
    public static class HistoryEvent{
        public long time;
        public String type = "", sector = "", player = "", text = "";
    }

    public static class NoteRequest{
        public String planet = "", player = "", title = "", note = "";
        public int id = -1;
    }

    public static class StatusResponse{
        public String home;
        public int players, sectors, maxSectors, maxPlayers;
        public String[] instances = {};
    }

    private final Config config;
    private final Fi campaign, homeFile, stopFile;
    private final InstanceManager instances;
    private final Object campaignLock = new Object();

    private volatile SectorRunner home;
    private volatile String homeSector;
    private volatile String pendingHome;
    private volatile int homePlayers;
    private volatile String[] homePlayerNames = {};
    private long limitSince, lastLimitKick;
    private final ObjectMap<String, Boolean> attackState = new ObjectMap<>();
    private long lastAttackCheck;
    private long startedAt;
    private long lastAutosave;
    private long lastImportRefresh;
    private HttpServer http;
    private ExecutorService httpExecutor;
    private boolean shuttingDown;
    private Fi snapshotsDir;
    private Fi dailyDir;
    private volatile boolean restoring;
    /** Campaign-wide pause: no landing on new sectors while true. */
    private volatile boolean paused;
    private volatile boolean started;
    private long lastSnapshot;
    private long lastUpdateCheck;
    private volatile boolean updateCheckRunning;
    private static final int maxSnapshots = 50;
    private static final int maxDailySnapshots = 7;
    private static final long snapshotInterval = 10 * 60 * 1000L;

    //cross-sector chat: a small ring buffer, polled by the clients of every sector
    private final ArrayList<ChatMessage> chat = new ArrayList<>();
    private long chatId;
    private Fi chatFile;
    private final ObjectMap<String, long[]> chatRate = new ObjectMap<>();
    private final ObjectMap<String, String> chatText = new ObjectMap<>();
    private final ObjectMap<String, Long> chatTextTime = new ObjectMap<>();
    private static final int maxChatMessages = 200;
    private static final int chatBurst = 5;
    private static final long chatWindow = 10_000L;

    //campaign log and per-sector names/notes
    private final ArrayList<HistoryEvent> history = new ArrayList<>();
    private static final int maxHistoryEvents = 300;
    private final ObjectMap<String, Boolean> captured = new ObjectMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> validSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private Fi historyFile, notesFile;

    //named campaign save slots ("companies"): the active one is campaign/, the rest live here
    private Fi companiesDir;
    private Fi companyFile;
    private volatile String company = "Основная";
    private Fi modsDir;

    public Coordinator(Config config){
        this.config = config;
        this.campaign = Core.files.absolute(config.campaign);
        //instances run in their own working directories; a relative path would resolve wrongly there
        this.config.campaign = this.campaign.absolutePath();
        this.homeFile = campaign.child("home.json");
        this.stopFile = Core.files.local("stop.flag");
        this.instances = new InstanceManager(config, this::watchdogMessage);
    }

    public void start(){
        startedAt = System.currentTimeMillis();

        //a stale stop flag from a killed process would shut the fresh coordinator down immediately
        stopFile.delete();

        ResearchServer.campaignPath = config.campaign;
        companiesDir = campaign.parent().child("companies");
        companyFile = companiesDir.child("current.txt");
        company = readCompany();
        modsDir = locateModsDir();
        try{
            if(config.serverJar == null || config.serverJar.isEmpty()){
                Log.err("[mp-sectors] coordinator: serverJar is not set");
                return;
            }
            if(config.javaPath == null || config.javaPath.isEmpty()) config.javaPath = Vars.javaPath;

            homeSector = readHomeSector();
            if(homeSector == null){
                Log.err("[mp-sectors] coordinator: no sector saves in @", campaign.child("sectors").absolutePath());
                return;
            }

            importInfo();

            home = new SectorRunner(config.campaign, homeSector);
            if(!home.load()){
                //a home whose core is gone (lost, or a save made with content mods that are not loaded)
                //is generated again; load() already marked it unowned
                Log.warn("[mp-sectors] coordinator: home sector @ has no base, regenerating it", homeSector);
                home = new SectorRunner(config.campaign, homeSector);
                if(!home.load()){
                    Log.err("[mp-sectors] coordinator: could not load home sector @", homeSector);
                    return;
                }
            }

            Core.settings.put("port", config.gamePort);
            Core.settings.put("strict", !config.allowDuplicateClients);
            Core.settings.put("playerlimit", config.maxPlayers);
            if(config.steam) SteamSupport.init();
            Log.info("[mp-sectors] gate cores: foundation=@ nucleus=@", Blocks.coreFoundation.unlocked(), Blocks.coreNucleus.unlocked());

            //read the shared lists before the port opens: a banned player must not slip in
            Moderators.init(campaign);
            Bans.init(campaign);

            //a campaign paused before a coordinator restart stays paused
            paused = campaign.child("paused.flag").exists();

            Vars.netServer.admins.setPlayerLimit(config.maxPlayers);
            Vars.netServer.openServer();
            if(paused) MpSectorsMod.adminPause(true);

            lastAutosave = System.currentTimeMillis();
            lastImportRefresh = System.currentTimeMillis();
            snapshotsDir = (config.snapshotDir == null || config.snapshotDir.isEmpty())
                ? campaign.parent().child("backups")
                : Core.files.absolute(config.snapshotDir);
            snapshotsDir.mkdirs();
            dailyDir = snapshotsDir.child("daily");
            dailyDir.mkdirs();
            lastSnapshot = System.currentTimeMillis();

            historyFile = campaign.child("history.json");
            notesFile = campaign.child("notes.json");
            chatFile = campaign.child("chat.json");
            loadHistory();
            loadChat();
            checkSnapshotsAsync();

            startHttp();
            started = true;
            Log.info("[mp-sectors] coordinator ready: home=@ game=@ http=@ duplicates=@", homeSector, config.gamePort, config.httpPort, config.allowDuplicateClients ? "allowed" : "blocked");
        }catch(Throwable t){
            Log.err("[mp-sectors] coordinator failed to start", t);
        }
    }

    public void update(){
        if(stopFile.exists()){
            Log.info("[mp-sectors] coordinator stop flag found, shutting down.");
            stopFile.delete();
            shutdown();
            Core.app.exit();
            return;
        }

        var players = Groups.player.copy();
        homePlayers = players.size;
        String[] names = new String[players.size];
        for(int i = 0; i < players.size; i++){
            names[i] = players.get(i).plainName();
        }
        homePlayerNames = names;

        instances.tick();

        long now = System.currentTimeMillis();
        checkAttacks(now);
        enforcePlayerLimit(now);

        if(config.updateCheck && now - lastUpdateCheck >= 24L * 60L * 60L * 1000L){
            checkUpdates();
        }

        if(!restoring && now - lastImportRefresh >= 5000L){
            lastImportRefresh = now;
            if(home != null && home.loaded) home.refreshImportRates();
        }

        if(!restoring && !paused && pendingHome != null && homePlayers == 0){
            switchHome(pendingHome);
            pendingHome = null;
        }

        if(!restoring && config.autosaveSeconds > 0 && now - lastAutosave >= config.autosaveSeconds * 1000L){
            lastAutosave = now;
            if(home != null && home.loaded) home.save();
        }

        if(!restoring && now - lastSnapshot >= snapshotInterval){
            takeSnapshot();
        }
    }

    public synchronized void shutdown(){
        if(shuttingDown) return;
        shuttingDown = true;
        try{
            if(http != null) http.stop(0);
            if(httpExecutor != null) httpExecutor.shutdownNow();
        }catch(Throwable ignored){}
        try{
            instances.stopAll();
            if(home != null && home.loaded) home.save();
            saveChat();
        }catch(Throwable t){
            Log.err("[mp-sectors] coordinator shutdown failed", t);
        }
    }

    // ---------------- campaign snapshots ----------------

    /** Copies the whole campaign into backups/<timestamp>, keeping the newest 50 plus 7 daily. */
    private boolean takeSnapshot(){
        return takeSnapshot("");
    }

    /** @param prefix empty for a regular snapshot, "before-restore_" for the safety copy. */
    private boolean takeSnapshot(String prefix){
        if(snapshotsDir == null) return false;

        boolean ok = false;
        synchronized(campaignLock){
            try{
                snapshotsDir.mkdirs();
                dailyDir.mkdirs();

                //build into a temp name: /snapshots must never list a half-copied campaign
                for(Fi old : snapshotsDir.list()){
                    if(old.isDirectory() && old.name().startsWith("tmp-")) old.deleteDirectory();
                }
                for(Fi old : dailyDir.list()){
                    if(old.isDirectory() && old.name().startsWith("tmp-")) old.deleteDirectory();
                }

                Fi dir = snapshotsDir.child(prefix + snapshotName());
                if(dir.exists()) dir.deleteDirectory();

                Fi tmp = snapshotsDir.child("tmp-" + dir.name());
                copyTree(campaign, tmp);
                tmp.moveTo(dir);

                pruneSnapshots();

                //one snapshot per day, kept for a week: "go back to yesterday" without scrolling
                String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                Fi daily = dailyDir.child(today);
                if(!daily.exists()){
                    Fi dailyTmp = dailyDir.child("tmp-" + today);
                    dailyTmp.deleteDirectory();
                    copyTree(campaign, dailyTmp);
                    dailyTmp.moveTo(daily);
                    pruneDaily();
                    Log.info("[mp-sectors] daily snapshot @ created", today);
                }

                addHistory("snapshot", "", "", prefix.isEmpty() ? "Снимок " + dir.name() : "Страховочный снимок " + dir.name());
                Log.info("[mp-sectors] campaign snapshot @ created", dir.name());
                ok = true;
            }catch(Throwable t){
                Log.err("[mp-sectors] could not create a campaign snapshot", t);
            }finally{
                lastSnapshot = System.currentTimeMillis();
            }
        }
        return ok;
    }

    private String snapshotsJson(HttpExchange ex){
        StringBuilder json = new StringBuilder("{\"snapshots\":[");
        boolean first = true;

        for(Fi dir : snapshots()){
            first = appendSnapshot(json, dir, false, first);
        }
        for(Fi dir : dailySnapshots()){
            first = appendSnapshot(json, dir, true, first);
        }

        return json.append("]}").toString();
    }

    private boolean appendSnapshot(StringBuilder json, Fi dir, boolean daily, boolean first){
        Fi commentFile = dir.child("comment.txt");
        String comment = "";
        try{
            if(commentFile.exists()) comment = commentFile.readString().trim();
        }catch(Throwable ignored){
        }

        if(!first) json.append(',');
        json.append("{\"name\":\"").append(esc(dir.name())).append("\",")
            .append("\"time\":").append(snapshotTime(dir)).append(',')
            .append("\"size\":").append(dirSize(dir)).append(',')
            .append("\"daily\":").append(daily).append(',')
            .append("\"beforeRestore\":").append(dir.name().startsWith("before-restore_")).append(',')
            .append("\"comment\":\"").append(esc(comment)).append("\",")
            .append("\"valid\":").append(validSnapshotCached(dir)).append('}');
        return false;
    }

    private String snapshotJson(HttpExchange ex) throws IOException{
        LandRequest req = JsonIO.read(LandRequest.class, body(ex));

        if(req == null || !Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Снимки кампании доступны только модераторам.\"}";
        }
        if(restoring){
            return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";
        }

        return snapshotBlocking();
    }

    /** Creates a snapshot on the game thread and waits for it (called from an HTTP thread). */
    private String snapshotBlocking(){
        CountDownLatch latch = new CountDownLatch(1);
        Core.app.post(() -> {
            try{
                takeSnapshot();
            }finally{
                latch.countDown();
            }
        });

        try{
            if(!latch.await(30, TimeUnit.SECONDS)){
                return "{\"ok\":false,\"error\":\"Не удалось создать снимок вовремя.\"}";
            }
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"Создание снимка прервано.\"}";
        }

        return "{\"ok\":true}";
    }

    private String snapshotSectorsJson(HttpExchange ex) throws IOException{
        RestoreRequest req = JsonIO.read(RestoreRequest.class, body(ex));
        if(req == null || req.name == null || !Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Недостаточно прав.\"}";
        }

        Fi dir = findSnapshot(req.name);
        if(dir == null) return "{\"ok\":false,\"error\":\"Снимок не найден.\"}";

        StringBuilder json = new StringBuilder("{\"ok\":true,\"sectors\":[");
        boolean first = true;
        Fi sectors = dir.child("sectors");
        if(sectors.exists()){
            for(Fi save : sectors.list()){
                if(!save.extension().equals("msav")) continue;
                if(!first) json.append(',');
                first = false;
                json.append('"').append(esc(save.nameWithoutExtension())).append('"');
            }
        }
        return json.append("]}").toString();
    }

    private String snapshotCommentJson(HttpExchange ex) throws IOException{
        RestoreRequest req = JsonIO.read(RestoreRequest.class, body(ex));
        if(req == null || req.name == null || !Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Недостаточно прав.\"}";
        }

        Fi dir = findSnapshot(req.name);
        if(dir == null) return "{\"ok\":false,\"error\":\"Снимок не найден.\"}";

        String comment = req.comment == null ? "" : req.comment.trim();
        if(comment.length() > 200) comment = comment.substring(0, 200);

        try{
            SectorRunner.writeAtomic(dir.child("comment.txt"), comment);
        }catch(Throwable t){
            return "{\"ok\":false,\"error\":\"Не удалось сохранить комментарий.\"}";
        }

        addHistory("snapshot-comment", "", req.player, "Комментарий к снимку " + req.name + ": " + comment);
        return "{\"ok\":true}";
    }

    /** Restores one sector from a snapshot; the home sector still needs the full restore. */
    private String restoreSectorJson(HttpExchange ex) throws IOException{
        RestoreRequest req = JsonIO.read(RestoreRequest.class, body(ex));
        if(req == null || req.name == null || req.sector == null || !Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Недостаточно прав.\"}";
        }
        if(restoring) return "{\"ok\":false,\"error\":\"Кампания уже восстанавливается.\"}";
        if(req.sector.equals(homeSector)){
            return "{\"ok\":false,\"error\":\"Домашний сектор откатывается только целиком.\"}";
        }
        if(sectorForKey(req.sector) == null){
            return "{\"ok\":false,\"error\":\"Нет такого сектора.\"}";
        }

        Fi dir = findSnapshot(req.name);
        if(dir == null) return "{\"ok\":false,\"error\":\"Снимок не найден.\"}";
        if(!validSnapshot(dir)) return "{\"ok\":false,\"error\":\"Снимок повреждён.\"}";

        Fi srcSave = dir.child("sectors").child(req.sector + ".msav");
        Fi srcInfo = dir.child("info").child(req.sector + ".json");
        if(!srcSave.exists() || !srcInfo.exists()){
            return "{\"ok\":false,\"error\":\"В снимке нет этого сектора.\"}";
        }

        restoring = true;
        try{
            InstanceManager.Instance inst = instances.get(req.sector);
            if(inst != null) instances.stop(req.sector);

            takeSnapshot("before-restore_");

            synchronized(campaignLock){
                SectorRunner.copy(srcSave, campaign.child("sectors").child(req.sector + ".msav"));
                SectorRunner.copy(srcInfo, campaign.child("info").child(req.sector + ".json"));
            }

            addHistory("restore-sector", req.sector, req.player, "Сектор восстановлен из " + req.name);
            Log.info("[mp-sectors] sector @ restored from @", req.sector, req.name);
            return "{\"ok\":true}";
        }catch(Throwable t){
            Log.err("[mp-sectors] could not restore sector @", req.sector, t);
            return "{\"ok\":false,\"error\":\"Ошибка восстановления сектора.\"}";
        }finally{
            restoring = false;
        }
    }

    private String restoreJson(HttpExchange ex) throws IOException{
        RestoreRequest req = JsonIO.read(RestoreRequest.class, body(ex));

        if(req == null || req.name == null || !req.name.matches("(before-restore_)?[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}|[0-9]{4}-[0-9]{2}-[0-9]{2}")){
            return "{\"ok\":false,\"error\":\"Некорректное имя снимка.\"}";
        }
        if(!Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Снимки кампании доступны только модераторам.\"}";
        }
        if(restoring){
            return "{\"ok\":false,\"error\":\"Кампания уже восстанавливается.\"}";
        }

        Fi dir = findSnapshot(req.name);
        if(dir == null){
            return "{\"ok\":false,\"error\":\"Снимок не найден.\"}";
        }
        if(!validSnapshot(dir)){
            return "{\"ok\":false,\"error\":\"Снимок повреждён: в нём нет читаемых сохранений секторов.\"}";
        }

        restoring = true;
        Log.info("[mp-sectors] restoring campaign from snapshot @", req.name);
        Call.sendMessage("[accent]Кампания восстанавливается из снимка " + req.name + "...");

        try{
            //instances save their sectors on stop; the snapshot is copied over them afterwards
            instances.stopAll();

            //the home autosaves on a timer, so its last minute would be lost by a rollback
            saveHomeBlocking();

            //a rollback can itself be wrong; keep the state we are about to leave behind
            takeSnapshot("before-restore_");

            //build the restored campaign next to the live one, then swap: a failed copy
            //must not leave a half-deleted campaign
            synchronized(campaignLock){
                Fi tmp = campaign.parent().child("campaign.restore");
                tmp.deleteDirectory();
                copyTree(dir, tmp);

                //swap by renaming: the live campaign is never deleted before the copy is complete
                Fi old = campaign.parent().child("campaign.old");
                old.deleteDirectory();
                campaign.moveTo(old);

                try{
                    tmp.moveTo(campaign);
                }catch(Throwable t){
                    old.moveTo(campaign);
                    throw t;
                }
                old.deleteDirectory();

                //transient launch/accelerator requests from the snapshot must not fire again
                Fi requests = campaign.child("requests");
                if(requests.exists()) requests.deleteDirectory();
            }

            addHistory("restore", "", req.player, "Кампания восстановлена из " + req.name);

            CountDownLatch latch = new CountDownLatch(1);
            Core.app.post(() -> {
                try{
                    reloadHome();
                }finally{
                    latch.countDown();
                }
            });

            if(!latch.await(90, TimeUnit.SECONDS)){
                restoring = false;
                return "{\"ok\":false,\"error\":\"Не удалось перезагрузить кампанию вовремя.\"}";
            }

            return "{\"ok\":true}";
        }catch(Throwable t){
            restoring = false;
            Log.err("[mp-sectors] could not restore the campaign", t);
            return "{\"ok\":false,\"error\":\"Ошибка восстановления: " + esc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** Saves the home on the game thread; restore runs on an HTTP thread, so waiting is safe. */
    private void saveHomeBlocking(){
        if(home == null || !home.loaded) return;

        CountDownLatch latch = new CountDownLatch(1);
        Core.app.post(() -> {
            try{
                if(home != null && home.loaded) home.save();
            }finally{
                latch.countDown();
            }
        });

        try{
            latch.await(15, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    /** Runs on the game thread: kicks everyone and loads the restored home sector. */
    private void reloadHome(){
        try{
            //the old world is gone after logic.reset(); never autosave it over the snapshot
            if(home != null) home.loaded = false;
            pendingHome = null;
            paused = campaign.child("paused.flag").exists();

            for(Player player : Groups.player.copy()){
                player.kick(KickReason.serverRestarting);
            }

            Vars.logic.reset();

            //the process still has the old campaign's research unlocked; the next unlock event
            //would merge it back into the restored unlocks.json
            SectorRunner.relockTo(campaign.child("unlocks.json"));

            //the in-memory log belongs to the old campaign; load the restored one
            loadHistory();
            loadChat();
            captured.clear();

            //the restored campaign may have different moderators/bans than the live lists
            Moderators.init(campaign);
            Bans.init(campaign);

            String key = readHomeSector();
            if(key == null) key = homeSector;

            SectorRunner next = new SectorRunner(config.campaign, key);
            if(!next.load()){
                Log.warn("[mp-sectors] restored home @ has no base, regenerating it", key);
                next = new SectorRunner(config.campaign, key);
                if(!next.load()){
                    Log.err("[mp-sectors] could not load the restored home @", key);
                    return;
                }
            }

            home = next;
            homeSector = key;
            lastAutosave = System.currentTimeMillis();
            lastSnapshot = System.currentTimeMillis();

            //the capture path may have closed the server while the vanilla game-over rotated the map
            try{
                if(!Vars.net.server()) Vars.netServer.openServer();
            }catch(Throwable t){
                Log.err("[mp-sectors] could not reopen the game server", t);
            }
            if(paused) MpSectorsMod.adminPause(true);

            Log.info("[mp-sectors] campaign restored, home=@", key);
        }catch(Throwable t){
            Log.err("[mp-sectors] could not reload the restored campaign", t);
        }finally{
            restoring = false;
        }
    }

    private ArrayList<Fi> snapshots(){
        ArrayList<Fi> out = new ArrayList<>();
        if(snapshotsDir == null || !snapshotsDir.exists()) return out;

        for(Fi file : snapshotsDir.list()){
            if(file.isDirectory() && file.name().matches("(before-restore_)?[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")){
                out.add(file);
            }
        }

        out.sort(Comparator.comparingLong(Coordinator::snapshotTime).reversed());
        return out;
    }

    private ArrayList<Fi> dailySnapshots(){
        ArrayList<Fi> out = new ArrayList<>();
        if(dailyDir == null || !dailyDir.exists()) return out;

        for(Fi file : dailyDir.list()){
            if(file.isDirectory() && file.name().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")){
                out.add(file);
            }
        }

        out.sort(Comparator.comparing(Fi::name).reversed());
        return out;
    }

    private Fi findSnapshot(String name){
        if(snapshotsDir != null){
            Fi dir = snapshotsDir.child(name);
            if(dir.exists() && dir.isDirectory()) return dir;
        }
        if(dailyDir != null){
            Fi dir = dailyDir.child(name);
            if(dir.exists() && dir.isDirectory()) return dir;
        }
        return null;
    }

    private void pruneSnapshots(){
        ArrayList<Fi> list = snapshots();
        while(list.size() > maxSnapshots){
            Fi oldest = list.remove(list.size() - 1);
            oldest.deleteDirectory();
            validSnapshots.remove(oldest.name());
            Log.info("[mp-sectors] removed old snapshot @", oldest.name());
        }
    }

    private void pruneDaily(){
        ArrayList<Fi> list = dailySnapshots();
        while(list.size() > maxDailySnapshots){
            Fi oldest = list.remove(list.size() - 1);
            oldest.deleteDirectory();
            validSnapshots.remove(oldest.name());
            Log.info("[mp-sectors] removed old daily snapshot @", oldest.name());
        }
    }

    /** Verifies every snapshot once at startup; a copy interrupted by a crash is marked unusable. */
    private void checkSnapshotsAsync(){
        Thread thread = new Thread(() -> {
            try{
                int bad = 0;
                for(Fi dir : snapshots()){
                    boolean valid = validSnapshot(dir);
                    validSnapshots.put(dir.name(), valid);
                    if(!valid) bad++;
                }
                for(Fi dir : dailySnapshots()){
                    boolean valid = validSnapshot(dir);
                    validSnapshots.put(dir.name(), valid);
                    if(!valid) bad++;
                }
                Log.info("[mp-sectors] snapshot check: @ checked, @ invalid", validSnapshots.size(), bad);
            }catch(Throwable t){
                Log.err("[mp-sectors] snapshot check failed", t);
            }
        }, "mp-sectors-snapshot-check");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean validSnapshotCached(Fi dir){
        //a snapshot created after startup is not in the map yet; validate it once on first use
        return validSnapshots.computeIfAbsent(dir.name(), name -> validSnapshot(dir));
    }

    /** A snapshot is usable when it has sectors/ with at least one readable MSAV file. */
    private static boolean validSnapshot(Fi dir){
        Fi sectors = dir.child("sectors");
        if(!sectors.exists() || !sectors.isDirectory()) return false;

        boolean any = false;
        for(Fi save : sectors.list()){
            if(!save.extension().equals("msav")) continue;
            any = true;
            if(!validSave(save)) return false;
        }
        return any;
    }

    private static boolean validSave(Fi file){
        try{
            //the save is zlib-compressed; the MSAV magic is inside the stream
            try(java.io.InputStream in = new java.util.zip.InflaterInputStream(file.read())){
                byte[] magic = new byte[4];
                if(readFully(in, magic) && isMagic(magic)) return true;
            }
        }catch(Throwable ignored){
        }

        //uncompressed saves start with the magic directly
        try(java.io.InputStream in = file.read()){
            byte[] magic = new byte[4];
            return readFully(in, magic) && isMagic(magic);
        }catch(Throwable t){
            return false;
        }
    }

    private static boolean readFully(java.io.InputStream in, byte[] bytes) throws java.io.IOException{
        int read = 0;
        while(read < bytes.length){
            int count = in.read(bytes, read, bytes.length - read);
            if(count < 0) break;
            read += count;
        }
        return read == bytes.length;
    }

    private static boolean isMagic(byte[] magic){
        return magic[0] == 'M' && magic[1] == 'S' && magic[2] == 'A' && magic[3] == 'V';
    }

    private static String snapshotName(){
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    }

    /** The folder mtime changes when snapshots are moved between disks; the name is stable. */
    private static long snapshotTime(Fi dir){
        String name = dir.name();
        if(name.startsWith("before-restore_")) name = name.substring("before-restore_".length());

        try{
            String pattern = name.length() == 10 ? "yyyy-MM-dd" : "yyyy-MM-dd_HH-mm-ss";
            return new SimpleDateFormat(pattern).parse(name).getTime();
        }catch(Throwable t){
            return dir.lastModified();
        }
    }

    private static long dirSize(Fi file){
        if(file.isDirectory()){
            long sum = 0;
            for(Fi child : file.list()) sum += dirSize(child);
            return sum;
        }
        return file.length();
    }

    private static void copyTree(Fi src, Fi dst){
        if(src.isDirectory()){
            dst.mkdirs();
            for(Fi child : src.list()){
                copyTree(child, dst.child(child.name()));
            }
        }else{
            //ponytail: an instance may be writing this file right now; the size/mtime check
            //catches the common case, a coordinated save would be the upgrade
            for(int attempt = 0; attempt < 3; attempt++){
                long length = src.length();
                long modified = src.lastModified();
                src.copyTo(dst);
                if(src.length() == length && src.lastModified() == modified) return;
            }
        }
    }

    // ---------------- history, notes and cross-sector chat ----------------

    private static class HistoryList{
        public HistoryEvent[] events = {};
    }

    private synchronized void loadHistory(){
        history.clear();
        if(historyFile == null || !historyFile.exists()) return;

        try{
            HistoryList list = JsonIO.read(HistoryList.class, historyFile.readString());
            if(list == null || list.events == null) return;

            for(HistoryEvent event : list.events){
                if(event != null) history.add(event);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read history.json", t);
        }
    }

    private synchronized boolean addHistory(String type, String sector, String player, String text){
        HistoryEvent event = new HistoryEvent();
        event.time = System.currentTimeMillis();
        event.type = type == null ? "" : type;
        event.sector = sector == null ? "" : sector;
        event.player = player == null ? "" : player;
        event.text = text == null ? "" : text;

        //the instance reports a capture/loss, and the coordinator's map scan may report it again
        if((event.type.equals("capture") || event.type.equals("lost")) && !history.isEmpty()){
            HistoryEvent last = history.get(history.size() - 1);
            if(last.type.equals(event.type) && last.sector.equals(event.sector)
                && Math.abs(event.time - last.time) < 10 * 60 * 1000L){
                return false;
            }
        }

        history.add(event);
        while(history.size() > maxHistoryEvents) history.remove(0);

        if(historyFile != null){
            try{
                HistoryList list = new HistoryList();
                list.events = history.toArray(new HistoryEvent[0]);
                SectorRunner.writeAtomic(historyFile, JsonIO.write(list));
            }catch(Throwable t){
                Log.err("[mp-sectors] could not write history.json", t);
            }
        }
        return true;
    }

    /** Writes the history line and, for the important events, a campaign-wide chat notification. */
    private void notifyEvent(String type, String sector, String player, String historyText){
        if(!addHistory(type, sector, player, historyText)) return;

        String label = sectorLabel(sector);
        String message = switch(type){
            case "land" -> "Высадка: " + label
                + (player == null || player.isEmpty() ? "" : " — " + player);
            case "lost" -> label + " потерян";
            case "capture" -> label + " захвачен";
            case "attack" -> label + " атакован";
            case "defend" -> "Атака на " + label + " отбита";
            default -> historyText;
        };
        addSystemChat(message);
    }

    private String sectorLabel(String key){
        Sector sector = sectorForKey(key);
        return sector == null ? (key == null || key.isEmpty() ? "?" : key) : sector.name();
    }

    private String historyJson(HttpExchange ex) throws IOException{
        if("POST".equalsIgnoreCase(ex.getRequestMethod())){
            HistoryEvent req = JsonIO.read(HistoryEvent.class, body(ex));
            if(req == null || req.type == null || !req.type.matches("lost|capture|land|attack|defend|snapshot|restore|home|pads|note")){
                return "{\"ok\":false}";
            }
            if(req.type.matches("lost|capture|land|attack|defend")){
                notifyEvent(req.type, req.sector, req.player, req.text);
            }else{
                addHistory(req.type, req.sector, req.player, req.text);
            }
            return "{\"ok\":true}";
        }

        StringBuilder json = new StringBuilder("{\"events\":[");
        synchronized(this){
            boolean first = true;
            for(int i = history.size() - 1; i >= 0; i--){
                HistoryEvent event = history.get(i);
                if(!first) json.append(',');
                first = false;

                json.append("{\"time\":").append(event.time)
                    .append(",\"type\":\"").append(esc(event.type))
                    .append("\",\"sector\":\"").append(esc(event.sector))
                    .append("\",\"player\":\"").append(esc(event.player))
                    .append("\",\"text\":\"").append(esc(event.text)).append("\"}");
            }
        }
        return json.append("]}").toString();
    }

    private String noteJson(HttpExchange ex) throws IOException{
        NoteRequest req = JsonIO.read(NoteRequest.class, body(ex));
        Sector sector = req == null ? null : sector(req.planet, req.id);
        if(sector == null) return "{\"ok\":false,\"error\":\"Нет такого сектора.\"}";

        String key = req.planet + "-" + req.id;
        synchronized(campaignLock){
            Jval notes = readNotes();
            Jval entry = Jval.newObject();
            entry.put("title", req.title == null ? "" : req.title.trim());
            entry.put("note", req.note == null ? "" : req.note.trim());
            notes.put(key, entry);

            notesFile.parent().mkdirs();
            SectorRunner.writeAtomic(notesFile, notes.toString());
        }

        addHistory("note", key, req.player, "Название/заметка обновлены");
        return "{\"ok\":true}";
    }

    /** Moderator controls: pause/speed, stop a sector, kick a player, manage moderators, read logs. */
    private String adminJson(HttpExchange ex) throws IOException{
        AdminRequest req = JsonIO.read(AdminRequest.class, body(ex));

        if(req == null || !Moderators.isModerator(req.player)){
            return "{\"ok\":false,\"error\":\"Действие доступно только модераторам кампании.\"}";
        }
        if(req.action == null) req.action = "";
        if(restoring && !req.action.equals("log")){
            return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";
        }

        switch(req.action){
            case "pause" -> {
                boolean paused = !("false".equalsIgnoreCase(req.value) || "off".equalsIgnoreCase(req.value) || "0".equals(req.value));
                setPaused(paused, req.player);
                return "{\"ok\":true}";
            }
            case "speed" -> {
                float speed;
                try{
                    speed = Float.parseFloat(req.value);
                }catch(Throwable t){
                    return "{\"ok\":false,\"error\":\"Неверная скорость.\"}";
                }
                if(!Float.isFinite(speed) || speed < 0.25f || speed > 16f){
                    return "{\"ok\":false,\"error\":\"Скорость должна быть от 0.25 до 16.\"}";
                }
                setSpeedAll(speed, req.player);
                return "{\"ok\":true}";
            }
            case "stop" -> {
                if(req.sector == null || req.sector.isEmpty() || req.sector.equals(homeSector)){
                    return "{\"ok\":false,\"error\":\"Домашний сектор нельзя остановить из админки.\"}";
                }
                if(instances.get(req.sector) == null){
                    return "{\"ok\":false,\"error\":\"Этот сектор не запущен.\"}";
                }
                instances.stopAsync(req.sector);
                addHistory("admin", req.sector, req.player, "Сектор остановлен модератором");
                return "{\"ok\":true}";
            }
            case "kick" -> {
                if(req.name == null || req.name.isBlank()){
                    return "{\"ok\":false,\"error\":\"Не указан игрок.\"}";
                }
                if(req.sector == null || req.sector.isEmpty() || req.sector.equals(homeSector)){
                    Core.app.post(() -> MpSectorsMod.adminKick(req.name));
                }else if(instances.get(req.sector) == null){
                    return "{\"ok\":false,\"error\":\"Этот сектор не запущен.\"}";
                }else{
                    command(req.sector, "kick", req.name);
                }
                addHistory("admin", req.sector, req.player, "Кик игрока: " + req.name);
                return "{\"ok\":true}";
            }
            case "modadd" -> {
                if(req.name == null || req.name.isBlank()){
                    return "{\"ok\":false,\"error\":\"Не указан игрок.\"}";
                }
                if(!Moderators.add(req.name)){
                    return "{\"ok\":false,\"error\":\"Этот игрок уже модератор.\"}";
                }
                addHistory("admin", "", req.player, "Модератор добавлен: " + req.name);
                return "{\"ok\":true}";
            }
            case "modremove" -> {
                if(req.name == null || req.name.isBlank()){
                    return "{\"ok\":false,\"error\":\"Не указан игрок.\"}";
                }
                if(!Moderators.isModerator(req.name)){
                    return "{\"ok\":false,\"error\":\"Этот игрок не модератор.\"}";
                }
                if(Moderators.list().length <= 1){
                    return "{\"ok\":false,\"error\":\"Нельзя снять последнего модератора.\"}";
                }
                if(!Moderators.remove(req.name)){
                    return "{\"ok\":false,\"error\":\"Этот игрок не модератор.\"}";
                }
                addHistory("admin", "", req.player, "Модератор снят: " + req.name);
                return "{\"ok\":true}";
            }
            case "ban" -> {
                if(req.name == null || req.name.isBlank()){
                    return "{\"ok\":false,\"error\":\"Не указан игрок.\"}";
                }
                if(Moderators.isModerator(req.name)){
                    return "{\"ok\":false,\"error\":\"Нельзя забанить модератора. Сначала снимите его.\"}";
                }
                ban(req.name, req.player, req.reason, Math.max(0, req.hours));
                return "{\"ok\":true}";
            }
            case "companycreate" -> {
                return companyCreate(req.value, req.player);
            }
            case "companyswitch" -> {
                return companySwitch(req.value, req.player);
            }
            case "unban" -> {
                if(req.name == null || req.name.isBlank()){
                    return "{\"ok\":false,\"error\":\"Не указан игрок.\"}";
                }
                unban(req.name, req.player);
                return "{\"ok\":true}";
            }
            case "bans" -> {
                StringBuilder json = new StringBuilder("{\"ok\":true,\"bans\":[");
                Bans.BanEntry[] bans = Bans.entries();
                for(int i = 0; i < bans.length; i++){
                    if(i > 0) json.append(',');
                    json.append("{\"name\":\"").append(esc(bans[i].name)).append("\",")
                        .append("\"reason\":\"").append(esc(bans[i].reason)).append("\",")
                        .append("\"expires\":").append(bans[i].expires).append('}');
                }
                return json.append("]}").toString();
            }
            case "snapshot" -> {
                return snapshotBlocking();
            }
            case "log" -> {
                return logJson(req.sector);
            }
            default -> {
                return "{\"ok\":false,\"error\":\"Неизвестное действие.\"}";
            }
        }
    }

    /** Applies a command to the home at once and asks every running instance to do the same. */
    private void broadcastCommand(String action, String value){
        Core.app.post(() -> MpSectorsMod.adminCommand(action, value));
        for(InstanceManager.Instance inst : instances.all()){
            if(inst.stopping) continue;
            command(inst.sector, action, value);
        }
    }

    private void command(String sector, String action, String value){
        writeRequest("command", "{\"sector\":\"" + esc(sector) + "\",\"action\":\"" + esc(action)
            + "\",\"value\":\"" + esc(value) + "\",\"time\":" + System.currentTimeMillis() + "}");
    }

    /** Last 150 lines of the coordinator log or one instance's server.log. */
    private String logJson(String sector){
        Fi file;
        if(sector == null || sector.isEmpty() || sector.equals(homeSector)){
            file = Core.files.local("config/logs/log-0.txt");
        }else{
            //only keys of known instances: the client must not be able to read arbitrary files
            if(instances.get(sector) == null){
                return "{\"ok\":false,\"error\":\"Этот сектор не запущен.\"}";
            }
            file = Core.files.local("instances").child(sector).child("server.log");
        }

        if(!file.exists()) return "{\"ok\":false,\"error\":\"Лог не найден.\"}";

        try{
            String[] lines = readTail(file, 64 * 1024).split("\n", -1);
            int start = Math.max(0, lines.length - 150);
            StringBuilder out = new StringBuilder();
            for(int i = start; i < lines.length; i++){
                out.append(lines[i]).append('\n');
            }
            if(out.length() > 30000) out.delete(0, out.length() - 30000);
            return "{\"ok\":true,\"log\":\"" + esc(out.toString()) + "\"}";
        }catch(Throwable t){
            return "{\"ok\":false,\"error\":\"Не удалось прочитать лог.\"}";
        }
    }

    /** Reads the end of a log without loading a multi-gigabyte file into memory. */
    private static String readTail(Fi file, int maxBytes) throws IOException{
        long length = file.length();
        if(length <= 0) return "";

        long start = Math.max(0, length - maxBytes);
        byte[] bytes = new byte[(int)(length - start)];
        try(java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.file(), "r")){
            raf.seek(start);
            raf.readFully(bytes);
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        if(start > 0){
            //the cut may be in the middle of a line; drop the partial first line
            int newline = text.indexOf('\n');
            if(newline >= 0) text = text.substring(newline + 1);
        }
        return text;
    }

    private Jval readNotes(){
        if(notesFile == null || !notesFile.exists()) return Jval.newObject();
        try{
            Jval json = Jval.read(notesFile.readString());
            return json == null || !json.isObject() ? Jval.newObject() : json;
        }catch(Throwable t){
            return Jval.newObject();
        }
    }

    private static String noteField(Jval notes, String key, String field){
        Jval entry = notes == null ? null : notes.get(key);
        if(entry == null || !entry.isObject()) return "";
        return entry.getString(field, "");
    }

    /** POST adds a line, GET returns everything newer than ?since=. */
    private String chatJson(HttpExchange ex) throws IOException{
        if("POST".equalsIgnoreCase(ex.getRequestMethod())){
            ChatPost req = JsonIO.read(ChatPost.class, body(ex));
            if(req == null || req.message == null || req.message.isBlank()) return "{\"ok\":false}";

            //the client input is already limited, but direct requests are not
            if(req.message.length() > 300) req.message = req.message.substring(0, 300);
            if(!allowChat(req.player, req.message)) return "{\"ok\":false,\"error\":\"Слишком много сообщений, подождите.\"}";

            synchronized(chat){
                ChatMessage message = new ChatMessage();
                message.id = ++chatId;
                message.time = System.currentTimeMillis();
                message.player = req.player == null ? "" : req.player;
                message.sector = req.sector == null ? "" : req.sector;
                message.message = req.message;
                chat.add(message);

                while(chat.size() > maxChatMessages) chat.remove(0);
            }
            saveChat();
            return "{\"ok\":true}";
        }

        long since = 0;
        String query = ex.getRequestURI().getQuery();
        if(query != null){
            for(String part : query.split("&")){
                if(part.startsWith("since=")){
                    try{
                        since = Long.parseLong(part.substring("since=".length()));
                    }catch(NumberFormatException ignored){
                    }
                }
            }
        }

        StringBuilder json = new StringBuilder();
        synchronized(chat){
            //a restarted coordinator has a lower id again. Do not replay the whole restored
            //buffer (clients would see 200 old lines); skip to the current id, so new lines
            //start at chatId + 1 and are not lost. A fresh client (since = 0) still gets the buffer.
            if(since > chatId) since = chatId;
            if(!chat.isEmpty() && since < chat.get(0).id - 1) since = chat.get(0).id - 1;

            json.append("{\"next\":").append(chatId).append(",\"messages\":[");
            boolean first = true;
            for(ChatMessage message : chat){
                if(message.id <= since) continue;
                if(!first) json.append(',');
                first = false;

                json.append("{\"id\":").append(message.id)
                    .append(",\"time\":").append(message.time)
                    .append(",\"player\":\"").append(esc(message.player))
                    .append("\",\"sector\":\"").append(esc(message.sector))
                    .append("\",\"message\":\"").append(esc(message.message)).append("\"}");
            }
            json.append("]}");
        }
        return json.toString();
    }

    /** Sliding 10 s window: five relayed messages per player, and no identical line twice. */
    private boolean allowChat(String player, String message){
        String key = player == null || player.isEmpty() ? "?" : player;
        long now = System.currentTimeMillis();

        synchronized(chatRate){
            long[] state = chatRate.get(key);
            if(state == null){
                state = new long[]{now, 0};
                chatRate.put(key, state);
            }
            if(now - state[0] > chatWindow){
                state[0] = now;
                state[1] = 0;
            }
            if(state[1] >= chatBurst) return false;

            String last = chatText.get(key);
            long lastTime = chatTextTime.get(key, 0L);
            if(message != null && message.equals(last) && now - lastTime < 10_000L) return false;

            chatText.put(key, message);
            chatTextTime.put(key, now);
            state[1]++;
        }
        return true;
    }

    /** The last 200 chat lines survive a coordinator restart and snapshot restores. */
    private void loadChat(){
        synchronized(chat){
            chat.clear();
            chatId = 0;
            if(chatFile == null || !chatFile.exists()) return;

            try{
                ChatMessage[] stored = JsonIO.read(ChatMessage[].class, chatFile.readString());
                if(stored != null){
                    for(ChatMessage message : stored){
                        if(message == null || message.message == null || message.message.isEmpty()) continue;
                        chat.add(message);
                        chatId = Math.max(chatId, message.id);
                    }
                }
                while(chat.size() > maxChatMessages) chat.remove(0);
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read chat.json", t);
            }
        }
    }

    private void saveChat(){
        if(chatFile == null) return;

        synchronized(chat){
            try{
                StringBuilder json = new StringBuilder("[");
                for(int i = 0; i < chat.size(); i++){
                    ChatMessage m = chat.get(i);
                    if(i > 0) json.append(',');
                    json.append("{\"id\":").append(m.id)
                        .append(",\"time\":").append(m.time)
                        .append(",\"player\":\"").append(esc(m.player)).append('"')
                        .append(",\"sector\":\"").append(esc(m.sector)).append('"')
                        .append(",\"message\":\"").append(esc(m.message)).append("\"}");
                }
                json.append(']');

                campaign.mkdirs();
                SectorRunner.writeAtomic(chatFile, json.toString());
            }catch(Throwable t){
                Log.err("[mp-sectors] could not write chat.json", t);
            }
        }
    }

    /** A campaign-wide line (watchdog, player limit), shown as "◆ message" in every sector. */
    public void addSystemChat(String message){
        if(message == null || message.isEmpty()) return;

        synchronized(chat){
            ChatMessage m = new ChatMessage();
            m.id = ++chatId;
            m.time = System.currentTimeMillis();
            m.player = "Кампания";
            m.sector = "";
            m.message = message;
            chat.add(m);
            while(chat.size() > maxChatMessages) chat.remove(0);
        }
        saveChat();
    }

    private void watchdogMessage(String text){
        Log.info("[mp-sectors] @", text);
        addSystemChat(text);
        addHistory("watchdog", "", "", text);
    }

    /** Notifies all sectors when a sector's attacked state flips. */
    private void checkAttacks(long now){
        if(now - lastAttackCheck < 2000L) return;
        lastAttackCheck = now;

        ObjectSet<String> current = new ObjectSet<>();

        if(home != null && home.loaded){
            Sector sector = home.sector();
            if(sector != null){
                boolean attacked = sector.info != null && sector.info.hasCore
                    && (sector.info.waves || sector.info.attack);
                updateAttack(homeSector, attacked, current);
            }
        }

        for(InstanceManager.Instance inst : instances.all()){
            if(inst.stopping || !inst.statusSeen) continue;
            updateAttack(inst.sector, inst.attacked, current);
        }

        Seq<String> remove = new Seq<>();
        for(String key : attackState.keys()){
            if(!current.contains(key)) remove.add(key);
        }
        for(String key : remove) attackState.remove(key);
    }

    private void updateAttack(String key, boolean attacked, ObjectSet<String> current){
        if(key == null || key.isEmpty()) return;
        current.add(key);

        Boolean previous = attackState.get(key);
        if(previous == null){
            //do not announce the state a sector already had when it was loaded
            attackState.put(key, attacked);
            return;
        }
        if(previous == attacked) return;

        attackState.put(key, attacked);
        notifyEvent(attacked ? "attack" : "defend", key, "",
            attacked ? "Сектор атакован" : "Атака отбита");
    }

    /**
     * Direct connections to an instance port bypass the maxPlayers check in /land. If the
     * campaign stays over the limit for five seconds, kick the extra players.
     */
    private void enforcePlayerLimit(long now){
        int total = homePlayers + instances.totalPlayers();
        if(total <= config.maxPlayers){
            limitSince = 0;
            return;
        }

        if(limitSince == 0){
            limitSince = now;
            return;
        }
        if(now - limitSince < 5000L || now - lastLimitKick < 5000L) return;
        lastLimitKick = now;

        int excess = total - config.maxPlayers;
        for(InstanceManager.Instance inst : instances.all()){
            if(excess <= 0) break;
            if(inst.stopping) continue;
            String[] names = inst.playerNames;
            if(names == null) continue;

            for(String name : names){
                if(excess <= 0) break;
                if(name == null || name.isEmpty()) continue;
                command(inst.sector, "limit", name);
                addSystemChat("Лимит " + config.maxPlayers + " игроков: " + name + " отключён.");
                excess--;
            }
        }

        if(excess > 0){
            var players = Groups.player.copy();
            for(int i = players.size - 1; i >= 0 && excess > 0; i--){
                //keep the first home player (usually the host) when there is someone else
                if(players.size > 1 && i == 0) continue;
                Player player = players.get(i);
                player.kick(KickReason.playerLimit);
                addSystemChat("Лимит " + config.maxPlayers + " игроков: " + player.plainName() + " отключён.");
                excess--;
            }
        }
    }

    /** Snapshot of who is where, used by /who and the map panel. */
    public String[] whoLines(){
        Seq<String> lines = new Seq<>();
        lines.add("[accent]Дом (" + homeSector + "): [white]" + joinNames(homePlayerNames));
        for(InstanceManager.Instance inst : instances.all()){
            if(inst.stopping) continue;
            Sector sector = sectorForKey(inst.sector);
            String name = sector == null ? inst.sector : sector.name();
            lines.add("[accent]" + name + " (" + inst.sector + "): [white]" + joinNames(inst.playerNames));
        }
        return lines.toArray(String.class);
    }

    private static String joinNames(String[] names){
        if(names == null) return "пусто";

        StringBuilder out = new StringBuilder();
        for(String name : names){
            if(name == null || name.isEmpty()) continue;
            if(out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.length() == 0 ? "пусто" : out.toString();
    }

    private Sector sectorForKey(String key){
        if(key == null) return null;
        int split = key.lastIndexOf('-');
        if(split <= 0) return null;
        try{
            return sector(key.substring(0, split), Integer.parseInt(key.substring(split + 1)));
        }catch(NumberFormatException e){
            return null;
        }
    }

    public Fi unlocksFile(){
        return home == null ? null : home.unlocksFile;
    }

    public void saveHome(){
        if(home != null && home.loaded) home.save();
    }

    /** Manual snapshot, used by /snapshot and the mpsnapshot console command. */
    public boolean snapshotNow(){
        return takeSnapshot();
    }

    public boolean isPaused(){
        return paused;
    }

    public boolean isStarted(){
        return started;
    }

    public boolean isRestoring(){
        return restoring;
    }

    /** Sets the speed of the home and every running sector. */
    public void setSpeedAll(float speed, String player){
        broadcastCommand("speed", Float.toString(speed));
        addHistory("admin", "", player == null ? "" : player, "Скорость кампании: x" + speed);
    }

    /** Pauses/resumes the home and every running sector and blocks new landings while paused. */
    public synchronized void setPaused(boolean paused, String player){
        this.paused = paused;

        Fi flag = campaign.child("paused.flag");
        try{
            if(paused){
                SectorRunner.writeAtomic(flag, "1");
            }else if(flag.exists()){
                flag.delete();
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write paused.flag", t);
        }

        broadcastCommand("pause", paused ? "true" : "false");
        addHistory("admin", "", player == null ? "" : player, paused ? "Кампания на паузе" : "Кампания продолжена");
        Log.info("[mp-sectors] campaign paused=@", paused);
    }

    /** Adds a campaign-wide ban and kicks the player from the home and every instance. */
    public void ban(String name, String player){
        ban(name, player, "", 0);
    }

    public void ban(String name, String player, String reason, long hours){
        if(name == null || name.isBlank()) return;

        String uuid = "";
        Player local = Groups.player.find(p -> p.plainName().equalsIgnoreCase(name));
        if(local != null && local.con != null) uuid = local.con.uuid;

        long expires = hours > 0 ? System.currentTimeMillis() + hours * 60L * 60L * 1000L : 0;
        Bans.add(name, uuid, reason, player, expires);
        addHistory("admin", "", player == null ? "" : player, "Бан: " + name
            + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        Core.app.post(() -> MpSectorsMod.adminBan(name));
        for(InstanceManager.Instance inst : instances.all()){
            if(inst.stopping) continue;
            command(inst.sector, "ban", name);
        }
    }

    public void unban(String name, String player){
        if(name == null || name.isBlank()) return;

        Bans.remove(name);
        addHistory("admin", "", player == null ? "" : player, "Разбан: " + name);
    }

    public String[] banLines(){
        Bans.BanEntry[] bans = Bans.entries();
        if(bans.length == 0) return new String[]{"[lightgray]Банов нет."};

        String[] lines = new String[bans.length + 1];
        lines[0] = "[accent]Баны (" + bans.length + "):";
        for(int i = 0; i < bans.length; i++){
            Bans.BanEntry ban = bans[i];
            lines[i + 1] = "[white]" + ban.name
                + (ban.reason.isEmpty() ? "" : " [lightgray]— " + ban.reason)
                + (ban.expires <= 0 ? "" : " [orange]до "
                    + new SimpleDateFormat("dd.MM HH:mm").format(new Date(ban.expires)));
        }
        return lines;
    }

    // ---------------- companies (named campaign slots) ----------------

    private String readCompany(){
        try{
            if(companyFile != null && companyFile.exists()){
                String name = companyFile.readString().trim();
                if(!name.isEmpty()) return name;
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read companies/current.txt", t);
        }
        return "Основная";
    }

    private void writeCompany(String name){
        try{
            companiesDir.mkdirs();
            SectorRunner.writeAtomic(companyFile, name);
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write companies/current.txt", t);
        }
    }

    private ArrayList<Fi> companies(){
        ArrayList<Fi> out = new ArrayList<>();
        if(companiesDir == null || !companiesDir.exists()) return out;

        for(Fi dir : companiesDir.list()){
            if(!dir.isDirectory() || dir.name().startsWith("tmp-")) continue;
            if(dir.child("home.json").exists() || dir.child("sectors").exists()) out.add(dir);
        }
        out.sort(Comparator.comparing(Fi::name));
        return out;
    }

    /** @return a filesystem-safe name, or null if the name is empty/invalid. */
    private static String sanitizeCompany(String name){
        if(name == null) return null;
        String out = name.trim();
        if(out.isEmpty() || out.length() > 40) return null;
        if(!out.matches("[\\p{L}\\p{N} _-]+")) return null;
        return out;
    }

    private String companyCreate(String name, String player){
        if(restoring) return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";

        String safe = sanitizeCompany(name);
        if(safe == null) return "{\"ok\":false,\"error\":\"Имя компании: буквы, цифры, пробел, _ и - (до 40).\"}";
        if(safe.equalsIgnoreCase(company)) return "{\"ok\":false,\"error\":\"Эта компания уже активна.\"}";
        if(companiesDir.child(safe).exists()) return "{\"ok\":false,\"error\":\"Компания с таким именем уже есть.\"}";

        restoring = true;
        Log.info("[mp-sectors] creating company @", safe);
        try{
            instances.stopAll();
            saveHomeBlocking();
            archiveCompany(company);

            //a fresh campaign keeps research and the shared lists, but not sectors, chat or history
            Fi tmp = campaign.parent().child("campaign.new");
            tmp.deleteDirectory();
            tmp.mkdirs();
            copyIfExists(campaign.child("unlocks.json"), tmp.child("unlocks.json"));
            copyIfExists(campaign.child("moderators.json"), tmp.child("moderators.json"));
            copyIfExists(campaign.child("bans.json"), tmp.child("bans.json"));
            copyIfExists(campaign.child("home.json"), tmp.child("home.json"));
            tmp.child("sectors").mkdirs();
            tmp.child("info").mkdirs();

            swapCampaign(tmp);

            company = safe;
            writeCompany(safe);
            if(!reloadHomeBlocking() || home == null || !home.loaded){
                return "{\"ok\":false,\"error\":\"Компания создана, но домашний сектор не загрузился. Перезапустите координатор.\"}";
            }
            addHistory("admin", "", player == null ? "" : player, "Создана компания: " + safe);
            Log.info("[mp-sectors] company @ created", safe);
            return "{\"ok\":true}";
        }catch(Throwable t){
            restoring = false;
            Log.err("[mp-sectors] could not create company @", safe, t);
            return "{\"ok\":false,\"error\":\"Не удалось создать компанию: " + esc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    private String companySwitch(String name, String player){
        if(restoring) return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";

        String safe = sanitizeCompany(name);
        if(safe == null) return "{\"ok\":false,\"error\":\"Некорректное имя компании.\"}";
        if(safe.equalsIgnoreCase(company)) return "{\"ok\":true}";

        Fi target = companiesDir.child(safe);
        if(!target.exists()) return "{\"ok\":false,\"error\":\"Компания не найдена.\"}";

        restoring = true;
        Log.info("[mp-sectors] switching company @ -> @", company, safe);
        try{
            instances.stopAll();
            saveHomeBlocking();
            archiveCompany(company);

            synchronized(campaignLock){
                Fi old = campaign.parent().child("campaign.old");
                old.deleteDirectory();
                campaign.moveTo(old);
                try{
                    target.moveTo(campaign);
                }catch(Throwable t){
                    old.moveTo(campaign);
                    throw t;
                }
                old.deleteDirectory();
                deleteRequests();
            }

            company = safe;
            writeCompany(safe);
            if(!reloadHomeBlocking() || home == null || !home.loaded){
                return "{\"ok\":false,\"error\":\"Компания переключена, но домашний сектор не загрузился. Перезапустите координатор.\"}";
            }
            addHistory("admin", "", player == null ? "" : player, "Компания: " + safe);
            Log.info("[mp-sectors] company @ is active", safe);
            return "{\"ok\":true}";
        }catch(Throwable t){
            restoring = false;
            Log.err("[mp-sectors] could not switch to company @", safe, t);
            return "{\"ok\":false,\"error\":\"Не удалось переключить компанию: " + esc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** Copies the live campaign into companies/<name>, replacing the older archive. */
    private void archiveCompany(String name) throws Exception{
        companiesDir.mkdirs();
        Fi target = companiesDir.child(name);
        target.deleteDirectory();
        copyTree(campaign, target);
        Log.info("[mp-sectors] company @ archived", name);
    }

    private void swapCampaign(Fi tmp){
        synchronized(campaignLock){
            Fi old = campaign.parent().child("campaign.old");
            old.deleteDirectory();
            campaign.moveTo(old);
            try{
                tmp.moveTo(campaign);
            }catch(Throwable t){
                old.moveTo(campaign);
                throw t;
            }
            old.deleteDirectory();
            deleteRequests();
        }
    }

    private void deleteRequests(){
        Fi requests = campaign.child("requests");
        if(requests.exists()) requests.deleteDirectory();
    }

    private static void copyIfExists(Fi from, Fi to){
        try{
            if(from.exists()) SectorRunner.copy(from, to);
        }catch(Throwable t){
            Log.err("[mp-sectors] could not copy @", from.name(), t);
        }
    }

    private boolean reloadHomeBlocking(){
        CountDownLatch latch = new CountDownLatch(1);
        Core.app.post(() -> {
            try{
                reloadHome();
            }finally{
                latch.countDown();
            }
        });

        try{
            return latch.await(120, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The home sector's core was destroyed: keep the save, mark it unowned, stop autosaving it. */
    public void homeLost(){
        if(home == null || !home.loaded) return;
        home.save();
        home.markLost();
        home.loaded = false;
        notifyEvent("lost", homeSector, "", "Домашний сектор потерян");

        //do not keep serving a dead world on the home port until the coordinator restarts
        for(Player player : Groups.player.copy()){
            player.kick(KickReason.serverRestarting);
        }
        try{
            if(Vars.net != null) Vars.net.closeServer();
        }catch(Throwable t){
            Log.err("[mp-sectors] could not close the home server", t);
        }
    }

    /** The home sector was won: keep the base, record it and reload the world. */
    public void homeCaptured(){
        if(home == null || !home.loaded) return;
        home.save();
        home.markCaptured();
        notifyEvent("capture", homeSector, "", "Домашний сектор захвачен");
        home.loaded = false;
        //the vanilla game-over code may rotate to a built-in map; reload the real home after it
        Core.app.post(this::reloadHome);
    }

    // ---------------- http ----------------

    private void startHttp() throws IOException{
        http = HttpServer.create(new InetSocketAddress(config.httpPort), 0);
        http.createContext("/sectors", ex -> respond(ex, handle(ex, this::sectorsJson)));
        http.createContext("/land", ex -> respond(ex, handle(ex, this::landJson)));
        http.createContext("/home", ex -> respond(ex, handle(ex, this::homeJson)));
        http.createContext("/pads", ex -> respond(ex, handle(ex, this::padsJson)));
        http.createContext("/status", ex -> respond(ex, handle(ex, this::statusJson)));
        http.createContext("/snapshots", ex -> respond(ex, handle(ex, this::snapshotsJson)));
        http.createContext("/snapshot", ex -> respond(ex, handle(ex, this::snapshotJson)));
        http.createContext("/snapshot-sectors", ex -> respond(ex, handle(ex, this::snapshotSectorsJson)));
        http.createContext("/snapshot-comment", ex -> respond(ex, handle(ex, this::snapshotCommentJson)));
        http.createContext("/restore-sector", ex -> respond(ex, handle(ex, this::restoreSectorJson)));
        http.createContext("/restore", ex -> respond(ex, handle(ex, this::restoreJson)));
        http.createContext("/chat", ex -> respond(ex, handle(ex, this::chatJson)));
        http.createContext("/history", ex -> respond(ex, handle(ex, this::historyJson)));
        http.createContext("/note", ex -> respond(ex, handle(ex, this::noteJson)));
        http.createContext("/admin", ex -> respond(ex, handle(ex, this::adminJson)));
        http.createContext("/mods", ex -> respond(ex, handle(ex, this::modsJson)));
        http.createContext("/download", this::downloadMod);
        httpExecutor = Executors.newFixedThreadPool(4);
        http.setExecutor(httpExecutor);
        http.start();
    }

    private interface Handler{
        String get(HttpExchange ex) throws Exception;
    }

    private String handle(HttpExchange ex, Handler handler){
        try{
            return handler.get(ex);
        }catch(Throwable t){
            Log.err("[mp-sectors] http error", t);
            return "{\"ok\":false,\"error\":\"internal error\"}";
        }
    }

    private void respond(HttpExchange ex, String json){
        try{
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, bytes.length);
            try(OutputStream out = ex.getResponseBody()){
                out.write(bytes);
            }
        }catch(IOException e){
            Log.err("[mp-sectors] could not write http response", e);
        }finally{
            ex.close();
        }
    }

    private String body(HttpExchange ex) throws IOException{
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * The host the client used to reach the coordinator (its Host header), without the port.
     * ex.getRemoteAddress() is the player's own address, which is useless for LAN/VPN clients.
     */
    private static String requestHost(HttpExchange ex){
        String host = ex.getRequestHeaders().getFirst("Host");

        if(host == null || host.isEmpty()){
            return ex.getRemoteAddress().getAddress().getHostAddress();
        }

        host = host.trim();

        if(host.startsWith("[")){
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }

        int colon = host.lastIndexOf(':');
        if(colon > 0 && host.indexOf(':') == colon){
            return host.substring(0, colon);
        }

        return host;
    }

    // ---------------- api ----------------

    private String sectorsJson(HttpExchange ex){
        SectorsResponse out = new SectorsResponse();
        out.home = homeSector;
        out.maxSectors = config.maxSectors;
        out.maxPlayers = config.maxPlayers;
        out.players = homePlayers + instances.totalPlayers();

        Snapshot snap = snapshot();
        Jval notes = readNotes();
        StringBuilder json = new StringBuilder("{\"home\":\"").append(esc(homeSector)).append("\",");
        json.append("\"maxSectors\":").append(config.maxSectors).append(',');
        json.append("\"maxPlayers\":").append(config.maxPlayers).append(',');
        json.append("\"players\":").append(out.players).append(',');
        json.append("\"paused\":").append(paused).append(',');
        json.append("\"steamHost\":\"").append(esc(steamHost())).append("\",");
        json.append("\"steamId\":").append(SteamSupport.steamId()).append(',');
        json.append("\"moderators\":[");
        String[] moderators = Moderators.list();
        for(int i = 0; i < moderators.length; i++){
            if(i > 0) json.append(',');
            json.append('"').append(esc(moderators[i])).append('"');
        }
        json.append("],");
        json.append("\"sectors\":[");

        boolean first = true;
        for(Planet planet : Vars.content.planets()){
            if(planet.sectors.size == 0 || !planet.accessible || !planet.visible || planet.removed) continue;

            for(Sector sector : planet.sectors){
                String key = planet.name + "-" + sector.id;
                SectorInfo info = snap.info.get(key);
                boolean hasBase = snap.hasBase.get(key, false);
                InstanceManager.Instance inst = instances.get(key);
                boolean running = (key.equals(homeSector) && home != null && home.loaded)
                    || (inst != null && !inst.stopping);
                int players = key.equals(homeSector) ? homePlayers
                    : (inst == null || inst.stopping ? 0 : inst.players);
                boolean captured = info != null && info.hasCore && !info.waves && !info.attack;

                //the map asks for this list often; record a capture once, when it flips
                synchronized(this){
                    Boolean previous = this.captured.get(key);
                    if(previous != null && !previous && captured){
                        notifyEvent("capture", key, "", "Сектор захвачен");
                    }
                    this.captured.put(key, captured);
                }

                if(!first) json.append(',');
                first = false;

                json.append('{')
                    .append("\"planet\":\"").append(esc(planet.name)).append("\",")
                    .append("\"id\":").append(sector.id).append(',')
                    .append("\"name\":\"").append(esc(sectorName(sector, info))).append("\",")
                    .append("\"title\":\"").append(esc(noteField(notes, key, "title"))).append("\",")
                    .append("\"note\":\"").append(esc(noteField(notes, key, "note"))).append("\",")
                    .append("\"hasBase\":").append(hasBase).append(',')
                    .append("\"captured\":").append(captured).append(',')
                    .append("\"attacked\":").append(info != null && info.hasCore && (info.waves || info.attack)).append(',')
                    .append("\"allowed\":").append(allowed(sector, snap)).append(',')
                    .append("\"running\":").append(running).append(',')
                    .append("\"players\":").append(players);

                if(info != null){
                    json.append(",\"info\":\"").append(esc(JsonIO.write(info))).append('"');
                }
                json.append('}');
            }
        }

        json.append("]}");
        return json.toString();
    }

    private String landJson(HttpExchange ex) throws IOException{
        LandRequest req = JsonIO.read(LandRequest.class, body(ex));
        LandResponse out = new LandResponse();

        Log.info("[mp-sectors] land request @-@ from @-@", req.planet, req.id, req.fromPlanet, req.fromId);

        if(restoring){
            out.error = "Кампания восстанавливается, подождите.";
            return landJson(out);
        }

        Sector sector = sector(req.planet, req.id);
        if(sector == null){
            out.error = "Нет такого сектора.";
            return landJson(out);
        }

        String key = req.planet + "-" + req.id;

        //a paused campaign still lets players return to the home lobby, but starts no new sector
        if(paused && !key.equals(homeSector)){
            out.error = "Кампания на паузе: высадка на секторы запрещена.";
            return landJson(out);
        }

        Snapshot snap = snapshot();

        //the interplanetary accelerator is the vanilla way to reach a new planet for the first time
        if(!req.accelerator && !allowed(sector, snap)){
            out.error = "Этот сектор ещё не открыт.";
            return landJson(out);
        }

        //the client must connect to the host it used for the HTTP request, not to its own address
        String host = requestHost(ex);

        if(key.equals(homeSector)){
            if(home == null || !home.loaded){
                out.error = "Домашний сектор потерян: он отстроится заново при следующем запуске координатора.";
                return landJson(out);
            }
            out.ok = true;
            out.host = host;
            out.port = config.gamePort;
            out.home = true;
            out.steamHost = waitSteamHost();
            out.steamId = SteamSupport.steamId();
            consumeLanding(req);

            //an accelerator can also target an owned sector; its save ignores the loadout
            if(req.accelerator && req.resources != null && !req.resources.isEmpty()){
                String items = req.resources;
                Core.app.post(() -> {
                    if(home != null && home.loaded) home.addCargo(items);
                });
            }
            return landJson(out);
        }

        InstanceManager.Instance inst = instances.get(key);
        //a sector being stopped is still in the list; do not hand its dying port to the client
        if(inst != null && inst.stopping) inst = null;

        if(inst == null){
            if(1 + instances.runningCount() + 1 > config.maxSectors){
                out.error = "Места заняты: уже загружено " + config.maxSectors + " секторов.";
                return landJson(out);
            }

            int total = homePlayers + instances.totalPlayers();
            if(!isOnline(req.player) && total >= config.maxPlayers){
                out.error = "Места заняты: на сервере уже " + config.maxPlayers + " игроков.";
                return landJson(out);
            }

            inst = instances.start(key, req.loadout, req.resources);
            if(inst == null){
                out.error = "Не удалось запустить сектор.";
                return landJson(out);
            }
        }

        //another player may be landing on the same sector right now
        if(!inst.ready){
            inst = instances.waitReady(inst, 45_000L);
            if(inst == null){
                out.error = "Не удалось запустить сектор.";
                return landJson(out);
            }
        }

        //an admin may have stopped this sector while another player was landing
        if(inst.stopping){
            out.error = "Сектор останавливается, попробуйте ещё раз.";
            return landJson(out);
        }

        //consume the launch source even when the target sector was already running
        consumeLanding(req);

        //target already has a base: its save ignores the launch loadout, so deliver the cargo
        if(req.accelerator && req.resources != null && !req.resources.isEmpty() && snap.hasBase.get(key, false)){
            command(inst.sector, "cargo", req.resources);
        }

        notifyEvent("land", key, req.player, "Высадка в сектор");

        if(config.steam){
            for(int i = 0; i < 40 && inst.steamHost.isEmpty(); i++){
                try{
                    Thread.sleep(250);
                }catch(InterruptedException e){
                    break;
                }
            }
        }

        out.ok = true;
        out.host = host;
        out.port = inst.port;
        out.steamHost = inst.steamHost;
        out.steamId = inst.steamId;
        return landJson(out);
    }

    private static String steamHost(){
        long handle = SteamSupport.lobbyHandle();
        return handle == 0 ? "" : "steam:" + handle;
    }

    /** The Steam lobby is created asynchronously right after openServer(). */
    private String waitSteamHost(){
        if(!config.steam) return "";

        for(int i = 0; i < 40; i++){
            String host = steamHost();
            if(!host.isEmpty()) return host;
            try{
                Thread.sleep(250);
            }catch(InterruptedException e){
                break;
            }
        }
        return steamHost();
    }

    /** Removes the accelerator / launch cost from the sector the player is leaving. */
    private void consumeLanding(LandRequest req){
        if(req.accelerator && req.fromId >= 0 && req.tileX >= 0 && req.tileY >= 0){
            consumeAccelerator(req.fromPlanet, req.fromId, req.tileX, req.tileY);
        }else if(req.cost != null && !req.cost.isEmpty() && req.fromId >= 0){
            deductLaunch(req.fromPlanet, req.fromId, req.cost);
        }
    }

    /** Removes the loadout cost from the launch source: live for the home, via a request file for instances. */
    private void deductLaunch(String fromPlanet, int fromId, String cost){
        String key = fromPlanet + "-" + fromId;
        if(key.equals(homeSector)){
            Core.app.post(() -> {
                if(home != null && home.loaded) home.deduct(cost);
            });
        }else{
            writeRequest("launch", "{\"sector\":\"" + esc(key) + "\",\"cost\":\"" + esc(cost) + "\",\"time\":" + System.currentTimeMillis() + "}");
        }
    }

    /** Consumes the interplanetary accelerator in the launch source. */
    private void consumeAccelerator(String fromPlanet, int fromId, int x, int y){
        String key = fromPlanet + "-" + fromId;
        if(key.equals(homeSector)){
            Core.app.post(() -> {
                if(home != null && home.loaded) home.consumeAccelerator(x, y);
            });
        }else{
            writeRequest("accelerator", "{\"sector\":\"" + esc(key) + "\",\"x\":" + x + ",\"y\":" + y + ",\"time\":" + System.currentTimeMillis() + "}");
        }
    }

    /**
     * Each request is its own file: several instances poll the same folder, and a shared file
     * would be deleted by whichever sector read it first.
     */
    private void writeRequest(String type, String json){
        try{
            Fi dir = campaign.child("requests");
            dir.mkdirs();

            long now = System.currentTimeMillis();
            for(Fi old : dir.list()){
                if(now - old.file().lastModified() > 120_000L) old.delete();
            }

            SectorRunner.writeAtomic(dir.child(type + "-" + System.nanoTime() + ".json"), json);
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write @ request", type, t);
        }
    }

    /**
     * JsonIO writes "minimal" JSON, which leaves strings like 127.0.0.1 unquoted; the client's
     * parser then swallows the following ",port:..." and the address looks empty. Build valid JSON.
     */
    private static String landJson(LandResponse out){
        StringBuilder json = new StringBuilder("{\"ok\":").append(out.ok);
        if(out.error != null) json.append(",\"error\":\"").append(esc(out.error)).append('"');
        if(out.host != null) json.append(",\"host\":\"").append(esc(out.host)).append('"');
        json.append(",\"port\":").append(out.port);
        if(out.steamHost != null && !out.steamHost.isEmpty()){
            json.append(",\"steamHost\":\"").append(esc(out.steamHost)).append('"');
            json.append(",\"steamId\":").append(out.steamId);
        }
        json.append(",\"home\":").append(out.home).append('}');
        return json.toString();
    }

    private String homeJson(HttpExchange ex) throws IOException{
        LandRequest req = JsonIO.read(LandRequest.class, body(ex));
        Sector sector = sector(req.planet, req.id);
        if(sector == null) return "{\"ok\":false,\"error\":\"Нет такого сектора.\"}";

        if(restoring) return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";
        if(paused) return "{\"ok\":false,\"error\":\"Кампания на паузе: смена дома запрещена.\"}";

        String key = req.planet + "-" + req.id;

        //a sector without a core cannot become the home; writing home.json anyway would
        //point the next coordinator start at a lost sector
        Fi infoFile = campaign.child("info").child(key + ".json");
        SectorInfo info = null;
        if(infoFile.exists()){
            try{
                info = JsonIO.read(SectorInfo.class, infoFile.readString());
            }catch(Throwable ignored){
            }
        }
        if(info == null || !info.hasCore){
            return "{\"ok\":false,\"error\":\"У этого сектора нет базы.\"}";
        }

        writeHome(key);
        addHistory("home", key, req.player, "Домашний сектор изменён");

        if(homePlayers == 0){
            Core.app.post(() -> switchHome(key));
            return "{\"ok\":true,\"message\":\"Домашний сектор изменён.\"}";
        }else{
            pendingHome = key;
            return "{\"ok\":true,\"message\":\"Домашний сектор сменится, когда все выйдут из текущего.\"}";
        }
    }

    /** Points every sector's launch pads at one destination sector. */
    private String padsJson(HttpExchange ex) throws IOException{
        LandRequest req = JsonIO.read(LandRequest.class, body(ex));
        Sector sector = sector(req.planet, req.id);
        if(sector == null) return "{\"ok\":false,\"error\":\"Нет такого сектора.\"}";

        if(restoring) return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";

        Snapshot snap = snapshot();
        if(!allowed(sector, snap)) return "{\"ok\":false,\"error\":\"Этот сектор ещё не открыт.\"}";

        int updated = 0;
        for(Fi file : campaign.child("info").list()){
            if(!file.extension().equals("json")) continue;
            //launch pads only work inside one planet
            if(!file.nameWithoutExtension().startsWith(req.planet + "-")) continue;
            try{
                SectorInfo info = JsonIO.read(SectorInfo.class, file.readString());
                if(info == null) continue;
                info.destination = sector;
                SectorRunner.writeAtomic(file, JsonIO.write(info));
                updated++;
            }catch(Throwable t){
                Log.err("[mp-sectors] could not update launch destination in @", file.name());
            }
        }

        if(home != null && home.loaded && home.sector() != null && home.sector().planet.name.equals(req.planet)){
            home.sector().info.destination = sector;
        }

        //instances poll this file and apply the same destination to their live sector
        SectorRunner.writeAtomic(campaign.child("pads.json"), "{\"planet\":\"" + esc(req.planet) + "\",\"id\":" + req.id + "}");
        addHistory("pads", req.planet + "-" + req.id, req.player, "Пусковые площадки направлены на " + req.planet + "-" + req.id);
        Log.info("[mp-sectors] launch pads directed to @ (@ sectors updated)", req.planet + "-" + req.id, updated);
        return "{\"ok\":true}";
    }

    private String statusJson(HttpExchange ex){
        StatusResponse out = new StatusResponse();
        out.home = homeSector;
        out.players = homePlayers + instances.totalPlayers();
        out.sectors = 1 + instances.runningCount();
        out.maxSectors = config.maxSectors;
        out.maxPlayers = config.maxPlayers;

        StringBuilder json = new StringBuilder("{\"home\":\"").append(esc(homeSector)).append("\",");
        json.append("\"players\":").append(out.players).append(',');
        json.append("\"sectors\":").append(out.sectors).append(',');
        json.append("\"maxSectors\":").append(config.maxSectors).append(',');
        json.append("\"maxPlayers\":").append(config.maxPlayers).append(',');
        json.append("\"paused\":").append(paused).append(',');
        json.append("\"serverVersion\":\"").append(esc(modVersion("mp-sectors"))).append("\",");
        json.append("\"campaignVersion\":\"").append(esc(modVersion("mp-campaign"))).append("\",");
        json.append("\"uptime\":").append(startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt).append(',');
        json.append("\"snapshotCount\":").append(snapshots().size() + dailySnapshots().size()).append(',');
        json.append("\"lastSnapshot\":").append(lastSnapshot).append(',');
        java.io.File backup = snapshotsDir == null ? null : snapshotsDir.file();
        boolean backupOk = backup != null && backup.exists();
        json.append("\"diskFree\":").append(backupOk ? backup.getUsableSpace() : -1).append(',');
        json.append("\"diskTotal\":").append(backupOk ? backup.getTotalSpace() : -1).append(',');
        Runtime runtime = Runtime.getRuntime();
        json.append("\"memoryUsed\":").append(runtime.totalMemory() - runtime.freeMemory()).append(',');
        json.append("\"memoryMax\":").append(runtime.maxMemory()).append(',');
        json.append("\"moderators\":[");
        String[] moderators = Moderators.list();
        for(int i = 0; i < moderators.length; i++){
            if(i > 0) json.append(',');
            json.append('"').append(esc(moderators[i])).append('"');
        }
        json.append("],");
        json.append("\"company\":\"").append(esc(company)).append("\",");
        json.append("\"companies\":[");
        ArrayList<Fi> slots = companies();
        for(int i = 0; i < slots.size(); i++){
            if(i > 0) json.append(',');
            json.append("{\"name\":\"").append(esc(slots.get(i).name())).append("\",")
                .append("\"time\":").append(slots.get(i).lastModified()).append('}');
        }
        json.append("],");
        json.append("\"bans\":[");
        Bans.BanEntry[] bans = Bans.entries();
        for(int i = 0; i < bans.length; i++){
            if(i > 0) json.append(',');
            Bans.BanEntry ban = bans[i];
            json.append("{\"name\":\"").append(esc(ban.name)).append("\",")
                .append("\"reason\":\"").append(esc(ban.reason)).append("\",")
                .append("\"by\":\"").append(esc(ban.by)).append("\",")
                .append("\"created\":").append(ban.created).append(',')
                .append("\"expires\":").append(ban.expires).append('}');
        }
        json.append("],");
        json.append("\"homePlayers\":[");
        String[] homeNames = homePlayerNames;
        for(int i = 0; i < homeNames.length; i++){
            if(i > 0) json.append(',');
            json.append('"').append(esc(homeNames[i])).append('"');
        }
        json.append("],");
        json.append("\"who\":[");
        String[] who = whoLines();
        for(int i = 0; i < who.length; i++){
            if(i > 0) json.append(',');
            json.append('"').append(esc(who[i])).append('"');
        }
        json.append("],");
        json.append("\"instances\":[");
        boolean first = true;
        for(InstanceManager.Instance inst : instances.all()){
            if(inst.stopping) continue;
            if(!first) json.append(',');
            first = false;
            json.append("{\"sector\":\"").append(esc(inst.sector)).append("\",\"port\":").append(inst.port)
                .append(",\"players\":").append(inst.players).append(",\"names\":[");
            String[] names = inst.playerNames;
            if(names != null){
                boolean firstPlayer = true;
                for(String name : names){
                    if(name == null || name.isEmpty()) continue;
                    if(!firstPlayer) json.append(',');
                    firstPlayer = false;
                    json.append('"').append(esc(name)).append('"');
                }
            }
            json.append("]}");
        }
        json.append("]}");
        return json.toString();
    }

    // ---------------- client mod distribution ----------------

    private static Fi locateModsDir(){
        try{
            if(Vars.mods != null){
                var mod = Vars.mods.locateMod("mp-sectors");
                if(mod != null && mod.file != null) return mod.file.parent();
            }
        }catch(Throwable ignored){
        }
        return Vars.modDirectory;
    }

    private String modsJson(HttpExchange ex){
        StringBuilder json = new StringBuilder("{\"ok\":true,\"files\":[");
        boolean first = true;
        for(String name : new String[]{"mp-campaign.jar", "any-research.jar"}){
            Fi file = modsDir == null ? null : modsDir.child(name);
            if(file == null || !file.exists()) continue;

            if(!first) json.append(',');
            first = false;
            String version = name.startsWith("mp-campaign") ? modVersion("mp-campaign") : modVersion("any-research");
            json.append("{\"name\":\"").append(esc(name)).append("\",")
                .append("\"size\":").append(file.length()).append(',')
                .append("\"version\":\"").append(esc(version)).append("\"}");
        }
        return json.append("],\"campaignVersion\":\"").append(esc(modVersion("mp-campaign"))).append("\"}").toString();
    }

    private void downloadMod(HttpExchange ex){
        Fi source = null;
        try{
            String name = query(ex, "file");
            if(name != null && (name.equals("mp-campaign.jar") || name.equals("any-research.jar"))){
                Fi candidate = modsDir == null ? null : modsDir.child(name);
                if(candidate != null && candidate.exists()) source = candidate;
            }

            if(source == null){
                respond(ex, "{\"ok\":false,\"error\":\"Файл не найден.\"}");
                return;
            }

            byte[] bytes = source.readBytes();
            ex.getResponseHeaders().set("Content-Type", "application/java-archive");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + source.name() + "\"");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, bytes.length);
            try(OutputStream out = ex.getResponseBody()){
                out.write(bytes);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not serve a mod download", t);
        }finally{
            ex.close();
        }
    }

    private static String query(HttpExchange ex, String key){
        String raw = ex.getRequestURI().getRawQuery();
        if(raw == null || raw.isEmpty()) return null;

        for(String part : raw.split("&")){
            int split = part.indexOf('=');
            if(split <= 0 || !part.substring(0, split).equals(key)) continue;
            try{
                return java.net.URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
            }catch(Throwable ignored){
            }
        }
        return null;
    }

    // ---------------- state ----------------

    /**
     * Imported campaigns (a copy of a real save folder) have no info/*.json yet. The game stores
     * SectorInfo in settings.bin as "&lt;planet&gt;-s-&lt;id&gt;-info", so copy that into the campaign
     * folder once. Without it every imported save would look like a base and the whole map unlocks.
     */
    private void importInfo(){
        for(Fi save : campaign.child("sectors").list()){
            if(!save.extension().equals("msav")) continue;

            String key = save.nameWithoutExtension();
            Fi infoFile = campaign.child("info").child(key + ".json");
            if(infoFile.exists()) continue;

            int split = key.lastIndexOf('-');
            if(split <= 0) continue;

            Planet planet = Vars.content.getByName(ContentType.planet, key.substring(0, split));
            int id;
            try{
                id = Integer.parseInt(key.substring(split + 1));
            }catch(NumberFormatException e){
                continue;
            }
            if(planet == null || id < 0 || id >= planet.sectors.size) continue;

            SectorInfo info = planet.sectors.get(id).info;
            infoFile.parent().mkdirs();
            SectorRunner.writeAtomic(infoFile, JsonIO.write(info));
            Log.info("[mp-sectors] imported sector info for @ (core=@)", key, info.hasCore);
        }
    }

    private static class Snapshot{
        final ObjectMap<String, Boolean> hasBase = new ObjectMap<>();
        final ObjectMap<String, SectorInfo> info = new ObjectMap<>();
    }

    /** Reads the campaign folder once per request. */
    private Snapshot snapshot(){
        Snapshot out = new Snapshot();

        Fi[] saves = campaign.child("sectors").list();
        for(Fi save : saves){
            if(!save.extension().equals("msav")) continue;

            String key = save.nameWithoutExtension();
            Fi infoFile = campaign.child("info").child(key + ".json");
            SectorInfo info = null;
            if(infoFile.exists()){
                try{
                    info = JsonIO.read(SectorInfo.class, infoFile.readString());
                }catch(Throwable ignored){
                }
            }

            if(info != null) out.info.put(key, info);
            out.hasBase.put(key, info != null && info.hasCore);
        }

        return out;
    }

    private synchronized boolean allowed(Sector sector, Snapshot snap){
        if(!sector.planet.accessible || !sector.planet.visible || sector.planet.generator == null) return false;

        //already yours
        if(snap.hasBase.get(sector.planet.name + "-" + sector.id, false)) return true;

        //another planet is only reachable through a base there
        Planet homePlanet = campaignPlanet();
        if(homePlanet != null && sector.planet != homePlanet) return false;

        //the starting sector is always available
        if(sector.id == sector.planet.startSector) return true;
        if(sector.isShielded()) return false;

        boolean nearBase = false;
        for(Sector near : sector.near()){
            if(near != null && snap.hasBase.get(near.planet.name + "-" + near.id, false)){
                nearBase = true;
                break;
            }
        }

        //story sectors only: submissions and numbered sectors use the preset slot too
        if(sector.preset != null && sector.preset.requireUnlock){
            return !sector.preset.locked() || nearBase;
        }

        //numbered sectors (including submission presets): only after a better core, next to a base
        if(!Blocks.coreFoundation.unlocked() && !Blocks.coreNucleus.unlocked()) return false;
        return nearBase;
    }

    private @Nullable Planet campaignPlanet(){
        if(home == null) return null;
        Sector sector = home.sector();
        return sector == null ? null : sector.planet;
    }

    private Sector sector(String planetName, int id){
        Planet planet = planetName == null ? null : Vars.content.getByName(ContentType.planet, planetName);
        if(planet == null){
            Log.err("[mp-sectors] no planet named @", planetName);
            return null;
        }
        if(id < 0 || id >= planet.sectors.size){
            Log.err("[mp-sectors] planet @ has @ sectors, asked for @", planetName, planet.sectors.size, id);
            return null;
        }
        return planet.sectors.get(id);
    }

    private String sectorName(Sector sector, SectorInfo info){
        if(info != null && info.name != null && !info.name.isEmpty()) return info.name;
        return sector.name();
    }

    private boolean isOnline(String name){
        if(name == null || name.isEmpty()) return false;
        for(mindustry.gen.Player player : Groups.player.copy()){
            if(player.plainName().equals(name)) return true;
        }
        for(InstanceManager.Instance inst : instances.all()){
            for(String other : inst.playerNames){
                if(other != null && other.equals(name)) return true;
            }
        }
        return false;
    }

    private String readHomeSector(){
        if(homeFile.exists()){
            try{
                Home h = JsonIO.read(Home.class, homeFile.readString());
                if(h != null && h.sector != null && !h.sector.isEmpty()) return h.sector;
            }catch(Throwable ignored){
            }
        }

        Fi best = null;
        for(Fi save : campaign.child("sectors").list()){
            if(!save.extension().equals("msav")) continue;
            if(best == null || save.file().lastModified() > best.file().lastModified()) best = save;
        }
        return best == null ? null : best.nameWithoutExtension();
    }

    private void writeHome(String key){
        homeFile.parent().mkdirs();
        Home h = new Home();
        h.sector = key;
        SectorRunner.writeAtomic(homeFile, JsonIO.write(h));
    }

    private void switchHome(String key){
        if(key.equals(homeSector)) return;

        String previous = homeSector;
        Log.info("[mp-sectors] switching home sector @ -> @", homeSector, key);
        if(home != null && home.loaded) home.save();

        SectorRunner next = new SectorRunner(config.campaign, key);
        if(!next.load()){
            Log.err("[mp-sectors] could not switch home to @", key);

            //the failed load replaced the live world with the target; without reloading the
            //previous home the next autosave would write the target into the old home's save
            if(previous != null){
                writeHome(previous);
                if(home != null) home.loaded = false;

                SectorRunner back = new SectorRunner(config.campaign, previous);
                if(back.load()){
                    home = back;
                    homeSector = previous;
                }else{
                    Log.err("[mp-sectors] could not restore the previous home @", previous);
                    home = null;
                }
            }
            return;
        }

        home = next;
        homeSector = key;
        writeHome(key);
    }

    private static String modVersion(String name){
        try{
            var mod = Vars.mods.getMod(name);
            return mod == null || mod.meta == null || mod.meta.version == null ? "" : mod.meta.version;
        }catch(Throwable t){
            return "";
        }
    }

    /** Once a day: ask GitHub for the latest release tag and log it. Never downloads anything. */
    private synchronized void checkUpdates(){
        if(updateCheckRunning) return;
        updateCheckRunning = true;
        lastUpdateCheck = System.currentTimeMillis();

        Thread thread = new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try{
                connection = (java.net.HttpURLConnection)new java.net.URL(
                    "https://api.github.com/repos/gghanera/mp-sectors/releases/latest").openConnection();
                connection.setRequestProperty("User-Agent", "mp-sectors");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);

                if(connection.getResponseCode() != 200) return;

                String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(body);
                if(!matcher.find()) return;

                String latest = matcher.group(1);
                //the release tag matches the client mod version (2.1.4), not the server mod version
                String current = modVersion("mp-campaign");
                if(current.isEmpty()){
                    Log.info("[mp-sectors] latest release: v@ (current version unknown)", latest);
                }else if(!current.equals(latest)){
                    Log.info("[mp-sectors] update available: v@ (current @)", latest, current);
                }else{
                    Log.info("[mp-sectors] up to date (@)", current);
                }
            }catch(Throwable t){
                Log.info("[mp-sectors] update check failed: @", t.getMessage());
            }finally{
                if(connection != null) connection.disconnect();
                updateCheckRunning = false;
            }
        }, "mp-sectors-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    static String esc(String value){
        if(value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            switch(c){
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    //JSON strings cannot contain raw control characters
                    if(c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}











