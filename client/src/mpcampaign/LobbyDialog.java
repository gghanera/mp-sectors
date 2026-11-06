package mpcampaign;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.net.Net;
import mindustry.type.Sector;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.PlanetDialog;

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
        if(top != null && top.parent != null) top.toFront();
        if(bottom != null && bottom.parent != null) bottom.toFront();
    }

    private void buildOverlay(){
        if(top != null) top.remove();
        if(bottom != null) bottom.remove();

        top = new Table();
        top.setFillParent(true);
        top.top().left();
        top.touchable = Touchable.childrenOnly;
        top.margin(12f);

        Table panel = new Table();
        panel.background(Styles.black6);
        panel.margin(10f);

        panel.add("[accent]КАМПАНИЯ").left().row();
        panel.label(() -> MpCampaignMod.loading ? "[lightgray]обновление..." : statusText()).color(Color.lightGray).left().row();
        panel.add().height(4f).row();

        panel.table(row -> {
            row.defaults().height(42f).padRight(6f);
            row.button("Обновить", Icon.refresh, () -> MpCampaignMod.refresh(null)).width(150f);
            row.button("Адрес", Icon.settings, this::editUrl).width(120f);
            row.button("Снимки", Icon.save, MpCampaignMod::openSnapshots).width(140f);
            row.button("В лобби", Icon.home, MpCampaignMod::returnToLobby).width(140f);
        }).left().row();

        panel.button("Пригласить друзей", Icon.host, MpCampaignMod::inviteFriends)
            .height(40f).growX().padTop(6f).left().row();

        top.add(panel).left().top();
        Core.scene.add(top);

        bottom = new Table();
        bottom.setFillParent(true);
        bottom.bottom().right();
        bottom.touchable = Touchable.childrenOnly;
        bottom.margin(12f);

        Table actions = new Table();
        actions.background(Styles.black6);
        actions.margin(10f);

        actions.label(this::selectedText).color(Color.lightGray).left().row();

        actions.table(row -> {
            row.defaults().height(54f).padRight(8f);
            row.button("Высадиться", Icon.play, () -> MpCampaignMod.land(selected)).width(200f);
            row.button("Домашний", Icon.home, () -> MpCampaignMod.setHome(selected)).width(180f);
        }).left().row();

        actions.button("Направить пусковые площадки", Icon.upOpen, () -> MpCampaignMod.setLaunchPads(selected))
            .height(46f).growX().padTop(6f).left().row();

        bottom.add(actions).right().bottom();
        Core.scene.add(bottom);
    }

    private String statusText(){
        if(!MpCampaignMod.online){
            return "[scarlet]" + (MpCampaignMod.lastError.isEmpty() ? "нет связи с координатором" : MpCampaignMod.lastError);
        }

        String homeName = homeName();
        return "[lightgray]игроки: [white]" + MpCampaignMod.players + "/" + MpCampaignMod.maxPlayers
            + "   [lightgray]секторы: [white]" + MpCampaignMod.sectors + "/" + MpCampaignMod.maxSectors
            + "   [lightgray]дом: [white]" + homeName;
    }

    private String homeName(){
        MpCampaignMod.Entry entry = MpCampaignMod.entries.get(MpCampaignMod.home);
        if(entry != null) return entry.name;
        return MpCampaignMod.home.isEmpty() ? "?" : MpCampaignMod.home;
    }

    private String selectedText(){
        Sector sector = selected;
        if(sector == null) return "[lightgray]Выберите сектор на карте";

        MpCampaignMod.Entry entry = MpCampaignMod.entry(sector);
        if(entry == null) return "[lightgray]" + sector.name();

        StringBuilder out = new StringBuilder("[accent]").append(entry.name).append("[]  ");
        if(entry.running) out.append("[green]на сервере").append(entry.players > 0 ? " (" + entry.players + ")" : "").append("[]  ");
        if(entry.hasBase) out.append(entry.attacked ? "[scarlet]атака[]  " : "[green]база[]  ");
        if(!entry.allowed) out.append("[scarlet]не открыт[]  ");
        if(Vars.state.getSector() == sector) out.append("[lightgray]вы здесь[]");
        return out.toString();
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


