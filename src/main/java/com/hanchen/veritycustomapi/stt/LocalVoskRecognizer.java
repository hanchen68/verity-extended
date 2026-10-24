package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@OnlyIn(Dist.CLIENT)
public class LocalVoskRecognizer implements SpeechRecognizer {

    private final Model voskModel;
    private final boolean jnaUtf8Mode;
    private final String modelPath;

    public LocalVoskRecognizer(String modelPath) {
        this.modelPath = modelPath;
        Model tempModel = null;
        try {
            File modelDir = new File(modelPath);
            if (!modelDir.exists()) {
                OllamaProxyMod.LOGGER.error("Vosk模型目录不存在: {}", modelDir.getAbsolutePath());
            } else if (!modelDir.isDirectory()) {
                OllamaProxyMod.LOGGER.error("Vosk模型路径不是目录: {}", modelDir.getAbsolutePath());
            } else {
                File amFile = new File(modelDir, "am/final.mdl");
                if (!amFile.exists()) {
                    OllamaProxyMod.LOGGER.warn("Vosk模型缺少 am/final.mdl 文件，可能不是完整模型");
                }
                tempModel = new Model(modelPath);
                OllamaProxyMod.LOGGER.info("Vosk模型加载成功，路径: {}", modelDir.getAbsolutePath());
            }
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.error("Vosk模型加载失败: {}", modelPath, e);
            tempModel = null;
        } catch (UnsatisfiedLinkError e) {
            OllamaProxyMod.LOGGER.error("Vosk本地库加载失败，请确保已安装Vosk本地依赖", e);
            tempModel = null;
        } catch (Throwable e) {
            // 必须是 Throwable 而不是 Exception：缺 JNA（com.sun.jna）或原生库架构不匹配时
            // 抛的是 NoClassDefFoundError / ExceptionInInitializerError 这类 Error。
            // 只捕 Exception 会让它逃到调用方，直接把 STT 初始化线程干掉（而且没有可读提示）。
            OllamaProxyMod.LOGGER.error("Vosk模型初始化失败（{}）", e.getClass().getSimpleName(), e);
            tempModel = null;
        }
        this.voskModel = tempModel;
        this.jnaUtf8Mode = "UTF-8".equalsIgnoreCase(System.getProperty("jna.encoding"));
    }

    @Override
    public String recognize(byte[] pcmData) throws Exception {
        if (voskModel == null) {
            return "";
        }
        
        if (pcmData == null || pcmData.length < 100) {
            return "";
        }
        
        Recognizer recognizer = new Recognizer(voskModel, 16000f);
        recognizer.setWords(true);
        recognizer.setPartialWords(true);
        
        try {
            boolean accepted = recognizer.acceptWaveForm(pcmData, pcmData.length);
            String rawResult = accepted ? recognizer.getResult() : recognizer.getFinalResult();
            
            if (rawResult == null || rawResult.isEmpty()) {
                rawResult = recognizer.getPartialResult();
            }
            
            // jna.encoding 已由 OllamaProxyMod/VoskNative 程序化设为 UTF-8
            // 此处的检测仅作为兜底：如果 JNA 在更早时机已被其他库初始化，结果可能仍乱码
            if (!jnaUtf8Mode) {
                try {
                    if (rawResult != null) {
                        // 检测是否已含有效中文字符 -> 说明编码正确，不需要转换
                        if (!containsValidChinese(rawResult)) {
                            rawResult = new String(rawResult.getBytes("GBK"), StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception e) { Silent.ignore("LocalVoskRecognizer", e); }
            }
            
            String result = parseTextFromJson(rawResult);
            
            if (result.isEmpty()) {
                result = parseTextFromJson(recognizer.getPartialResult());
            }
            
            return result;
        } finally {
            recognizer.close();
        }
    }

    @Override
    public boolean isReady() {
        return voskModel != null;
    }

    @Override
    public void shutdown() {
        if (voskModel != null) {
            try {
                voskModel.close();
            } catch (Exception e) { Silent.ignore("LocalVoskRecognizer", e); }
        }
    }

    private String parseTextFromJson(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) {
            return "";
        }

        String keyCompact = "\"text\":\"";
        String keySpaced = "\"text\" : \"";
        String keyPartial = "\"partial\":\"";
        String keyPartialSpaced = "\"partial\" : \"";

        int startIdx = rawJson.indexOf(keyCompact);
        int keyLen = keyCompact.length();

        if (startIdx == -1) {
            startIdx = rawJson.indexOf(keySpaced);
            keyLen = keySpaced.length();
        }

        if (startIdx == -1) {
            startIdx = rawJson.indexOf(keyPartial);
            keyLen = keyPartial.length();
        }

        if (startIdx == -1) {
            startIdx = rawJson.indexOf(keyPartialSpaced);
            keyLen = keyPartialSpaced.length();
        }

        if (startIdx == -1) {
            int bracketStart = rawJson.indexOf('[');
            if (bracketStart != -1) {
                int bracketEnd = rawJson.indexOf(']', bracketStart);
                if (bracketEnd != -1) {
                    String arrayContent = rawJson.substring(bracketStart + 1, bracketEnd);
                    int textStart = arrayContent.indexOf(keyCompact);
                    if (textStart != -1) {
                        startIdx = bracketStart + 1 + textStart;
                        keyLen = keyCompact.length();
                    }
                }
            }
        }

        if (startIdx == -1) {
            return "";
        }

        startIdx += keyLen;
        int endIdx = rawJson.indexOf("\"", startIdx);
        if (endIdx == -1) {
            return "";
        }

        String result = rawJson.substring(startIdx, endIdx).trim();
        result = result.replaceAll("[\\p{C}\\p{Z}]+", " ").trim();
        result = result.replaceAll("\\s+", " ");
        
        return result;
    }

    /**
     * 检测字符串中是否包含有效中文字符（Unicode CJK统一表意文字范围）
     * 用于判断 Vosk 返回结果是否已经是正确的 UTF-8 中文，避免误做 GBK 转换
     */
    private static boolean containsValidChinese(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF)     // CJK统一表意文字
                    || (c >= 0x3400 && c <= 0x4DBF)  // CJK扩展A
                    || (c >= 0x20000 && c <= 0x2A6DF)) { // CJK扩展B
                return true;
            }
        }
        return false;
    }
}