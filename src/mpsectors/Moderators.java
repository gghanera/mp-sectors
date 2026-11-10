package mpsectors;

import arc.files.Fi;
import arc.util.Log;
import mindustry.io.JsonIO;

import java.util.ArrayList;
import java.util.List;

/**
 * Campaign moderators: the players allowed to pause, change speed, abandon sectors and manage
 * snapshots. The list lives in campaign/moderators.json, so the coordinator and every sector
 * instance read the same file; the host is a moderator by default.
 */
public final class Moderators{

    private static final String defaultName = "GGhanera";

    private static Fi file;
    private static final List<String> names = new ArrayList<>();
    private static boolean loaded;
    private static long lastModified;

    private Moderators(){
    }

    public static synchronized void init(Fi campaign){
        file = campaign.child("moderators.json");
        loaded = false;
        names.clear();
        load();
    }

    private static void load(){
        if(loaded) return;
        loaded = true;
        lastModified = file == null ? 0 : file.lastModified();

        if(file != null && file.exists()){
            try{
                String[] read = JsonIO.read(String[].class, file.readString());
                if(read != null){
                    for(String name : read){
                        if(name != null && !name.isBlank() && !contains(name)) names.add(name.trim());
                    }
                }
            }catch(Throwable t){
                Log.err("[mp-sectors] could not read moderators.json", t);
            }
        }

        if(names.isEmpty()) names.add(defaultName);

        //create the file on first run so the host can edit it without guessing the format
        if(file != null && !file.exists()) save();
    }

    /** The host may edit moderators.json by hand; pick the change up without a restart. */
    private static void reloadIfChanged(){
        if(file == null) return;

        long modified = file.lastModified();
        if(modified != lastModified){
            loaded = false;
            names.clear();
            load();
        }
    }

    public static synchronized boolean isModerator(String name){
        if(name == null || name.isEmpty()) return false;
        reloadIfChanged();
        return contains(name);
    }

    private static boolean contains(String name){
        for(String other : names){
            if(other.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static synchronized String[] list(){
        reloadIfChanged();
        return names.toArray(new String[0]);
    }

    /** @return false if the name was already a moderator. */
    public static synchronized boolean add(String name){
        if(name == null || name.isBlank()) return false;

        //the coordinator and every instance write this file; merge before changing it
        reloadIfChanged();
        if(contains(name)) return false;

        names.add(name.trim());
        save();
        return true;
    }

    /** @return false if the name was not a moderator, or if it is the last one. */
    public static synchronized boolean remove(String name){
        if(name == null) return false;

        reloadIfChanged();
        if(names.size() <= 1) return false;

        for(int i = 0; i < names.size(); i++){
            if(names.get(i).equalsIgnoreCase(name)){
                names.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    private static void save(){
        if(file == null) return;
        file.parent().mkdirs();

        //plain JSON with quoted strings: the file is meant to be edited by hand
        StringBuilder json = new StringBuilder("[");
        for(int i = 0; i < names.size(); i++){
            if(i > 0) json.append(',');
            json.append('"').append(Coordinator.esc(names.get(i))).append('"');
        }
        json.append(']');

        SectorRunner.writeAtomic(file, json.toString());
        lastModified = file.lastModified();
    }
}
