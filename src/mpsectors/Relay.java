package mpsectors;

import arc.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fire-and-forget messages from a sector server to the coordinator (chat lines and campaign
 * history). Runs on a daemon thread: gameplay must never wait for HTTP.
 */
public final class Relay{

    private Relay(){
    }

    /** A player chat line, shown to the players of the other sectors. */
    public static void chat(int httpPort, String sector, String player, String message){
        if(message == null || message.isEmpty()) return;

        post(httpPort, "/chat", "{\"sector\":\"" + Coordinator.esc(sector) + "\","
            + "\"player\":\"" + Coordinator.esc(player) + "\","
            + "\"message\":\"" + Coordinator.esc(message) + "\"}");
    }

    /** A campaign log line, e.g. a lost sector. Synchronous: the sector server may exit right after. */
    public static void history(int httpPort, String type, String sector, String player, String text){
        if(httpPort <= 0) return;
        send(httpPort, "/history", "{\"type\":\"" + Coordinator.esc(type) + "\","
            + "\"sector\":\"" + Coordinator.esc(sector) + "\","
            + "\"player\":\"" + Coordinator.esc(player) + "\","
            + "\"text\":\"" + Coordinator.esc(text) + "\"}");
    }

    private static void post(int httpPort, String path, String body){
        if(httpPort <= 0) return;

        Thread thread = new Thread(() -> send(httpPort, path, body), "mp-sectors-relay");
        thread.setDaemon(true);
        thread.start();
    }

    private static void send(int httpPort, String path, String body){
        HttpURLConnection connection = null;
        try{
            connection = (HttpURLConnection)new URL("http://127.0.0.1:" + httpPort + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out = connection.getOutputStream()){
                out.write(bytes);
            }
            connection.getResponseCode();
        }catch(Throwable t){
            Log.err("[mp-sectors] could not send @ to the coordinator", path, t);
        }finally{
            if(connection != null) connection.disconnect();
        }
    }
}
