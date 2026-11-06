package mpcampaign;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.io.JsonIO;

/**
 * Starts/stops the campaign coordinator (mp-sectors) from inside the game.
 *
 * The coordinator is a normal headless Mindustry process; this class prepares its folder (mods,
 * config, campaign) and runs it with the game's own JRE.
 */
public class ServerProcess{

    public static class Config{
        public String campaign = "";
        public int httpPort = 8090;
        public int gamePort = 6567;
        public int maxSectors = 5;
        public int maxPlayers = 10;
        public int idleStopSeconds = 60;
        public int autosaveSeconds = 60;
        public int playerLimit = 5;
        public int instanceBasePort = 6570;
        public int memoryMb = 1024;
        public boolean allowDuplicateClients = true;
        public String serverJar = "";
        public String javaPath = "";
        public boolean steam;
        public String steamJar = "";
        public String steamNative = "";
        public String snapshotDir = "";
    }

    private static Process process;
    private static long lastStatus;

    public static boolean statusOnline;
    public static int statusPlayers, statusSectors, statusMaxSectors = 5, statusMaxPlayers = 10;

    public static Fi serverDir(){
        String configured = Core.settings.getString("mpcampaign-server-dir", "");
        if(configured != null && !configured.isEmpty()) return Core.files.absolute(configured);

        Fi desktop = Core.files.absolute(System.getProperty("user.home") + "/Desktop/MP-Sectors");
        if(desktop.exists()) return desktop;

        return Core.settings.getDataDirectory().child("mp-sectors");
    }

    public static Fi coordinatorDir(){
        return serverDir().child("coordinator");
    }

    public static Fi serverJar(){
        String configured = Core.settings.getString("mpcampaign-server-jar", "");
        if(configured != null && !configured.isEmpty()) return Core.files.absolute(configured);
        return serverDir().child("libs/server-release.jar");
    }

    public static Fi campaign(){
        String configured = Core.settings.getString("mpcampaign-campaign", "");
        if(configured != null && !configured.isEmpty()) return Core.files.absolute(configured);
        return serverDir().child("campaign");
    }

    public static String javaPath(){
        String configured = Core.settings.getString("mpcampaign-java", "");
        if(configured != null && !configured.isEmpty() && !isGameJre(configured)) return configured;
        return findJava();
    }

    /** The JRE bundled with the Steam build is a trimmed jlink image without java.logging. */
    private static boolean isGameJre(String path){
        return path.replace('\\', '/').toLowerCase().contains("/jre/bin/java");
    }

    /** Finds a full JDK/JRE: the headless server needs java.logging, which the bundled JRE lacks. */
    private static String findJava(){
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        String home = System.getenv("JAVA_HOME");
        if(home != null && !home.isEmpty()){
            Fi candidate = Core.files.absolute(home).child("bin").child(windows ? "java.exe" : "java");
            if(candidate.exists() && !isGameJre(candidate.absolutePath())) return candidate.absolutePath();
        }

        try{
            ProcessBuilder builder = new ProcessBuilder(windows ? "where" : "which", "java");
            builder.redirectErrorStream(true);
            Process p = builder.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for(String line : output.split("\\R")){
                String path = line.trim();
                if(!path.isEmpty() && Core.files.absolute(path).exists() && !isGameJre(path)) return path;
            }
        }catch(Throwable ignored){
        }

        return Vars.javaPath;
    }

    public static int setting(String key, int def){
        return Core.settings.getInt(key, def);
    }

    public static boolean running(){
        return process != null && process.isAlive();
    }

    public static String apiUrl(){
        return "http://127.0.0.1:" + setting("mpcampaign-http-port", 8090);
    }

    /** Where campaign snapshots are stored; F: when it exists, otherwise next to the package. */
    public static String snapshotDir(){
        String configured = Core.settings.getString("mpcampaign-snapshot-dir", "");
        if(configured != null && !configured.isEmpty()) return configured;

        return new java.io.File("F:/").exists()
            ? "F:/MP-Sectors/backups"
            : serverDir().child("backups").absolutePath();
    }

