package mpcampaign;

import arc.Core;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny blocking HTTP helper; callbacks are posted back to the Arc thread. */
public class Http{

    public interface Callback{
        void done(Jval value);
    }

    /** Interactive GET: 8 s. A hung coordinator must not freeze the map/dialogs for minutes. */
    public static void get(String url, Callback callback){
        request(url, "GET", null, callback, 8000);
    }

    public static void get(String url, int readTimeoutMs, Callback callback){
        request(url, "GET", null, callback, readTimeoutMs);
    }

    public static void post(String url, Jval body, Callback callback){
        //landing starts a sector and a restore copies the campaign: both need the long timeout
        request(url, "POST", body == null ? null : body.toString(), callback, 180000);
    }

    /**
     * Downloads a jar into target. If the running game keeps the file locked, the file is saved
     * next to the data directory instead; the callback gets the saved path.
     */
    public static void download(String url, arc.files.Fi target, arc.func.Cons<String> callback){
        Thread thread = new Thread(() -> {
            String saved = null;
            arc.files.Fi temp = target.parent().child(target.name() + ".part");
            HttpURLConnection connection = null;
            try{
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(180000);

                if(connection.getResponseCode() != 200){
                    throw new java.io.IOException("HTTP " + connection.getResponseCode());
                }

                temp.delete();
                try(InputStream in = connection.getInputStream(); OutputStream out = temp.write(false)){
                    in.transferTo(out);
                }

                //a JSON error page must never be saved as a mod jar
                try(InputStream check = temp.read()){
                    if(check.read() != 'P' || check.read() != 'K') throw new java.io.IOException("not a jar");
                }

                try{
                    target.delete();
                    temp.moveTo(target);
                    saved = target.absolutePath();
                }catch(Throwable locked){
                    //the jar of the running mod may be locked on Windows
                    arc.files.Fi fallback = Core.settings.getDataDirectory()
                        .child("mp-campaign-updates").child(target.name());
                    fallback.parent().mkdirs();
                    fallback.delete();
                    temp.moveTo(fallback);
                    saved = fallback.absolutePath();
                }
            }catch(Throwable t){
                Log.err("[mp-campaign] download @ failed", url);
                temp.delete();
            }finally{
                if(connection != null) connection.disconnect();
            }

            String path = saved;
            Core.app.post(() -> callback.get(path));
        }, "mp-campaign-download");
        thread.setDaemon(true);
        thread.start();
    }

    private static void request(String url, String method, String body, Callback callback, int readTimeoutMs){
        Thread thread = new Thread(() -> {
            Jval result = null;
            HttpURLConnection connection = null;
            try{
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(4000);
                //landing starts a whole sector and restoring a snapshot can take a while
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestProperty("Accept", "application/json");
                String token = Core.settings.getString("mpcampaign-token", "");
                if(token != null && !token.isEmpty()) connection.setRequestProperty("X-MP-Token", token);

                if(body != null){
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try(OutputStream out = connection.getOutputStream()){
                        out.write(bytes);
                    }
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : read(stream);

                if(!text.isEmpty()){
                    result = Jval.read(text);
                }
            }catch(Throwable t){
                Log.err("[mp-campaign] request @ @ failed", method, url);
            }finally{
                //a timed-out connection keeps its socket/thread otherwise
                if(connection != null) connection.disconnect();
            }

            Jval value = result;
            Core.app.post(() -> callback.done(value));
        }, "mp-campaign-http");

        thread.setDaemon(true);
        thread.start();
    }

    private static String read(InputStream stream) throws Exception{
        try(InputStream in = stream){
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while((read = in.read(buffer)) != -1){
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
