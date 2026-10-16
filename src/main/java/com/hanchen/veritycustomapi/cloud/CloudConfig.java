package com.hanchen.veritycustomapi.cloud;

public class CloudConfig {
    public int code;
    public String message;
    public Data data;
    public Billing billing;

    public boolean isSuccess() {
        return code == 0 && data != null;
    }

    public static class Data {
        public String config_id;
        public Integer version;
        /**
         * 星绒 API 迁移用户的余额可能带小数（1 额度 = 500,000 quota），
         * 因此不能声明为 Integer，否则 Gson 解析小数会抛异常导致整份配置作废。
         */
        public Double credits_remaining;
        public Double credits_total;
        public Pricing pricing;
        public Billing billing;
        public MimoConfig mimo;
        public Llm llm;
        public Stt stt;
        public Tts tts;
        public BuiltinApiConfig builtin_api;
        /**
         * 通用下发通道：任意配置项 -> 值，键用 TOML 点分路径（如 "hud.dialogueHud"），
         * 也接受同构的嵌套对象。上面那些具名字段只覆盖了 30 项，这里把剩下的全接上。
         *
         * 刻意用 JsonObject 而不是 Map&lt;String,Object&gt;：值可能是布尔/数字/字符串，
         * 也可能是嵌套对象，走 Map 会丢掉「数字是整数还是小数」这类信息。
         */
        public com.google.gson.JsonObject overrides;
    }

    public static class Billing {
        @com.google.gson.annotations.SerializedName("protocol_version")
        public Integer protocolVersion;
        /** new_api 表示计费已迁移到星绒 API，Mod 端不再执行旧 Verity 账本扣费。 */
        @com.google.gson.annotations.SerializedName("billing_mode")
        public String billingMode;
        @com.google.gson.annotations.SerializedName("legacy_billing_ignored")
        public Boolean legacyBillingIgnored;
        @com.google.gson.annotations.SerializedName("pre_authorization_required_for_paid_chat")
        public Boolean preAuthorizationRequiredForPaidChat;
        @com.google.gson.annotations.SerializedName("reserve_url")
        public String reserveUrl;
        @com.google.gson.annotations.SerializedName("settle_url")
        public String settleUrl;
        @com.google.gson.annotations.SerializedName("release_url")
        public String releaseUrl;
        @com.google.gson.annotations.SerializedName("deduct_url")
        public String deductUrl;
        @com.google.gson.annotations.SerializedName("reservation_ttl_seconds")
        public Integer reservationTtlSeconds;
    }

    public static class Pricing {
        @com.google.gson.annotations.SerializedName("billing_mode")
        public String billingMode;
        /** 文档 §6.3：迁移用户为 true，表示旧 Verity 账本不再作为扣费来源。 */
        @com.google.gson.annotations.SerializedName("legacy_billing_ignored")
        public Boolean legacyBillingIgnored;
        @com.google.gson.annotations.SerializedName("min_deduct_cost")
        public Integer minDeductCost;
        @com.google.gson.annotations.SerializedName("chat_paid_per_1k_tokens")
        public Integer chatPaidPer1kTokens;
        @com.google.gson.annotations.SerializedName("stt_paid_per_minute")
        public Integer sttPaidPerMinute;
        @com.google.gson.annotations.SerializedName("tts_paid_per_1k_chars")
        public Integer ttsPaidPer1kChars;
        @com.google.gson.annotations.SerializedName("tts_clone_per_1k_chars")
        public Integer ttsClonePer1kChars;
        @com.google.gson.annotations.SerializedName("chat_free_cost")
        public Integer chatFreeCost;
        @com.google.gson.annotations.SerializedName("chat_paid_cost")
        public Integer chatPaidCost;
        @com.google.gson.annotations.SerializedName("stt_free_cost")
        public Integer sttFreeCost;
        @com.google.gson.annotations.SerializedName("stt_paid_cost")
        public Integer sttPaidCost;
        @com.google.gson.annotations.SerializedName("tts_free_cost")
        public Integer ttsFreeCost;
        @com.google.gson.annotations.SerializedName("tts_paid_cost")
        public Integer ttsPaidCost;
        @com.google.gson.annotations.SerializedName("tts_clone_cost")
        public Integer ttsCloneCost;
    }

    public static class MimoConfig {
        @com.google.gson.annotations.SerializedName(value = "api_url", alternate = {"base_url"})
        public String apiUrl;
        @com.google.gson.annotations.SerializedName("api_key")
        public String apiKey;
        public String model;
        @com.google.gson.annotations.SerializedName("route_id")
        public String routeId;
        public String tier;
        /** 文档 §3：云端可下发音色列表路径，例如 /api/v1/mimo/voices。 */
        @com.google.gson.annotations.SerializedName("voices_url")
        public String voicesUrl;
    }

    /** 云端 MiMo 音色 */
    public static class MimoVoice {
        public String id;
        public String name;
        public String type; // "builtin" 或 "clone"
        @com.google.gson.annotations.SerializedName("sample_url")
        public String sampleUrl;
        @com.google.gson.annotations.SerializedName("file_size")
        public long fileSize;
        @com.google.gson.annotations.SerializedName("is_public")
        @com.google.gson.annotations.JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
        public Integer isPublic;
        @com.google.gson.annotations.SerializedName("is_owner")
        @com.google.gson.annotations.JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
        public Integer isOwner;
        @com.google.gson.annotations.SerializedName("is_global")
        @com.google.gson.annotations.JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
        public Integer isGlobal;
        @com.google.gson.annotations.SerializedName("can_toggle")
        @com.google.gson.annotations.JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
        public Integer canToggle;
        @com.google.gson.annotations.SerializedName("created_at")
        public String createdAt;
    }

    public static class Llm {
        public String apiProvider;
        public String apiUrl;
        public String apiKey;
        public String model;
        public Boolean enableCustomPrompt;
        public String selectedPreset;
        public String customPromptFile;
        public Integer requestTimeout;
    }

    public static class Stt {
        public Boolean enableVoiceInput;
        public String mode;
        public String apiProvider;
        public String apiUrl;
        public String apiKey;
        public String model;
        public String requestFormat;
        public String authType;
        public String responseField;
        public String voskModelPath;
        public Integer sampleRate;
    }

    public static class Tts {
        public Boolean enableTts;
        public String mode;
        public String apiProvider;
        public String apiUrl;
        public String apiKey;
        public String apiModel;
        public String apiVoice;
        public Integer sampleRate;
        public Double speed;
        public Double volume;
    }

}
