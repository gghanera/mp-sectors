package mpsectors;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.io.JsonIO;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts/stops one headless Mindustry process per sector and tracks who is inside.
 *
 * Each instance gets its own working directory (so its own config/saves/logs), a copy of every mod
 * from the coordinator's mods folder, and a generated config/mp-sectors.json. The instance writes
 * status.json (player names) every couple of seconds; that is the source of truth for player counts.
 *
 * Locking: the manager monitor only guards the instance list, the port bookkeeping and the limit
 * counters. Copying mods, probing ports and starting/waiting for a process all happen outside it,
 * because the coordinator's game thread calls tick() and must never wait for that.
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
        /** volatile: written from start()/tick(), read from HTTP threads without the manager lock */
        public volatile long emptySince;
        public long started;
        /** read from HTTP threads without the manager lock; must be volatile */
        public volatile boolean stopping;
        /** true once the instance wrote its status.json; set outside the manager lock */
        public volatile boolean ready;
        /** Launch loadout/resources, reused if the watchdog has to restart a sector. */
        public String loadout = "", resources = "";

        public Instance(String sector, int port, Fi dir){
            this.sector = sector;
            this.port = port;
            this.dir = dir;
        }
    }

    /**
     * Limits reserved for one landing. Reserve before checking/starting a sector and release as soon
     * as the landing is answered, so two concurrent /land requests can never both pass the limits.
     */
    public static class Reservation{
        public final String sector;
        /** the live instance when the sector is already up; null when a process has to be started */
        public final Instance instance;
        boolean released;

        Reservation(String sector, Instance instance){
            this.sector = sector;
            this.instance = instance;
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
    /** sectors with a start() in progress: their process is not in the list yet */
    private final Set<String> starting = new HashSet<>();
    /** ports picked for a start in progress, so two starts never pick the same one */
    private final Set<Integer> reservedPorts = new HashSet<>();
    /** players landing into a not-yet-ready instance: counted against maxPlayers until released */
    private int reservedPlayers;
    /** players on the coordinator's home; set by the coordinator so the player limit is exact */
    private volatile int homePlayers;
    private long lastTick;
    /** Set while the coordinator swaps campaigns; no instance may start in that window. */
    private volatile boolean frozen;
    /** Set by stopAll(): the manager is shutting down and must not start anything else. */
    private volatile boolean stoppingAll;

    public InstanceManager(Coordinator.Config config, Cons<String> notify){
        this.config = config;
        this.notify = notify;
    }

    public synchronized Seq<Instance> all(){
        return instances.copy();
    }

    public synchronized void freeze(){
        frozen = true;
    }

    public synchronized void unfreeze(){
        frozen = false;
        stoppingAll = false;
    }

    /** Players on the home sector: without it the player limit could only be checked with a race. */
    public void setHomePlayers(int count){
        homePlayers = Math.max(0, count);
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

    /** True while a landing is booting this sector but the process is not in the list yet. */
    public synchronized boolean isStarting(String sector){
        return starting.contains(sector);
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

    /**
     * Reserves the sector/player slots for one landing, atomically with the duplicate check.
     *
     * @return null when the manager is frozen/shutting down, a limit would be exceeded, or another
     *         start for the same sector is already in progress.
     */
    public Reservation reserve(String sector){
        return reserve(sector, false);
    }

    /**
     * @param ignorePlayerLimit true for a player who is already connected (the coordinator's
     *                          isOnline() bypass): an existing player does not need a new slot.
     */
    public Reservation reserve(String sector, boolean ignorePlayerLimit){
        synchronized(this){
            if(frozen || stoppingAll) return null;

            Instance existing = get(sector);
            if(existing != null && !existing.stopping){
                //the sector is already up; only the player slot has to fit
                if(!hasPlayerSlot(ignorePlayerLimit)) return null;
                reservedPlayers++;
                return new Reservation(sector, existing);
            }

            //a stopped/stopping instance is still in the list: the caller has to wait for it
            if(existing != null || starting.contains(sector)) return null;

            //the home is always one sector; every start in progress becomes one more
            int booting = 0;
            for(String s : starting){
                if(get(s) == null) booting++;
            }
            if(1 + runningCount() + booting + 1 > config.maxSectors) return null;
            if(!hasPlayerSlot(ignorePlayerLimit)) return null;

            starting.add(sector);
            reservedPlayers++;
            return new Reservation(sector, null);
        }
    }

    /** Drops a reservation; idempotent. */
    public void release(Reservation reservation){
        if(reservation == null) return;

        synchronized(this){
            if(reservation.released) return;
            reservation.released = true;
            if(reservedPlayers > 0) reservedPlayers--;
            if(reservation.instance == null) starting.remove(reservation.sector);
        }
    }

    /** Lock held. Reserved landings count too, otherwise two of them pass the check together. */
    private boolean hasPlayerSlot(boolean ignore){
        if(ignore || config.maxPlayers <= 0) return true;
        return homePlayers + totalPlayers() + reservedPlayers < config.maxPlayers;
    }

    /** Starts the sector instance and blocks until it listens on its port. */
    public Instance start(String sector){
        return start(sector, "", "");
    }

    public Instance start(String sector, String loadout, String resources){
        Reservation reservation = reserveWaiting(sector, false);
        if(reservation == null) return null;

        try{
            return start(reservation, loadout, resources);
        }finally{
            release(reservation);
        }
    }

    /**
     * Starts (or joins) the instance of a reservation.
     * @return null when the limits/shutdown did not allow it or the process failed to come up.
     */
    public Instance start(Reservation reservation, String loadout, String resources){
        if(reservation == null) return null;
        //the sector is already up: nothing to launch
        if(reservation.instance != null) return reservation.instance.stopping ? null : reservation.instance;

        return boot(reservation.sector, loadout, resources);
    }

    /** Waits for a stopping instance of the same sector, then reserves. */
    public synchronized Reservation reserveWaiting(String sector, boolean ignorePlayerLimit){
        long deadline = System.currentTimeMillis() + 45_000L;

        while(true){
            Reservation reservation = reserve(sector, ignorePlayerLimit);
            if(reservation != null) return reservation;

            boolean waitable;
            synchronized(this){
                if(frozen || stoppingAll) return null;
                Instance existing = get(sector);
                //a sector being stopped lingers in the list until its process exits; wait instead
                //of handing a dying instance to a player who lands during the stop. Same for a
                //start already in progress (two players landing together).
                waitable = (existing != null && existing.stopping) || starting.contains(sector);
            }

            if(!waitable) return null;

            if(System.currentTimeMillis() > deadline){
                Log.err("[mp-sectors] sector @ did not become available in time", sector);
                return null;
            }
            try{
                Thread.sleep(500);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** Creates the instance directory and launches the process. All heavy I/O happens outside the lock. */
    private Instance boot(String sector, String loadout, String resources){
        Fi dir = Core.files.local("instances").child(sector);
        Instance out = null;
        Process process = null;
        int port = -1;

        try{
            Fi mods = dir.child("config/mods");
            mods.mkdirs();
            dir.child("config/saves").mkdirs();

            for(Fi mod : Vars.modDirectory.list()){
                if(mod.extension().equals("jar") || mod.extension().equals("zip")){
                    SectorRunner.copy(mod, mods.child(mod.name()));
                }
            }

            Coordinator.InstanceConfig cfg = new Coordinator.InstanceConfig();
            cfg.campaign = config.campaign;
            cfg.sector = sector;
            cfg.httpPort = config.httpPort;
            cfg.playerLimit = config.playerLimit;
            cfg.autosaveSeconds = config.autosaveSeconds;
            cfg.allowDuplicateClients = config.allowDuplicateClients;
            cfg.loadout = loadout == null ? "" : loadout;
            cfg.resources = resources == null ? "" : resources;
            cfg.steam = config.steam;
            cfg.steamJar = config.steamJar;
            cfg.steamNative = config.steamNative;
            cfg.httpToken = config.httpToken == null ? "" : config.httpToken;

            if(config.steam && config.steamJar != null && !config.steamJar.isEmpty()){
                dir.child("steam_appid.txt").writeString("1127400");
            }

            Fi cfgFile = dir.child("config/mp-sectors.json");
            Fi log = dir.child("server.log");
            Fi err = dir.child("server.err.log");

            //stale files from a previous run: a leftover stop flag would kill the new process
            dir.child("status.json").delete();
            dir.child("stop.flag").delete();
            dir.child("exit.reason").delete();

            for(int attempt = 0; attempt < 3 && process == null; attempt++){
                int candidate = reservePort();
                if(candidate < 0) break;

                cfg.port = candidate;
                SectorRunner.writeAtomic(cfgFile, JsonIO.write(cfg));

                //re-check right before the launch: the port may have been taken in the meantime
                if(portOpen(candidate) || portBound(candidate)){
                    releasePort(candidate);
                    continue;
                }

                ProcessBuilder builder;
                if(config.steam && config.steamJar != null && !config.steamJar.isEmpty()){
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

                try{
                    process = builder.start();
                }catch(Throwable t){
                    releasePort(candidate);
                    throw t;
                }
                port = candidate;
                //nobody writes to the child's stdin; an open pipe leaks a handle per restart
                closeStdin(process);
            }

            if(process == null || port < 0){
                Log.err("[mp-sectors] no free port for instance @", sector);
                return null;
            }

            out = new Instance(sector, port, dir);
            out.loadout = cfg.loadout;
            out.resources = cfg.resources;
            out.process = process;
            out.started = System.currentTimeMillis();

            boolean abort;
            synchronized(this){
                //freeze()/stopAll() may have run while the mods were being copied
                abort = frozen || stoppingAll || get(sector) != null;
                if(!abort) instances.add(out);
            }

            if(abort){
                Log.err("[mp-sectors] instance @ was started during a shutdown/freeze, killing it", sector);
                out.stopping = true;
                killAndWait(process);
                return null;
            }

            Log.info("[mp-sectors] starting instance @ on port @ ...", sector, port);

            //wait outside the lock: the coordinator's game loop must keep running while a sector boots
            long deadline = System.currentTimeMillis() + 45_000L;
            while(System.currentTimeMillis() < deadline){
                if(out.stopping || !process.isAlive()) break;

                //status.json is written by the instance itself, so its appearance proves the
                //process listening on the port is ours and not a leftover of a previous run
                Status status = readStatus(out);
                if(status != null && sector.equals(status.sector)){
                    out.emptySince = System.currentTimeMillis();
                    out.ready = true;
                    applyStatus(out, status);
                    return out;
                }

                try{
                    Thread.sleep(300);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Log.err("[mp-sectors] instance @ did not open port @ in time", sector, port);
            synchronized(this){
                instances.remove(out);
            }
            out.stopping = true;
            out.ready = false;
            killAndWait(process);
            return null;
        }catch(Throwable t){
            Log.err("[mp-sectors] could not start instance @", sector, t);
            if(out != null){
                synchronized(this){
                    instances.remove(out);
                }
                out.stopping = true;
            }
            killAndWait(process);
            return null;
        }finally{
            releasePort(port);
        }
    }

    /** Waits until an instance started by another request is listening (two players landing together). */
    public Instance waitReady(Instance inst, long timeoutMs){
        if(inst == null) return null;

        long deadline = System.currentTimeMillis() + timeoutMs;

        while(System.currentTimeMillis() < deadline){
            if(inst.stopping) return null;
            if(inst.ready && inst.process != null && inst.process.isAlive()) return inst;
            if(inst.process != null && !inst.process.isAlive()) return null;

            try{
                Thread.sleep(200);
            }catch(InterruptedException e){
                return null;
            }
        }

        return inst.ready && !inst.stopping && inst.process != null && inst.process.isAlive() ? inst : null;
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
            if(status != null) applyStatus(inst, status);

            if(inst.players > 0){
                inst.emptySince = 0;
            }else{
                if(inst.emptySince == 0) inst.emptySince = now;
                //before the first status.json the player count is unknown (0): a sector with real
                //players must never be stopped by the idle timer in that window
                if(inst.statusSeen && config.idleStopSeconds > 0
                    && now - inst.emptySince > config.idleStopSeconds * 1000L){
                    Log.info("[mp-sectors] instance @ is empty, stopping", inst.sector);
                    stopAsync(inst);
                }
            }
        }
    }

    private static void applyStatus(Instance inst, Status status){
        inst.players = status.count;
        inst.playerNames = status.players == null ? new String[0] : status.players;
        inst.steamHost = status.steamHost == null ? "" : status.steamHost;
        inst.steamId = status.steamId;
        inst.attacked = status.attacked;
        inst.statusSeen = true;
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
            //reserve() refuses while stopped/frozen, so a watchdog restart cannot resurrect the
            //campaign during a shutdown or a company switch; ignore the player limit: a crash
            //must not silently leave the sector dead when the server is full
            Reservation reservation = reserveWaiting(inst.sector, true);
            Instance restarted = null;
            try{
                restarted = start(reservation, inst.loadout, inst.resources);
            }finally{
                release(reservation);
            }
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
        markStopping(inst);

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
            markStopping(inst);
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

    /** Must be called with the manager lock held. */
    private static void markStopping(Instance inst){
        inst.stopping = true;
        //a stopping instance is not a landing target any more
        inst.ready = false;
    }

    private void stop(Instance inst){
        try{
            inst.dir.child("stop.flag").writeString("stop");

            long deadline = System.currentTimeMillis() + 30_000L;
            while(System.currentTimeMillis() < deadline){
                if(inst.process == null || !inst.process.isAlive()) break;
                Thread.sleep(300);
            }

            //kill and wait: the flag/dir must not be reused while the old process still owns
            //the port, the save and status.json
            if(inst.process != null) killAndWait(inst.process);

            inst.dir.child("stop.flag").delete();
        }catch(Throwable t){
            Log.err("[mp-sectors] could not stop instance @", inst.sector, t);
        }
    }

    /**
     * Stops every instance and refuses all new starts until unfreeze(). The list is re-scanned after
     * the loop because a start that was already running may add its instance mid-shutdown.
     */
    public void stopAll(){
        synchronized(this){
            stoppingAll = true;
            for(Instance inst : instances) markStopping(inst);
        }

        long deadline = System.currentTimeMillis() + 180_000L;
        while(true){
            Seq<Instance> copy;
            boolean booting;
            synchronized(this){
                copy = instances.copy();
                booting = !starting.isEmpty();
            }

            if(copy.isEmpty() && !booting) return;

            //wait outside the lock: the coordinator's game thread must keep running meanwhile.
            //the instances stay in the list while stopping so a concurrent landing waits for them.
            for(Instance inst : copy) stop(inst);

            synchronized(this){
                for(Instance inst : copy) instances.remove(inst);
            }

            if(System.currentTimeMillis() > deadline){
                Log.err("[mp-sectors] instances did not stop in time, continuing shutdown");
                return;
            }

            if(booting){
                try{
                    Thread.sleep(200);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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

    /** Picks a free port: the candidate is marked used under the lock, the socket probe is not. */
    private int reservePort(){
        int from = config.instanceBasePort;
        for(int attempt = 0; attempt < 200; attempt++){
            int port;
            synchronized(this){
                port = freePortNumber(from);
                reservedPorts.add(port);
            }

            if(!portOpen(port) && !portBound(port)) return port;

            releasePort(port);
            //keep scanning upwards: otherwise the same occupied port is probed 200 times
            from = port + 1;
        }
        return -1;
    }

    /** Lock held. @return the lowest port >= from not used by a running instance or another start. */
    private int freePortNumber(int from){
        int port = from;
        outer:
        while(true){
            if(reservedPorts.contains(port)){
                port++;
                continue;
            }
            for(Instance i : instances){
                if(i.port == port){
                    port++;
                    continue outer;
                }
            }
            return port;
        }
    }

    private void releasePort(int port){
        if(port < 0) return;
        synchronized(this){
            reservedPorts.remove(port);
        }
    }

    /** Kills a process and waits for it, so its port/dir/save are really free before a restart. */
    private static void killAndWait(Process process){
        if(process == null) return;

        process.destroyForcibly();
        try{
            process.waitFor(5, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    private static void closeStdin(Process process){
        try{
            process.getOutputStream().close();
        }catch(Throwable ignored){
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
        try(ServerSocket socket = new ServerSocket()){
            //no SO_REUSEADDR: on Windows it lets the bind succeed even while the port is in use,
            //which would make this probe useless
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return false;
        }catch(IOException e){
            return true;
        }
    }
}