    /** @return null on success, an error message otherwise. */
    public static String start(){
        if(running()) return "Сервер уже запущен.";

        Fi dir = serverDir();
        Fi coord = coordinatorDir();
        Fi mods = coord.child("config/mods");
        mods.mkdirs();
        coord.child("config/saves").mkdirs();

        Fi serverMod = dir.child("mods/mp-sectors.jar");
        if(!serverMod.exists()) serverMod = dir.child("build/mp-sectors.jar");
        if(!serverMod.exists()) return "Не найден mp-sectors.jar в " + dir.absolutePath();

        Fi jar = serverJar();
        if(!jar.exists()) return "Не найден серверный jar: " + jar.absolutePath();

        Fi clientMod = null;
        if(Vars.mods.getMod("mp-campaign") != null) clientMod = Vars.mods.getMod("mp-campaign").file;
        if(clientMod == null || !clientMod.exists()) clientMod = dir.child("mods/mp-campaign.jar");
        if(clientMod == null || !clientMod.exists()) return "Не найден mp-campaign.jar.";

        try{
            copy(serverMod, mods.child("mp-sectors.jar"));
            copy(clientMod, mods.child("mp-campaign.jar"));

            Config config = new Config();
            config.campaign = campaign().absolutePath().replace('\\', '/');
            config.snapshotDir = snapshotDir().replace('\\', '/');
            config.httpPort = setting("mpcampaign-http-port", 8090);
            config.gamePort = setting("mpcampaign-game-port", 6567);
            config.maxSectors = setting("mpcampaign-max-sectors", 5);
            config.maxPlayers = setting("mpcampaign-max-players", 10);
            config.idleStopSeconds = setting("mpcampaign-idle-stop", 60);
            config.playerLimit = setting("mpcampaign-player-limit", 5);
            config.instanceBasePort = setting("mpcampaign-instance-port", 6570);
            config.memoryMb = setting("mpcampaign-memory", 1024);
            config.allowDuplicateClients = Core.settings.getBool("mpcampaign-allow-duplicates", true);
            config.serverJar = jar.absolutePath().replace('\\', '/');
            config.javaPath = javaPath();

            //Steam build: the dedicated server gets Steam networking only when the desktop jar is on its classpath
            Fi gameDir = Core.settings.getDataDirectory().parent();
            Fi steamJar = gameDir.child("jre/desktop.jar");
            boolean steam = Vars.steam && steamJar.exists() && Core.settings.getBool("mpcampaign-steam", false);
            config.steam = steam;
            config.steamJar = steam ? steamJar.absolutePath().replace('\\', '/') : "";
            config.steamNative = steam ? gameDir.absolutePath().replace('\\', '/') : "";

            if(isGameJre(config.javaPath)){
                return "Нужен полный JDK/JRE: встроенная Java игры не содержит java.logging. Укажи путь в поле Java.";
            }

            coord.child("config/mp-coordinator.json").writeString(JsonIO.write(config));

            ProcessBuilder builder;
            if(steam){
                coord.child("steam_appid.txt").writeString("1127400");
                builder = new ProcessBuilder(
                    javaPath(), "-Xmx" + config.memoryMb + "m",
                    "-Djava.library.path=" + gameDir.absolutePath(),
                    "-cp", jar.absolutePath() + java.io.File.pathSeparator + steamJar.absolutePath(),
                    "mindustry.server.ServerLauncher"
                );
            }else{
                builder = new ProcessBuilder(javaPath(), "-Xmx" + config.memoryMb + "m", "-jar", jar.absolutePath());
            }
            builder.directory(coord.file());
            builder.redirectOutput(coord.child("server.log").file());
            builder.redirectError(coord.child("server.err.log").file());
            process = builder.start();
            coord.child("coordinator.pid").writeString(String.valueOf(process.pid()));

            Core.settings.put(MpCampaignMod.urlKey, "http://127.0.0.1:" + config.httpPort);
            Log.info("[mp-campaign] coordinator started, api http://127.0.0.1:@", config.httpPort);
            return null;
        }catch(Throwable t){
            Log.err("[mp-campaign] could not start the coordinator", t);
            return "Ошибка запуска: " + t.getMessage();
        }
    }

    public static void stop(){
        if(!running()) return;

        try{
            coordinatorDir().child("stop.flag").writeString("stop");
        }catch(Throwable ignored){
        }

        Process current = process;
        Thread thread = new Thread(() -> {
            try{
                for(int i = 0; i < 120 && current.isAlive(); i++){
                    Thread.sleep(250);
                }
                if(current.isAlive()) current.destroyForcibly();
            }catch(Throwable ignored){
            }
        }, "mp-campaign-stop");
        thread.setDaemon(true);
        thread.start();
    }

    public static void refreshStatus(){
        if(!running()){
            statusOnline = false;
            statusPlayers = 0;
            statusSectors = 0;
            return;
        }
        if(System.currentTimeMillis() - lastStatus < 2000) return;
        lastStatus = System.currentTimeMillis();

        Http.get(apiUrl() + "/status", 5000, json -> {
            if(json == null){
                statusOnline = false;
                return;
            }
            statusOnline = true;
            statusPlayers = num(json, "players");
            statusSectors = num(json, "sectors");
            statusMaxSectors = num(json, "maxSectors");
            statusMaxPlayers = num(json, "maxPlayers");
        });
    }

    private static int num(Jval value, String key){
        Jval out = value.get(key);
        return out == null || out.isNull() ? 0 : out.asInt();
    }

    private static void copy(Fi from, Fi to){
        to.parent().mkdirs();
        if(to.exists()) to.delete();
        from.copyTo(to);
    }
}

