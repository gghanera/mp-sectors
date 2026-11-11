package mpsectors;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.Jval;

import java.util.ArrayList;
import java.util.List;

/**
 * Campaign-wide bans shared by the coordinator and every sector instance.
 *
 * The file lives in campaign/bans.json, so a ban made in one sector applies everywhere and
 * survives a coordinator restart. Like Moderators, it is reloaded when the file mtime changes.
 * Old files that contain a plain string array are still read.
 */
public final class Bans{

    public static class BanEntry{
        public String name = "", reason = "", by = "", uuid = "";
        public long created, expires;

        public boolean active(){
            return expires <= 0 || expires > System.currentTimeMillis();
        }

        public boolean matches(String otherName, String otherUuid){
            if(otherName != null && !otherName.isEmpty() && otherName.equalsIgnoreCase(name)) return true;
            return otherUuid != null && !otherUuid.isEmpty() && otherUuid.equals(uuid);
        }
    }

    private static Fi file;
    private static final List<BanEntry> entries = new ArrayList<>();
    private static boolean loaded;
    private static long lastModified;

    private Bans(){
    }

    public static synchronized void init(Fi campaign){
        file = campaign.child("bans.json");
        loaded = false;
        entries.clear();
        load();
    }

    private static void load(){
        if(loaded) return;
        loaded = true;
        lastModified = file == null ? 0 : file.lastModified();

        if(file == null || !file.exists()) return;

        try{
            Jval root = Jval.read(file.readString());
            if(root == null || !root.isArray()) return;

            for(Jval value : root.asArray()){
                BanEntry entry = new BanEntry();
                if(value.isString()){
                    entry.name = value.asString();
                }else{
                    entry.name = value.getString("name", "");
                    entry.reason = value.getString("reason", "");
                    entry.by = value.getString("by", "");
                    entry.uuid = value.getString("uuid", "");
                    entry.created = value.getLong("created", 0L);
                    entry.expires = value.getLong("expires", 0L);
                }
                if(entry.name != null && !entry.name.isBlank()) merge(entry);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not read bans.json", t);
        }finally{
            changed = false;
        }
    }

    private static void reloadIfChanged(){
        if(file == null) return;

        long modified = file.lastModified();
        if(modified != lastModified){
            loaded = false;
            entries.clear();
            load();
        }
    }

    public static synchronized boolean isBanned(String name){
        return isBanned(name, null);
    }

    public static synchronized boolean isBanned(String name, String uuid){
        return entry(name, uuid) != null;
    }

    /** @return the active ban for this player, or null. */
    public static synchronized @Nullable BanEntry entry(String name, String uuid){
        reloadIfChanged();
        return find(name, uuid);
    }

    public static synchronized @Nullable BanEntry entry(String name){
        return entry(name, null);
    }

    private static @Nullable BanEntry find(String name, String uuid){
        for(BanEntry entry : entries){
            if(entry.active() && entry.matches(name, uuid)) return entry;
        }
        return null;
    }

    private static @Nullable BanEntry findAny(String name, String uuid){
        for(BanEntry entry : entries){
            if(entry.matches(name, uuid)) return entry;
        }
        return null;
    }

    public static synchronized String[] list(){
        reloadIfChanged();
        List<String> names = new ArrayList<>();
        for(BanEntry entry : entries){
            if(entry.active()) names.add(entry.name);
        }
        return names.toArray(new String[0]);
    }

    public static synchronized BanEntry[] entries(){
        reloadIfChanged();
        List<BanEntry> active = new ArrayList<>();
        for(BanEntry entry : entries){
            if(entry.active()) active.add(entry);
        }
        return active.toArray(new BanEntry[0]);
    }

    public static synchronized boolean add(String name){
        return add(name, null, "", "", 0);
    }

    public static synchronized boolean add(String name, String reason, String by, long expires){
        return add(name, null, reason, by, expires);
    }

    /** @return false if the name/uuid was already banned (the entry is still updated). */
    public static synchronized boolean add(String name, String uuid, String reason, String by, long expires){
        if(name == null || name.isBlank()) return false;

        //the coordinator and every instance write this file; merge before changing it
        reloadIfChanged();

        BanEntry entry = new BanEntry();
        entry.name = name.trim();
        entry.uuid = uuid == null ? "" : uuid;
        entry.reason = reason == null ? "" : reason.trim();
        entry.by = by == null ? "" : by.trim();
        entry.created = System.currentTimeMillis();
        entry.expires = expires;

        boolean added = merge(entry);
        if(added || changed) save();
        return added;
    }

    /** Adds a UUID to an existing name ban without creating a new one. */
    public static synchronized void enrich(String name, String uuid){
        if(name == null || name.isBlank() || uuid == null || uuid.isEmpty()) return;

        reloadIfChanged();
        BanEntry entry = findAny(name, uuid);
        if(entry == null || !entry.uuid.isEmpty() || entry.uuid.equals(uuid)) return;

        entry.uuid = uuid;
        save();
    }

    private static boolean changed;

    /** Merges a freshly read or created entry into the list. */
    private static boolean merge(BanEntry incoming){
        BanEntry existing = findAny(incoming.name, incoming.uuid);
        if(existing == null){
            entries.add(incoming);
            changed = true;
            return true;
        }

        if(!incoming.reason.isEmpty() && !incoming.reason.equals(existing.reason)){
            existing.reason = incoming.reason;
            changed = true;
        }
        if(!incoming.by.isEmpty() && !incoming.by.equals(existing.by)){
            existing.by = incoming.by;
            changed = true;
        }
        if(!incoming.uuid.isEmpty() && !incoming.uuid.equals(existing.uuid)){
            existing.uuid = incoming.uuid;
            changed = true;
        }
        if(!existing.active()){
            //a new ban after an expired one replaces its reason and duration
            existing.created = incoming.created;
            existing.expires = incoming.expires;
            changed = true;
        }else if(incoming.expires > existing.expires){
            existing.expires = incoming.expires;
            changed = true;
        }
        return false;
    }

    /** @return false if the name was not banned. */
    public static synchronized boolean remove(String name){
        if(name == null) return false;

        reloadIfChanged();
        for(int i = 0; i < entries.size(); i++){
            if(entries.get(i).name.equalsIgnoreCase(name)){
                entries.remove(i);
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
        boolean first = true;
        for(BanEntry entry : entries){
            if(!first) json.append(',');
            first = false;
            json.append("{\"name\":\"").append(Coordinator.esc(entry.name)).append("\",")
                .append("\"reason\":\"").append(Coordinator.esc(entry.reason)).append("\",")
                .append("\"by\":\"").append(Coordinator.esc(entry.by)).append("\",")
                .append("\"uuid\":\"").append(Coordinator.esc(entry.uuid)).append("\",")
                .append("\"created\":").append(entry.created).append(',')
                .append("\"expires\":").append(entry.expires).append('}');
        }
        json.append(']');

        try{
            SectorRunner.writeAtomic(file, json.toString());
            lastModified = file.lastModified();
            changed = false;
        }catch(Throwable t){
            Log.err("[mp-sectors] could not write bans.json", t);
        }
    }
}
