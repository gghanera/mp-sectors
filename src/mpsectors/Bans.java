package mpsectors;

import arc.files.Fi;
import arc.util.Log;
import mindustry.io.JsonIO;

import java.util.ArrayList;
import java.util.List;

/**
 * Campaign-wide bans shared by the coordinator and every sector instance.
 *
 * The file lives in campaign/bans.json, so a ban made in one sector applies everywhere and
 * survives a coordinator restart. Like Moderators, it is reloaded when the file mtime changes.
 */
public final class Bans{

    private static Fi file;
    private static final List<String> names = new ArrayList<>();
    private static boolean loaded;
    private static long lastModified;

    private Bans(){
    }

    public static synchronized void init(Fi campaign){
        file = campaign.child("bans.json");
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
                Log.err("[mp-sectors] could not read bans.json", t);
            }
        }
    }

    private static void reloadIfChanged(){
        if(file == null) return;

        long modified = file.lastModified();
        if(modified != lastModified){
            loaded = false;
            names.clear();
            load();
        }
    }

    public static synchronized boolean isBanned(String name){
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

    /** @return false if the name was already banned. */
    public static synchronized boolean add(String name){
        if(name == null || name.isBlank() || contains(name)) return false;
        names.add(name.trim());
        save();
        return true;
    }

    /** @return false if the name was not banned. */
    public static synchronized boolean remove(String name){
        if(name == null) return false;

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
