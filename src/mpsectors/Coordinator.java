package mpsectors;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.Nullable;
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
    private long lastAutosave;
    private long lastImportRefresh;
    private HttpServer http;
    private ExecutorService httpExecutor;
    private boolean shuttingDown;
    private Fi snapshotsDir;
    private volatile boolean restoring;
    private long lastSnapshot;
    private static final int maxSnapshots = 50;
    private static final long snapshotInterval = 10 * 60 * 1000L;

    public Coordinator(Config config){
        this.config = config;
        this.campaign = Core.files.absolute(config.campaign);
        //instances run in their own working directories; a relative path would resolve wrongly there
        this.config.campaign = this.campaign.absolutePath();
        this.homeFile = campaign.child("home.json");
        this.stopFile = Core.files.local("stop.flag");
        this.instances = new InstanceManager(config);
    }

    public void start(){
        //a stale stop flag from a killed process would shut the fresh coordinator down immediately
        stopFile.delete();

        ResearchServer.campaignPath = config.campaign;
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
            Vars.netServer.admins.setPlayerLimit(config.maxPlayers);
            Vars.netServer.openServer();

            lastAutosave = System.currentTimeMillis();
            lastImportRefresh = System.currentTimeMillis();
            snapshotsDir = (config.snapshotDir == null || config.snapshotDir.isEmpty())
                ? campaign.parent().child("backups")
                : Core.files.absolute(config.snapshotDir);
            snapshotsDir.mkdirs();
            lastSnapshot = System.currentTimeMillis();

            startHttp();
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

        homePlayers = Groups.player.size();

        instances.tick();

        long now = System.currentTimeMillis();
        if(!restoring && now - lastImportRefresh >= 5000L){
            lastImportRefresh = now;
            if(home != null && home.loaded) home.refreshImportRates();
        }

        if(!restoring && pendingHome != null && homePlayers == 0){
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
        }catch(Throwable t){
            Log.err("[mp-sectors] coordinator shutdown failed", t);
        }
    }

    // ---------------- campaign snapshots ----------------

    /** Copies the whole campaign into backups/<timestamp>, keeping the newest 30. */
    private void takeSnapshot(){
        if(restoring || snapshotsDir == null) return;

        synchronized(campaignLock){
            try{
                snapshotsDir.mkdirs();

                //build into a temp name: /snapshots must never list a half-copied campaign
                for(Fi old : snapshotsDir.list()){
                    if(old.isDirectory() && old.name().startsWith("tmp-")) old.deleteDirectory();
                }

                Fi dir = snapshotsDir.child(snapshotName());
                if(dir.exists()) dir.deleteDirectory();

                Fi tmp = snapshotsDir.child("tmp-" + dir.name());
                copyTree(campaign, tmp);
                tmp.moveTo(dir);

                pruneSnapshots();

                Log.info("[mp-sectors] campaign snapshot @ created", dir.name());
            }catch(Throwable t){
                Log.err("[mp-sectors] could not create a campaign snapshot", t);
            }finally{
                lastSnapshot = System.currentTimeMillis();
            }
        }
    }

    private String snapshotsJson(HttpExchange ex){
        StringBuilder json = new StringBuilder("{\"snapshots\":[");
        boolean first = true;

        for(Fi dir : snapshots()){
            if(!first) json.append(',');
            first = false;

            json.append("{\"name\":\"").append(esc(dir.name())).append("\",")
                .append("\"time\":").append(snapshotTime(dir)).append(',')
                .append("\"size\":").append(dirSize(dir)).append('}');
        }

        return json.append("]}").toString();
    }

    private String snapshotJson(HttpExchange ex) throws IOException{
        LandRequest req = JsonIO.read(LandRequest.class, body(ex));

        if(req == null || req.player == null || !req.player.equalsIgnoreCase("GGhanera")){
            return "{\"ok\":false,\"error\":\"Только GGhanera может создавать снимки.\"}";
        }
        if(restoring){
            return "{\"ok\":false,\"error\":\"Кампания восстанавливается, подождите.\"}";
        }

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

    private String restoreJson(HttpExchange ex) throws IOException{
        RestoreRequest req = JsonIO.read(RestoreRequest.class, body(ex));

        if(req == null || req.name == null || !req.name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")){
            return "{\"ok\":false,\"error\":\"Некорректное имя снимка.\"}";
        }
        if(req.player == null || !req.player.equalsIgnoreCase("GGhanera")){
            return "{\"ok\":false,\"error\":\"Только GGhanera может загружать снимки.\"}";
        }
        if(restoring){
            return "{\"ok\":false,\"error\":\"Кампания уже восстанавливается.\"}";
        }

        Fi dir = snapshotsDir.child(req.name);
        if(!dir.exists() || !dir.isDirectory()){
            return "{\"ok\":false,\"error\":\"Снимок не найден.\"}";
        }

        restoring = true;
        Log.info("[mp-sectors] restoring campaign from snapshot @", req.name);
        Call.sendMessage("[accent]Кампания восстанавливается из снимка " + req.name + "...");

        try{
            //instances save their sectors on stop; the snapshot is copied over them afterwards
            instances.stopAll();

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

    /** Runs on the game thread: kicks everyone and loads the restored home sector. */
    private void reloadHome(){
        try{
            //the old world is gone after logic.reset(); never autosave it over the snapshot
            if(home != null) home.loaded = false;
            pendingHome = null;

            for(Player player : Groups.player.copy()){
                player.kick(KickReason.serverRestarting);
            }

            Vars.logic.reset();

            //the process still has the old campaign's research unlocked; the next unlock event
            //would merge it back into the restored unlocks.json
            SectorRunner.relockTo(campaign.child("unlocks.json"));

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
            if(file.isDirectory() && file.name().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}")){
                out.add(file);
            }
        }

        out.sort(Comparator.comparing(Fi::name).reversed());
        return out;
    }

    private void pruneSnapshots(){
        ArrayList<Fi> list = snapshots();
        while(list.size() > maxSnapshots){
            Fi oldest = list.remove(list.size() - 1);
            oldest.deleteDirectory();
            Log.info("[mp-sectors] removed old snapshot @", oldest.name());
        }
    }

    private static String snapshotName(){
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    }

    /** The folder mtime changes when snapshots are moved between disks; the name is stable. */
    private static long snapshotTime(Fi dir){
        try{
            return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").parse(dir.name()).getTime();
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

    public Fi unlocksFile(){
        return home == null ? null : home.unlocksFile;
    }

    public void saveHome(){
        if(home != null && home.loaded) home.save();
    }

    /** Manual snapshot, used by the mpsnapshot console command. */
    public void snapshotNow(){
        takeSnapshot();
    }

    /** The home sector's core was destroyed: keep the save, mark it unowned, stop autosaving it. */
    public void homeLost(){
        if(home == null || !home.loaded) return;
        home.save();
        home.markLost();
        home.loaded = false;
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
        http.createContext("/restore", ex -> respond(ex, handle(ex, this::restoreJson)));
        httpExecutor = Executors.newFixedThreadPool(2);
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
        StringBuilder json = new StringBuilder("{\"home\":\"").append(esc(homeSector)).append("\",");
        json.append("\"maxSectors\":").append(config.maxSectors).append(',');
        json.append("\"maxPlayers\":").append(config.maxPlayers).append(',');
        json.append("\"players\":").append(out.players).append(',');
        json.append("\"steamHost\":\"").append(esc(steamHost())).append("\",");
        json.append("\"steamId\":").append(SteamSupport.steamId()).append(',');
        json.append("\"sectors\":[");

        boolean first = true;
        for(Planet planet : Vars.content.planets()){
            if(planet.sectors.size == 0 || !planet.accessible || !planet.visible || planet.removed) continue;

            for(Sector sector : planet.sectors){
                String key = planet.name + "-" + sector.id;
                SectorInfo info = snap.info.get(key);
                boolean hasBase = snap.hasBase.get(key, false);
                boolean running = key.equals(homeSector) || instances.get(key) != null;
                int players = key.equals(homeSector) ? homePlayers : (instances.get(key) == null ? 0 : instances.get(key).players);

                if(!first) json.append(',');
                first = false;

                json.append('{')
                    .append("\"planet\":\"").append(esc(planet.name)).append("\",")
                    .append("\"id\":").append(sector.id).append(',')
                    .append("\"name\":\"").append(esc(sectorName(sector, info))).append("\",")
                    .append("\"hasBase\":").append(hasBase).append(',')
                    .append("\"captured\":").append(info != null && info.hasCore && !info.waves && !info.attack).append(',')
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
            return landJson(out);
        }

        InstanceManager.Instance inst = instances.get(key);
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

        //consume the launch source even when the target sector was already running
        if(req.accelerator && req.fromId >= 0 && req.tileX >= 0 && req.tileY >= 0){
            consumeAccelerator(req.fromPlanet, req.fromId, req.tileX, req.tileY);
        }else if(!req.cost.isEmpty() && req.fromId >= 0){
            deductLaunch(req.fromPlanet, req.fromId, req.cost);
        }

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
        Fi dir = campaign.child("requests");
        dir.mkdirs();

        long now = System.currentTimeMillis();
        for(Fi old : dir.list()){
            if(now - old.file().lastModified() > 120_000L) old.delete();
        }

        SectorRunner.writeAtomic(dir.child(type + "-" + System.nanoTime() + ".json"), json);
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

        if(home != null && home.loaded && home.sector() != null){
            home.sector().info.destination = sector;
        }

        //instances poll this file and apply the same destination to their live sector
        SectorRunner.writeAtomic(campaign.child("pads.json"), "{\"planet\":\"" + esc(req.planet) + "\",\"id\":" + req.id + "}");
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
        json.append("\"instances\":[");
        boolean first = true;
        for(InstanceManager.Instance inst : instances.all()){
            if(!first) json.append(',');
            first = false;
            json.append("{\"sector\":\"").append(esc(inst.sector)).append("\",\"port\":").append(inst.port)
                .append(",\"players\":").append(inst.players).append('}');
        }
        json.append("]}");
        return json.toString();
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

    private static String esc(String value){
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
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}











