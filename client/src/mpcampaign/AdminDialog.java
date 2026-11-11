package mpcampaign;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

import java.text.SimpleDateFormat;
import java.util.Date;

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
        }).grow().padTop(8f).maxHeight(Core.graphics.getHeight() * 0.65f).row();

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
        //the moderator list and the pause flag can change from this panel; apply them first
        MpCampaignMod.parseStatus(status);
        if(!MpCampaignMod.isModerator()){
            hide();
            Vars.ui.showInfoFade("[scarlet]Админ-панель доступна только модераторам кампании.");
            return;
        }

        String home = MpCampaignMod.str(status, "home");
        int players = MpCampaignMod.num(status, "players", 0);
        int running = MpCampaignMod.num(status, "sectors", 1);
        int maxSectors = MpCampaignMod.num(status, "maxSectors", 5);
        int maxPlayers = MpCampaignMod.num(status, "maxPlayers", 10);
        long uptime = MpCampaignMod.numLong(status, "uptime", 0);
        int snapshotCount = MpCampaignMod.num(status, "snapshotCount", 0);
        long lastSnapshot = MpCampaignMod.numLong(status, "lastSnapshot", 0);
        long diskFree = MpCampaignMod.numLong(status, "diskFree", -1);
        long diskTotal = MpCampaignMod.numLong(status, "diskTotal", -1);
        long memoryUsed = MpCampaignMod.numLong(status, "memoryUsed", 0);
        long memoryMax = MpCampaignMod.numLong(status, "memoryMax", 0);

        body.add("[accent]Кампания").left().row();
        body.add("[lightgray]игроки: [white]" + players + "/" + maxPlayers
            + "   [lightgray]секторы: [white]" + running + "/" + maxSectors
            + "   [lightgray]дом: [white]" + sectorName(sectors, home)
            + "   [lightgray]режим: " + (MpCampaignMod.paused ? "[scarlet]пауза[]" : "[green]идёт[]"))
            .width(500f).wrap().left().row();
        body.add("[lightgray]аптайм: [white]" + formatDuration(uptime)
            + "   [lightgray]снимки: [white]" + snapshotCount
            + "   [lightgray]последний: [white]" + (lastSnapshot > 0 ? formatTime(lastSnapshot) : "—"))
            .width(500f).wrap().left().row();
        body.add("[lightgray]диск: [white]" + formatBytes(diskFree) + " из " + formatBytes(diskTotal)
            + "   [lightgray]память: [white]" + formatBytes(memoryUsed) + "/" + formatBytes(memoryMax))
            .width(500f).wrap().left().row();
        body.add().height(6f).row();

        body.table(t -> {
            t.defaults().height(44f).padRight(6f);
            t.button("Пауза", Icon.pause, () -> post("pause", null, null, "true")).width(130f);
            t.button("Продолжить", Icon.play, () -> post("pause", null, null, "false")).width(150f);
            t.button("Снимок", Icon.save, () -> post("snapshot", null, null, null)).width(140f);
        }).left().row();

        body.table(t -> {
            t.defaults().height(40f).padRight(6f);
            t.button("x0.5", () -> post("speed", null, null, "0.5")).width(80f);
            t.button("x1", () -> post("speed", null, null, "1")).width(70f);
            t.button("x2", () -> post("speed", null, null, "2")).width(70f);
            t.button("x4", () -> post("speed", null, null, "4")).width(70f);
        }).left().row();

        // ---- companies ----
        body.add().height(8f).row();
        body.add("[accent]Компании").left().row();
        body.add("[lightgray]активная: [white]" + (MpCampaignMod.company.isEmpty()
            ? "Основная" : MpCampaignMod.company) + "[]").left().row();

        Table companyList = new Table();
        companyList.top().left();
        body.add(companyList).growX().left().row();

        if(MpCampaignMod.companies.length == 0){
            companyList.add("[lightgray]Сохранённых компаний пока нет.").left().row();
        }else{
            for(String name : MpCampaignMod.companies){
                if(name == null || name.isEmpty()) continue;
                if(name.equalsIgnoreCase(MpCampaignMod.company)) continue;

                Table row = new Table();
                row.left();
                row.add("[white]" + name + "[]").width(300f).wrap().left();
                row.button("Переключить", Icon.refresh, () -> confirmCompanySwitch(name)).size(170f, 40f);
                companyList.add(row).growX().left().padBottom(2f).row();
            }
        }

        String[] newCompany = {""};
        companyList.table(row -> {
            row.left();
            row.field("", value -> newCompany[0] = value).width(280f).height(44f);
            row.button("Новая", Icon.add, () -> confirmCompanyCreate(newCompany[0])).size(140f, 44f).padLeft(6f);
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
        int moderatorCount = moderators != null && moderators.isArray() ? moderators.asArray().size : 0;
        if(moderators != null && moderators.isArray()){
            for(Jval value : moderators.asArray()){
                String name = value.asString();
                if(name == null || name.isEmpty()) continue;

                Table row = new Table();
                row.left();
                row.add("[white]" + name + "[]").width(280f).wrap().left();
                if(moderatorCount > 1){
                    row.button("Снять", Icon.cancel, () -> confirmRemoveModerator(name)).size(130f, 40f);
                }else{
                    row.add("[lightgray]последний[]").width(130f).left();
                }
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

        // ---- bans ----
        body.add().height(8f).row();
        body.add("[accent]Баны").left().row();

        Table banList = new Table();
        banList.top().left();
        body.add(banList).growX().left().row();

        Jval bans = status.get("bans");
        if(bans != null && bans.isArray() && !bans.asArray().isEmpty()){
            for(Jval value : bans.asArray()){
                String name = value.isString() ? value.asString() : MpCampaignMod.str(value, "name");
                if(name == null || name.isEmpty()) continue;

                String reason = value.isString() ? "" : MpCampaignMod.str(value, "reason");
                long expires = value.isString() ? 0 : MpCampaignMod.numLong(value, "expires", 0);

                Table row = new Table();
                row.left();
                row.add("[white]" + name + "[]").width(150f).wrap().left();
                row.add("[lightgray]" + (reason.isEmpty() ? "без причины" : reason) + "[]")
                    .width(160f).wrap().left();
                row.add(expires <= 0 ? "[lightgray]навсегда[]"
                    : "[orange]до " + formatTime(expires) + "[]").width(125f).left();
                row.button("Разбанить", Icon.cancel, () -> post("unban", null, name, null)).size(135f, 40f);
                banList.add(row).growX().left().padBottom(2f).row();
            }
        }else{
            banList.add("[lightgray]Банов нет.").left().row();
        }

        banList.button("Забанить...", Icon.cancel, this::showBanDialog).size(190f, 44f).left().row();
    }

    private void showBanDialog(){
        BaseDialog dialog = new BaseDialog("Забанить игрока");
        String[] name = {""}, reason = {""}, hours = {"0"};

        dialog.cont.table(t -> {
            t.left();
            t.add("[lightgray]Ник").left().width(120f);
            t.field("", value -> name[0] = value).width(320f).height(44f).row();
            t.add("[lightgray]Причина").left().width(120f);
            t.field("", value -> reason[0] = value).width(320f).height(44f).row();
            t.add("[lightgray]Часов (0 — навсегда)").left().width(280f);
            t.field("0", value -> hours[0] = value).width(120f).height(44f).row();
        }).left();

        dialog.buttons.button("Забанить", Icon.cancel, () -> {
            String target = name[0] == null ? "" : name[0].trim();
            if(target.isEmpty()) return;

            long until = 0;
            try{
                until = Math.max(0, Long.parseLong(hours[0].trim()));
            }catch(Throwable ignored){
            }
            post("ban", null, target, null, reason[0] == null ? "" : reason[0].trim(), until);
            dialog.hide();
        }).size(160f, 46f);
        dialog.buttons.button("Отмена", dialog::hide).size(140f, 46f);
        dialog.show();
    }

    private static String formatDuration(long ms){
        long seconds = Math.max(0, ms / 1000L);
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if(days > 0) return days + " д " + hours + " ч";
        if(hours > 0) return hours + " ч " + minutes + " мин";
        return minutes + " мин";
    }

    private static String formatBytes(long bytes){
        if(bytes < 0) return "?";
        if(bytes >= 1024L * 1024L * 1024L) return String.format("%.1f ГБ", bytes / 1024f / 1024f / 1024f);
        if(bytes >= 1024L * 1024L) return String.format("%.0f МБ", bytes / 1024f / 1024f);
        return String.format("%.0f КБ", bytes / 1024f);
    }

    private static String formatTime(long time){
        return new SimpleDateFormat("dd.MM HH:mm").format(new Date(time));
    }

    private void addSectorRow(Table list, String key, String info, Jval names, boolean home, Jval sectors){
        int count = names != null && names.isArray() ? names.asArray().size : 0;

        Table row = new Table();
        row.left();
        row.add("[accent]" + sectorName(sectors, key) + "[]").width(210f).wrap().left();
        row.add("[lightgray]" + key + "  " + info + "[]").width(190f).left();
        row.add("[white]" + (count > 0 ? count + " игр." : "пусто")).width(90f).left();
        row.row();

        row.button("Лог", Icon.list, () -> showLog(key, sectorName(sectors, key))).size(110f, 38f).left();
        if(!home){
            row.button("Остановить", Icon.cancel, () -> confirmStop(key, sectorName(sectors, key)))
                .size(160f, 38f).padLeft(6f).left();
        }

        list.add(row).growX().left().padBottom(6f).row();
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
            row.add("[white]" + name + "[]").width(200f).wrap().left();
            row.add("[lightgray]" + sectorName(sectors, sector) + "[]").width(190f).wrap().left();
            row.button("Кик", Icon.cancel, () -> confirmKick(sector, name)).size(100f, 38f);
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
        post(action, sector, name, value, null, 0);
    }

    private void post(String action, String sector, String name, String value, String reason, long hours){
        Jval body = Jval.newObject();
        body.add("player", MpCampaignMod.localName());
        body.add("action", action);
        if(sector != null) body.add("sector", sector);
        if(name != null) body.add("name", name);
        if(value != null) body.add("value", value);
        if(reason != null && !reason.isEmpty()) body.add("reason", reason);
        if(hours > 0) body.add("hours", Jval.valueOf(hours));

        Http.post(MpCampaignMod.url() + "/admin", body, json -> {
            if(json != null && MpCampaignMod.bool(json, "ok")){
                Vars.ui.showInfoFade("[accent]Готово.");
                load();
                //a stopped sector and a kicked player disappear from the list a moment later
                if("stop".equals(action) || "kick".equals(action)){
                    arc.util.Timer.schedule(() -> {
                        if(isShown()) load();
                    }, 2f);
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

    private void confirmCompanySwitch(String name){
        Vars.ui.showConfirm("Переключить компанию?",
            "Текущая компания сохранится. Все секторы будут остановлены, затем загрузится «" + name + "».",
            () -> post("companyswitch", null, null, name));
    }

    private void confirmCompanyCreate(String name){
        String target = name == null ? "" : name.trim();
        if(target.isEmpty()){
            Vars.ui.showInfoFade("[scarlet]Введите имя компании.");
            return;
        }
        Vars.ui.showConfirm("Создать компанию?",
            "Текущая компания сохранится в архиве. Новая начнётся с чистыми секторами, но с текущими "
                + "исследованиями, модераторами и банами.",
            () -> post("companycreate", null, null, target));
    }

    private void confirmRemoveModerator(String name){
        Vars.ui.showConfirm("Снять модератора?", name + " больше не сможет управлять кампанией.",
            () -> post("modremove", null, name, null));
    }

    private void showLog(String sector, String title){
        BaseDialog dialog = new BaseDialog("Лог: " + title);
        float logWidth = Math.max(320f, Math.min(700f, Core.graphics.getWidth() - 120f));

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
                content.add(text.replace("[", "[[")).width(logWidth).left().wrap().pad(4f);
            });
        };

        dialog.buttons.button("Обновить", Icon.refresh, load).size(170f, 46f);
        dialog.addCloseButton();
        dialog.show();
        load.run();
    }
}
