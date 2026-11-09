package mpsectors;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnlockEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.JsonIO;
import mindustry.mod.Mod;
import mindustry.net.Packets.KickReason;
import mindustry.server.ServerControl;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.world.blocks.LaunchAnimator;

/**
 * MP Sectors - one campaign sector per server process.
 *
 * Two server roles, chosen by which config exists in the server data directory:
 *  - config/mp-coordinator.json -> coordinator (home sector + HTTP API + instance manager)
 *  - config/mp-sectors.json     -> one sector instance
 *
 * Without either config the mod does nothing and the server runs normally.
 */
public class MpSectorsMod extends Mod{

    private Coordinator coordinator;
    private SectorRunner runner;
    private Coordinator.InstanceConfig instanceConfig;

    private Fi stopFile;
    private boolean instanceMode;
    private int chatPort;
    private String chatSector = "";
    private String lastChatPlayer = "", lastChatMessage = "";
    private long lastChatTime;
    private long lastAutosave, lastStopCheck, lastStatus;
    private long lastUnlockCheck, lastUnlocksModified;
    private long lastImportRefresh, lastPadsPoll, lastPadsCheck, lastLaunchPoll;

    //Time Control support: the client sends the speed chosen in the mod, the server simulates at it
    private static final String speedPacket = "mpcampaign-speed";
    private static final String acceleratorPacket = "mpcampaign-accelerator";
    private static final String acceleratorAnimPacket = "mpcampaign-accelerator-anim";
    private static final float minSpeed = 0.25f, maxSpeed = 16f;
    private static float gameSpeed = 1f;

    @Override
    public void init(){
        Events.on(ServerLoadEvent.class, e -> start());
        Events.on(UnlockEvent.class, e -> writeUnlocksIfChanged());
        Events.on(PlayerConnect.class, e -> {
            Log.info("[mp-sectors] player connected: @", e.player.plainName());
            if(e.player.con != null) Call.clientPacketReliable(e.player.con, speedPacket, Float.toString(gameSpeed));
        });
        Events.on(PlayerLeave.class, e -> {
            if(instanceMode && runner != null && runner.loaded && Groups.player.size() == 0) runner.save();
        });
        Events.on(PlayerChatEvent.class, e -> {
            if(e.player == null || e.message == null || e.message.isEmpty()) return;

            //commands are not chat; the event fires before command handling
            if(e.message.startsWith("/")) return;

            //a graphical host can fire this twice for one message
            long now = System.currentTimeMillis();
            String name = e.player.plainName();
            if(name.equals(lastChatPlayer) && e.message.equals(lastChatMessage) && now - lastChatTime < 1000L) return;
            lastChatPlayer = name;
            lastChatMessage = e.message;
            lastChatTime = now;

            //the event fires before the vanilla word filter; relay what players will actually see
            String message = e.message;
            if(Vars.netServer != null && Vars.netServer.admins != null){
                message = Vars.netServer.admins.filterMessage(e.player, message);
                if(message == null || message.isEmpty()) return;
            }

            Sector sector = Vars.state.getSector();
            String key = sector != null ? sector.planet.name + "-" + sector.id : chatSector;
            Relay.chat(chatPort, key, name, message);
        });
        Events.on(WorldLoadEvent.class, e -> {
            if(coordinator != null){
                //the vanilla game-over handler rotates a dedicated server to a built-in map;
                //the coordinator must never serve that map instead of the campaign home
                if(Vars.state.getSector() == null){
                    Log.err("[mp-sectors] coordinator loaded a non-campaign map; closing the game server");
                    for(Player player : Groups.player.copy()){
                        player.kick(KickReason.serverRestarting);
                    }
                    Vars.state.set(GameState.State.menu);
                    try{
                        Vars.net.closeServer();
                    }catch(Throwable t){
                        Log.err("[mp-sectors] could not close the game server", t);
                    }
                }
                return;
            }

            if(!instanceMode || runner == null || !runner.loaded) return;
            Sector now = Vars.state.getSector();
            if(now != null && now != runner.sector()){
                Log.err("[mp-sectors] instance switched to @ instead of @, saving disabled. Restart the instance.", now, runner.sectorName);
                runner.loaded = false;
            }
        });
        Events.run(Trigger.update, this::update);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mp-sectors-save"));

        Log.info("[mp-sectors] loaded.");
    }

