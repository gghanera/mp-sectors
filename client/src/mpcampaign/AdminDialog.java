package mpcampaign;

import arc.scene.ui.layout.Table;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Moderator panel for the running campaign.
 *
 * The coordinator exposes one POST /admin endpoint; every action carries the player name, and the
 * server checks it against campaign/moderators.json. Pause/speed are relayed to every sector
 * instance through campaign/requests, so the panel controls the whole campaign, not just the
 * sector the moderator is standing in.
 */
public class AdminDialog extends BaseDialog{

    private Table body;

    public AdminDialog(){
        super("Админ-панель");
        addCloseButton();

        cont.table(t -> {
            t.button("Обновить", Icon.refresh, this::load).size(170f, 46f).left();
            t.add("[lightgray]пауза, скорость, секторы, игроки, логи").padLeft(12f).left();
        }).growX().left().row();

        cont.pane(t -> {
            body = t;
            t.top().left();
        }).grow().padTop(8f).row();

        shown(this::load);
    }

    private void load(){
        body.clear();
        body.add("[lightgray]Загрузка...").left().row();

        Http.get(MpCampaignMod.url() + "/sectors", sectors -> {
            if(sectors == null){
                fail();
                return;
            }

            Http.get(MpCampaignMod.url() + "/status", status -> {
                if(status == null){
                    fail();
                    return;
                }
                build(sectors, status);
            });
        });
    }

    private void fail(){
        body.clear();
        body.add("[scarlet]Координатор недоступен.").left().row();
    }

    private void build(Jval sectors, Jval status){
        String home = MpCampaignMod.str(status, "home");
        int players = MpCampaignMod.num(status, "players", 0);
        int running = MpCampaignMod.num(status, "sectors", 1);
        int maxSectors = MpCampaignMod.num(status, "maxSectors", 5);
        int maxPlayers = MpCampaignMod.num(status, "maxPlayers", 10);

        body.add("[accent]Кампания").left().row();
        body.add("[lightgray]игроки: [white]" + players + "/" + maxPlayers
            + "   [lightgray]секторы: [white]" + running + "/" + maxSectors
            + "   [lightgray]дом: [white]" + sectorName(sectors, home)).left().row();
        body.add().height(6f).row();

        body.table(t -> {
            t.defaults().height(44f).padRight(6f);
            t.button("Пауза", Icon.pause, () -> post("pause", null, null, "true")).width(130f);
            t.button("Продолжить", Icon.play, () -> post("pause", null, null, "false")).width(150f);
            t.button("x0.5", () -> post("speed", null, null, "0.5")).width(80f);
            t.button("x1", () -> post("speed", null, null, "1")).width(70f);
            t.button("x2", () -> post("speed", null, null, "2")).width(70f);
            t.button("x4", () -> post("speed", null, null, "4")).width(70f);
            t.button("Снимок", Icon.save, () -> post("snapshot", null, null, null)).width(140f);
        }).left().row();

        // ---- sectors ----
        body.add().height(8f).row();
        body.add("[accent]Секторы").left().row();

        Table sectorList = new Table();
        sectorList.top().left();
        body.add(sectorList).growX().left().row();

        addSectorRow(sectorList, home, "дом", status.get("homePlayers"), true, sectors);

        Jval instances = status.get("instances");
        if(instances != null && instances.isArray()){
            for(Jval value : instances.asArray()){
                String key = MpCampaignMod.str(value, "sector");
                addSectorRow(sectorList, key, "порт " + MpCampaignMod.num(value, "port", 0),
                    value.get("names"), false, sectors);
            }
        }

        // ---- players ----
        body.add().height(8f).row();
        body.add("[accent]Игроки").left().row();

        Table playerList = new Table();
        playerList.top().left();
        body.add(playerList).growX().left().row();

        int found = addPlayerRows(playerList, status.get("homePlayers"), home, sectors);
        if(instances != null && instances.isArray()){
            for(Jval value : instances.asArray()){
                found += addPlayerRows(playerList, value.get("names"), MpCampaignMod.str(value, "sector"), sectors);
            }
        }
        if(found == 0){
            playerList.add("[lightgray]Никого нет на серверах.").left().row();
        }

        // ---- moderators ----
        body.add().height(8f).row();
        body.add("[accent]Модераторы").left().row();

        Table modList = new Table();
        modList.top().left();
        body.add(modList).growX().left().row();

        Jval moderators = status.get("moderators");
        if(moderators != null && moderators.isArray()){
            for(Jval value : moderators.asArray()){
                String name = value.asString();
                if(name == null || name.isEmpty()) continue;

                Table row = new Table();
                row.left();
                row.add("[white]" + name + "[]").width(280f).left();
                row.button("Снять", Icon.cancel, () -> confirmRemoveModerator(name)).size(130f, 40f);
                modList.add(row).growX().left().padBottom(2f).row();
            }
        }

        String[] added = {""};
        modList.table(row -> {
            row.left();
            row.field("", value -> added[0] = value).width(280f).height(44f);
            row.button("Добавить", Icon.add, () -> {
                String name = added[0] == null ? "" : added[0].trim();
                if(!name.isEmpty()) post("modadd", null, name, null);
            }).size(150f, 44f).padLeft(6f);
        }).left().row();
    }

