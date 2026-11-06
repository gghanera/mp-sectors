package anyresearch;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ClientServerConnectEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Objectives;
import mindustry.game.Objectives.Objective;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.mod.Mod;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.ui.dialogs.ResearchDialog;

/**
 * Any Research - multiplayer research for everyone who has the mod.
 *
 * Why it has to work like this (Mindustry v160.4):
 *  - research state is server-side: Rules.researched (game/Rules.java:195)
 *  - the stock packet Call.researched is server -> client only (an @Remote method has
 *    targets = Loc.server by default, annotations/Annotations.java:234), so a client has no stock
 *    way to ask the server to research anything. That is why only the host can research in vanilla.
 *  - the restriction lives in the client UI and its methods are package-private
 *    (ui/dialogs/ResearchDialog.java:462, 506, 551, 568), so they cannot be overridden.
 *
 * So the client half catches clicks on tech tree nodes and asks the server over the stock
 * chat-command channel (the channel the vanilla /votekick command uses). The server half then
 * applies exactly the rules the vanilla tech tree applies - objectives, prerequisites, item cost -
 * and unlocks the node, which makes the game broadcast the stock Call.researched to every client,
 * vanilla ones included.
 */
public class AnyResearchMod extends Mod{

    /**
     * Handshake value: only the client half of this mod sends it, so players without the mod
     * cannot research by typing the command.
     * ponytail: this is a gate, not a security boundary - it is a constant in the jar.
     */
    private static final String TOKEN = "anyresearch-8q2f7";

