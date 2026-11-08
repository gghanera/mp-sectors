package mpcampaign;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.ClickListener;
import arc.scene.event.EventListener;
import arc.scene.event.Touchable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ClientServerConnectEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Saves.SaveSlot;
import mindustry.game.Schematic;
import mindustry.game.SectorInfo;
import mindustry.gen.Call;
import mindustry.gen.Icon;
import mindustry.io.JsonIO;
import mindustry.io.SaveMeta;
import mindustry.mod.Mod;
import mindustry.type.ItemSeq;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.ui.dialogs.PlanetDialog;
import mindustry.world.blocks.LaunchAnimator;
import mindustry.world.blocks.campaign.Accelerator;
import mindustry.world.blocks.storage.CoreBlock;

/**
 * MP Campaign (coordinator client).
 *
 * Talks to the campaign coordinator over HTTP: fetches the sector list, paints the local Sector
 * objects with it, and turns "land" into a server switch (JoinDialog.connect).
 *
 * The server side is not needed for this mod; it is installed on the sector servers only so the
 * mod list matches (the mod is visible).
 */
public class MpCampaignMod extends Mod{

    public static final String urlKey = "mpcampaign-url";
    public static final String defaultUrl = "http://127.0.0.1:8090";

    public static class Entry{
        public String planet = "", name = "", title = "", note = "", info;
        public int id, players;
        public boolean hasBase, captured, attacked, allowed, running;
        public SectorInfo parsed;
    }

    public static final ObjectMap<String, Entry> entries = new ObjectMap<>();
    public static final arc.struct.ObjectSet<String> moderators = new arc.struct.ObjectSet<>();
    public static final ObjectMap<Sector, SectorInfo> originalInfo = new ObjectMap<>();
    private static final ObjectMap<UnlockableContent, Boolean> originalUnlocked = new ObjectMap<>();
    private static java.lang.reflect.Field unlockedField;
    public static final ObjectMap<Sector, SaveSlot> originalSave = new ObjectMap<>();

    public static String home = "";
    public static String steamHost = "";
    public static int players, sectors, maxSectors = 5, maxPlayers = 10;
    public static boolean loading, online;
    public static boolean campaignMapOpen;
    public static String lastError = "";

    private static boolean installed;
    private static Table menuButton;
    private static ServerDialog serverDialog;
    private static SnapshotDialog snapshotDialog;
    private static AdminDialog adminDialog;

    //Time Control bridge: the mod only scales the client, so the speed is mirrored onto the server
    private static final String speedPacket = "mpcampaign-speed";
    private static final String acceleratorPacket = "mpcampaign-accelerator";
    private static final String acceleratorAnimPacket = "mpcampaign-accelerator-anim";
    private static final float minSpeed = 0.25f, maxSpeed = 16f;
    private static float serverSpeed = 1f, pendingSpeed = 1f;
    private static long pendingSince;
    private static boolean serverSpeedPending;

    //cross-sector chat: the coordinator keeps a buffer, every client polls what it has not seen
    private static long lastChatId, lastChatPoll;
    private static boolean chatPending;

