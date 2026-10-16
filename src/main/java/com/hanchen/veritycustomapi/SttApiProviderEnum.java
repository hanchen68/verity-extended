package com.hanchen.veritycustomapi;

public enum SttApiProviderEnum {
    CUSTOM_OPENAI("自定义 (OpenAI兼容)", "", "", AuthType.BEARER),
    OPENAI("OpenAI", "https://api.openai.com/v1/audio/transcriptions", "whisper-1", AuthType.BEARER),
    GROQ("Groq", "https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3", AuthType.BEARER),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.cn/v1/audio/transcriptions", "FunAudioLLM/SenseVoiceSmall", AuthType.BEARER),
    AZURE("Azure", "https://{region}.cognitiveservices.azure.com/openai/deployments/{deployment}/audio/transcriptions?api-version=2024-06-01", "whisper-1", AuthType.BEARER),
    BYTEDANCE("字节跳动火山引擎", "https://openspeech.bytedance.com/api/v1/transcription", "", AuthType.BEARER),   // 域名从 speech.bytedance.net 迁到 openspeech.bytedance.com
    
    ALIBABA("阿里云", "https://nls-gateway.cn-shanghai.aliyuncs.com/stream/v1/asr", "", AuthType.ALIBABA),
    TENCENT("腾讯云", "https://asr.tencentcloudapi.com", "", AuthType.TENCENT),
    BAIDU("百度智能云", "https://vop.baidu.com/server_api", "", AuthType.BAIDU),
    IFLYTEK("科大讯飞 (WebSocket)", "wss://iat-api.xfyun.cn/v2/iat", "", AuthType.IFLYTEK);  // 走 WebSocket，不是 HTTP

    public final String displayName;
    public final String apiUrl;
    public final String defaultModel;
    public final AuthType authType;

    SttApiProviderEnum(String displayName, String apiUrl, String defaultModel, AuthType authType) {
        this.displayName = displayName;
        this.apiUrl = apiUrl;
        this.defaultModel = defaultModel;
        this.authType = authType;
    }

    public enum AuthType {
        BEARER,
        ALIBABA,
        TENCENT,
        BAIDU,
        IFLYTEK
    }

    @Override
    public String toString() {
        return displayName;
    }
}