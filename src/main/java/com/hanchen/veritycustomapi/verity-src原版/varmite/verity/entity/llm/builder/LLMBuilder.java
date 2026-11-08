/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  dev.langchain4j.model.chat.ChatModel
 *  dev.langchain4j.model.google.genai.GoogleGenAiChatModel
 *  dev.langchain4j.model.mistralai.MistralAiChatModel
 *  dev.langchain4j.model.mistralai.MistralAiChatModelName
 *  dev.langchain4j.model.ollama.OllamaChatModel
 *  dev.langchain4j.model.ollama.OllamaChatModel$OllamaChatModelBuilder
 *  dev.langchain4j.model.openai.OpenAiChatModel
 */
package varmite.verity.entity.llm.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModelName;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import varmite.verity.VerityConfig;
import varmite.verity.types.AiProvider;

public class LLMBuilder {
    private AiProvider provider;
    private String model;
    private String endpoint;
    private String apiKey;
    private boolean thinking = false;

    public LLMBuilder setProvider(AiProvider provider) {
        this.provider = provider;
        return this;
    }

    public LLMBuilder setModel(String model) {
        this.model = model;
        return this;
    }

    public LLMBuilder setEndpoint(String endpoint) {
        this.endpoint = endpoint.replace("localhost", "127.0.0.1");
        return this;
    }

    public LLMBuilder setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public LLMBuilder setThinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    public ChatModel build() {
        return switch (this.provider) {
            default -> throw new IncompatibleClassChangeError();
            case AiProvider.OLLAMA -> ((OllamaChatModel.OllamaChatModelBuilder)((OllamaChatModel.OllamaChatModelBuilder)((OllamaChatModel.OllamaChatModelBuilder)((OllamaChatModel.OllamaChatModelBuilder)((OllamaChatModel.OllamaChatModelBuilder)((OllamaChatModel.OllamaChatModelBuilder)OllamaChatModel.builder().baseUrl(this.endpoint)).modelName(this.model)).numCtx(Integer.valueOf(16000))).think(Boolean.valueOf(this.thinking))).temperature(Double.valueOf(0.8))).logResponses((Boolean)VerityConfig.DEV_MODE.get())).build();
            case AiProvider.OPENAI -> OpenAiChatModel.builder().baseUrl(this.endpoint).apiKey(this.apiKey).modelName(this.model).returnThinking(Boolean.valueOf(this.thinking)).temperature(Double.valueOf(0.8)).logResponses((Boolean)VerityConfig.DEV_MODE.get()).build();
            case AiProvider.GEMINI -> GoogleGenAiChatModel.builder().apiKey(this.apiKey).modelName(this.model).temperature(Double.valueOf(0.8)).logResponses((Boolean)VerityConfig.DEV_MODE.get()).build();
            case AiProvider.GROQ -> OpenAiChatModel.builder().baseUrl("https://api.groq.com/openai/v1/").apiKey(this.apiKey).modelName(this.model).returnThinking(Boolean.valueOf(this.thinking)).temperature(Double.valueOf(0.8)).logResponses((Boolean)VerityConfig.DEV_MODE.get()).build();
            case AiProvider.OPENROUTER -> OpenAiChatModel.builder().baseUrl("https://openrouter.ai/api/v1/").apiKey(this.apiKey).modelName(this.model).returnThinking(Boolean.valueOf(this.thinking)).temperature(Double.valueOf(0.8)).logResponses((Boolean)VerityConfig.DEV_MODE.get()).build();
            case AiProvider.MISTRAL -> MistralAiChatModel.builder().apiKey(this.apiKey).modelName(MistralAiChatModelName.MISTRAL_SMALL_LATEST).temperature(Double.valueOf(0.8)).logResponses((Boolean)VerityConfig.DEV_MODE.get()).build();
        };
    }
}

