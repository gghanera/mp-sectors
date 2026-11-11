package mpsectors;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.io.JsonIO;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Starts/stops one headless Mindustry process per sector and tracks who is inside.
 *
 * Each instance gets its own working directory (so its own config/saves/logs), a copy of every mod
 * from the coordinator's mods folder, and a generated config/mp-sectors.json. The instance writes
 * status.json (player names) every couple of seconds; that is the source of truth for player counts.
 */
public class InstanceManager{

    public static class Instance{
        public final String sector;
        public final int port;
        public final Fi dir;
        public Process process;
        public volatile int players;
        public volatile String[] playerNames = {};
        public volatile String steamHost = "";
        public volatile long steamId;
        public volatile boolean attacked;
        public volatile boolean statusSeen;
        public long emptySince;
        public long started;
        public boolean stopping;
        /** true once the instance is listening; set after the port opens, outside the manager lock */
        public volatile boolean ready;

        public Instance(String sector, int port, Fi dir){
            this.sector = sector;
            this.port = port;
            this.dir = dir;
        }
    }

    public static class Status{
        public String sector = "";
        public int count = 0;
        public String[] players = {};
        public int wave = 1;
        public String steamHost = "";
        public long steamId;
        public boolean attacked;
    }

    private final Coordinator.Config config;
    private final Cons<String> notify;
    private final Seq<Instance> instances = new Seq<>();
    private final ObjectMap<String, long[]> crashCounts = new ObjectMap<>();
    private long lastTick;

    public InstanceManager(Coordinator.Config config, Cons<String> notify){
        this.config = config;
        this.notify = notify;
    }

    public synchronized Seq<Instance> all(){
        return instances.copy();
    }

    public synchronized Instance get(String sector){
        for(Instance i : instances){
            if(i.sector.equals(sector)) return i;
        }
        return null;
    }

    public synchronized int runningCount(){
        int count = 0;
        for(Instance i : instances){
            if(!i.stopping) count++;
        }
        return count;
    }

    public synchronized int totalPlayers(){
        int sum = 0;
        for(Instance i : instances){
            //a stopping instance's status is stale; its players are already disconnecting
            if(!i.stopping) sum += i.players;
        }
        return sum;
    }

    public synchronized String[] playersOf(String sector){
        Instance i = get(sector);
        return i == null ? new String[0] : i.playerNames;
    }

    /** Starts the sector instance and blocks until it listens on its port. */
    public Instance start(String sector){
        return start(sector, "", "");
    }

