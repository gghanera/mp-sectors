package mpsectors;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.Player;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Steam networking. Only active when the server process runs with the Steam desktop jar
 * on its classpath and the Steam client is running. Everything is reflective so a normal
 * (non-Steam) server keeps working without the desktop jar.
 */
public class SteamSupport{

    private static boolean active;
    private static Object sNet;
    private static Class<?> sNetClass;
    private static Method runCallbacks;
    private static long lastLobby;
    private static Player dummy;

    public static boolean init(){
        if(active) return true;

        try{
            Class<?> api = Class.forName("steamworks.SteamAPI");
            api.getMethod("loadLibraries").invoke(null);

            if(!(boolean)api.getMethod("init").invoke(null)){
                Log.warn("[mp-sectors] Steam client is not running, using TCP/UDP only.");
                return false;
            }

            Class<?> svars = Class.forName("mindustry.desktop.steam.SVars");
            sNetClass = Class.forName("mindustry.desktop.steam.SNet");

            //same objects DesktopLauncher.initSteam() creates
            svars.getField("net").set(null, sNetClass.getConstructor(Net.NetProvider.class).newInstance(new ArcNetProvider()));
            Class<?> sUser = Class.forName("mindustry.desktop.steam.SUser");
            svars.getField("user").set(null, sUser.getConstructor().newInstance());

            sNet = svars.getField("net").get(null);
            Vars.net = new Net((Net.NetProvider)sNet);
            Vars.steam = true;

            runCallbacks = api.getMethod("runCallbacks");
            active = true;
            Log.info("[mp-sectors] Steam networking enabled.");
            return true;
        }catch(Throwable t){
            Log.warn("[mp-sectors] Steam networking unavailable: @", t.toString());
            return false;
        }
    }

    public static boolean active(){
        return active;
    }

    public static void update(){
        if(!active || runCallbacks == null) return;

        Player old = Vars.player;
        if(old == null) Vars.player = dummy();

        try{
            runCallbacks.invoke(null);
        }catch(Throwable ignored){
        }finally{
            Vars.player = old;
        }

        long lobby = lobbyHandle();
        if(lobby != 0 && lobby != lastLobby){
            lastLobby = lobby;
            fillLobbyData(lobby);
        }
    }

    private static Player dummy(){
        if(dummy == null){
            dummy = Player.create();
            dummy.name = "Server";
        }
        return dummy;
    }

    public static long lobbyHandle(){
        if(!active) return 0;

        try{
            Field field = sNetClass.getDeclaredField("currentLobby");
            field.setAccessible(true);
            Object lobby = field.get(sNet);
            return lobby == null ? 0 : (long)lobby.getClass().getMethod("handle").invoke(lobby);
        }catch(Throwable t){
            return 0;
        }
    }

    public static long steamId(){
        if(!active) return 0;

        try{
            Class<?> svars = Class.forName("mindustry.desktop.steam.SVars");
            Object user = svars.getField("user").get(null);
            if(user == null) return 0;

            Object sUser = user.getClass().getField("user").get(user);
            if(sUser == null) return 0;

            Object id = sUser.getClass().getMethod("getSteamID").invoke(sUser);
            return ((Number)id.getClass().getMethod("getAccountID").invoke(id)).longValue();
        }catch(Throwable t){
            return 0;
        }
    }

    /** SNet's own onLobbyCreated uses Vars.player, which is null on a headless server. */
    private static void fillLobbyData(long handle){
        try{
            Field smatField = sNetClass.getField("smat");
            Object smat = smatField.get(sNet);

            Field lobbyField = sNetClass.getDeclaredField("currentLobby");
            lobbyField.setAccessible(true);
            Object lobby = lobbyField.get(sNet);
            if(lobby == null) return;

            Class<?> steamID = Class.forName("steamworks.SteamID");
            Method set = smat.getClass().getMethod("setLobbyData", steamID, String.class, String.class);

            set.invoke(smat, lobby, "name", "MP Campaign");
            set.invoke(smat, lobby, "version", Version.build + "");
            set.invoke(smat, lobby, "versionType", Version.type);
            set.invoke(smat, lobby, "mapname", Vars.state.map.name());
            set.invoke(smat, lobby, "wave", Vars.state.wave + "");
            set.invoke(smat, lobby, "gamemode", "survival");
            set.invoke(smat, lobby, "hidden", "false");
            set.invoke(smat, lobby, "banned", "");

            Log.info("[mp-sectors] Steam lobby ready: @", handle);
        }catch(Throwable t){
            Log.warn("[mp-sectors] could not fill Steam lobby data: @", t.toString());
        }
    }

    public static void shutdown(){
        if(!active) return;
        try{
            Class.forName("steamworks.SteamAPI").getMethod("shutdown").invoke(null);
        }catch(Throwable ignored){
        }
        active = false;
    }
}
