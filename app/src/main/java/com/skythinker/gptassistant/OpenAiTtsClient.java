package com.skythinker.gptassistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;

import java.io.IOException;

public class OpenAiTtsClient {
    private static final String TTS_ENDPOINT = "/v1/audio/speech"; // OpenAI TTS endpoint
    private String apiKey;
    private String baseUrl;
    private OkHttpClient client;
    private MediaPlayer mediaPlayer;

    public OpenAiTtsClient(Context context, String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.client = new OkHttpClient();
        this.mediaPlayer = new MediaPlayer();
        this.mediaPlayer.setAudioAttributes(
            new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        );
    }

    public void speak(String text, String voice) {
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("model", "tts-1");
            requestBody.put("input", text);
            requestBody.put("voice", voice != null ? voice : "alloy");
            requestBody.put("response_format", "mp3");
        } catch (Exception e) {
            Log.e("OpenAiTtsClient", "Error creating request body", e);
            return;
        }

        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + TTS_ENDPOINT)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("OpenAiTtsClient", "TTS request failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        byte[] audioBytes = response.body().bytes();
                        playAudio(audioBytes);
                    } catch (Exception e) {
                        Log.e("OpenAiTtsClient", "Error playing audio", e);
                    }
                } else {
                    Log.e("OpenAiTtsClient", "TTS API error: " + response.message());
                }
            }
        });
    }

    private void playAudio(byte[] audioBytes) {
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(new java.io.ByteArrayInputStream(audioBytes));
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e("OpenAiTtsClient", "Error playing audio", e);
        }
    }

    public void stop() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
    }

    public void shutdown() {
        mediaPlayer.release();
        client.dispatcher().executorService().shutdown();
    }
}
