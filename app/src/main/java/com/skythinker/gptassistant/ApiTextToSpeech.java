package com.skythinker.gptassistant;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiTextToSpeech {
    private static final String TAG = "ApiTextToSpeech";
    private static final String API_URL = "http://localhost:8080/api/tts";
    private static final String VOICE = "en-US-AvaMultilingualNeural";

    private Context context;
    private OnInitListener initListener;
    private UtteranceProgressListener utteranceProgressListener;
    private Queue<SpeechItem> speechQueue = new ConcurrentLinkedQueue<>();
    private boolean isSpeaking = false;
    private OkHttpClient client;
    private Handler mainHandler;
    private MediaPlayer mediaPlayer;

    public ApiTextToSpeech(Context context, OnInitListener listener) {
        this.context = context;
        this.initListener = listener;
        this.client = new OkHttpClient();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.mediaPlayer = new MediaPlayer();

        mediaPlayer.setOnCompletionListener(mp -> {
            isSpeaking = false;
            speakNext();
        });

        // Notify that initialization is complete
        mainHandler.post(() -> {
            if (initListener != null) {
                initListener.onInit(TextToSpeech.SUCCESS);
            }
        });
    }

    public int setLanguage(Locale locale) {
        // We're always using the same voice, so this method doesn't change anything
        return TextToSpeech.LANG_AVAILABLE;
    }

    public int speak(String text, int queueMode, Bundle params, String utteranceId) {
        SpeechItem item = new SpeechItem(text, utteranceId);
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            speechQueue.clear();
        }
        speechQueue.offer(item);
        if (!isSpeaking) {
            speakNext();
        }
        return TextToSpeech.SUCCESS;
    }

    private void speakNext() {
        SpeechItem item = speechQueue.poll();
        if (item != null) {
            isSpeaking = true;
            synthesizeSpeech(item);
        } else {
            isSpeaking = false;
        }
    }

    private void synthesizeSpeech(SpeechItem item) {
        try {
            JSONObject json = new JSONObject();
            json.put("text", item.text);
            json.put("voice", VOICE);

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "API call failed", e);
                    mainHandler.post(() -> {
                        if (utteranceProgressListener != null) {
                            utteranceProgressListener.onError(item.utteranceId);
                        }
                        isSpeaking = false;
                        speakNext();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        throw new IOException("Unexpected code " + response);
                    }

                    File tempFile = File.createTempFile("tts_", ".mp3", context.getCacheDir());
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    InputStream is = response.body().byteStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.close();
                    is.close();

                    mainHandler.post(() -> {
                        if (utteranceProgressListener != null) {
                            utteranceProgressListener.onStart(item.utteranceId);
                        }
                        try {
                            mediaPlayer.reset();
                            mediaPlayer.setDataSource(tempFile.getPath());
                            mediaPlayer.prepare();
                            mediaPlayer.start();
                        } catch (IOException e) {
                            Log.e(TAG, "Error playing audio", e);
                            if (utteranceProgressListener != null) {
                                utteranceProgressListener.onError(item.utteranceId);
                            }
                            isSpeaking = false;
                            speakNext();
                        }
                    });

                    mediaPlayer.setOnCompletionListener(mp -> {
                        tempFile.delete();
                        mainHandler.post(() -> {
                            if (utteranceProgressListener != null) {
                                utteranceProgressListener.onDone(item.utteranceId);
                            }
                            isSpeaking = false;
                            speakNext();
                        });
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error synthesizing speech", e);
            mainHandler.post(() -> {
                if (utteranceProgressListener != null) {
                    utteranceProgressListener.onError(item.utteranceId);
                }
                isSpeaking = false;
                speakNext();
            });
        }
    }

    public void setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        this.utteranceProgressListener = listener;
    }

    public int stop() {
        speechQueue.clear();
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
        isSpeaking = false;
        return TextToSpeech.SUCCESS;
    }

    public void shutdown() {
        stop();
        mediaPlayer.release();
    }

    private static class SpeechItem {
        String text;
        String utteranceId;

        SpeechItem(String text, String utteranceId) {
            this.text = text;
            this.utteranceId = utteranceId;
        }
    }

    public interface OnInitListener {
        void onInit(int status);
    }
}