    public Instance start(String sector, String loadout, String resources){
        Instance out;

        //a sector being stopped lingers in the list until its process exits; wait instead
        //of handing a dying instance to a player who lands during the stop
        long stopDeadline = System.currentTimeMillis() + 45_000L;
        while(true){
            synchronized(this){
                Instance existing = get(sector);
                if(existing == null) break;
                if(!existing.stopping) return existing;
            }

            if(System.currentTimeMillis() > stopDeadline){
                Log.err("[mp-sectors] sector @ did not stop in time", sector);
                return null;
            }
            try{
                Thread.sleep(500);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return null;
            }
        }

        try{
            synchronized(this){
                Instance existing = get(sector);
                if(existing != null) return existing;

                Fi dir = Core.files.local("instances").child(sector);
                Fi mods = dir.child("config/mods");
                mods.mkdirs();
                dir.child("config/saves").mkdirs();

                for(Fi mod : Vars.modDirectory.list()){
                    if(mod.extension().equals("jar") || mod.extension().equals("zip")){
                        SectorRunner.copy(mod, mods.child(mod.name()));
                    }
                }

                int port = nextPort();

                Coordinator.InstanceConfig cfg = new Coordinator.InstanceConfig();
                cfg.campaign = config.campaign;
                cfg.sector = sector;
                cfg.port = port;
                cfg.httpPort = config.httpPort;
                cfg.playerLimit = config.playerLimit;
                cfg.autosaveSeconds = config.autosaveSeconds;
                cfg.allowDuplicateClients = config.allowDuplicateClients;
                cfg.loadout = loadout == null ? "" : loadout;
                cfg.resources = resources == null ? "" : resources;
                cfg.steam = config.steam;
                cfg.steamJar = config.steamJar;
                cfg.steamNative = config.steamNative;
                SectorRunner.writeAtomic(dir.child("config/mp-sectors.json"), JsonIO.write(cfg));

                Fi log = dir.child("server.log");
                Fi err = dir.child("server.err.log");

                ProcessBuilder builder;
                if(config.steam && config.steamJar != null && !config.steamJar.isEmpty()){
                    dir.child("steam_appid.txt").writeString("1127400");
                    builder = new ProcessBuilder(
                        config.javaPath, "-Xmx" + config.memoryMb + "m",
                        "-Djava.library.path=" + config.steamNative,
                        "-cp", config.serverJar + java.io.File.pathSeparator + config.steamJar,
                        "mindustry.server.ServerLauncher"
                    );
                }else{
                    builder = new ProcessBuilder(config.javaPath, "-Xmx" + config.memoryMb + "m", "-jar", config.serverJar);
                }
                builder.directory(dir.file());
                builder.redirectOutput(log.file());
                builder.redirectError(err.file());
                builder.redirectInput(ProcessBuilder.Redirect.PIPE);

                //stale files from a previous run: a leftover stop flag would kill the new process
                dir.child("status.json").delete();
                dir.child("stop.flag").delete();
                dir.child("exit.reason").delete();

                out = new Instance(sector, port, dir);
                out.process = builder.start();
                out.started = System.currentTimeMillis();
                instances.add(out);

                Log.info("[mp-sectors] starting instance @ on port @ ...", sector, port);
            }
        }catch(Throwable t){
            Log.err("[mp-sectors] could not start instance @", sector, t);
            return null;
        }

        //wait outside the lock: the coordinator's game loop must keep running while a sector boots
        long deadline = System.currentTimeMillis() + 45_000L;
        while(System.currentTimeMillis() < deadline){
            if(out.process == null || !out.process.isAlive()) break;
            if(portOpen(out.port)){
                out.ready = true;
                out.emptySince = System.currentTimeMillis();
                return out;
            }
            try{
                Thread.sleep(300);
            }catch(InterruptedException e){
                break;
            }
        }

        Log.err("[mp-sectors] instance @ did not open port @ in time", sector, out.port);
        if(out.process != null) out.process.destroyForcibly();
        synchronized(this){
            instances.remove(out);
        }
        return null;
    }

    /** Waits until an instance started by another request is listening (two players landing together). */
    public Instance waitReady(Instance inst, long timeoutMs){
        long deadline = System.currentTimeMillis() + timeoutMs;

        while(System.currentTimeMillis() < deadline){
            if(inst.ready) return inst;
            if(inst.process != null && !inst.process.isAlive()) return null;

            try{
                Thread.sleep(200);
            }catch(InterruptedException e){
                return null;
            }
        }

        return inst.ready ? inst : null;
    }

    /** Called from the main thread; status files only change every 2 s, so once a second is enough. */
    public synchronized void tick(){
        long now = System.currentTimeMillis();
        if(now - lastTick < 1000L) return;
        lastTick = now;

        for(int i = instances.size - 1; i >= 0; i--){
            Instance inst = instances.get(i);

            if(inst.stopping) continue;

            if(inst.process != null && !inst.process.isAlive()){
                //start() is still waiting for the port and owns cleanup of a failed boot;
                //handling it here too would race the original start and restart without loadout
                if(!inst.ready) continue;

                String reason = readExitReason(inst);
                instances.remove(i);

                boolean expected = inst.stopping || "gameover".equals(reason)
                    || "stop".equals(reason) || "shutdown".equals(reason);
                if(expected){
                    Log.info("[mp-sectors] instance @ exited (@)", inst.sector, reason.isEmpty() ? "stopped" : reason);
                }else{
                    Log.err("[mp-sectors] instance @ crashed", inst.sector);
                    handleCrash(inst, now);
                }
                continue;
            }

            if(!inst.ready) continue;

            Status status = readStatus(inst);
            if(status != null){
                inst.players = status.count;
                inst.playerNames = status.players == null ? new String[0] : status.players;
                inst.steamHost = status.steamHost == null ? "" : status.steamHost;
                inst.steamId = status.steamId;
                inst.attacked = status.attacked;
                inst.statusSeen = true;
            }

            if(inst.players > 0){
                inst.emptySince = 0;
            }else{
                if(inst.emptySince == 0) inst.emptySince = now;
                if(config.idleStopSeconds > 0 && now - inst.emptySince > config.idleStopSeconds * 1000L){
                    Log.info("[mp-sectors] instance @ is empty, stopping", inst.sector);
                    stopAsync(inst);
                }
            }
        }
    }

