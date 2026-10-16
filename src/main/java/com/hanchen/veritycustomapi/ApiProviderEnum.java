package com.hanchen.veritycustomapi;

public enum ApiProviderEnum {
    CUSTOM_OPENAI("自定义 (OpenAI 兼容)", "", ""),
    Ollama("Ollama 本地运行", "http://127.0.0.1:11434/v1/", "qwen2.5:7b"),
    OPENAI("OpenAI 官方", "https://api.openai.com/v1/", "gpt-3.5-turbo"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),       // deepseek-chat 2026-07-24 已下线，默认模型跟着换
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/", "qwen-plus"),
    ZHIPU("智谱 AI", "https://open.bigmodel.cn/api/paas/v4/", "glm-4.7");          // 默认模型从 glm-4-flash 换成 glm-4.7

    public final String displayName;
    public final String apiUrl;
    public final String defaultModel;

    ApiProviderEnum(String displayName, String apiUrl, String defaultModel) {
        this.displayName = displayName;
        this.apiUrl = apiUrl;
        this.defaultModel = defaultModel;
    }

    @Override
    public String toString() {
        return displayName;
    }
}