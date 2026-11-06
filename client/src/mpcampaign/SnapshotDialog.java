package mpcampaign;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.Strings;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Campaign snapshots made by the coordinator every 10 minutes; the owner can roll back. */
public class SnapshotDialog extends BaseDialog{

    private Table list;

    public SnapshotDialog(){
        super("Снимки кампании");
        addCloseButton();

        cont.table(t -> {
            t.button("Создать снимок", Icon.save, MpCampaignMod::saveSnapshot).size(200f, 46f).left();
            t.button("Обновить", Icon.refresh, this::load).size(150f, 46f).padLeft(6f).left();
            t.add("[lightgray]новый снимок каждые 10 минут, хранится до 50").padLeft(12f).left();
        }).growX().left().row();

        cont.pane(t -> {
            list = t;
            t.top().left();
        }).grow().padTop(8f).row();

        shown(this::load);
    }

    /** Refreshes the list; called after a manual snapshot. */
    public void reload(){
        load();
    }

    private void load(){
        list.clear();
        list.add("[lightgray]Загрузка...").left().row();

        Http.get(MpCampaignMod.url() + "/snapshots", json -> {
            list.clear();

            if(json == null){
                list.add("[scarlet]Координатор недоступен.").left().row();
                return;
            }

            Jval array = json.get("snapshots");
            if(array == null || !array.isArray() || array.asArray().isEmpty()){
                list.add("[lightgray]Снимков пока нет. Первый появится через 10 минут после запуска.").left().row();
                return;
            }

            for(Jval value : array.asArray()){
                String name = MpCampaignMod.str(value, "name");
                long time = MpCampaignMod.numLong(value, "time", 0);
                long size = MpCampaignMod.numLong(value, "size", 0);

                Table row = new Table();
                row.left();
                row.add("[accent]" + name + "[]").width(190f).left();
                row.add("[lightgray]" + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(time)) + "[]").width(170f).left();
                row.add("[lightgray]" + Strings.format("%.1f МБ", size / 1024f / 1024f) + "[]").width(110f).left();
                row.button("Загрузить", Icon.upload, () -> confirm(name)).size(160f, 46f).right();

                list.add(row).growX().left().padBottom(4f).row();
            }
        });
    }

    private void confirm(String name){
        Vars.ui.showConfirm("Загрузить снимок?",
            "Кампания будет заменена снимком " + name + ". Все запущенные секторы остановятся, игроков отключит.",
            () -> restore(name));
    }

    private void restore(String name){
        Jval body = Jval.newObject();
        body.add("name", name);
        body.add("player", Vars.player == null ? "" : Vars.player.plainName());

        Vars.ui.loadfrag.show("Восстановление кампании...");
        Http.post(MpCampaignMod.url() + "/restore", body, json -> {
            Vars.ui.loadfrag.hide();

            if(json == null || !MpCampaignMod.bool(json, "ok")){
                String error = json == null ? "Координатор недоступен." : MpCampaignMod.str(json, "error");
                Vars.ui.showInfoFade("[scarlet]" + (error == null || error.isEmpty() ? "Не удалось загрузить снимок." : error));
                return;
            }

            hide();
            if(Vars.ui.planet != null) Vars.ui.planet.hide();
            Vars.ui.showInfoFade("[accent]Кампания восстановлена, подключаюсь...");

            //the coordinator reloads the home in the same process; reconnect when it is ready
            Timer.schedule(() -> Core.app.post(() -> MpCampaignMod.refresh(() -> MpCampaignMod.returnToLobby())), 2f);
        });
    }
}