    private String readExitReason(Instance inst){
        Fi file = inst.dir.child("exit.reason");
        if(!file.exists()) return "";
        try{
            String reason = file.readString().trim();
            file.delete();
            return reason;
        }catch(Throwable t){
            return "";
        }
    }

    /** Restarts a crashed instance, but no more than three times in ten minutes. */
    private void handleCrash(Instance inst, long now){
        long[] state = crashCounts.get(inst.sector);
        if(state == null || now - state[0] > 10L * 60L * 1000L){
            state = new long[]{now, 0};
            crashCounts.put(inst.sector, state);
        }
        state[1]++;

        if(state[1] > 3){
            if(notify != null) notify.get("Сектор " + inst.sector + " падает снова и снова; перезапуск остановлен.");
            return;
        }

        if(notify != null) notify.get("Сектор " + inst.sector + " упал, перезапускаю (" + state[1] + "/3)...");
        Thread thread = new Thread(() -> {
            Instance restarted = start(inst.sector);
            if(notify != null){
                notify.get(restarted == null
                    ? "Не удалось перезапустить сектор " + inst.sector + "."
                    : "Сектор " + inst.sector + " снова запущен.");
            }
        }, "mp-sectors-watchdog-" + inst.sector);
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops on a background thread: waiting for a process must never freeze the coordinator's game loop. */
    private void stopAsync(Instance inst){
        inst.stopping = true;

        Thread thread = new Thread(() -> {
            try{
                stop(inst);
            }finally{
                synchronized(this){
                    instances.remove(inst);
                }
            }
        }, "mp-sectors-stop-" + inst.sector);
        thread.setDaemon(true);
        thread.start();
    }

    public void stop(String sector){
        Instance inst;
        synchronized(this){
            inst = get(sector);
            if(inst == null) return;

            //keep the instance in the list while it saves and exits: start() must wait for it,
            //otherwise a landing could spawn a second process for the same sector and both
            //would overwrite the same save
            inst.stopping = true;
        }

        //wait outside the manager lock: the coordinator's game thread must keep ticking
        stop(inst);

        synchronized(this){
            instances.remove(inst);
        }
    }

    /** Stops in the background, so the HTTP/admin thread never waits for a process. */
    public synchronized void stopAsync(String sector){
        Instance inst = get(sector);
        if(inst != null && !inst.stopping) stopAsync(inst);
    }

    private void stop(Instance inst){
        try{
            inst.dir.child("stop.flag").writeString("stop");

            long deadline = System.currentTimeMillis() + 30_000L;
            while(System.currentTimeMillis() < deadline){
                if(inst.process == null || !inst.process.isAlive()) break;
                Thread.sleep(300);
            }

            if(inst.process != null && inst.process.isAlive()){
                inst.process.destroyForcibly();
            }

            inst.dir.child("stop.flag").delete();
        }catch(Throwable t){
            Log.err("[mp-sectors] could not stop instance @", inst.sector, t);
        }
    }

    public void stopAll(){
        Seq<Instance> copy;
        synchronized(this){
            copy = instances.copy();
            for(Instance inst : copy) inst.stopping = true;
        }

        //wait outside the lock: the coordinator's game thread must keep running meanwhile.
        //the instances stay in the list while stopping so a concurrent landing waits for them.
        for(Instance inst : copy){
            stop(inst);
        }

        synchronized(this){
            for(Instance inst : copy) instances.remove(inst);
        }
    }

    private Status readStatus(Instance inst){
        Fi file = inst.dir.child("status.json");
        if(!file.exists()) return null;

        try{
            return JsonIO.read(Status.class, file.readString());
        }catch(Throwable t){
            return null;
        }
    }

    private int nextPort(){
        int port = config.instanceBasePort;
        outer:
        while(true){
            for(Instance i : instances){
                if(i.port == port){
                    port++;
                    continue outer;
                }
            }
            if(!portOpen(port) && !portBound(port)) return port;
            port++;
        }
    }

    private static boolean portOpen(int port){
        try(Socket socket = new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        }catch(IOException e){
            return false;
        }
    }

    private static boolean portBound(int port){
        try(java.net.ServerSocket socket = new java.net.ServerSocket()){
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return false;
        }catch(IOException e){
            return true;
        }
    }
}



