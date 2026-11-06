package mpsectors;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Objectives;
import mindustry.game.Objectives.Objective;
import mindustry.game.SectorInfo;
import mindustry.gen.Player;
import mindustry.io.JsonIO;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.type.Sector;

/**
 * Server half of the Any Research mod, ported into the campaign server.
 *
 * Guests cannot research on a dedicated server (the tech tree is host-only), so the client mod
 * sends "/anyresearch <token> <content>"; this handler applies the same rules the vanilla tree
 * uses (objectives, prerequisites, item cost) and unlocks the node, which makes the server
 * broadcast the stock researched packet to every client.
 *
 * Cost is paid from the campaign store: the base sectors of the node's planet, the sector being
 * played taken live and last. Unlike the original mod it does not use Sector.save/hasBase, because
 * a headless sector server has no SaveSlot objects.
 */
public class ResearchServer{

    /** Same token the Any Research client sends. */
    private static final String TOKEN = "anyresearch-8q2f7";

    public static String campaignPath;

    public static void register(CommandHandler handler){
        handler.<Player>register("anyresearch", "<token> <name>", "Internal command sent by the Any Research mod.", (args, player) -> {
            if(!TOKEN.equals(args[0]) || !Vars.net.server() || campaignPath == null) return;

            TechNode node = find(args[1]);
            if(node == null){
                reply(player, "[scarlet]Нет такого исследования: [white]" + args[1]);
                return;
            }
            research(node, player);
        });
    }

    /** Console entry: research by content name, with the same rules players get. */
    public static void researchByName(String name, @Nullable Player player){
        TechNode node = find(name);
        if(node == null){
            reply(player, "[scarlet]Нет такого исследования: [white]" + name);
            return;
        }
        research(node, player);
    }

    private static void research(TechNode node, @Nullable Player player){        UnlockableContent content = node.content;

        if(content.unlockedHost()){
            reply(player, "[lightgray]Уже исследовано: [white]" + label(content));
            return;
        }

        if(!objectivesMet(node)){
            Seq<String> pending = new Seq<>();
            for(Objective objective : node.objectives){
                if(!objective.complete()) pending.add(describe(objective));
            }
            reply(player, "[scarlet]Условия ещё не выполнены: [lightgray]" + String.join(", ", pending));
            return;
        }

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
        if(Vars.state.rules != null) Vars.state.rules.researched.add(content);
        reply(player, "[accent]Исследовано: [white]" + label(content)
            + (spent.isEmpty() ? "" : " [lightgray](потрачено: " + spent + ")"));
        Log.info("[mp-sectors] researched @ (spent: @)", content.name, spent.isEmpty() ? "-" : spent);
    }

    private static boolean objectivesMet(TechNode node){
        for(Objective objective : node.objectives){
            if(!objective.complete()) return false;
        }
        return true;
    }

    private static class Stock{
        final ObjectMap<Integer, SectorInfo> infos = new ObjectMap<>();
        final ObjectMap<Integer, Fi> files = new ObjectMap<>();
        final ObjectSet<Integer> bases = new ObjectSet<>();
    }

    private static Stock loadStock(Planet planet){
        Stock stock = new Stock();

        Fi dir = Core.files.absolute(campaignPath);
        if(!dir.child("info").exists()) return stock;
        Fi[] files = dir.child("info").list();

        for(Fi file : files){
            if(!file.extension().equals("json")) continue;

            String name = file.nameWithoutExtension();
            int split = name.lastIndexOf('-');
            if(split <= 0 || !name.substring(0, split).equals(planet.name)) continue;

            int id;
            try{
                id = Integer.parseInt(name.substring(split + 1));
            }catch(NumberFormatException ignored){
                continue;
            }

            SectorInfo info;
            try{
                info = JsonIO.read(SectorInfo.class, file.readString());
            }catch(Throwable t){
                continue;
            }
            if(info == null) continue;

            stock.infos.put(id, info);
            stock.files.put(id, file);
            if(info.hasCore && dir.child("sectors").child(name + ".msav").exists()){
                stock.bases.add(id);
            }
        }

        return stock;
    }

