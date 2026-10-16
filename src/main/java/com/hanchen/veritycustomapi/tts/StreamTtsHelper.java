package com.hanchen.veritycustomapi.tts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.MimoDirectProtocol;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * MiMo SSE 流式 TTS 播放器。
 * 仅对 MiMo 端点生效，启用后通过 SSE 实时接收 PCM 并播放，失败自动回退。
 */
public final class StreamTtsHelper {

    private static final int SAMPLE_RATE = 24000;
    private static volatile long sessionGeneration;
    private static volatile SourceDataLine currentLine;
    private static volatile InputStream currentStream;

    private StreamTtsHelper() {
    }

    public static void startSession(long generation) {
        sessionGeneration = generation;
        InputStream stream = currentStream;
        currentStream = null;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
        SourceDataLine line = currentLine;
        currentLine = null;
        if (line != null) {
            try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) {}
        }
        VanillaAudioSink.stopAll();
    }

    public static boolean tryPlay(String text, String baseUrl, String apiKey, String model, String voice, int entityId,
                                  long expectedSession) {
        // Android/FCL 不支持 Java Sound SourceDataLine，直接回退 OnlineTtsService
        if (RuntimePlatform.isAndroidRuntime()) return false;
        if (sessionGeneration != expectedSession) return true;
        SourceDataLine line = null;
        VanillaAudioSink sink = null;
        InputStream stream = null;
        long talkToken = 0;
        boolean audioStarted = false;
        String billingRequestId = BillingService.requestId();
        try {
            if (!ConfigManager.isTtsStreamEnabled()) return false;
            ConfigManager.TtsRoute route = ConfigManager.snapshotTtsRoutes().stream()
                    .filter(item -> TtsApiProviderEnum.MIMO_TTS.name().equals(item.apiProvider()))
                    .findFirst().orElse(null);
            if (route == null || !TtsApiProviderEnum.MIMO_TTS.name().equals(route.apiProvider())
                    || route.apiKey() == null || route.apiKey().isBlank()) return false;
            int requiredCredits = "paid".equalsIgnoreCase(route.tier()) ? ConfigManager.getTtsCost(false) : 0;
            if (requiredCredits > 0 && !ConfigManager.isCreditsSufficient(requiredCredits)) return false;
            baseUrl = route.apiUrl();
            apiKey = route.apiKey();
            model = route.model();
            if (ConfigManager.isMimoCloneVoice(voice) || ConfigManager.isSavedMimoVoiceClone(voice)) return false;
            voice = MimoDirectProtocol.normalizeVoice(voice);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray messages = new JsonArray();
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", text);
            messages.add(msg);
            body.add("messages", messages);
            JsonObject audio = new JsonObject();
            audio.addProperty("format", "pcm16");
            audio.addProperty("voice", voice);
            body.add("audio", audio);
            body.addProperty("stream", true);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(MimoDirectProtocol.requireEndpoint(baseUrl))
                    .header("api-key", apiKey).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                try { resp.body().close(); } catch (Exception ignored) {}
                return false;
            }
            if (route.tier() != null) {
                BillingService.reportTts(billingRequestId, route.tier(), route.id(),
                        UsageMeter.characters(text), false);
            }
            if (sessionGeneration != expectedSession) {
                resp.body().close();
                return true;
            }

            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            // 优先走原版音频通道；拿不到才退回自带的 Java Sound 输出设备
            sink = VanillaAudioSink.open(fmt, (float) ConfigManager.getTtsVolume(), entityId);
            if (sink == null) {
                line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                line.open(fmt);
                line.start();
                currentLine = line;
            }
            stream = resp.body();
            currentStream = stream;
            talkToken = com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.begin(entityId);

            int total = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (sessionGeneration != expectedSession) return true;
                    if (!l.startsWith("data:")) continue;
                    String d = l.substring(5).trim();
                    if (d.isEmpty() || "[DONE]".equals(d)) continue;
                    try {
                        JsonObject c = JsonParser.parseString(d).getAsJsonObject();
                        if (!c.has("choices") || c.getAsJsonArray("choices").size() == 0) continue;
                        JsonObject ch = c.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (!ch.has("delta") || !ch.getAsJsonObject("delta").has("audio")) continue;
                        JsonObject a = ch.getAsJsonObject("delta").getAsJsonObject("audio");
                        if (!a.has("data")) continue;
                        byte[] pcm = Base64.getDecoder().decode(a.get("data").getAsString());
                        VerityEntityTalkAnimator.updatePcm(entityId, talkToken, pcm, 0, pcm.length);
                        if (sink != null) {
                            sink.push(pcm, 0, pcm.length);
                        } else {
                            line.write(pcm, 0, pcm.length);
                        }
                        audioStarted = true;
                        total += pcm.length;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (currentStream == stream) currentStream = null;
            if (sessionGeneration != expectedSession) return true;
            if (total == 0) {
                if (sink != null) {
                    sink.finish();
                    sink.close();
                } else {
                    line.close();
                }
                return false;
            }
            if (sink != null) {
                sink.finish();
                // 24kHz 16bit 单声道：每 48 字节约 1ms
                sink.awaitDone(Math.max(5000L, total / 48L + 3000L));
                sink.close();
            } else {
                line.drain();
                line.close();
            }
            return true;
        } catch (Throwable t) {
            if (ConfigManager.isDevMode()) OllamaProxyMod.LOGGER.warn("[StreamTTS] {}", t.getMessage());
            if (line != null) try { line.close(); } catch (Exception ignored) {}
            if (sink != null) try { sink.close(); } catch (Exception ignored) {}
            return audioStarted;
        } finally {
            if (stream != null && sessionGeneration != expectedSession) {
                try { stream.close(); } catch (Exception ignored) {}
            }
            if (currentStream == stream) currentStream = null;
            if (currentLine == line) currentLine = null;
            if (sink != null) try { sink.close(); } catch (Exception ignored) {}
            com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.end(entityId, talkToken);
        }
    }
}
