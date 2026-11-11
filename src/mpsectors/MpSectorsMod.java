package mpsectors;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Jval;
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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
    private final ObjectMap<String, long[]> chatRates = new ObjectMap<>();
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
            String name = e.player.plainName();
            if(Bans.isBanned(name)){
                Log.info("[mp-sectors] banned player tried to join: @", name);
                e.player.kick(KickReason.banned);
                return;
            }

            Log.info("[mp-sectors] player connected: @", name);
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

            //relay the raw event text: calling admins.filterMessage() here would run the
            //vanilla anti-spam filter once already, and the server runs it again right after
            //this event - every first message would look like a duplicate
            String message = e.message;

            if(!allowRelayChat(name)){
                e.player.sendMessage("[scarlet]Слишком много сообщений, подождите.");
                return;
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

        handler.register("pauseall", "Pause the home and every running sector; block new landings.", args -> pauseAll(null, true));

        handler.register("resumeall", "Resume the home and every running sector.", args -> pauseAll(null, false));

        handler.register("who", "Show who is in which sector.", args -> who(null));

        handler.register("speedall", "<x>", "Set the speed of the whole campaign.", args -> {
            try{
                speedAll(null, Float.parseFloat(args[0]));
            }catch(NumberFormatException e){
                Log.err("[mp-sectors] speedall must be a number, e.g. 4 or 0.5");
            }
        });

        handler.register("speed", "<x>", "Set campaign game speed, e.g. 4 or 0.5.", args -> {
            try{
                setGameSpeed(Float.parseFloat(args[0]), true);
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
        handler.<Player>register("pauseall", "Поставить всю кампанию на паузу и запретить высадку (модераторы).", (args, player) -> pauseAll(player, true));
        handler.<Player>register("resumeall", "Снять паузу со всей кампании (модераторы).", (args, player) -> pauseAll(player, false));
        handler.<Player>register("who", "Кто в каком секторе.", (args, player) -> who(player));
        handler.<Player>register("speedall", "<x>", "Скорость всей кампании: 4, 0.5 и т.д. (модераторы).", (args, player) -> {
            try{
                speedAll(player, Float.parseFloat(args[0]));
            }catch(NumberFormatException e){
                player.sendMessage("[scarlet]Скорость должна быть числом, например 4 или 0.5.");
            }
        });
        handler.<Player>register("snapshot", "Создать снимок кампании (модераторы).", (args, player) -> snapshotCampaign(player));
        handler.<Player>register("ban", "<name>", "Забанить игрока во всей кампании (модераторы).", (args, player) -> ban(player, args[0], true));
        handler.<Player>register("unban", "<name>", "Разбанить игрока во всей кампании (модераторы).", (args, player) -> ban(player, args[0], false));
        handler.<Player>register("bans", "Список банов кампании.", (args, player) -> listBans(player));
        handler.<Player>register("speed", "<x>", "Скорость игры: 4, 0.5 и т.д. (модераторы).", (args, player) -> {
            if(player != null && !Moderators.isModerator(player.plainName())){
                player.sendMessage("[scarlet]Только модераторы могут менять скорость игры.");
                return;
            }
            try{
                setGameSpeed(Float.parseFloat(args[0]), true);
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
        if(data == null) return;
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
        if(player == null || data == null) return;

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
        setGameSpeed(speed, false);
    }

    /**
     * @param force true for an explicit command/admin action: even a moderator running Time
     *              Control must accept the server speed instead of pushing their slider back.
     */
    private static void setGameSpeed(float speed, boolean force){
        //NaN/Infinity would poison Time.delta and freeze the world
        if(!Float.isFinite(speed)) return;

        float next = Mathf.clamp(speed, minSpeed, maxSpeed);
        boolean changed = Math.abs(next - gameSpeed) > 0.001f;
        gameSpeed = next;

        //same formula Time Control uses, so the client and the server stay in step
        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f * gameSpeed, 3f * gameSpeed));

        Log.info("[mp-sectors] game speed x@", gameSpeed);
        if(changed){
            Call.sendMessage("[accent]Скорость игры: " + speedText(gameSpeed));
        }
        if(changed || force){
            Call.clientPacketReliable(speedPacket, (force ? "force:" : "") + gameSpeed);
        }
    }

    /** Applies a pause/speed command from the coordinator (home) or from campaign/requests (instance). */
    static void adminCommand(String action, String value){
        switch(action){
            case "pause" -> adminPause(Boolean.parseBoolean(value));
            case "speed" -> {
                try{
                    setGameSpeed(Float.parseFloat(value), true);
                }catch(NumberFormatException ignored){
                }
            }
            default -> {
            }
        }
    }

    static void adminPause(boolean paused){
        adminPause(paused, true);
    }

    static void adminPause(boolean paused, boolean announce){
        if(!Vars.state.isGame()) return;

        boolean changed = Vars.state.isPaused() != paused;
        Vars.state.set(paused ? GameState.State.paused : GameState.State.playing);
        if(announce && changed){
            Call.sendMessage("[accent]Кампания " + (paused ? "на паузе" : "продолжена") + ".");
        }
        Log.info("[mp-sectors] campaign @", paused ? "paused" : "resumed");
    }

    /** Five relayed lines per ten seconds; the sender's own sector still sees them. */
    private boolean allowRelayChat(String name){
        long now = System.currentTimeMillis();
        synchronized(chatRates){
            long[] state = chatRates.get(name);
            if(state == null){
                state = new long[]{now, 0};
                chatRates.put(name, state);
            }
            if(now - state[0] > 10_000L){
                state[0] = now;
                state[1] = 0;
            }
            if(state[1] >= 5) return false;
            state[1]++;
        }
        return true;
    }

    static void adminKick(String name){
        adminKick(name, KickReason.kick);
    }

    static void adminKick(String name, KickReason reason){
        if(name == null || name.isEmpty()) return;

        for(Player player : Groups.player.copy()){
            if(player.plainName().equalsIgnoreCase(name)){
                player.kick(reason);
                Log.info("[mp-sectors] @ kicked (@)", name, reason);
                return;
            }
        }
    }

    static void adminBan(String name){
        adminKick(name, KickReason.banned);
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

    /** Pauses/resumes the whole campaign: the coordinator does it directly, an instance asks it over HTTP. */
    private void pauseAll(@arc.util.Nullable Player player, boolean paused){
        if(player != null && !Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут управлять паузой кампании.");
            return;
        }

        if(coordinator != null){
            coordinator.setPaused(paused, player == null ? "console" : player.plainName());
            return;
        }

        postAdmin(player, "pause", null, paused ? "true" : "false", 15000, ok -> {
            if(ok){
                //apply at once here too; the coordinator also queues a command
                adminPause(paused, true);
            }else{
                reply(player, "[scarlet]Не удалось изменить паузу кампании.");
            }
        });
    }

    private void speedAll(@arc.util.Nullable Player player, float speed){
        if(player != null && !Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут менять скорость.");
            return;
        }
        if(!Float.isFinite(speed) || speed < minSpeed || speed > maxSpeed){
            reply(player, "[scarlet]Скорость должна быть от 0.25 до 16.");
            return;
        }

        if(coordinator != null){
            coordinator.setSpeedAll(speed, player == null ? "console" : player.plainName());
            return;
        }

        postAdmin(player, "speed", null, Float.toString(speed), 15000, ok -> {
            if(ok){
                setGameSpeed(speed, true);
                reply(player, "[accent]Скорость кампании: " + speedText(speed) + ".");
            }else{
                reply(player, "[scarlet]Не удалось изменить скорость кампании.");
            }
        });
    }

    private void snapshotCampaign(@arc.util.Nullable Player player){
        if(player != null && !Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут создавать снимки.");
            return;
        }

        if(coordinator != null){
            if(coordinator.isRestoring()){
                reply(player, "[scarlet]Кампания восстанавливается, подождите.");
                return;
            }
            coordinator.snapshotNow();
            reply(player, "[accent]Снимок кампании создан.");
            return;
        }

        postAdmin(player, "snapshot", null, null, 60000, ok -> {
            reply(player, ok ? "[accent]Снимок кампании создан." : "[scarlet]Не удалось создать снимок.");
        });
    }

    private void ban(@arc.util.Nullable Player player, String name, boolean banned){
        if(player != null && !Moderators.isModerator(player.plainName())){
            player.sendMessage("[scarlet]Только модераторы могут управлять банами.");
            return;
        }
        if(name == null || name.isBlank()){
            reply(player, "[scarlet]Укажите ник.");
            return;
        }
        String target = name.trim();
        if(banned && Moderators.isModerator(target)){
            reply(player, "[scarlet]Нельзя забанить модератора. Сначала снимите его через mpmod remove.");
            return;
        }

        if(coordinator != null){
            if(banned) coordinator.ban(target, player == null ? "console" : player.plainName());
            else coordinator.unban(target, player == null ? "console" : player.plainName());
            reply(player, banned ? "[accent]Забанен: " + target : "[accent]Разбанен: " + target);
            return;
        }

        postAdmin(player, banned ? "ban" : "unban", target, null, 15000, ok -> {
            if(ok){
                if(banned){
                    Bans.add(target);
                    adminBan(target);
                }else{
                    Bans.remove(target);
                }
                reply(player, banned ? "[accent]Забанен: " + target : "[accent]Разбанен: " + target);
            }else{
                reply(player, "[scarlet]Не удалось изменить бан-лист.");
            }
        });
    }

    private void listBans(@arc.util.Nullable Player player){
        sendLines(player, coordinator != null ? coordinator.banLines() : localBanLines());
    }

    private static String[] localBanLines(){
        String[] bans = Bans.list();
        if(bans.length == 0) return new String[]{"[lightgray]Банов нет."};

        String[] lines = new String[bans.length + 1];
        lines[0] = "[accent]Баны (" + bans.length + "):";
        for(int i = 0; i < bans.length; i++) lines[i + 1] = "[white]" + bans[i];
        return lines;
    }

    private void who(@arc.util.Nullable Player player){
        if(coordinator != null){
            sendLines(player, coordinator.whoLines());
            return;
        }
        if(chatPort <= 0){
            reply(player, "[scarlet]Это не сервер кампании.");
            return;
        }

        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try{
                connection = (HttpURLConnection)new URL("http://127.0.0.1:" + chatPort + "/status").openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(8000);
                String response = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Jval json = Jval.read(response);
                Jval array = json == null ? null : json.get("who");
                Seq<String> lines = new Seq<>();
                if(array != null && array.isArray()){
                    for(Jval value : array.asArray()) lines.add(value.asString());
                }
                sendLines(player, lines.toArray(String.class));
            }catch(Throwable t){
                reply(player, "[scarlet]Координатор недоступен.");
            }finally{
                if(connection != null) connection.disconnect();
            }
        }, "mp-sectors-who");
        thread.setDaemon(true);
        thread.start();
    }

    /** POST /admin from an instance; the callback runs on the game thread. */
    private void postAdmin(@arc.util.Nullable Player player, String action, String name, String value,
                           int timeoutMs, Cons<Boolean> done){
        if(chatPort <= 0){
            reply(player, "[scarlet]Это не сервер кампании.");
            if(done != null) Core.app.post(() -> done.get(false));
            return;
        }

        String who = player == null ? firstModerator() : player.plainName();
        StringBuilder body = new StringBuilder("{\"player\":\"").append(Coordinator.esc(who))
            .append("\",\"action\":\"").append(Coordinator.esc(action)).append('"');
        if(name != null) body.append(",\"name\":\"").append(Coordinator.esc(name)).append('"');
        if(value != null) body.append(",\"value\":\"").append(Coordinator.esc(value)).append('"');
        body.append('}');
        String json = body.toString();

        Thread thread = new Thread(() -> {
            boolean ok = false;
            HttpURLConnection connection = null;
            try{
                connection = (HttpURLConnection)new URL("http://127.0.0.1:" + chatPort + "/admin").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(timeoutMs);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out = connection.getOutputStream()){
                    out.write(bytes);
                }

                String response = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                ok = response.contains("\"ok\":true");
                if(!ok) Log.err("[mp-sectors] admin @ rejected: @", action, response);
            }catch(Throwable t){
                Log.err("[mp-sectors] admin @ request failed", action, t);
            }finally{
                if(connection != null) connection.disconnect();
            }

            boolean result = ok;
            if(done != null) Core.app.post(() -> done.get(result));
        }, "mp-sectors-admin-" + action);
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendLines(@arc.util.Nullable Player player, String[] lines){
        if(lines == null) return;

        if(player == null){
            for(String line : lines) Log.info("[mp-sectors] @", line.replaceAll("\\[[^\\]]*\\]", ""));
            return;
        }

        Core.app.post(() -> {
            if(player.con == null) return;
            for(String line : lines) player.sendMessage(line);
        });
    }

    private static String firstModerator(){
        String[] moderators = Moderators.list();
        return moderators.length == 0 ? "console" : moderators[0];
    }

    private static void reply(@arc.util.Nullable Player player, String message){
        if(player != null){
            Core.app.post(() -> {
                if(player.con != null) player.sendMessage(message);
            });
        }
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
                    coordinator = new Coordinator(config);
                    chatPort = config.httpPort;
                    coordinator.start();
                    if(!coordinator.isStarted()){
                        Log.err("[mp-sectors] coordinator failed to start; continuing as a normal server");
                        try{
                            coordinator.shutdown();
                        }catch(Throwable ignored){
                        }
                        coordinator = null;
                        chatPort = 0;
                        return;
                    }
                    installGameOverListener();
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
            if(instanceMode){
                try{
                    Core.files.local("exit.reason").writeString("gameover");
                }catch(Throwable ignored){
                }
                Core.app.exit();
            }
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
            Bans.init(Core.files.absolute(instanceConfig.campaign));
            chatPort = instanceConfig.httpPort;
            chatSector = instanceConfig.sector;
            runner = new SectorRunner(instanceConfig.campaign, instanceConfig.sector);
            runner.loadout = instanceConfig.loadout;
            runner.resources = instanceConfig.resources;
            stopFile = Core.files.local("stop.flag");

            if(!runner.load()){ Core.app.exit(); return; }
            installGameOverListener();

            if(instanceConfig.port > 0) Core.settings.put("port", instanceConfig.port);
            Core.settings.put("playerlimit", instanceConfig.playerLimit);
            if(instanceConfig.steam) SteamSupport.init();

            int limit = instanceConfig.playerLimit <= 0 ? 5 : instanceConfig.playerLimit;
            Vars.netServer.admins.setPlayerLimit(limit);
            Core.settings.put("strict", !instanceConfig.allowDuplicateClients);

            //a dedicated server does not open its port by itself in 160.6; the host command does this
            Vars.netServer.openServer();

            instanceMode = true;

            //a sector that somehow started while the campaign is paused must not run
            if(Core.files.absolute(instanceConfig.campaign).child("paused.flag").exists()){
                adminPause(true);
            }

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
                try{
                    Core.files.local("exit.reason").writeString("stop");
                }catch(Throwable ignored){
                }
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
                        switch(req.action){
                            case "kick" -> adminKick(req.value);
                            case "limit" -> adminKick(req.value, KickReason.playerLimit);
                            case "ban" -> {
                                Bans.add(req.value);
                                adminBan(req.value);
                            }
                            case "cargo" -> runner.addCargo(req.value);
                            default -> adminCommand(req.action, req.value);
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

            Sector current = runner.sector();
            boolean attacked = current != null && current.info != null && current.info.hasCore
                && (current.info.waves || current.info.attack);
            json.append("\"attacked\":").append(attacked).append(',');

            json.append("\"steamHost\":\"").append(SteamSupport.lobbyHandle() == 0 ? "" : "steam:" + SteamSupport.lobbyHandle()).append("\",");
            json.append("\"steamId\":").append(SteamSupport.steamId()).append(',');
            json.append("\"players\":[");

            boolean first = true;
            for(Player player : Groups.player){
                if(!first) json.append(',');
                first = false;
                json.append('"').append(Coordinator.esc(player.plainName())).append('"');
            }

            json.append("]}");
            SectorRunner.writeAtomic(Core.files.local("status.json"), json.toString());
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




