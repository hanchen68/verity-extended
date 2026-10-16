package com.hanchen.veritycustomapi;

public enum TtsApiProviderEnum {
    CUSTOM_OPENAI("自定义 (OpenAI 兼容)", "", "", "alloy", "openai", "bearer", "wav", "audio"),
    OPENAI_TTS("OpenAI 官方 TTS", "https://api.openai.com/v1/audio/speech", "tts-1", "alloy", "openai", "bearer", "wav", "audio"),
    KOKORO("Kokoro 本地 TTS", "http://127.0.0.1:8880/v1/audio/speech", "kokoro", "am_fenrir", "openai", "bearer", "wav", "audio"),
    ALIBABA_TTS("阿里云 TTS", "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts", "", "zh-CN-XiaoxiaoNeural", "alibaba", "alibaba", "wav", "audio"),
    TENCENT_TTS("腾讯云 TTS", "https://tts.tencentcloudapi.com/", "", "Zhiyu", "tencent", "tencent", "wav", "audio"),
    BAIDU_TTS("百度智能云 TTS", "https://tsn.baidu.com/text2audio", "", "0", "baidu", "baidu", "mp3", "audio"),
    BYTEDANCE_TTS("火山引擎 TTS", "https://openspeech.bytedance.com/api/v1/tts", "", "", "bytedance", "bearer", "wav", "audio"),   // 域名从 tts.bytedance.net 迁到 openspeech.bytedance.com
    IFLYTEK_TTS("讯飞开放平台 TTS (WebSocket)", "wss://tts-api.xfyun.cn/v2/tts", "", "xiaoyan", "iflytek", "iflytek", "wav", "audio"),  // 走 WebSocket，不是 HTTP
    SILICONFLOW_TTS("硅基流动 TTS", "https://api.siliconflow.cn/v1/audio/speech", "FunAudioLLM/CosyVoice2-0.5B", "", "siliconflow", "bearer", "mp3", "audio"),
    MOONSHOT_TTS("Moonshot 语音", "https://api.moonshot.cn/v1/audio/speech", "", "zh-CN-1", "moonshot", "bearer", "wav", "audio"),
    QIANWAN_TTS("千帆大模型 TTS", "https://qianwan.bce.baidu.com/api/tts", "", "", "qianwan", "bearer", "wav", "audio"),
    // Fish Audio 的请求体是 text + reference_id（音色模型 id），和 OpenAI 那套 model/input/voice 不同，
    // 所以单独走 fish 分支。defaultVoice 留空，音色必须在配置里填（Reference ID）。
    FISH_TTS("Fish Audio", "https://api.fish.audio/v1/tts", "", "", "fish", "bearer", "wav", "audio"),
    MIMO_TTS("小米 MiMo TTS", "https://api.xiaomimimo.com/v1/chat/completions", "mimo-v2.5-tts", "苏打", "mimo", "api-key", "wav", "base64"),
    // Groq 的请求体与 OpenAI 完全一致，直接复用 openai 分支，不用单独写 builder。
    GROQ_TTS("Groq", "https://api.groq.com/openai/v1/audio/speech", "", "", "openai", "bearer", "wav", "audio"),
    MINIMAX_TTS("MiniMax", "https://api.minimax.cn/v1/t2a_v2", "", "", "minimax", "bearer", "wav", "audio"),
    // Cartesia 用 X-API-Key 头，并且必须带 Cartesia-Version 头。
    CARTESIA_TTS("Cartesia", "https://api.cartesia.ai/tts/bytes", "", "", "cartesia", "x-api-key", "wav", "audio"),
    // 通义千问 / CosyVoice：地址按自己的区域自行填写（北京/新加坡端点不同），
    // 模型可用 cosyvoice-v2 / cosyvoice-v3 / qwen-audio-3.0-tts 等；响应是 JSON 里的音频链接。
    QWEN_TTS("通义千问 / CosyVoice", "", "cosyvoice-v2", "", "qwen", "bearer", "wav", "url"),
    // Moss（魔音工坊，mosi.cn）：响应是 JSON 里的音频下载链接。
    MOSS_TTS("Moss 魔音工坊", "https://api.mosi.cn/v1/audio/speech", "", "", "moss", "bearer", "wav", "url");

    public final String displayName;
    public final String apiUrl;
    public final String defaultModel;
    public final String defaultVoice;
    public final String requestFormat;
    public final String authType;
    public final String responseFormat;
    public final String responseType;

    TtsApiProviderEnum(String displayName, String apiUrl, String defaultModel, String defaultVoice, 
                       String requestFormat, String authType, String responseFormat, String responseType) {
        this.displayName = displayName;
        this.apiUrl = apiUrl;
        this.defaultModel = defaultModel;
        this.defaultVoice = defaultVoice;
        this.requestFormat = requestFormat;
        this.authType = authType;
        this.responseFormat = responseFormat;
        this.responseType = responseType;
    }

    @Override
    public String toString() {
        return displayName;
    }
}