    @Override
    public void registerServerCommands(arc.util.CommandHandler handler){
        handler.register("mpsave", "Save the current campaign sector to the campaign folder.", args -> {
            if(coordinator != null){
                coordinator.saveHome();
                return;
            }
            if(runner == null || !runner.loaded){
                Log.err("[mp-sectors] no sector is loaded.");
                return;
            }
            runner.save();
        });

        handler.register("anyresearch", "<name>", "Research a tech tree node under the same rules players get.", args -> {
            ResearchServer.researchByName(args[0], null);
        });

        handler.register("mpsnapshot", "Create a campaign snapshot now.", args -> {
            if(coordinator == null){
                Log.err("[mp-sectors] snapshots are made by the coordinator.");
                return;
            }
            coordinator.snapshotNow();
        });

        handler.register("pause", "Toggle campaign pause.", args -> togglePause(null));

        handler.register("speed", "<x>", "Set campaign game speed, e.g. 4 or 0.5.", args -> {
            try{
                setGameSpeed(Float.parseFloat(args[0]));
            }catch(NumberFormatException e){
                Log.err("[mp-sectors] speed must be a number, e.g. 4 or 0.5");
            }
        });

        handler.register("abandon", "Abandon the current campaign sector.", args -> abandonSector());

        handler.register("mpmod", "[add|remove|list] [name]", "Manage campaign moderators.", args -> {
            if(args.length == 0 || args[0].equalsIgnoreCase("list")){
                Log.info("[mp-sectors] moderators: @", String.join(", ", Moderators.list()));
                return;
            }

            if(args.length < 2){
                Log.err("[mp-sectors] usage: mpmod add|remove <player>");
                return;
            }

            if(args[0].equalsIgnoreCase("add")){
                Log.info("[mp-sectors] @ is now a moderator.", args[1]);
                if(!Moderators.add(args[1])) Log.info("[mp-sectors] @ was already a moderator.", args[1]);
            }else if(args[0].equalsIgnoreCase("remove")){
                if(!Moderators.isModerator(args[1])){
                    Log.err("[mp-sectors] @ is not a moderator.", args[1]);
                }else if(Moderators.list().length <= 1){
                    Log.err("[mp-sectors] cannot remove the last moderator.");
                }else if(Moderators.remove(args[1])){
                    Log.info("[mp-sectors] @ is no longer a moderator.", args[1]);
                }else{
                    Log.err("[mp-sectors] @ is not a moderator.", args[1]);
                }
            }else{
                Log.err("[mp-sectors] usage: mpmod add|remove|list [player]");
            }
        });
    }

    @Override
    public void registerClientCommands(arc.util.CommandHandler handler){
        //server half of Any Research: guests cannot research on a dedicated server otherwise
        ResearchServer.register(handler);
        handler.<Player>register("pause", "Поставить или снять паузу (модераторы).", (args, player) -> togglePause(player));
        handler.<Player>register("speed", "<x>", "Скорость игры: 4, 0.5 и т.д. (модераторы).", (args, player) -> {
            if(player != null && !Moderators.isModerator(player.plainName())){
                player.sendMessage("[scarlet]Только модераторы могут менять скорость игры.");
                return;
            }
            try{
                setGameSpeed(Float.parseFloat(args[0]));
            }catch(NumberFormatException e){
                if(player != null) player.sendMessage("[scarlet]Скорость должна быть числом, например 4 или 0.5.");
            }
        });

        handler.<Player>register("abandon", "Покинуть сектор (модераторы).", (args, player) -> {
            if(player != null && !Moderators.isModerator(player.plainName())){
                player.sendMessage("[scarlet]Только модераторы могут покинуть сектор.");
                return;
            }
            abandonSector();
        });
    }

