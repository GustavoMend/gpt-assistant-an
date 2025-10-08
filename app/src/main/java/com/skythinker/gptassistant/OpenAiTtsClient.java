package com.skythinker.gptassistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Queue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAiTtsClient {
    private static final String TAG = "OpenAiTtsClient";
    private static final String TTS_ENDPOINT = "/v1/audio/speech";
    public static final int QUEUE_FLUSH = 0;
    public static final int QUEUE_ADD = 1;

    private String apiKey;
    private String baseUrl;
    private OkHttpClient client;
    private MediaPlayer mediaPlayer;
    private Context context;
    private Handler mainHandler;
    private String selectedVoice = "alloy"; // Default OpenAI voice

    // Queue management for TTS
    private Queue<TtsRequest> ttsQueue = new LinkedList<>();
    private boolean isProcessing = false;
    private UtteranceProgressListener utteranceProgressListener;

    public interface UtteranceProgressListener {
        void onStart(String utteranceId);
        void onDone(String utteranceId);
        void onError(String utteranceId);
    }

    private static class TtsRequest {
        String text;
        int queueMode;
        String utteranceId;

        TtsRequest(String text, int queueMode, String utteranceId) {
            this.text = text;
            this.queueMode = queueMode;
            this.utteranceId = utteranceId;
        }
    }

    public OpenAiTtsClient(Context context, String baseUrl, String apiKey) {
        this.context = context;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mediaPlayer = new MediaPlayer();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        mediaPlayer.setAudioAttributes(
            new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        );

        mediaPlayer.setOnCompletionListener(mp -> {
            Log.d(TAG, "Audio playback completed");
            processNextInQueue();
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            processNextInQueue();
            return true;
        });
    }

    public void setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        this.utteranceProgressListener = listener;
    }

    public void setVoice(String voice) {
        // Available voices: alloy, echo, fable, onyx, nova, shimmer
        this.selectedVoice = voice;
    }

    public int speak(String text, int queueMode, android.os.Bundle params, String utteranceId) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        TtsRequest request = new TtsRequest(text, queueMode, utteranceId);

        synchronized (ttsQueue) {
            if (queueMode == QUEUE_FLUSH) {
                stop();
                ttsQueue.clear();
            }
            ttsQueue.offer(request);
        }

        if (!isProcessing) {
            processNextInQueue();
        }

        return 0;
    }

    private void processNextInQueue() {
        synchronized (ttsQueue) {
            if (ttsQueue.isEmpty()) {
                isProcessing = false;
                return;
            }

            isProcessing = true;
            TtsRequest request = ttsQueue.poll();
            if (request != null) {
                generateAndPlaySpeech(request);
            }
        }
    }

    private void generateAndPlaySpeech(TtsRequest request) {
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "tts-1");
            requestBody.put("input", request.text);
            requestBody.put("voice", selectedVoice);
            requestBody.put("response_format", "mp3");

            RequestBody body = RequestBody.create(
                MediaType.get("application/json"),
                requestBody.toString()
            );

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + TTS_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            Log.d(TAG, "Sending TTS request for text: " + request.text.substring(0, Math.min(50, request.text.length())));

            // Notify start
            if (utteranceProgressListener != null && request.utteranceId != null) {
                mainHandler.post(() -> utteranceProgressListener.onStart(request.utteranceId));
            }

            client.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "TTS request failed", e);
                    if (utteranceProgressListener != null && request.utteranceId != null) {
                        mainHandler.post(() -> utteranceProgressListener.onError(request.utteranceId));
                    }
                    mainHandler.post(() -> processNextInQueue());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    Log.d(TAG, "TTS response received: " + response.code());
                    
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            // Read the entire stream into memory first
                            InputStream inputStream = response.body().byteStream();
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
                            
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                            
                            byte[] audioBytes = outputStream.toByteArray();
                            inputStream.close();
                            outputStream.close();
                            
                            Log.d(TAG, "Audio data received: " + audioBytes.length + " bytes");
                            
                            if (audioBytes.length == 0) {
                                Log.e(TAG, "Received empty audio data");
                                if (utteranceProgressListener != null && request.utteranceId != null) {
                                    mainHandler.post(() -> utteranceProgressListener.onError(request.utteranceId));
                                }
                                mainHandler.post(() -> processNextInQueue());
                                return;
                            }

                            // Write to temporary file
                            File tempFile = File.createTempFile("tts_audio_", ".mp3", context.getCacheDir());
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            fos.write(audioBytes);
                            fos.close();
                            
                            Log.d(TAG, "Audio file saved: " + tempFile.getAbsolutePath());

                            mainHandler.post(() -> {
                                try {
                                    mediaPlayer.reset();
                                    mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                                    mediaPlayer.prepare();
                                    mediaPlayer.setOnCompletionListener(mp -> {
                                        Log.d(TAG, "Playback completed for utterance: " + request.utteranceId);
                                        if (utteranceProgressListener != null && request.utteranceId != null) {
                                            utteranceProgressListener.onDone(request.utteranceId);
                                        }
                                        tempFile.delete();
                                        processNextInQueue();
                                    });
                                    Log.d(TAG, "Starting audio playback");
                                    mediaPlayer.start();
                                } catch (Exception e) {
                                    Log.e(TAG, "Error playing audio", e);
                                    if (utteranceProgressListener != null && request.utteranceId != null) {
                                        utteranceProgressListener.onError(request.utteranceId);
                                    }
                                    tempFile.delete();
                                    processNextInQueue();
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing audio", e);
                            if (utteranceProgressListener != null && request.utteranceId != null) {
                                mainHandler.post(() -> utteranceProgressListener.onError(request.utteranceId));
                            }
                            mainHandler.post(() -> processNextInQueue());
                        }
                    } else {
                        String errorBody = "";
                        try {
                            if (response.body() != null) {
                                errorBody = response.body().string();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error reading error body", e);
                        }
                        Log.e(TAG, "TTS API error: " + response.code() + " " + response.message() + " - " + errorBody);
                        if (utteranceProgressListener != null && request.utteranceId != null) {
                            mainHandler.post(() -> utteranceProgressListener.onError(request.utteranceId));
                        }
                        mainHandler.post(() -> processNextInQueue());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating TTS request", e);
            if (utteranceProgressListener != null && request.utteranceId != null) {
                mainHandler.post(() -> utteranceProgressListener.onError(request.utteranceId));
            }
            mainHandler.post(() -> processNextInQueue());
        }
    }

    public void stop() {
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error stopping MediaPlayer", e);
        }
    }

    public void shutdown() {
        stop();
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        try {
            mediaPlayer.release();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing MediaPlayer", e);
        }
        client.dispatcher().executorService().shutdown();
    }
}