    /** @return the spent items as text, or null when the cost could not be paid. */
    private static @Nullable String pay(TechNode node, @Nullable Player player){
        Planet planet = planetOf(node);
        if(planet == null){
            reply(player, "[scarlet]Не удалось определить планету исследования.");
            return null;
        }

        Sector current = Vars.state.isCampaign() ? Vars.state.getSector() : null;
        boolean currentOnPlanet = current != null && current.planet == planet;
        int currentId = currentOnPlanet ? current.id : -1;

        Stock stock = loadStock(planet);

        int[] owed = new int[node.requirements.length];
        Seq<String> missing = new Seq<>();

        for(int i = 0; i < node.requirements.length; i++){
            ItemStack requirement = node.requirements[i];
            owed[i] = Math.max(requirement.amount - node.finishedRequirements[i].amount, 0);
            if(owed[i] == 0) continue;

            int have = stock(stock, current, currentOnPlanet, currentId, requirement.item);
            if(have < owed[i]) missing.add(label(requirement.item) + " " + have + "/" + owed[i]);
        }

        if(!missing.isEmpty()){
            reply(player, "[scarlet]Не хватает ресурсов: [white]" + String.join(", ", missing));
            return null;
        }

        Seq<String> spent = new Seq<>();

        for(int i = 0; i < node.requirements.length; i++){
            if(owed[i] == 0) continue;
            ItemStack requirement = node.requirements[i];
            int remaining = owed[i];

            //other base sectors first
            for(Integer id : stock.bases){
                if(remaining <= 0) break;
                if(id == currentId) continue;

                SectorInfo info = stock.infos.get(id);
                int take = Math.min(Math.max(info.items.get(requirement.item), 0), remaining);
                if(take > 0){
                    info.items.add(requirement.item, -take);
                    remaining -= take;
                }
            }

            //then the sector being played
            if(remaining > 0 && currentOnPlanet){
                int take = Math.min(Math.max(current.items().get(requirement.item), 0), remaining);
                if(take > 0){
                    current.removeItem(requirement.item, take);
                    remaining -= take;
                }
            }

            if(remaining > 0){
                reply(player, "[scarlet]Не удалось списать ресурсы, исследование отменено.");
                return null;
            }

            node.finishedRequirements[i].amount = requirement.amount;
            spent.add(label(requirement.item) + " " + owed[i]);
        }

        //write the changed sector infos back to the campaign store
        for(Integer id : stock.bases){
            if(id == currentId) continue;
            Fi file = stock.files.get(id);
            if(file != null) SectorRunner.writeAtomic(file, JsonIO.write(stock.infos.get(id)));
        }

        return String.join(", ", spent);
    }

    private static int stock(Stock stock, Sector current, boolean currentOnPlanet, int currentId, Item item){
        int total = 0;

        for(Integer id : stock.bases){
            if(id == currentId) continue;
            total += Math.max(stock.infos.get(id).items.get(item), 0);
        }

        if(currentOnPlanet){
            total += Math.max(current.items().get(item), 0);
        }

        return total;
    }

    private static @Nullable Planet planetOf(TechNode node){
        TechNode root = node.rootNode != null ? node.rootNode : node;
        for(Planet planet : Vars.content.planets()){
            if(planet.techTree == root) return planet;
        }
        return null;
    }

    private static @Nullable TechNode find(String name){
        String key = normalize(name);
        if(key.isEmpty()) return null;

        for(TechNode node : TechTree.all){
            if(normalize(node.content.name).equals(key) || normalize(node.content.localizedName).equals(key)){
                return node;
            }
        }
        return null;
    }

    private static void reply(@Nullable Player player, String message){
        if(player != null){
            player.sendMessage(message);
        }else{
            Log.info("[mp-sectors] @", message.replaceAll("\\[[^\\]]*\\]", ""));
        }
    }

    private static String normalize(String value){
        return value == null ? "" : value.toLowerCase().replace(" ", "").replace("-", "");
    }

    /** A dedicated server has no locale bundles, so localizedName is the raw key there. */
    private static String label(UnlockableContent content){
        String localized = content.localizedName;
        return localized == null || localized.contains(".") ? content.name : localized;
    }

    private static String describe(Objective objective){
        if(objective instanceof Objectives.Research research) return "исследовать " + label(research.content);
        if(objective instanceof Objectives.Produce produce) return "произвести " + label(produce.content);
        if(objective instanceof Objectives.SectorComplete complete) return "пройти сектор " + complete.preset.name;
        if(objective instanceof Objectives.OnSector onSector) return "высадиться на " + onSector.preset.name;
        if(objective instanceof Objectives.OnPlanet onPlanet) return "прилететь на " + onPlanet.planet.name;
        return objective.getClass().getSimpleName();
    }
}