    private boolean patched;

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> patchTree());
        Events.on(ClientServerConnectEvent.class, e -> patchTree());
        Events.on(WorldLoadEvent.class, e -> patchTree());
        Events.run(Trigger.update, this::updateNodeInfo);
    }

    // ---------------- client side ----------------

    /** Makes tech tree nodes clickable for a guest; the host already has the stock behaviour. */
    private void patchTree(){
        if(patched || Vars.ui == null || Vars.ui.research == null || Vars.ui.research.view == null) return;

        Vars.ui.research.view.addCaptureListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                //the host researches natively; only guests need this
                if(!Vars.net.client() || button != KeyCode.mouseLeft) return false;

                Element hit = Core.scene.hit(event.stageX, event.stageY, true);
                while(hit != null && !(hit.userObject instanceof TechNode)) hit = hit.parent;
                if(hit == null) return false;

                TechNode node = (TechNode)hit.userObject;
                //already researched on this server - leave the stock behaviour alone
                if(node.content.unlockedHost()) return true;

                Log.info("[any-research] requesting @ from the server.", node.content.name);
                Call.sendChatMessage("/anyresearch " + TOKEN + " " + node.content.name);
                return true;
            }
        });

        patched = true;
        Log.info("[any-research] tech tree patched for multiplayer research.");
    }

    private @Nullable Table reqRows;

    /**
     * A guest never sees what a locked node costs: the stock dialog prints "@locked" instead of the
     * requirement list when connected to a server (ResearchDialog.java:677). Draw those numbers next
     * to it ourselves, straight from the node's static requirements, so nothing can go stale.
     */
    private void updateNodeInfo(){
        if(Vars.ui == null || !Vars.net.client() || Vars.ui.research == null) return;

        ResearchDialog dialog = Vars.ui.research;
        if(!dialog.isShown() || dialog.view == null || dialog.view.infoTable == null) return;

        TechNode hovered = null;
        if(dialog.view.hoverNode != null && dialog.view.hoverNode.userObject instanceof TechNode node){
            hovered = node;
        }

        if(hovered == null || hovered.content.unlockedHost() || hovered.requirements.length == 0){
            if(reqRows != null && reqRows.parent != null) reqRows.remove();
            return;
        }

        //the stock dialog rebuilds its info table on every hover change, dropping our rows with it
        if(reqRows == null) reqRows = new Table();
        if(reqRows.parent != dialog.view.infoTable){
            Seq<String> needs = new Seq<>();
            for(ItemStack req : hovered.requirements){
                needs.add(label(req.item) + " " + req.amount);
            }

            reqRows.clear();
            reqRows.add("[lightgray]Нужно: " + String.join(", ", needs)).left();
            dialog.view.infoTable.row();
            dialog.view.infoTable.add(reqRows).left().pad(6f);
        }
    }

    // ---------------- server side ----------------

    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("anyresearch", "<token> <name>", "Internal command sent by the Any Research mod.", (args, player) -> {
            //not sent by a client running this mod - ignore silently
            if(!TOKEN.equals(args[0]) || !Vars.net.server()) return;

            TechNode node = find(args[1]);
            if(node == null){
                player.sendMessage("[scarlet]Нет такого исследования: [white]" + args[1]);
                return;
            }
            research(node, player);
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("anyresearch", "<name>", "Research a tech tree node under the same rules players get.", args -> {
            TechNode node = find(args[0]);
            if(node == null){
                Log.err("[any-research] no such research: @", args[0]);
                return;
            }
            research(node, null);
        });

        handler.register("anyresearch-status", "[name]", "Print Any Research diagnostics.", args -> {
            Log.info("[any-research] tech tree nodes: @", TechTree.all.size);

            int withCost = 0, withObjectives = 0;
            for(TechNode node : TechTree.all){
                if(node.requirements.length > 0) withCost++;
                if(!node.objectives.isEmpty()) withObjectives++;
            }
            Log.info("[any-research] nodes: with cost @, with objectives @", withCost, withObjectives);

            //what could be researched right now, and whether any of it is free
            int shown = 0, freeShown = 0;
            for(TechNode node : TechTree.all){
                if(node.content.unlockedHost()) continue;
                if(node.parent != null && !node.parent.content.unlockedHost()) continue;
                if(!objectivesMet(node)) continue;
                if(node.requirements.length == 0 && freeShown < 3){
                    Log.info("[any-research] free + researchable now: @", node.content.name);
                    freeShown++;
                }
                if(shown < 3){
                    Log.info("[any-research] researchable now: @ (cost @)", node.content.name, node.requirements.length);
                    shown++;
                }
            }

            if(args.length > 0){
                TechNode node = find(args[0]);
                if(node == null){
                    Log.err("[any-research] no such node: @", args[0]);
                }else{
                    TechNode root = node.rootNode != null ? node.rootNode : node;
                    Planet planet = planetOf(node);
                    Log.info("[any-research] @: unlocked=@ cost=@ objectivesMet=@ parent=@ root=@ planet=@ baseSectors=@",
                        node.content.name, node.content.unlockedHost(), node.requirements.length, objectivesMet(node),
                        node.parent == null ? "-" : node.parent.content.name,
                        root.name == null ? "?" : root.name,
                        planet == null ? "NOT MATCHED" : planet.name,
                        sectorsFor(node).size);
                }
            }

            Vars.mods.getMods().each(m -> Log.info("[any-research] loaded mod: @ v@ hidden=@", m.name, m.meta.version, m.meta.hidden));
            Log.info("[any-research] mod list sent to joining clients: @",
                Vars.mods.getModStrings().isEmpty() ? "<empty - vanilla clients can join>" : Vars.mods.getModStrings().toString(", "));

            boolean registered = false;
            for(CommandHandler.Command c : Vars.netServer.clientCommands.getCommandList()){
                if(c.text.equals("anyresearch")) registered = true;
            }
            Log.info("[any-research] player command registered on server: @", registered);
        });
    }

    /** Shared research path. Uses the same gates the vanilla tech tree uses, then pays the cost. */
    private static void research(TechNode node, @Nullable Player player){
        UnlockableContent content = node.content;

        if(content.unlockedHost()){
            reply(player, "[lightgray]Уже исследовано: [white]" + label(content));
            return;
        }

        //vanilla rule (ResearchDialog.selectable, :403): a node can be researched once every
        //objective is met. This is what keeps items (produce X) and sectors (capture Y) out of it.
        if(!objectivesMet(node)){
            Seq<String> pending = new Seq<>();
            for(Objective o : node.objectives){
                if(!o.complete()) pending.add(describe(o));
            }
            reply(player, "[scarlet]Условия ещё не выполнены: [lightgray]" + String.join(", ", pending));
            return;
        }

        //prerequisites, in case a node has no objectives describing its parent
        for(TechNode parent = node.parent; parent != null; parent = parent.parent){
            if(!parent.content.unlockedHost()){
                reply(player, "[scarlet]Сначала исследуйте: [white]" + label(parent.content));
                return;
            }
        }

        String spent = "";
        if(node.requirements.length > 0){
            spent = pay(node, player);
            if(spent == null) return;
        }

        content.unlock();
        reply(player, "[accent]Исследовано: [white]" + label(content)
            + (spent.isEmpty() ? "" : " [lightgray](списано: " + spent + ")"));
        Log.info("[any-research] researched @ (spent: @)", content.name, spent.isEmpty() ? "-" : spent);
    }

    private static boolean objectivesMet(TechNode node){
        for(Objective o : node.objectives){
            if(!o.complete()) return false;
        }
        return true;
    }

    /**
     * Takes the cost out of the same stock the vanilla tech tree itself uses: every base sector of
     * the node's planet, with the sector being played drained last
     * (Sector.items / Sector.removeItem, used by ResearchDialog.rebuildItems).
     * ponytail: paid in full, all-or-nothing. Vanilla accumulates partial progress in
     * TechNode.finishedRequirements, but that lives in each machine's own settings and would
     * desync between the server and guest clients, so it is deliberately not used.
     */
    private static @Nullable String pay(TechNode node, @Nullable Player player){
        Seq<Sector> sectors = sectorsFor(node);

        //how much is still owed per item; the host may have paid part of it in the stock dialog already
        int[] owed = new int[node.requirements.length];
        Seq<String> missing = new Seq<>();
        for(int i = 0; i < node.requirements.length; i++){
            ItemStack req = node.requirements[i];
            owed[i] = Math.max(req.amount - node.finishedRequirements[i].amount, 0);
            if(owed[i] == 0) continue;
            int have = stock(sectors, req.item);
            if(have < owed[i]) missing.add(label(req.item) + " " + have + "/" + owed[i]);
        }
        if(!missing.isEmpty()){
            reply(player, "[scarlet]Не хватает ресурсов: [white]" + String.join(", ", missing));
            return null;
        }

        Seq<String> spent = new Seq<>();
        for(int i = 0; i < node.requirements.length; i++){
            if(owed[i] == 0) continue;
            ItemStack req = node.requirements[i];

            int before = stock(sectors, req.item);
            int remaining = owed[i];
            for(Sector sector : sectors){
                if(remaining <= 0) break;
                int take = Math.min(Math.max(sector.items().get(req.item), 0), remaining);
                if(take > 0){
                    sector.removeItem(req.item, take);
                    remaining -= take;
                }
            }

            //research is never handed out for free: if the stock did not actually go down, cancel
            int after = stock(sectors, req.item);
            if(after != before - owed[i]){
                Log.warn("[any-research] deduction mismatch for @: @ -> @, expected @", req.item.name, before, after, before - owed[i]);
                reply(player, "[scarlet]Не удалось списать ресурсы, исследование отменено.");
                return null;
            }

            node.finishedRequirements[i].amount = req.amount;
            spent.add(label(req.item) + " " + owed[i]);
        }

        node.save();
        return String.join(", ", spent);
    }

    private static int stock(Seq<Sector> sectors, Item item){
        int total = 0;
        for(Sector sector : sectors){
            total += Math.max(sector.items().get(item), 0);
        }
        return total;
    }

    /** Base sectors of the node's planet, the played sector last. */
    private static Seq<Sector> sectorsFor(TechNode node){
        TechNode root = node.rootNode != null ? node.rootNode : node;
        Sector current = Vars.state.isCampaign() ? Vars.state.rules.sector : null;

        Seq<Sector> sectors = new Seq<>();
        boolean currentAdded = false;

        for(Planet planet : Vars.content.planets()){
            if(planet.techTree != root) continue;
            for(Sector sector : planet.sectors){
                if(!sector.hasBase() || sector.isFrozen()) continue;
                if(sector == current){
                    currentAdded = true;
                    continue;
                }
                sectors.add(sector);
            }
        }
        if(currentAdded) sectors.add(current);
        return sectors;
    }

    /** The planet whose tech tree this node belongs to. */
    private static @Nullable Planet planetOf(TechNode node){
        TechNode root = node.rootNode != null ? node.rootNode : node;
        for(Planet planet : Vars.content.planets()){
            if(planet.techTree == root) return planet;
        }
        return null;
    }

    /** Finds a tech tree node by internal or localized name, ignoring case, spaces and dashes. */
    private static TechNode find(String name){
        String key = normalize(name);
        if(key.isEmpty()) return null;
        for(TechNode node : TechTree.all){
            if(normalize(node.content.name).equals(key) || normalize(node.content.localizedName).equals(key)){
                return node;
            }
        }
        return null;
    }

    private static String normalize(String s){
        return s == null ? "" : s.toLowerCase().replace(" ", "").replace("-", "");
    }

    /**
     * Readable name. A dedicated server has no locale bundles loaded, so localizedName there is the
     * raw key ("block.conveyor.name") - fall back to the internal name.
     */
    private static String label(UnlockableContent content){
        String localized = content.localizedName;
        return localized == null || localized.contains(".") ? content.name : localized;
    }

    /** Short description of an objective, without relying on bundles (they are missing on a dedicated server). */
    private static String describe(Objective o){
        if(o instanceof Objectives.Research r) return "исследовать " + label(r.content);
        if(o instanceof Objectives.Produce p) return "добыть " + label(p.content);
        if(o instanceof Objectives.SectorComplete s) return "пройти сектор " + s.preset.name;
        if(o instanceof Objectives.OnSector s) return "высадиться в сектор " + s.preset.name;
        if(o instanceof Objectives.OnPlanet p) return "иметь базу на " + p.planet.name;
        return o.getClass().getSimpleName();
    }

    private static void reply(@Nullable Player player, String message){
        if(player != null){
            player.sendMessage(message);
        }else{
            Log.info("[any-research] @", message.replaceAll("\\[[^\\]]*\\]", ""));
        }
    }
}
