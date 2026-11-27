package mpcampaign;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.io.JsonIO;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        public String planet = "serpulo";
        public String moderator = "";
        public String serverJar = "";
        public String javaPath = "";
        public boolean steam;
        public String steamJar = "";
        public String steamNative = "";
        public String snapshotDir = "";
        public boolean updateCheck = true;
        public String httpToken = "";
        public String publicHost = "";
    }

    private static volatile Process process;
    private static long lastStatus;

    /** Launched, but /status has not answered yet. */
    public static volatile boolean starting;
    /** Why the last start failed; cleared on the next start/stop. Shown by ServerDialog. */
    public static volatile String startError = "";

    public static volatile boolean statusOnline;
    public static volatile int statusPlayers, statusSectors, statusMaxSectors = 5, statusMaxPlayers = 10;

    /** pid file cache: running() is polled every frame, reading the file every frame is silly. */
    private static long pidCheckTime, pidCheckValue;

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
        String found = findJava();
        return found == null || found.isEmpty() ? "java" : found;
    }

    /** The launcher must run a Java with java.logging; the game's bundled jlink image has no such module. */
    private static boolean isGameJre(String path){
        return path != null && !path.isEmpty() && !hasJavaLogging(path);
    }

    private static String moduleCheckPath = "";
    private static boolean moduleCheckHasLogging;

    /** `java --list-modules` is the only reliable check: a full JDK may live in a folder named jre. */
    private static boolean hasJavaLogging(String javaExe){
        if(!javaExe.equals(moduleCheckPath)){
            moduleCheckHasLogging = checkJavaLogging(javaExe);
            moduleCheckPath = javaExe;
        }
        return moduleCheckHasLogging;
    }

    private static boolean checkJavaLogging(String javaExe){
        try{
            Process probe = new ProcessBuilder(javaExe, "--list-modules").redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try(InputStream in = probe.getInputStream()){
                    in.transferTo(out);
                }catch(Throwable ignored){
                }
            }, "mp-java-probe");
            reader.setDaemon(true);
            reader.start();

            if(!probe.waitFor(15, TimeUnit.SECONDS)){
                probe.destroyForcibly();
                return true;
            }
            reader.join(1000);
            String output = out.toString(java.nio.charset.StandardCharsets.UTF_8);
            return probe.exitValue() == 0 && output.contains("java.logging");
        }catch(Throwable t){
            //unreadable path or a non-Java file: let the real start attempt report the error
            return true;
        }
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
            if(!p.waitFor(5, TimeUnit.SECONDS)){
                p.destroyForcibly();
                return Vars.javaPath;
            }
            String output = new String(p.getInputStream().readAllBytes());
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

    /**
     * Whether a coordinator is running. `process` only exists in the session that started it;
     * after a game restart the pid file is the only proof, and without it Start would launch a
     * second coordinator on the same ports and campaign.
     */
    public static boolean running(){
        if(process != null && process.isAlive()) return true;
        return livePid() > 0;
    }

    private static long livePid(){
        long now = System.currentTimeMillis();
        if(now - pidCheckTime < 5000L) return pidCheckValue;

        pidCheckTime = now;
        pidCheckValue = checkPid(storedPid());
        return pidCheckValue;
    }

    /** the pid the last launcher wrote, or 0 */
    private static long storedPid(){
        try{
            Fi file = coordinatorDir().child("coordinator.pid");
            if(!file.exists()) return 0;
            return Long.parseLong(file.readString().trim());
        }catch(Throwable t){
            return 0;
        }
    }

    /** @return the pid when it is alive and looks like our coordinator, otherwise 0 (pid reuse). */
    private static long checkPid(long pid){
        if(pid <= 0) return 0;

        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if(handle == null || !handle.isAlive()) return 0;

        String command = handle.info().commandLine().orElse("").replace('\\', '/').toLowerCase();
        if(command.isEmpty()){
            //unreadable (other user/elevation): trust it only when the coordinator answers
            return statusAnswers(setting("mpcampaign-http-port", 8090)) ? pid : 0;
        }

        //reject a reused pid: the command must look like the coordinator we launched
        if(command.contains("mindustry") || command.contains("server-release") || command.contains("serverlauncher")) return pid;
        String jar = serverJar().absolutePath().replace('\\', '/').toLowerCase();
        return command.contains(jar) ? pid : 0;
    }

    private static boolean isAlive(long pid){
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Kills the coordinator and every sector instance it started (a forced stop skips its hook). */
    private static void killTree(long pid){
        ProcessHandle root = ProcessHandle.of(pid).orElse(null);
        if(root == null) return;

        //capture children before the parent dies, otherwise the handles are gone
        List<ProcessHandle> children = root.descendants().toList();
        for(ProcessHandle child : children){
            try{
                child.destroyForcibly();
            }catch(Throwable ignored){
            }
        }
        try{
            root.destroyForcibly();
        }catch(Throwable ignored){
        }
    }

    private static void clearPid(long pid){
        try{
            Fi file = coordinatorDir().child("coordinator.pid");
            if(file.exists() && file.readString().trim().equals(Long.toString(pid))) file.delete();
        }catch(Throwable ignored){
        }
    }

    private static void resetStatus(){
        statusOnline = false;
        statusPlayers = 0;
        statusSectors = 0;
    }

    private static boolean portOpen(int port){
        try(Socket socket = new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    /** Reads /status directly: this runs off the game thread, so it cannot use Http's callback. */
    private static boolean statusAnswers(int port){
        HttpURLConnection connection = null;
        try{
            connection = (HttpURLConnection)new URL("http://127.0.0.1:" + port + "/status").openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            return connection.getResponseCode() == 200;
        }catch(Throwable t){
            return false;
        }finally{
            if(connection != null) connection.disconnect();
        }
    }

    private static void notify(String message){
        if(Vars.headless || Vars.ui == null) return;
        Core.app.post(() -> Vars.ui.showInfoFade("[scarlet]" + message));
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

        int httpPort = setting("mpcampaign-http-port", 8090);
        //no pid file (e.g. the coordinator was started by the script) but something answers there
        if(portOpen(httpPort)){
            return "Порт " + httpPort + " уже занят: координатор, вероятно, уже запущен.";
        }

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
            config.planet = Core.settings.getString("mpcampaign-planet", "serpulo");
            //a brand-new campaign needs a moderator; the host is the one pressing the button
            config.moderator = MpCampaignMod.localName();
            config.serverJar = jar.absolutePath().replace('\\', '/');
            config.javaPath = javaPath();

            //preserve hand-set coordinator options the launcher does not own
            Fi cfgFile = coord.child("config/mp-coordinator.json");
            if(cfgFile.exists()){
                try{
                    Config old = JsonIO.read(Config.class, cfgFile.readString());
                    if(old != null){
                        config.updateCheck = old.updateCheck;
                        config.httpToken = old.httpToken == null ? "" : old.httpToken;
                        config.publicHost = old.publicHost == null ? "" : old.publicHost;
                    }
                }catch(Throwable ignored){
                }
            }

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
            Core.settings.put("mpcampaign-url-manual", false);
            Core.settings.put("mpcampaign-token", config.httpToken == null ? "" : config.httpToken);
            startError = "";
            starting = true;
            resetStatus();
            Log.info("[mp-campaign] coordinator started, api http://127.0.0.1:@", config.httpPort);

            //catch a wrong Java / bad config right here; the longer /status wait runs in the
            //background so the game thread is not frozen for the whole startup
            String failure = awaitLaunch(process, config.httpPort);
            return failure;
        }catch(Throwable t){
            Log.err("[mp-campaign] could not start the coordinator", t);
            starting = false;
            return "Ошибка запуска: " + t.getMessage();
        }
    }

    /**
     * Immediate death check (wrong Java, missing native, occupied port all exit at once), then a
     * 30 s background wait for /status. A process that is alive after ~1.5 s is reported as started.
     */
    private static String awaitLaunch(Process launched, int httpPort){
        long deadline = System.currentTimeMillis() + 1500L;
        while(System.currentTimeMillis() < deadline && launched.isAlive()){
            try{
                Thread.sleep(100L);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }
        }

        if(!launched.isAlive()){
            starting = false;
            process = null;
            clearPid(launched.pid());
            startError = "Координатор завершился при запуске" + exitHint(launched);
            Log.err("[mp-campaign] @", startError);
            return startError;
        }

        Thread thread = new Thread(() -> {
            boolean ready = false;
            long until = System.currentTimeMillis() + 30000L;

            while(System.currentTimeMillis() < until && launched.isAlive()){
                if(statusAnswers(httpPort)){
                    ready = true;
                    break;
                }
                try{
                    Thread.sleep(500L);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            starting = false;
            if(ready){
                statusOnline = true;
                return;
            }
            if(launched.isAlive()){
                startError = "Координатор запущен, но /status не отвечает за 30 с. Смотри server.log.";
            }else{
                process = null;
                clearPid(launched.pid());
                startError = "Координатор завершился при запуске" + exitHint(launched);
            }
            Log.err("[mp-campaign] @", startError);
            notify(startError);
        }, "mp-campaign-ready");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    private static String exitHint(Process finished){
        try{
            if(finished.waitFor(1, TimeUnit.SECONDS)) return " (код " + finished.exitValue() + "). Смотри server.err.log.";
        }catch(Throwable ignored){
        }
        return ". Смотри server.err.log.";
    }

    public static void stop(){
        //a coordinator from a previous session is not in `process`; the pid file is the handle
        long pid = process != null && process.isAlive() ? process.pid() : checkPid(storedPid());
        if(pid <= 0){
            process = null;
            starting = false;
            resetStatus();
            return;
        }

        try{
            coordinatorDir().child("stop.flag").writeString("stop");
        }catch(Throwable ignored){
        }

        //the status line must not keep claiming a live server while the shutdown runs
        starting = false;
        startError = "";
        resetStatus();

        Thread thread = new Thread(() -> {
            try{
                for(int i = 0; i < 120 && isAlive(pid); i++){
                    Thread.sleep(250);
                }
                if(isAlive(pid)){
                    //a forced stop skips the coordinator's hook: its sector instances would stay
                    //alive and keep the ports and saves, so kill the whole tree
                    killTree(pid);
                }
                clearPid(pid);
            }catch(Throwable ignored){
            }finally{
                if(process != null && !process.isAlive()) process = null;
                pidCheckTime = 0;
                resetStatus();
            }
        }, "mp-campaign-stop");
        thread.setDaemon(true);
        thread.start();
    }

    public static void refreshStatus(){
        if(starting || !running()){
            resetStatus();
            return;
        }
        if(System.currentTimeMillis() - lastStatus < 2000) return;
        lastStatus = System.currentTimeMillis();

        Http.get(apiUrl() + "/status", 5000, json -> {
            //stopped or crashed while the request was in flight
            if(json == null || starting || !running()){
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

