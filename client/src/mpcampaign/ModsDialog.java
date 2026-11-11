package mpcampaign;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Serves the current client mods from the coordinator. The jar is written into the game's mods
 * folder; on Windows the running mp-campaign.jar may be locked, so the dialog falls back to a
 * path outside the mods folder and asks the player to replace it after closing the game.
 */
public class ModsDialog extends BaseDialog{

    private Table body;

    public ModsDialog(){
        super("Моды кампании");
        addCloseButton();

        cont.pane(t -> {
            body = t;
            t.top().left();
        }).grow().maxHeight(Core.graphics.getHeight() * 0.65f).row();

        shown(this::load);
    }

    private void load(){
        body.clear();
        body.add("[lightgray]Загрузка...").left().row();

        Http.get(MpCampaignMod.url() + "/mods", json -> {
            body.clear();
            if(json == null){
                body.add("[scarlet]Координатор недоступен.").left().row();
                return;
            }

            body.add("[lightgray]Сервер: [white]" + MpCampaignMod.serverVersion
                + "   [lightgray]у вас: [white]" + MpCampaignMod.localVersion())
                .left().row();
            body.add("[lightgray]Скачайте мод и перезапустите игру.").left().padBottom(8f).row();

            Jval files = json.get("files");
            if(files == null || !files.isArray() || files.asArray().isEmpty()){
                body.add("[lightgray]На сервере нет файлов модов.").left().row();
                return;
            }

            for(Jval value : files.asArray()){
                String name = MpCampaignMod.str(value, "name");
                String version = MpCampaignMod.str(value, "version");
                long size = MpCampaignMod.numLong(value, "size", 0);
                if(name.isEmpty()) continue;

                Table row = new Table();
                row.left();
                row.add("[accent]" + name + "[]").width(210f).left();
                row.add("[lightgray]" + (version.isEmpty() ? "" : version + "  ") + size / 1024 + " КБ[]")
                    .width(190f).left();
                row.button("Скачать", Icon.download, () -> download(name)).size(150f, 42f);
                body.add(row).growX().left().padBottom(4f).row();
            }
        });
    }

    private void download(String name){
        arc.files.Fi target = Vars.modDirectory.child(name);
        Http.download(MpCampaignMod.url() + "/download?file=" + name, target, saved -> {
            if(saved == null){
                Vars.ui.showInfoFade("[scarlet]Не удалось скачать " + name + ".");
            }else if(saved.equals(target.absolutePath())){
                Vars.ui.showInfoFade("[accent]" + name + " обновлён. Перезапустите игру.");
            }else{
                Vars.ui.showInfoFade("[orange]" + name + " сохранён в " + saved
                    + ". Закройте игру и замените файл в папке mods.");
            }
        });
    }
}
