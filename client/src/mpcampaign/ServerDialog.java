package mpcampaign;

import arc.Core;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

/** In-game settings + start/stop for the campaign coordinator. */
public class ServerDialog extends BaseDialog{

    private TextField dir, jar, campaign, java, gamePort, httpPort, maxSectors, maxPlayers, idle, memory, instancePort, snapshots;

    public ServerDialog(){
        super("Сервер кампании");
        addCloseButton();

        cont.defaults().left().padBottom(6f);

        cont.add("Папка сервера").padRight(10f);
        dir = cont.field("", value -> {}).width(520f).get();
        cont.row();

        cont.add("Серверный jar").padRight(10f);
        jar = cont.field("", value -> {}).width(520f).get();
        cont.row();

        cont.add("Кампания").padRight(10f);
        campaign = cont.field("", value -> {}).width(520f).get();
        cont.row();

        cont.add("Снимки").padRight(10f);
        snapshots = cont.field("", value -> {}).width(520f).get();
        cont.row();

        cont.add("Java").padRight(10f);
        java = cont.field("", value -> {}).width(520f).get();
        cont.row();

        Table ports = new Table();
        ports.add("Игровой порт").padRight(6f);
        gamePort = ports.field("", value -> {}).width(90f).get();
        ports.add("   HTTP порт").padRight(6f);
        httpPort = ports.field("", value -> {}).width(90f).get();
        ports.add("   Порт инстансов").padRight(6f);
        instancePort = ports.field("", value -> {}).width(90f).get();
        cont.add(ports).row();

        Table limits = new Table();
        limits.add("Секторов").padRight(6f);
        maxSectors = limits.field("", value -> {}).width(70f).get();
        limits.add("   Игроков").padRight(6f);
        maxPlayers = limits.field("", value -> {}).width(70f).get();
        limits.add("   Idle stop, с").padRight(6f);
        idle = limits.field("", value -> {}).width(70f).get();
        cont.add(limits).row();

        Table misc = new Table();
        misc.add("Память, МБ").padRight(6f);
        memory = misc.field("", value -> {}).width(90f).get();
        cont.add(misc).row();

        Table flags = new Table();
        flags.check("Разрешить 2 окна с одного ПК", Core.settings.getBool("mpcampaign-allow-duplicates", true),
            value -> Core.settings.put("mpcampaign-allow-duplicates", value));
        flags.check("Steam-сеть", Core.settings.getBool("mpcampaign-steam", false),
            value -> Core.settings.put("mpcampaign-steam", value));
        cont.add(flags).row();

        cont.add().height(8f).row();

        cont.label(() -> {
            if(!ServerProcess.running()) return "[lightgray]Сервер: остановлен";
            return "[green]Сервер запущен[]   [lightgray]игроки: [white]" + ServerProcess.statusPlayers + "/" + ServerProcess.statusMaxPlayers
                + "   [lightgray]секторы: [white]" + ServerProcess.statusSectors + "/" + ServerProcess.statusMaxSectors
                + (ServerProcess.statusOnline ? "" : "   [scarlet]HTTP недоступен");
        }).left().row();

        cont.label(() -> "[lightgray]API: [white]" + ServerProcess.apiUrl()
            + "   [lightgray]карта: [white]" + (ServerProcess.statusOnline ? "доступна" : "нет связи")).left().row();

        cont.button("Снимки кампании", Icon.save, MpCampaignMod::openSnapshots).height(46f).growX().padTop(6f).row();

        buttons.defaults().size(190f, 54f).pad(4f);
        buttons.button("Запустить", Icon.play, () -> {
            saveFields();
            String error = ServerProcess.start();
            if(error != null){
                Vars.ui.showInfoFade("[scarlet]" + error);
            }else{
                Vars.ui.showInfoFade("[accent]Сервер запускается...");
            }
        }).disabled(b -> ServerProcess.running());

        buttons.button("Остановить", Icon.cancel, ServerProcess::stop).disabled(b -> !ServerProcess.running());

        buttons.button("Войти на сервер", Icon.play, () -> {
            saveFields();
            if(!ServerProcess.running()){
                Vars.ui.showInfoFade("[scarlet]Сначала запусти сервер.");
                return;
            }
            hide();
            Vars.ui.join.connect("127.0.0.1", ServerProcess.setting("mpcampaign-game-port", 6567));
        }).disabled(b -> !ServerProcess.running());

        buttons.button("Карта", Icon.map, () -> {
            hide();
            if(Vars.ui.planet instanceof LobbyDialog lobby){
                lobby.showCampaign();
            }else{
                Vars.ui.planet.show();
            }
        });
    }

    @Override
    public Dialog show(){
        dir.setText(ServerProcess.serverDir().absolutePath());
        jar.setText(ServerProcess.serverJar().absolutePath());
        campaign.setText(ServerProcess.campaign().absolutePath());
        snapshots.setText(ServerProcess.snapshotDir());
        java.setText(ServerProcess.javaPath());

        gamePort.setText(String.valueOf(ServerProcess.setting("mpcampaign-game-port", 6567)));
        httpPort.setText(String.valueOf(ServerProcess.setting("mpcampaign-http-port", 8090)));
        instancePort.setText(String.valueOf(ServerProcess.setting("mpcampaign-instance-port", 6570)));
        maxSectors.setText(String.valueOf(ServerProcess.setting("mpcampaign-max-sectors", 5)));
        maxPlayers.setText(String.valueOf(ServerProcess.setting("mpcampaign-max-players", 10)));
        idle.setText(String.valueOf(ServerProcess.setting("mpcampaign-idle-stop", 60)));
        memory.setText(String.valueOf(ServerProcess.setting("mpcampaign-memory", 1024)));

        return super.show();
    }

    private void saveFields(){
        Core.settings.put("mpcampaign-server-dir", dir.getText().trim());
        Core.settings.put("mpcampaign-server-jar", jar.getText().trim());
        Core.settings.put("mpcampaign-campaign", campaign.getText().trim());
        Core.settings.put("mpcampaign-snapshot-dir", snapshots.getText().trim());
        Core.settings.put("mpcampaign-java", java.getText().trim());

        Core.settings.put("mpcampaign-game-port", Strings.parseInt(gamePort.getText(), 6567));
        Core.settings.put("mpcampaign-http-port", Strings.parseInt(httpPort.getText(), 8090));
        Core.settings.put("mpcampaign-instance-port", Strings.parseInt(instancePort.getText(), 6570));
        Core.settings.put("mpcampaign-max-sectors", Strings.parseInt(maxSectors.getText(), 5));
        Core.settings.put("mpcampaign-max-players", Strings.parseInt(maxPlayers.getText(), 10));
        Core.settings.put("mpcampaign-idle-stop", Strings.parseInt(idle.getText(), 60));
        Core.settings.put("mpcampaign-memory", Strings.parseInt(memory.getText(), 1024));
    }
}

