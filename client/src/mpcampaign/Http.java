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

    public static void get(String url, Callback callback){
        request(url, "GET", null, callback, 180000);
    }

    public static void get(String url, int readTimeoutMs, Callback callback){
        request(url, "GET", null, callback, readTimeoutMs);
    }

    public static void post(String url, Jval body, Callback callback){
        request(url, "POST", body == null ? null : body.toString(), callback, 180000);
    }

    private static void request(String url, String method, String body, Callback callback, int readTimeoutMs){
        Thread thread = new Thread(() -> {
            Jval result = null;
            try{
                HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(4000);
                //landing starts a whole sector and restoring a snapshot can take a while
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestProperty("Accept", "application/json");

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

                connection.disconnect();
            }catch(Throwable t){
                Log.err("[mp-campaign] request @ @ failed", method, url);
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
