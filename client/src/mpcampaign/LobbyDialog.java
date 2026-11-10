package mpcampaign;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.Dialog;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.SectorInfo;
import mindustry.gen.Icon;
import mindustry.net.Net;
import mindustry.type.Item;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.PlanetDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;

/**
 * The campaign map for the coordinator setup.
 *
 * The stock map refuses to open for clients; this subclass bypasses that check for one call and
 * then puts the dialog into planet-launch mode, so a click on a sector asks the coordinator to land
 * there instead of switching a local world.
 */
public class LobbyDialog extends PlanetDialog{

    private static java.lang.reflect.Field activeField;

    private Table top, bottom;
    private boolean guest;
    private boolean forceCampaign;

    public LobbyDialog(){
        //vanilla behaviour: sectors that are not open yet cannot even be selected
        addCaptureListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                //the accelerator/vanilla maps are not campaign-gated; only block on our campaign map
                if(!guest) return false;
                if(button != KeyCode.mouseLeft) return false;
                if(Core.scene.hasMouse(event.stageX, event.stageY)) return false;

                Sector hovered = LobbyDialog.this.hovered;
                if(hovered == null) return false;

                MpCampaignMod.Entry entry = MpCampaignMod.entry(hovered);
                return entry == null || !entry.allowed;
            }
        });

        //the panel caps depend on the window height; rebuild them after a resize
        onResize(this::buildOverlay);
    }

    /** Rebuilds the overlay after fresh data arrives (moderator list, sector stats). */
    public void refreshOverlay(){
        //skip while a child dialog (Snapshots, Admin) is on top: rebuilding would add the
        //overlay above it and cover its buttons
        if(guest && (Core.scene.getDialog() == null || Core.scene.getDialog() == this)){
            buildOverlay();
        }
    }

    /** Opens the coordinator's campaign map even when the client is not connected to the server. */
    public void showCampaign(){
        forceCampaign = true;
        try{
            show();
        }finally{
            forceCampaign = false;
        }
    }

    @Override
    public Dialog show(){
        guest = forceCampaign || (Vars.net != null && Vars.net.client());

        if(!guest){
            return super.show();
        }

        //paint the map with fresh campaign data; it updates live as the answer arrives
        MpCampaignMod.refresh(null);

        boolean wasActive = setActive(false);
        try{
            super.show();
        }finally{
            setActive(wasActive);
        }

        if(!isShown()){
            return this;
        }

        MpCampaignMod.campaignMapOpen = true;
        selected = null;
        hovered = null;
        debugSelect = true;
        mode = Mode.planetLaunch;
        launchSector = Vars.state.getSector();
        launchCandidates = Vars.content.planets().select(p -> !p.removed && ((p.alwaysUnlocked && p.isLandable()) || p.sectors.contains(Sector::hasBase)));
        listener = MpCampaignMod::land;

        buildOverlay();
        return this;
    }

    @Override
    public void hide(){
        MpCampaignMod.campaignMapOpen = false;

        if(guest){
            debugSelect = false;
            guest = false;
        }
        if(top != null){
            top.remove();
            top = null;
        }
        if(bottom != null){
            bottom.remove();
            bottom = null;
        }
        super.hide();
    }

    /**
     * The planet view calls toFront() when it is touched, which would put the dialog above the
     * overlay and make the buttons disappear. Keep the overlay on top while the map is open.
     */
    @Override
    public void act(float delta){
        super.act(delta);

        //only while the campaign map itself is the active dialog: pushing the overlay to the
        //front unconditionally made it cover Snapshots/Admin dialogs opened from the map.
        //After a child dialog closes Mindustry sets the current dialog to null, so re-front then.
        if(Core.scene.getDialog() != null && Core.scene.getDialog() != this) return;

        if(top != null && top.parent != null) top.toFront();
        if(bottom != null && bottom.parent != null) bottom.toFront();
    }

    private void buildOverlay(){
        //onResize also fires for the stock planet view; only the campaign map gets this overlay
        if(!guest) return;

        if(top != null) top.remove();
        if(bottom != null) bottom.remove();

        top = new Table();
        top.setFillParent(true);
        //right column: the stock planet picker and Back button live bottom-left
        top.top().right();
        top.touchable = Touchable.childrenOnly;
        top.margin(12f);

        Table panel = new Table();
        panel.background(Styles.black6);
        panel.margin(10f);

        panel.add("[accent]КАМПАНИЯ").left().row();
        panel.label(() -> MpCampaignMod.loading ? "[lightgray]обновление..." : statusText())
            .color(Color.lightGray).width(420f).wrap().left().row();
        panel.add().height(4f).row();

        panel.table(row -> {
            row.defaults().height(40f).padRight(6f);
            row.button("Обновить", Icon.refresh, () -> MpCampaignMod.refresh(null)).width(110f);
            row.button("Адрес", Icon.settings, this::editUrl).width(80f);
            row.button("Снимки", Icon.save, MpCampaignMod::openSnapshots).width(100f);
            row.button("В лобби", Icon.home, MpCampaignMod::returnToLobby).width(100f);
        }).left().row();

        panel.table(row -> {
            row.defaults().height(36f).padRight(6f);
            row.button("Все", Icon.map, () -> showSectorList("all")).width(90f);
            row.button("Базы", Icon.home, () -> showSectorList("base")).width(90f);
            row.button("Атака", Icon.warning, () -> showSectorList("attack")).width(90f);
            row.button("Открытые", Icon.lock, () -> showSectorList("allowed")).width(120f);
        }).left().row();

        panel.table(row -> {
            row.defaults().height(36f).padRight(6f);
            row.button("Кто где", Icon.players, this::showWho).width(120f);
            row.button("История", Icon.list, this::showHistory).width(120f);
            if(MpCampaignMod.isModerator()){
                row.button("Админка", Icon.admin, MpCampaignMod::openAdmin).width(120f);
            }
        }).left().row();

        panel.add("[green]■ база   [scarlet]■ атака   [lightgray]■ не открыт   [accent]■ вы здесь").left().row();

        panel.button("Пригласить друзей", Icon.host, MpCampaignMod::inviteFriends)
            .height(40f).growX().padTop(6f).left().row();

        //independent top/bottom panels must never cover each other on a short screen
        ScrollPane topPane = new ScrollPane(panel, Styles.smallPane);
        topPane.setScrollingDisabled(true, false);
        topPane.setFadeScrollBars(false);
        topPane.setOverscroll(false, false);
        topPane.addListener(new InputListener(){
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, arc.scene.Element fromActor){
                Core.scene.setScrollFocus(topPane);
            }
        });

        top.add(topPane).right().top()
            .maxWidth(Math.max(300f, Math.min(520f, Core.graphics.getWidth() - 24f)))
            .maxHeight(Core.graphics.getHeight() * 0.42f);
        Core.scene.add(top);

        bottom = new Table();
        bottom.setFillParent(true);
        bottom.bottom().right();
        bottom.touchable = Touchable.childrenOnly;
        bottom.margin(12f);

        Table actions = new Table();
        actions.background(Styles.black6);
        actions.margin(10f);

        actions.label(this::selectedText).color(Color.lightGray).width(420f).wrap().left().row();
        actions.label(this::noteText).color(Color.lightGray).width(420f).wrap().left().row();
        actions.label(this::statsLine1).color(Color.lightGray).width(420f).wrap().left().row();
        actions.label(this::statsLine2).color(Color.lightGray).width(420f).wrap().left().row();

        actions.table(row -> {
            row.defaults().height(54f).padRight(8f);
            row.button("Высадиться", Icon.play, () -> MpCampaignMod.land(selected)).width(160f)
                .disabled(b -> MpCampaignMod.paused && !MpCampaignMod.isHome(selected));
            row.button("Домашний", Icon.home, () -> MpCampaignMod.setHome(selected)).width(140f);
            row.button("Заметка", Icon.edit, this::editNote).width(120f);
        }).left().row();

        actions.button("Направить пусковые площадки", Icon.upOpen, () -> MpCampaignMod.setLaunchPads(selected))
            .height(46f).growX().padTop(6f).left().row();

        ScrollPane actionsPane = new ScrollPane(actions, Styles.smallPane);
        actionsPane.setScrollingDisabled(true, false);
        actionsPane.setFadeScrollBars(false);
        actionsPane.setOverscroll(false, false);
        actionsPane.addListener(new InputListener(){
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, arc.scene.Element fromActor){
                Core.scene.setScrollFocus(actionsPane);
            }
        });

        bottom.add(actionsPane).right().bottom()
            .maxWidth(Math.max(300f, Math.min(520f, Core.graphics.getWidth() - 24f)))
            .maxHeight(Core.graphics.getHeight() * 0.50f);
        Core.scene.add(bottom);
    }

    private String statusText(){
        if(!MpCampaignMod.online){
            return "[scarlet]" + (MpCampaignMod.lastError.isEmpty() ? "нет связи с координатором" : MpCampaignMod.lastError);
        }

        String homeName = homeName();
        String version = MpCampaignMod.versionMismatch()
            ? "[scarlet]Обнови mp-campaign: сервер " + MpCampaignMod.campaignVersion
                + ", у тебя " + MpCampaignMod.localVersion() + "[]\n"
            : "";

        return version + (MpCampaignMod.paused ? "[scarlet]ПАУЗА  []" : "")
            + "[lightgray]игроки: [white]" + MpCampaignMod.players + "/" + MpCampaignMod.maxPlayers
            + "   [lightgray]секторы: [white]" + MpCampaignMod.sectors + "/" + MpCampaignMod.maxSectors
            + "   [lightgray]дом: [white]" + homeName;
    }

    private String homeName(){
        MpCampaignMod.Entry entry = MpCampaignMod.entries.get(MpCampaignMod.home);
        if(entry != null) return MpCampaignMod.displayName(entry);
        return MpCampaignMod.home.isEmpty() ? "?" : MpCampaignMod.home;
    }

    private String selectedText(){
        Sector sector = selected;
        if(sector == null) return "[lightgray]Выберите сектор на карте";

        MpCampaignMod.Entry entry = MpCampaignMod.entry(sector);
        if(entry == null) return "[lightgray]" + sector.name();

        StringBuilder out = new StringBuilder("[accent]").append(MpCampaignMod.displayName(entry)).append("[]  ");
        if(entry.running) out.append("[green]на сервере").append(entry.players > 0 ? " (" + entry.players + ")" : "").append("[]  ");
        if(entry.hasBase) out.append(entry.attacked ? "[scarlet]атака[]  " : "[green]база[]  ");
        if(!entry.allowed) out.append("[scarlet]не открыт[]  ");
        if(Vars.state.getSector() == sector) out.append("[lightgray]вы здесь[]");
        return out.toString();
    }

    private String noteText(){
        MpCampaignMod.Entry entry = MpCampaignMod.entry(selected);
        if(entry == null || entry.note == null || entry.note.isEmpty()) return "";
        return "[lightgray]Заметка: [white]" + entry.note;
    }

    private String statsLine1(){
        SectorInfo info = parsedInfo();
        if(info == null) return "";

        int total = info.items == null ? 0 : info.items.total;
        String core = info.hasCore
            ? formatAmount(total) + (info.storageCapacity > 0 ? "/" + formatAmount(info.storageCapacity) : "")
            : "нет ядра";
        String wave = info.waves
            ? "волна " + info.wave + (info.winWave > 0 ? "/" + info.winWave : "")
            : "без волн";

        return "[lightgray]Ядро: [white]" + core + "   [lightgray]" + wave;
    }

    private String statsLine2(){
        SectorInfo info = parsedInfo();
        if(info == null) return "";

        return "[lightgray]Добыча: [white]+" + Math.round(sum(info.production) * 60f) + "/мин"
            + "   [lightgray]Импорт: [white]+" + Math.round(sum(info.imports) * 60f) + "/мин"
            + "   [lightgray]Экспорт: [white]-" + Math.round(sum(info.export) * 60f) + "/мин";
    }

    private SectorInfo parsedInfo(){
        MpCampaignMod.Entry entry = MpCampaignMod.entry(selected);
        return entry == null ? null : entry.parsed;
    }

    private static float sum(arc.struct.ObjectMap<Item, SectorInfo.ExportStat> stats){
        if(stats == null) return 0f;

        float total = 0f;
        for(SectorInfo.ExportStat stat : stats.values()){
            if(stat != null) total += stat.mean;
        }
        return total;
    }

    private static String formatAmount(int value){
        if(value >= 1_000_000) return String.format("%.1fM", value / 1_000_000f);
        if(value >= 1_000) return String.format("%.1fK", value / 1_000f);
        return Integer.toString(value);
    }

    /** Opens a list of sectors matching a status filter; clicking one selects it on the map. */
    private void showSectorList(String mode){
        BaseDialog dialog = new BaseDialog("Секторы");

        ArrayList<MpCampaignMod.Entry> list = new ArrayList<>();
        for(MpCampaignMod.Entry entry : MpCampaignMod.entries.values()){
            if(matches(entry, mode)) list.add(entry);
        }
        list.sort(Comparator.comparing(MpCampaignMod::displayName));

        dialog.cont.pane(t -> {
            t.top().left();
            if(list.isEmpty()){
                t.add("[lightgray]Нет секторов под этот фильтр.").left().row();
                return;
            }

            for(MpCampaignMod.Entry entry : list){
                t.table(row -> {
                    row.left();
                    row.add("[accent]" + MpCampaignMod.displayName(entry)).width(240f).left();
                    row.add(statusOf(entry)).width(230f).left();
                    row.button("Выбрать", Icon.map, () -> {
                        dialog.hide();
                        selected = sectorOf(entry);
                    }).size(150f, 44f);
                }).growX().left().padBottom(4f).row();
            }
        }).grow();

        dialog.addCloseButton();
        dialog.show();
    }

    private static boolean matches(MpCampaignMod.Entry entry, String mode){
        return switch(mode){
            case "base" -> entry.hasBase;
            case "attack" -> entry.attacked;
            case "allowed" -> entry.allowed;
            default -> true;
        };
    }

    private static String statusOf(MpCampaignMod.Entry entry){
        if(entry.running) return "[green]на сервере" + (entry.players > 0 ? " (" + entry.players + ")" : "");
        if(entry.attacked) return "[scarlet]атака";
        if(entry.hasBase) return "[green]база";
        if(entry.allowed) return "[lightgray]открыт";
        return "[scarlet]закрыт";
    }

    private static Sector sectorOf(MpCampaignMod.Entry entry){
        Planet planet = Vars.content.getByName(mindustry.ctype.ContentType.planet, entry.planet);
        if(planet == null || entry.id < 0 || entry.id >= planet.sectors.size) return null;
        return planet.sectors.get(entry.id);
    }

    private void editNote(){
        Sector sector = selected;
        if(sector == null){
            Vars.ui.showInfoFade("[scarlet]Сначала выберите сектор на карте.");
            return;
        }

        MpCampaignMod.Entry entry = MpCampaignMod.entry(sector);
        String[] title = {entry == null || entry.title == null ? "" : entry.title};
        String[] note = {entry == null || entry.note == null ? "" : entry.note};

        BaseDialog dialog = new BaseDialog("Название и заметка");
        dialog.cont.table(t -> {
            t.defaults().left().padBottom(6f);
            t.add("[lightgray]Название сектора").left().row();
            t.field(title[0], value -> title[0] = value).width(420f).row();
            t.add("[lightgray]Заметка").left().row();
            t.area(note[0], value -> note[0] = value).width(420f).height(120f).row();
        }).left();

        dialog.buttons.button("Сохранить", Icon.ok, () -> {
            dialog.hide();
            MpCampaignMod.saveNote(sector, title[0], note[0]);
        }).size(180f, 50f);
        dialog.buttons.button("Отмена", dialog::hide).size(140f, 50f);
        dialog.show();
    }

    private void showWho(){
        BaseDialog dialog = new BaseDialog("Кто где");

        dialog.cont.pane(t -> {
            t.top().left();
            if(MpCampaignMod.whoLines.length == 0){
                t.add("[lightgray]Данных пока нет. Нажми «Обновить» на карте.").left().row();
            }else{
                for(String line : MpCampaignMod.whoLines){
                    t.add(line).left().padBottom(2f).row();
                }
            }
        }).grow();

        dialog.buttons.button("Обновить", Icon.refresh, () -> {
            dialog.hide();
            MpCampaignMod.refresh(() -> {
                if(isShown()) Core.app.post(this::showWho);
            });
        }).size(170f, 46f);
        dialog.addCloseButton();
        dialog.show();
    }

    private void showHistory(){
        BaseDialog dialog = new BaseDialog("История кампании");

        Table list = new Table();
        list.top().left();
        list.add("[lightgray]Загрузка...").left().row();

        dialog.cont.pane(list).grow();

        Http.get(MpCampaignMod.url() + "/history", json -> {
            list.clear();
            if(json == null){
                list.add("[scarlet]Координатор недоступен.").left().row();
                return;
            }

            Jval array = json.get("events");
            if(array == null || !array.isArray() || array.asArray().isEmpty()){
                list.add("[lightgray]Событий пока нет.").left().row();
                return;
            }

            SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm");
            for(Jval value : array.asArray()){
                String text = MpCampaignMod.str(value, "text");
                String sector = MpCampaignMod.str(value, "sector");
                long time = MpCampaignMod.numLong(value, "time", 0);

                String line = "[lightgray]" + format.format(new Date(time)) + "  [white]" + text;
                if(!sector.isEmpty()) line += " [lightgray](" + sector + ")";
                list.add(line).left().padBottom(2f).row();
            }
        });

        dialog.addCloseButton();
        dialog.show();
    }

    private void editUrl(){
        Vars.ui.showTextInput("Адрес координатора", "http://host:8090", 100, MpCampaignMod.url(), value -> {
            MpCampaignMod.url(value);
            MpCampaignMod.refresh(null);
        });
    }

    /** Net.client() is `!server && active`; turning `active` off for one call is enough. */
    private static boolean setActive(boolean active){
        try{
            if(activeField == null){
                activeField = Net.class.getDeclaredField("active");
                activeField.setAccessible(true);
            }
            boolean previous = activeField.getBoolean(Vars.net);
            activeField.setBoolean(Vars.net, active);
            return previous;
        }catch(Throwable t){
            return true;
        }
    }
}