    private void addSectorRow(Table list, String key, String info, Jval names, boolean home, Jval sectors){
        int count = names != null && names.isArray() ? names.asArray().size : 0;

        Table row = new Table();
        row.left();
        row.add("[accent]" + sectorName(sectors, key) + "[]").width(220f).left();
        row.add("[lightgray]" + key + "  " + info + "[]").width(210f).left();
        row.add("[white]" + (count > 0 ? count + " игр." : "пусто")).width(100f).left();

        row.button("Лог", Icon.list, () -> showLog(key, sectorName(sectors, key))).size(110f, 42f);
        if(home){
            row.add().width(170f);
        }else{
            row.button("Остановить", Icon.cancel, () -> confirmStop(key, sectorName(sectors, key))).size(170f, 42f);
        }

        list.add(row).growX().left().padBottom(4f).row();
    }

    private int addPlayerRows(Table list, Jval names, String sector, Jval sectors){
        if(names == null || !names.isArray()) return 0;

        int added = 0;
        for(Jval value : names.asArray()){
            String name = value.asString();
            if(name == null || name.isEmpty()) continue;
            added++;

            Table row = new Table();
            row.left();
            row.add("[white]" + name + "[]").width(240f).left();
            row.add("[lightgray]" + sectorName(sectors, sector) + "[]").width(240f).left();
            row.button("Кик", Icon.cancel, () -> confirmKick(sector, name)).size(120f, 40f);
            list.add(row).growX().left().padBottom(2f).row();
        }
        return added;
    }

    private static String sectorName(Jval sectors, String key){
        if(key == null || key.isEmpty()) return "?";

        Jval array = sectors == null ? null : sectors.get("sectors");
        if(array != null && array.isArray()){
            for(Jval value : array.asArray()){
                String candidate = MpCampaignMod.str(value, "planet") + "-" + MpCampaignMod.num(value, "id", -1);
                if(candidate.equals(key)){
                    String title = MpCampaignMod.str(value, "title");
                    return title.isEmpty() ? MpCampaignMod.str(value, "name") : title;
                }
            }
        }
        return key;
    }

    private void post(String action, String sector, String name, String value){
        Jval body = Jval.newObject();
        body.add("player", MpCampaignMod.localName());
        body.add("action", action);
        if(sector != null) body.add("sector", sector);
        if(name != null) body.add("name", name);
        if(value != null) body.add("value", value);

        Http.post(MpCampaignMod.url() + "/admin", body, json -> {
            if(json != null && MpCampaignMod.bool(json, "ok")){
                Vars.ui.showInfoFade("[accent]Готово.");
                load();
                //a stopped sector and a kicked player disappear from the list a moment later
                if("stop".equals(action) || "kick".equals(action)){
                    arc.util.Timer.schedule(this::load, 2f);
                }
            }else{
                String error = json == null ? "Координатор недоступен." : MpCampaignMod.str(json, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error.isEmpty() ? "Не удалось выполнить действие." : error));
            }
        });
    }

    private void confirmStop(String key, String name){
        Vars.ui.showConfirm("Остановить сектор?",
            "Сектор " + name + " будет сохранён и остановлен, игроков отключит.", () -> post("stop", key, null, null));
    }

    private void confirmKick(String sector, String name){
        Vars.ui.showConfirm("Кикнуть игрока?", name + " будет отключён от сервера.",
            () -> post("kick", sector, name, null));
    }

    private void confirmRemoveModerator(String name){
        Vars.ui.showConfirm("Снять модератора?", name + " больше не сможет управлять кампанией.",
            () -> post("modremove", null, name, null));
    }

    private void showLog(String sector, String title){
        BaseDialog dialog = new BaseDialog("Лог: " + title);

        Table content = new Table();
        content.top().left();
        dialog.cont.pane(content).grow().pad(8f).row();

        Runnable load = () -> {
            content.clear();
            content.add("[lightgray]Загрузка...").left().row();

            Jval body = Jval.newObject();
            body.add("player", MpCampaignMod.localName());
            body.add("action", "log");
            body.add("sector", sector == null ? "" : sector);

            Http.post(MpCampaignMod.url() + "/admin", body, json -> {
                content.clear();
                if(json == null || !MpCampaignMod.bool(json, "ok")){
                    String error = json == null ? "Координатор недоступен." : MpCampaignMod.str(json, "error");
                    content.add("[scarlet]" + (error.isEmpty() ? "Не удалось прочитать лог." : error)).left().row();
                    return;
                }

                String text = MpCampaignMod.str(json, "log");
                if(text.isEmpty()) text = "(пусто)";
                //the log must be literal: labels would otherwise parse [color] tags in it
                content.add(text.replace("[", "[[")).growX().left().wrap().pad(4f);
            });
        };

        dialog.buttons.button("Обновить", Icon.refresh, load).size(170f, 46f);
        dialog.addCloseButton();
        dialog.show();
        load.run();
    }
}
