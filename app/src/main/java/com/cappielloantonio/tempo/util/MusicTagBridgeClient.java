package com.cappielloantonio.tempo.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MusicTagBridgeClient {
    public interface Callback {
        void onSuccess(int timestampsChanged, int timestampsClamped);
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private MusicTagBridgeClient() {
    }

    public static void applyLyricsOffset(
            String musicPath,
            int offsetMs,
            Callback callback
    ) {
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;

            try {
                String baseUrl = Preferences.getMusicTagBridgeUrl();
                if (TextUtils.isEmpty(baseUrl)) {
                    throw new IllegalStateException(
                            "Music Tag bridge URL is not configured"
                    );
                }

                String endpoint = baseUrl.replaceAll("/+$", "")
                        + "/api/lyrics/offset";
                connection = (HttpURLConnection)
                        new URL(endpoint).openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(20000);
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );
                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );

                String apiKey = Preferences.getMusicTagBridgeApiKey();
                if (!TextUtils.isEmpty(apiKey)) {
                    connection.setRequestProperty("X-API-Key", apiKey);
                }

                JSONObject payload = new JSONObject();
                payload.put("path", musicPath);
                payload.put("offset_ms", offsetMs);

                byte[] requestBody = payload.toString()
                        .getBytes(StandardCharsets.UTF_8);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBody);
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String responseText = readAll(stream);
                JSONObject response = responseText.isEmpty()
                        ? new JSONObject()
                        : new JSONObject(responseText);

                if (status >= 200 && status < 300
                        && response.optBoolean("ok", false)) {
                    int changed = response.optInt(
                            "timestamps_changed",
                            0
                    );
                    int clamped = response.optInt(
                            "timestamps_clamped",
                            0
                    );

                    MAIN_HANDLER.post(() ->
                            callback.onSuccess(changed, clamped)
                    );
                    return;
                }

                String message = response.optString(
                        "error",
                        "Bridge request failed (" + status + ")"
                );
                MAIN_HANDLER.post(() -> callback.onError(message));

            } catch (Exception error) {
                String message = error.getMessage();
                if (TextUtils.isEmpty(message)) {
                    message = error.getClass().getSimpleName();
                }

                String finalMessage = message;
                MAIN_HANDLER.post(() ->
                        callback.onError(finalMessage)
                );

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream,
                        StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }
}