    //the moderator list and player counts change while playing; /status is cheap
    private static long lastStatusPoll;
    private static boolean statusPending;

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> {
            installDialog();
            installMenuButton();
            if(Vars.netClient != null){
                Vars.netClient.addPacketHandler(speedPacket, MpCampaignMod::onSpeedPacket);
                Vars.netClient.addPacketHandler(acceleratorAnimPacket, MpCampaignMod::onAcceleratorAnim);
            }
            if(Vars.ui != null && Vars.ui.paused != null) Vars.ui.paused.shown(MpCampaignMod::fixAbandonButton);
        });
        Events.on(ClientServerConnectEvent.class, e -> {
            installDialog();
            serverSpeed = 1f;
            pendingSpeed = 1f;
            serverSpeedPending = false;
        });
        Events.on(TapEvent.class, MpCampaignMod::onAcceleratorTap);
        Events.run(Trigger.update, MpCampaignMod::update);
    }

    public static String url(){
        String value = Core.settings.getString(urlKey, "");

        //by default use the same host as the game server (works through Radmin/VPN and LAN)
        if(value == null || value.isEmpty() || value.equals(defaultUrl)){
            String host = serverHost();
            if(!host.equals("127.0.0.1")){
                value = "http://" + host + ":8090";
            }else if(value == null || value.isEmpty()){
                value = defaultUrl;
            }
        }

        if(value == null || value.isEmpty()) value = defaultUrl;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** The address of the game server we joined; the coordinator usually runs on the same host. */
    private static String serverHost(){
        try{
            if(Vars.ui != null && Vars.ui.join != null){
                java.lang.reflect.Field field = Vars.ui.join.getClass().getDeclaredField("lastIp");
                field.setAccessible(true);
                Object ip = field.get(Vars.ui.join);
                if(ip instanceof String s && !s.isEmpty() && !s.startsWith("steam:")) return s;
            }
        }catch(Throwable ignored){
        }
        return "127.0.0.1";
    }

    public static void url(String value){
        Core.settings.put(urlKey, value == null ? defaultUrl : value.trim());
    }

    public static void installDialog(){
        if(Vars.headless || Vars.ui == null) return;
        if(Vars.ui.planet instanceof LobbyDialog) return;

        Vars.ui.planet = new LobbyDialog();
        installed = true;
        Log.info("[mp-campaign] coordinator client ready, api: @", url());
    }

    private static void update(){
        //left the server: put the local campaign data back
        if(installed && originalInfo.size > 0 && !campaignMapOpen && Vars.net != null && !Vars.net.client()){
            restoreOriginals();
        }

        if(menuButton != null){
            menuButton.visible = Vars.state.isMenu();
        }

        if(!Vars.headless && Vars.ui != null && !Core.scene.hasField() && Core.input.keyTap(KeyCode.f9)){
            openServerDialog();
        }

        if(!Vars.headless && Vars.ui != null && !Core.scene.hasField() && Core.input.keyTap(KeyCode.f10) && isModerator()){
            openAdmin();
        }

        if(!Vars.headless && Vars.state.isGame() && Vars.player != null
            && isModerator()
            && !Core.scene.hasField() && Core.input.keyTap(KeyCode.space)){
            Call.sendChatMessage("/pause");
        }

        if(!Vars.headless && Vars.player != null
            && isModerator()
            && !Core.scene.hasField() && Core.input.ctrl() && Core.input.keyTap(KeyCode.s)
            && ((Vars.net != null && Vars.net.client()) || ServerProcess.running())){
            saveSnapshot();
        }

        updateTimeControl();
        pollChat();
        pollStatus();

        if(ServerProcess.running()){
            ServerProcess.refreshStatus();
        }
    }

    /** Mirrors the speed chosen in the Time Control mod onto the campaign server. */
    private static void updateTimeControl(){
        if(Vars.headless || Vars.net == null || !Vars.net.client() || !Vars.state.isCampaign()) return;

        if(serverSpeedPending && Vars.player != null){
            serverSpeedPending = false;
            //the owner keeps the speed picked in Time Control; everyone else follows the server
            if(!isModerator() || Math.abs(currentSpeed() - 1f) <= 0.02f){
                setClientSpeed(serverSpeed);
            }
        }

        float ratio = currentSpeed();
        if(!Float.isFinite(ratio) || ratio <= 0f) return;

        if(!isModerator()){
            if(Math.abs(ratio - serverSpeed) > 0.02f) setClientSpeed(serverSpeed);
            return;
        }

        if(Math.abs(ratio - serverSpeed) <= 0.02f) return;

        long now = System.currentTimeMillis();
        if(Math.abs(ratio - pendingSpeed) > 0.001f){
            pendingSpeed = ratio;
            pendingSince = now;
        }
        if(now - pendingSince < 150L) return;

        float requested = Mathf.clamp(pendingSpeed, minSpeed, maxSpeed);
        Call.serverPacketReliable(speedPacket, Float.toString(requested));
        serverSpeed = requested;
        if(requested != pendingSpeed) setClientSpeed(requested);
    }

    public static boolean isModerator(){
        if(Vars.player == null) return false;

        String name = Vars.player.plainName();
        if(moderators.isEmpty()) return name.equalsIgnoreCase("GGhanera");

        for(String moderator : moderators){
            if(moderator.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Effective multiplier Time Control put into the client-side delta. */
    private static float currentSpeed(){
        float frame = Core.graphics.getDeltaTime() * 60f;
        if(frame <= 0.0001f) return -1f;
        return Time.delta / Math.min(frame, 3f);
    }

    private static void setClientSpeed(float speed){
        if(!Float.isFinite(speed) || speed <= 0f) return;
        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f * speed, 3f * speed));
    }

    private static void onSpeedPacket(String data){
        try{
            float speed = Float.parseFloat(data.trim());
            if(!Float.isFinite(speed)) return;

            serverSpeed = Mathf.clamp(speed, minSpeed, maxSpeed);
            serverSpeedPending = true;
        }catch(NumberFormatException ignored){
        }
    }

    /** The vanilla pause menu disables "abandon" for clients; the owner may use it in the campaign. */
    private static void fixAbandonButton(){
        if(Vars.headless || Vars.net == null || !Vars.net.client() || !Vars.state.isCampaign()) return;
        if(!isModerator()) return;

        TextButton button = findButton(Vars.ui.paused.cont, Core.bundle.get("abandon"));
        if(button == null) return;

        //drop the vanilla action listener, keep the visual one (pressed/over state)
        ClickListener visual = button.getClickListener();
        for(int i = button.getListeners().size - 1; i >= 0; i--){
            EventListener listener = button.getListeners().get(i);
            if(listener instanceof ClickListener && listener != visual) button.removeListener(listener);
        }

        button.setDisabled(() -> Vars.state.gameOver);
        button.clicked(MpCampaignMod::confirmAbandon);
    }

    private static TextButton findButton(Group root, String text){
        for(Element child : root.getChildren()){
            if(child instanceof TextButton button && text.contentEquals(button.getText())) return button;

            if(child instanceof Group group){
                TextButton found = findButton(group, text);
                if(found != null) return found;
            }
        }
        return null;
    }

    private static void confirmAbandon(){
        Vars.ui.showConfirm(Core.bundle.get("sector.abandon.confirm"), () -> {
            Vars.ui.paused.hide();
            Call.sendChatMessage("/abandon");

            //let the command reach the server before switching away from the sector
            Timer.schedule(() -> Core.app.post(MpCampaignMod::leaveAfterAbandon), 1.5f);
        });
    }

    private static void leaveAfterAbandon(){
        Sector current = Vars.state.getSector();
        String key = current == null ? "" : current.planet.name + "-" + current.id;

        if(key.equals(home)){
            if(Vars.net != null && Vars.net.client()) Vars.netClient.disconnectQuietly();
            Vars.ui.showInfoFade("[accent]Домашний сектор покинут. Он отстроится при следующем запуске координатора.");
        }else{
            returnToLobby();
        }
    }

    /** Button in the main menu: start the campaign server without leaving the game. */
    private static void installMenuButton(){
        if(menuButton != null || Vars.headless || Vars.ui == null) return;

        menuButton = new Table();
        menuButton.setFillParent(true);
        menuButton.bottom().left();
        menuButton.margin(16f);
        menuButton.touchable = Touchable.childrenOnly;
        menuButton.button("Сервер кампании", Icon.host, MpCampaignMod::openServerDialog).size(260f, 54f);
        Core.scene.add(menuButton);
    }

    public static void openServerDialog(){
        if(serverDialog == null) serverDialog = new ServerDialog();
        serverDialog.show();
    }

    public static void openSnapshots(){
        if(snapshotDialog == null) snapshotDialog = new SnapshotDialog();
        snapshotDialog.show();
    }

    public static void openAdmin(){
        if(adminDialog == null) adminDialog = new AdminDialog();
        adminDialog.show();
    }

    /** Creates a campaign snapshot now (button in the snapshot dialog and Ctrl+S). */
    public static void saveSnapshot(){
        Jval body = Jval.newObject();
        body.add("player", localName());

        Http.post(url() + "/snapshot", body, json -> {
            if(json != null && bool(json, "ok")){
                Vars.ui.showInfoFade("[accent]Снимок кампании создан.");
                if(snapshotDialog != null && snapshotDialog.isShown()) snapshotDialog.reload();
            }else{
                String error = json == null ? "Координатор недоступен." : str(json, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error == null || error.isEmpty() ? "Не удалось создать снимок." : error));
            }
        });
    }

    @Override
    public void registerServerCommands(arc.util.CommandHandler handler){
        handler.register("mpcampaign-server", "[start|stop|status]", "In-game campaign server launcher (diagnostics).", args -> {
            if(args.length == 0 || args[0].equals("status")){
                Log.info("[mp-campaign] running=@ api=@ dir=@ jar=@", ServerProcess.running(), ServerProcess.apiUrl(),
                    ServerProcess.serverDir().absolutePath(), ServerProcess.serverJar().absolutePath());
            }else if(args[0].equals("start")){
                Log.info("[mp-campaign] start: @", ServerProcess.start());
            }else if(args[0].equals("stop")){
                ServerProcess.stop();
                Log.info("[mp-campaign] stop requested");
            }
        });
    }

    public static Entry entry(Sector sector){
        return sector == null ? null : entries.get(sector.planet.name + "-" + sector.id);
    }

    /** Fetches /sectors and /status, then paints the local sectors. */
    public static void refresh(Runnable done){
        loading = true;
        lastError = "";

        Http.get(url() + "/sectors", sectorsJson -> {
            if(sectorsJson == null){
                loading = false;
                online = false;
                lastError = "Координатор недоступен.";
                if(done != null) done.run();
                return;
            }

            online = true;
            parseSectors(sectorsJson);

            Http.get(url() + "/status", statusJson -> {
                if(statusJson != null) parseStatus(statusJson);
                applyData();
                loading = false;
                if(done != null) done.run();
            });
        });
    }

    private static void parseSectors(Jval root){
        home = str(root, "home");
        steamHost = str(root, "steamHost");
        maxSectors = num(root, "maxSectors", 5);
        maxPlayers = num(root, "maxPlayers", 10);
        players = num(root, "players", 0);

        entries.clear();
        parseModerators(root);

        Jval array = root.get("sectors");
        if(array == null || !array.isArray()) return;

        for(Jval value : array.asArray()){
            Entry entry = new Entry();
            entry.planet = str(value, "planet");
            entry.id = num(value, "id", -1);
            entry.name = str(value, "name");
            entry.title = str(value, "title");
            entry.note = str(value, "note");
            entry.hasBase = bool(value, "hasBase");
            entry.captured = bool(value, "captured");
            entry.attacked = bool(value, "attacked");
            entry.allowed = bool(value, "allowed");
            entry.running = bool(value, "running");
            entry.players = num(value, "players", 0);

            Jval info = value.get("info");
            entry.info = info == null || info.isNull() ? null : info.asString();

            if(entry.id >= 0) entries.put(entry.planet + "-" + entry.id, entry);
        }
    }

    private static void parseStatus(Jval root){
        home = str(root, "home");
        players = num(root, "players", players);
        sectors = num(root, "sectors", 1);
        maxSectors = num(root, "maxSectors", maxSectors);
        maxPlayers = num(root, "maxPlayers", maxPlayers);
        parseModerators(root);
    }

    /** Keeps the moderator list (and player counts) fresh while playing. */
    private static void parseModerators(Jval root){
        Jval mods = root == null ? null : root.get("moderators");
        if(mods == null || !mods.isArray()) return;

        moderators.clear();
        for(Jval value : mods.asArray()){
            String name = value.asString();
            if(name != null && !name.isEmpty()) moderators.add(name);
        }
    }

    private static void pollStatus(){
        if(Vars.headless || Vars.net == null || !Vars.net.client() || !Vars.state.isGame() || !Vars.state.isCampaign()) return;

        long now = System.currentTimeMillis();
        if(statusPending || now - lastStatusPoll < 30000L) return;
        lastStatusPoll = now;
        statusPending = true;

        Http.get(url() + "/status", 8000, json -> {
            statusPending = false;
            if(json != null) parseStatus(json);
        });
    }

    private static void applyData(){
        for(Entry entry : entries.values()){
            Planet planet = Vars.content.getByName(ContentType.planet, entry.planet);
            if(planet == null || entry.id < 0 || entry.id >= planet.sectors.size) continue;

            Sector sector = planet.sectors.get(entry.id);
            snapshot(sector);

            SectorInfo info = null;
            if(entry.info != null){
                try{
                    info = JsonIO.read(SectorInfo.class, entry.info);
                }catch(Throwable ignored){
                }
            }
            if(info == null) info = new SectorInfo();
            entry.parsed = info;

            //stops PlanetDialog.show() from writing synced data into the client's own settings
            info.shown = true;

            sector.info = info;
            sector.save = entry.hasBase ? fakeSlot(planet, entry.id) : null;

            //allowed story sectors must not be drawn with a lock on the map
            if(entry.allowed) markPresetUnlocked(sector);
        }
    }

    private static void snapshot(Sector sector){
        if(!originalInfo.containsKey(sector)){
            originalInfo.put(sector, sector.info);
            originalSave.put(sector, sector.save);
        }
    }

    /** Marks a story sector as unlocked locally (no settings write), so the map has no lock icon. */
    private static void markPresetUnlocked(Sector sector){
        if(sector.preset == null || sector.preset.unlocked()) return;

        try{
            if(unlockedField == null){
                unlockedField = UnlockableContent.class.getDeclaredField("unlocked");
                unlockedField.setAccessible(true);
            }
            if(!originalUnlocked.containsKey(sector.preset)){
                originalUnlocked.put(sector.preset, unlockedField.getBoolean(sector.preset));
            }
            unlockedField.setBoolean(sector.preset, true);
        }catch(Throwable ignored){
        }
    }

    private static void restoreOriginals(){
        originalInfo.each((sector, info) -> sector.info = info);
        originalSave.each((sector, slot) -> sector.save = slot);

        //put the story-sector unlock flags back: they were forced on for the campaign map
        if(unlockedField != null){
            originalUnlocked.each((content, value) -> {
                try{
                    unlockedField.setBoolean(content, value);
                }catch(Throwable ignored){
                }
            });
        }

        originalInfo.clear();
        originalSave.clear();
        originalUnlocked.clear();
    }

    private static SaveSlot fakeSlot(Planet planet, int id){
        SaveSlot slot = Vars.control.saves.new SaveSlot(Core.files.local("mpcampaign-" + planet.name + "-" + id));
        slot.meta = new SaveMeta(0, 0, 0, 0, "", 0, null, new StringMap());
        return slot;
    }

    public static void land(Sector sector){
        if(sector == null) return;

        Entry entry = entry(sector);
        if(entry == null){
            Vars.ui.showInfoFade("[scarlet]Сектор ещё не загружен с координатора.");
            return;
        }
        if(!entry.allowed){
            Vars.ui.showInfoFade("[scarlet]Этот сектор ещё не открыт.");
            return;
        }
        if(Vars.state.getSector() == sector){
            Vars.ui.showInfoFade("[lightgray]Вы уже в этом секторе.");
            return;
        }

        Sector from = Vars.state.getSector();

        //browsing the map from the menu: use the home sector as the launch source so the
        //loadout dialog and the cost still apply
        if(from == null && !entry.hasBase){
            from = sectorByKey(home);
        }

        //new sector: show the vanilla core loadout dialog, then send the chosen loadout to the server
        Sector source = from;
        if(!entry.hasBase && source != null && source.hasBase() && Vars.ui.planet != null){
            PlanetDialog planet = Vars.ui.planet;
            CoreBlock core = source.info != null && source.info.bestCoreType instanceof CoreBlock b ? b : (CoreBlock)source.planet.defaultCore;
            planet.loadouts.show(core, source, sector, () -> sendLand(sector, source, true));
            return;
        }

        sendLand(sector, source, false);
    }

    /** Resolves a sector key like "serpulo-76"; used when the map is open from the main menu. */
    public static Sector sectorByKey(String key){
        if(key == null || key.isEmpty()) return null;

        int split = key.lastIndexOf('-');
        if(split <= 0) return null;

        Planet planet = Vars.content.getByName(ContentType.planet, key.substring(0, split));
        if(planet == null) return null;

        try{
            int id = Integer.parseInt(key.substring(split + 1));
            return id >= 0 && id < planet.sectors.size ? planet.sectors.get(id) : null;
        }catch(NumberFormatException e){
            return null;
        }
    }

    private static void sendLand(Sector sector, Sector from, boolean withLoadout){
        Jval body = Jval.newObject();
        body.add("planet", sector.planet.name);
        body.add("id", Jval.valueOf(sector.id));
        body.add("player", localName());

        if(withLoadout && from != null){
            try{
                Schematic schem = Vars.universe.getLastLoadout();
                ItemSeq resources = Vars.universe.getLaunchResources();

                ItemSeq cost = schem.requirements();
                resources.each(cost::add);

                body.add("fromPlanet", from.planet.name);
                body.add("fromId", Jval.valueOf(from.id));
                body.add("loadout", Vars.schematics.writeBase64(schem));
                body.add("resources", itemsString(resources));
                body.add("cost", itemsString(cost));
            }catch(Throwable t){
                Log.err("[mp-campaign] could not serialize launch loadout", t);
            }
        }

        //normal landing: play the vanilla core launch animation, like the accelerator does
        CoreBlock.CoreBuild core = Vars.player == null ? null : Vars.player.bestCore();
        boolean animate = core != null && from != null && from != sector && !Core.settings.getBool("skipcoreanimation");

        if(animate){
            Call.serverPacketReliable(acceleratorPacket, core.tileX() + "," + core.tileY());
            try{
                Vars.renderer.showLaunch(core);
            }catch(Throwable t){
                Log.err("[mp-campaign] could not play the launch animation", t);
            }
        }

        Vars.ui.loadfrag.show("@connecting");

        Http.post(url() + "/land", body, response -> {
            Vars.ui.loadfrag.hide();

            if(response != null && bool(response, "ok")){
                //keep the launch visible until it ends, then connect
                if(animate && Vars.renderer != null && Vars.renderer.isLaunching() && Vars.renderer.getLandTime() > 0f){
                    Time.runTask(Vars.renderer.getLandTime(), () -> connectFrom(response));
                }else{
                    connectFrom(response);
                }
            }else{
                String error = response == null ? "Координатор недоступен." : str(response, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error.isEmpty() ? "Не удалось высадиться." : error));
                refresh(null);
            }
        });
    }

    private static String itemsString(ItemSeq seq){
        StringBuilder out = new StringBuilder();
        seq.each((item, amount) -> {
            if(amount > 0){
                if(out.length() > 0) out.append(',');
                out.append(item.name).append(':').append(amount);
            }
        });
        return out.toString();
    }

    /** Connects directly, or through the Steam lobby when the server is a different Steam account. */
    private static void connectFrom(Jval response){
        String host = str(response, "host");
        int port = num(response, "port", 0);
        String steamHost = str(response, "steamHost");
        long serverSteam = numLong(response, "steamId", 0);
        long mySteam = steamId();

        //the local game is on the same PC as the server: direct TCP is more reliable than P2P
        boolean local = host.equals("127.0.0.1") || host.equals("localhost")
            || url().contains("127.0.0.1") || url().contains("localhost");

        if(!local && !steamHost.isEmpty() && serverSteam != 0 && mySteam != 0 && mySteam != serverSteam){
            Vars.ui.join.connect(steamHost, 0);
            return;
        }

        if(!host.isEmpty() && port > 0){
            Vars.ui.join.connect(host, port);
        }else{
            Vars.ui.showInfoFade("[scarlet]Координатор вернул пустой адрес.");
        }
    }

    private static long steamId(){
        if(!Vars.steam) return 0;

        try{
            Class<?> svars = Class.forName("mindustry.desktop.steam.SVars");
            Object user = svars.getField("user").get(null);
            if(user == null) return 0;

            Object sUser = user.getClass().getField("user").get(user);
            if(sUser == null) return 0;

            Object id = sUser.getClass().getMethod("getSteamID").invoke(sUser);
            return ((Number)id.getClass().getMethod("getAccountID").invoke(id)).longValue();
        }catch(Throwable t){
            return 0;
        }
    }

    /** Opens the Steam overlay invite dialog for the coordinator's lobby. */
    public static void inviteFriends(){
        if(steamHost == null || !steamHost.startsWith("steam:")){
            Vars.ui.showInfoFade("[scarlet]Steam-лобби ещё не готово, нажми «Обновить».");
            return;
        }

        try{
            long handle = Long.parseLong(steamHost.substring("steam:".length()));
            Class<?> svars = Class.forName("mindustry.desktop.steam.SVars");
            Object net = svars.getField("net").get(null);
            Object friends = net.getClass().getField("friends").get(net);
            Class<?> steamID = Class.forName("steamworks.SteamID");
            Object lobby = steamID.getMethod("createFromNativeHandle", long.class).invoke(null, handle);
            friends.getClass().getMethod("activateGameOverlayInviteDialog", steamID).invoke(friends, lobby);
        }catch(Throwable t){
            Log.err("[mp-campaign] could not open Steam invite dialog", t);
            Vars.ui.showInfoFade("[scarlet]Steam-приглашение недоступно.");
        }
    }

    /** Guest-side bridge for the vanilla Interplanetary Accelerator (the block itself is host-only). */
    private static void onAcceleratorTap(TapEvent e){
        if(Vars.headless || !installed || Vars.net == null || !Vars.net.client()) return;
        if(e.tile == null || !(e.tile.build instanceof Accelerator.AcceleratorBuild build)) return;
        if(Vars.player == null || build.team != Vars.player.team() || !Vars.state.isCampaign()) return;
        if(Vars.renderer != null && Vars.renderer.isLaunching()) return;

        Sector from = Vars.state.getSector();
        if(from == null) return;

        //the accelerator map must use the coordinator's sector data, not the local campaign
        refresh(() -> {
            if(Vars.ui.planet != null){
                Vars.ui.planet.showPlanetLaunch(from, null, target -> startAccelerator(build, from, target));
            }
        });
    }

    /** Plays the vanilla launch animation for everyone in the sector, then sends the landing request. */
    private static void startAccelerator(Accelerator.AcceleratorBuild build, Sector from, Sector target){
        if(Vars.renderer == null || Vars.renderer.isLaunching()) return;

        Log.info("[mp-campaign] accelerator launch @-@ -> @-@", from.planet.name, from.id, target.planet.name, target.id);

        //send the request first: the animation is cosmetic and must never block the landing
        Call.serverPacketReliable(acceleratorPacket, build.tileX() + "," + build.tileY());
        sendAccelerator(build, from, target);

        try{
            Vars.renderer.showLaunch(build);
        }catch(Throwable t){
            Log.err("[mp-campaign] could not play the accelerator launch animation", t);
        }
    }

    private static void onAcceleratorAnim(String data){
        if(Vars.headless || Vars.renderer == null || Vars.renderer.isLaunching()) return;

        String[] parts = data.split(",");
        if(parts.length != 2) return;

        try{
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            if(Vars.world.build(x, y) instanceof LaunchAnimator animator){
                Vars.renderer.showLaunch(animator);
            }
        }catch(NumberFormatException ignored){
        }
    }

    private static void sendAccelerator(Accelerator.AcceleratorBuild build, Sector from, Sector target){
        Jval body = Jval.newObject();
        body.add("planet", target.planet.name);
        body.add("id", Jval.valueOf(target.id));
        body.add("player", localName());
        body.add("accelerator", Jval.valueOf(true));
        body.add("fromPlanet", from.planet.name);
        body.add("fromId", Jval.valueOf(from.id));
        body.add("tileX", Jval.valueOf(build.tileX()));
        body.add("tileY", Jval.valueOf(build.tileY()));

        try{
            Vars.universe.updateLoadout((CoreBlock)Blocks.coreNucleus);
            Schematic schem = Vars.universe.getLastLoadout();

            //vanilla: the core requirements are the launch cost, the rest travels to the new core
            ItemSeq resources = new ItemSeq();
            resources.add(build.items);
            schem.requirements().each((item, amount) -> resources.remove(item, Math.min(resources.get(item), amount)));

            body.add("resources", itemsString(resources));
            body.add("loadout", Vars.schematics.writeBase64(schem));
        }catch(Throwable t){
            Log.err("[mp-campaign] could not serialize accelerator launch", t);
        }

        Vars.ui.loadfrag.show("@connecting");

        Http.post(url() + "/land", body, response -> {
            Vars.ui.loadfrag.hide();

            if(response != null && bool(response, "ok")){
                //the target sector is already booting; keep the launch animation visible until it ends
                if(Vars.renderer != null && Vars.renderer.isLaunching() && Vars.renderer.getLandTime() > 0f){
                    Time.runTask(Vars.renderer.getLandTime(), () -> connectFrom(response));
                }else{
                    connectFrom(response);
                }
            }else{
                String error = response == null ? "Координатор недоступен." : str(response, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error.isEmpty() ? "Не удалось запустить ускоритель." : error));
            }
        });
    }

    public static void returnToLobby(){
        if(home == null || home.isEmpty()){
            Vars.ui.showInfoFade("[scarlet]Домашний сектор неизвестен.");
            return;
        }

        Sector target = sectorByKey(home);
        if(target == null) return;
        land(target);
    }

    public static void setHome(Sector sector){
        if(sector == null) return;

        Jval body = Jval.newObject();
        body.add("planet", sector.planet.name);
        body.add("id", Jval.valueOf(sector.id));

        Http.post(url() + "/home", body, response -> {
            String message = response == null ? "" : str(response, "message");
            String error = response == null ? "" : str(response, "error");
            if(message.isEmpty()){
                Vars.ui.showInfoFade("[scarlet]" + (error == null || error.isEmpty() ? "Не удалось сменить домашний сектор." : error));
            }else{
                Vars.ui.showInfoFade("[accent]" + message);
                refresh(null);
            }
        });
    }

    /** Points every sector's launch pads at the selected sector. */
    public static void setLaunchPads(Sector sector){
        if(sector == null){
            Vars.ui.showInfoFade("[scarlet]Сначала выберите сектор на карте.");
            return;
        }

        Entry entry = entry(sector);
        if(entry == null || !entry.allowed){
            Vars.ui.showInfoFade("[scarlet]Этот сектор ещё не открыт.");
            return;
        }

        Jval body = Jval.newObject();
        body.add("planet", sector.planet.name);
        body.add("id", Jval.valueOf(sector.id));

        Http.post(url() + "/pads", body, response -> {
            if(response != null && bool(response, "ok")){
                Vars.ui.showInfoFade("[accent]Пусковые площадки направлены на " + entry.name);
                refresh(null);
            }else{
                String error = response == null ? "Координатор недоступен." : str(response, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error.isEmpty() ? "Не удалось направить площадки." : error));
            }
        });
    }

    /** The player name in game, or the configured name in the main menu. */
    public static String localName(){
        if(Vars.player != null) return Vars.player.plainName();
        String name = Core.settings.getString("name", "");
        return name == null ? "" : name;
    }

    /** Saves the player's own name and note for a sector. */
    public static void saveNote(Sector sector, String title, String note){
        if(sector == null) return;

        Jval body = Jval.newObject();
        body.add("planet", sector.planet.name);
        body.add("id", Jval.valueOf(sector.id));
        body.add("player", localName());
        body.add("title", title == null ? "" : title);
        body.add("note", note == null ? "" : note);

        Http.post(url() + "/note", body, response -> {
            if(response != null && bool(response, "ok")){
                Vars.ui.showInfoFade("[accent]Название и заметка сохранены.");
                refresh(null);
            }else{
                String error = response == null ? "Координатор недоступен." : str(response, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error.isEmpty() ? "Не удалось сохранить заметку." : error));
            }
        });
    }

    public static String displayName(Entry entry){
        if(entry == null) return "";
        return entry.title == null || entry.title.isEmpty() ? entry.name : entry.title;
    }

    /** Polls the coordinator for chat from the other sectors and adds it to the vanilla chat. */
    private static void pollChat(){
        if(Vars.headless || Vars.net == null || !Vars.net.client() || !Vars.state.isGame() || !Vars.state.isCampaign()) return;

        long now = System.currentTimeMillis();
        if(chatPending || now - lastChatPoll < 2000L) return;
        lastChatPoll = now;
        chatPending = true;

        Http.get(url() + "/chat?since=" + lastChatId, 8000, json -> {
            chatPending = false;
            if(json == null){
                //coordinator offline: retry slowly instead of logging an error twice a second
                lastChatPoll = System.currentTimeMillis() + 8000L;
                return;
            }

            long next = numLong(json, "next", lastChatId);
            //the coordinator restarted and its ids start over
            if(next < lastChatId) lastChatId = next;

            Jval array = json.get("messages");
            if(array != null && array.isArray()){
                String own = currentSectorKey();

                for(Jval value : array.asArray()){
                    long id = numLong(value, "id", 0);
                    if(id > lastChatId) lastChatId = id;

                    String sector = str(value, "sector");
                    if(sector.equals(own)) continue; //the vanilla chat already showed this line

                    String player = str(value, "player");
                    String message = str(value, "message");
                    if(message.isEmpty()) continue;

                    Vars.ui.chatfrag.addMessage("[orange][" + sectorLabel(sector) + "][white] " + player + ": [lightgray]" + message);
                }
            }

            if(next > lastChatId) lastChatId = next;
        });
    }

    private static String currentSectorKey(){
        Sector sector = Vars.state.getSector();
        return sector == null ? "" : sector.planet.name + "-" + sector.id;
    }

    private static String sectorLabel(String key){
        Entry entry = entries.get(key);
        if(entry == null) return key;

        String name = displayName(entry);
        return name == null || name.isEmpty() ? key : name;
    }

    // ---------------- json helpers ----------------

    static String str(Jval value, String key){
        Jval out = value == null ? null : value.get(key);
        return out == null || out.isNull() ? "" : out.asString();
    }

    static int num(Jval value, String key, int def){
        Jval out = value == null ? null : value.get(key);
        return out == null || out.isNull() ? def : out.asInt();
    }

    static long numLong(Jval value, String key, long def){
        Jval out = value == null ? null : value.get(key);
        return out == null || out.isNull() ? def : (long)out.asDouble();
    }

    static boolean bool(Jval value, String key){
        Jval out = value == null ? null : value.get(key);
        return out != null && !out.isNull() && out.asBool();
    }
}