    /** Kills every core of the player team: the sector is lost and the game-over handler saves it. */
    private void abandonSector(){
        if(!Vars.state.isCampaign() || Vars.state.rules.sector == null){
            Log.err("[mp-sectors] abandon: no campaign sector is loaded");
            return;
        }

        var cores = Vars.state.rules.defaultTeam.cores().copy();
        if(cores.isEmpty()){
            Log.info("[mp-sectors] abandon: sector @ has no cores", Vars.state.rules.sector.name());
            return;
        }

        Log.info("[mp-sectors] sector @ abandoned", Vars.state.rules.sector.name());
        if(Vars.state.isPaused()) Vars.state.set(GameState.State.playing);

        for(var core : cores) core.kill();
    }

    /** Plays the vanilla launch animation for every client in this sector. */
    private void handleAcceleratorPacket(Player player, String data){
        String[] parts = data.split(",");
        if(parts.length != 2) return;

        try{
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());

            //cores launch from the map, accelerators from their block: both are LaunchAnimators
            if(!(Vars.world.build(x, y) instanceof LaunchAnimator)) return;
            Call.clientPacketReliable(acceleratorAnimPacket, x + "," + y);
        }catch(NumberFormatException ignored){
        }
    }

    /** Time Control bridge: the client sends the speed it picked, the server simulates at it. */
    private void handleSpeedPacket(Player player, String data){
        if(player == null) return;

        if(!Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут менять скорость игры.");
            Call.clientPacketReliable(player.con, speedPacket, Float.toString(gameSpeed));
            return;
        }

        try{
            setGameSpeed(Float.parseFloat(data.trim()));
        }catch(NumberFormatException ignored){
        }
    }

    private static void setGameSpeed(float speed){
        //NaN/Infinity would poison Time.delta and freeze the world
        if(!Float.isFinite(speed)) return;

        gameSpeed = Mathf.clamp(speed, minSpeed, maxSpeed);

        //same formula Time Control uses, so the client and the server stay in step
        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f * gameSpeed, 3f * gameSpeed));

        Log.info("[mp-sectors] game speed x@", gameSpeed);
        Call.sendMessage("[accent]Скорость игры: " + speedText(gameSpeed));
        Call.clientPacketReliable(speedPacket, Float.toString(gameSpeed));
    }

    /** Applies a pause/speed command from the coordinator (home) or from campaign/requests (instance). */
    static void adminCommand(String action, String value){
        switch(action){
            case "pause" -> adminPause(Boolean.parseBoolean(value));
            case "speed" -> {
                try{
                    setGameSpeed(Float.parseFloat(value));
                }catch(NumberFormatException ignored){
                }
            }
            default -> {
            }
        }
    }

    static void adminPause(boolean paused){
        if(!Vars.state.isGame()) return;

        Vars.state.set(paused ? GameState.State.paused : GameState.State.playing);
        Call.sendMessage("[accent]Кампания " + (paused ? "на паузе" : "продолжена") + ".");
        Log.info("[mp-sectors] campaign @", paused ? "paused" : "resumed");
    }

    static void adminKick(String name){
        if(name == null || name.isEmpty()) return;

        for(Player player : Groups.player.copy()){
            if(player.plainName().equalsIgnoreCase(name)){
                player.kick(KickReason.kick);
                Log.info("[mp-sectors] @ kicked from the admin panel", name);
                return;
            }
        }
    }

    private static String speedText(float speed){
        return speed == Math.round(speed) ? "x" + (int)speed : "x" + speed;
    }

    private void togglePause(@arc.util.Nullable Player player){
        if(player != null && !Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут ставить паузу.");
            return;
        }

        adminPause(!Vars.state.isPaused());
    }

    private void start(){
        if(Vars.netServer != null){
            Vars.netServer.addPacketHandler(speedPacket, this::handleSpeedPacket);
            Vars.netServer.addPacketHandler(acceleratorPacket, this::handleAcceleratorPacket);
        }

        Fi coordFile = Vars.dataDirectory.child("mp-coordinator.json");
        if(coordFile.exists()){
            try{
                Coordinator.Config config = JsonIO.read(Coordinator.Config.class, coordFile.readString());
                if(config != null){
                    installGameOverListener();
                    coordinator = new Coordinator(config);
                    chatPort = config.httpPort;
                    coordinator.start();
                    Sector home = Vars.state.getSector();
                    chatSector = home == null ? "" : home.planet.name + "-" + home.id;
                    return;
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read mp-coordinator.json", t);
            }
        }

        startInstance();
    }

    /** Never rotate to a built-in map: a lost sector is saved, marked unowned and its server stops. */
    private void installGameOverListener(){
        if(ServerControl.instance == null) return;

        ServerControl.instance.gameOverListener = e -> {
            boolean won = e != null && Vars.state.rules != null && e.winner == Vars.state.rules.defaultTeam;
            Log.info("[mp-sectors] game over: won=@ campaign=@ cores=@ wave=@/@ enemies=@ attack=@",
                won, Vars.state.isCampaign(), Vars.state.teams.playerCores().size, Vars.state.wave,
                Vars.state.rules.winWave, Vars.state.enemies, Vars.state.rules.attackMode);

            if(coordinator != null){
                if(won){
                    Log.info("[mp-sectors] home sector captured, saving it");
                    coordinator.homeCaptured();
                }else{
                    Log.info("[mp-sectors] home sector lost, saving and marking it unowned");
                    coordinator.homeLost();
                }
                return;
            }

            if(runner == null || !runner.loaded) return;

            if(won){
                Log.info("[mp-sectors] sector @ captured, saving and stopping", runner.sectorName);
                runner.save();
                runner.markCaptured();
                runner.loaded = false;
                Relay.history(chatPort, "capture", runner.sectorName, "", "Сектор захвачен");
            }else{
                Log.info("[mp-sectors] sector @ lost, saving and stopping", runner.sectorName);
                runner.save();
                runner.markLost();
                runner.loaded = false;
                Relay.history(chatPort, "lost", runner.sectorName, "", "Сектор потерян");
            }
            if(instanceMode) Core.app.exit();
        };
    }

    private void startInstance(){
        Fi file = Vars.dataDirectory.child("mp-sectors.json");
        if(!file.exists()){
            Log.info("[mp-sectors] no config found, server runs as a normal server.");
            return;
        }
        if(!Vars.headless){
            Log.err("[mp-sectors] this mod is meant for dedicated servers only.");
            return;
        }

        try{
            instanceConfig = JsonIO.read(Coordinator.InstanceConfig.class, file.readString());
            if(instanceConfig == null || instanceConfig.campaign == null || instanceConfig.campaign.isEmpty() || instanceConfig.sector == null){
                Log.err("[mp-sectors] invalid mp-sectors.json");
                return;
            }

            ResearchServer.campaignPath = instanceConfig.campaign;
            Moderators.init(Core.files.absolute(instanceConfig.campaign));
            chatPort = instanceConfig.httpPort;
            chatSector = instanceConfig.sector;
            runner = new SectorRunner(instanceConfig.campaign, instanceConfig.sector);
            runner.loadout = instanceConfig.loadout;
            runner.resources = instanceConfig.resources;
            stopFile = Core.files.local("stop.flag");
            installGameOverListener();

            if(!runner.load()){ Core.app.exit(); return; }

            if(instanceConfig.port > 0) Core.settings.put("port", instanceConfig.port);
            Core.settings.put("playerlimit", instanceConfig.playerLimit);
            if(instanceConfig.steam) SteamSupport.init();

            int limit = instanceConfig.playerLimit <= 0 ? 5 : instanceConfig.playerLimit;
            Vars.netServer.admins.setPlayerLimit(limit);
            Core.settings.put("strict", !instanceConfig.allowDuplicateClients);

            //a dedicated server does not open its port by itself in 160.6; the host command does this
            Vars.netServer.openServer();

            instanceMode = true;
            lastAutosave = System.currentTimeMillis();
            lastStatus = 0;
            writeStatus();

            Log.info("[mp-sectors] sector @ loaded on port @, player limit @, wave @.",
                instanceConfig.sector, Core.settings.getInt("port", Vars.port), limit, Vars.state.wave);
        }catch(Throwable t){
            Log.err("[mp-sectors] failed to load the sector", t);
        }
    }

    private void update(){
        SteamSupport.update();

        //real time, not game time: Time Control must not multiply polling/autosave rates
        long now = System.currentTimeMillis();

        if(now - lastUnlockCheck >= 2000L){
            lastUnlockCheck = now;
            pollUnlocks();
        }

        if(now - lastPadsPoll >= 2000L){
            lastPadsPoll = now;
            pollPads();
        }

        if(now - lastLaunchPoll >= 2000L){
            lastLaunchPoll = now;
            pollRequests();
        }

        if(now - lastImportRefresh >= 5000L){
            lastImportRefresh = now;
            if(runner != null && runner.loaded) runner.refreshImportRates();
        }

        if(coordinator != null){
            coordinator.update();
            return;
        }

        if(!instanceMode || runner == null || !runner.loaded) return;

        if(now - lastStopCheck >= 1000L){
            lastStopCheck = now;
            if(stopFile != null && stopFile.exists()){
                Log.info("[mp-sectors] stop flag found, saving and shutting down.");
                runner.save();
                //the shutdown hook would save a second time
                runner.loaded = false;
                Core.app.exit();
                return;
            }
        }

        if(now - lastStatus >= 2000L){
            lastStatus = now;
            writeStatus();
        }

        if(instanceConfig.autosaveSeconds > 0 && now - lastAutosave >= instanceConfig.autosaveSeconds * 1000L){
            lastAutosave = now;
            runner.save();
        }
    }

    // ---------------- live research sync ----------------

    private Fi unlocksFile(){
        if(coordinator != null) return coordinator.unlocksFile();
        return runner == null ? null : runner.unlocksFile;
    }

    /** Called on every UnlockEvent: merge this unlock into the campaign file right away. */
    private void writeUnlocksIfChanged(){
        Fi file = unlocksFile();
        if(file == null) return;

        SectorRunner.writeUnlocks(file);
        lastUnlocksModified = file.file().lastModified();
    }

    /** Pulls unlocks made by other instances (the file is shared by the whole campaign). */
    private void pollUnlocks(){
        Fi file = unlocksFile();
        if(file == null || !file.exists()) return;

        long modified = file.file().lastModified();
        if(modified == lastUnlocksModified) return;
        lastUnlocksModified = modified;

        String[] names;
        try{
            names = JsonIO.read(String[].class, file.readString());
        }catch(Throwable t){
            return;
        }
        if(names == null) return;

        for(String name : names){
            MappableContent content = Vars.content.byName(name);
            if(content instanceof UnlockableContent u && !u.alwaysUnlocked && !u.unlockedHost()){
                u.unlock();
                if(Vars.state.rules != null) Vars.state.rules.researched.add(u);
                Log.info("[mp-sectors] live unlock: @", name);
            }
        }
    }

    /** Instances follow the destination chosen on the map (campaign/pads.json). */
    private void pollPads(){
        if(!instanceMode || runner == null || instanceConfig == null) return;

        Fi file = Core.files.absolute(instanceConfig.campaign).child("pads.json");
        if(!file.exists()) return;

        long modified = file.file().lastModified();
        if(modified == lastPadsCheck) return;
        lastPadsCheck = modified;

        try{
            Coordinator.LandRequest req = JsonIO.read(Coordinator.LandRequest.class, file.readString());
            Planet planet = Vars.content.getByName(ContentType.planet, req.planet);
            if(planet == null || req.id < 0 || req.id >= planet.sectors.size) return;

            Sector target = planet.sectors.get(req.id);
            Sector current = runner.sector();
            if(current != null && current.planet == target.planet){
                current.info.destination = target;
                Log.info("[mp-sectors] launch pads directed to @", target.planet.name + "-" + target.id);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read pads.json", t);
        }
    }

    /**
     * Applies launch-cost / accelerator requests from the coordinator (campaign/requests).
     * Every instance polls the same folder, so a request is deleted only by the sector it names.
     */
    private void pollRequests(){
        if(!instanceMode || runner == null || instanceConfig == null) return;

        Fi dir = Core.files.absolute(instanceConfig.campaign).child("requests");
        if(!dir.exists()) return;

        long now = System.currentTimeMillis();
        for(Fi file : dir.list()){
            if(!file.extension().equals("json")) continue;

            //nobody claimed this request in time (the target sector stopped, or the file is
            //for another sector): drop it instead of leaving it in the folder forever
            if(now - file.file().lastModified() > 120_000L){
                file.delete();
                continue;
            }

            try{
                String name = file.nameWithoutExtension();

                if(name.startsWith("launch-")){
                    Coordinator.LaunchDeduct req = JsonIO.read(Coordinator.LaunchDeduct.class, file.readString());
                    if(req == null || req.time <= 0 || now - req.time > 120_000L){
                        file.delete();
                    }else if(runner.sectorName.equals(req.sector)){
                        if(req.cost != null && !req.cost.isEmpty()) runner.deduct(req.cost);
                        file.delete();
                    }
                }else if(name.startsWith("accelerator-")){
                    Coordinator.AcceleratorRequest req = JsonIO.read(Coordinator.AcceleratorRequest.class, file.readString());
                    if(req == null || req.time <= 0 || now - req.time > 120_000L){
                        file.delete();
                    }else if(runner.sectorName.equals(req.sector)){
                        if(req.x >= 0 && req.y >= 0) runner.consumeAccelerator(req.x, req.y);
                        file.delete();
                    }
                }else if(name.startsWith("command-")){
                    Coordinator.CommandRequest req = JsonIO.read(Coordinator.CommandRequest.class, file.readString());
                    if(req == null || req.time <= 0 || now - req.time > 120_000L){
                        file.delete();
                    }else if(runner.sectorName.equals(req.sector)){
                        if("kick".equals(req.action)){
                            adminKick(req.value);
                        }else{
                            adminCommand(req.action, req.value);
                        }
                        file.delete();
                    }
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read @", file.name(), t);
                file.delete();
            }
        }
    }

    private void writeStatus(){
        if(runner == null) return;

        try{
            StringBuilder json = new StringBuilder("{\"sector\":\"").append(runner.sectorName).append("\",");
            json.append("\"count\":").append(Groups.player.size()).append(',');
            json.append("\"wave\":").append(Vars.state.wave).append(',');
            json.append("\"steamHost\":\"").append(SteamSupport.lobbyHandle() == 0 ? "" : "steam:" + SteamSupport.lobbyHandle()).append("\",");
            json.append("\"steamId\":").append(SteamSupport.steamId()).append(',');
            json.append("\"players\":[");

            boolean first = true;
            for(Player player : Groups.player){
                if(!first) json.append(',');
                first = false;
                json.append('"').append(player.plainName().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }

            json.append("]}");
            Core.files.local("status.json").writeString(json.toString());
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write status.json", t);
        }
    }

    private void shutdown(){
        try{
            if(coordinator != null){
                coordinator.shutdown();
            }else if(runner != null && runner.loaded){
                runner.save();
            }
        }catch(Throwable ignored){
        }
    }
}




