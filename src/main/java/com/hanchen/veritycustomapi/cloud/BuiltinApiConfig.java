package com.hanchen.veritycustomapi.cloud;

import com.google.gson.annotations.SerializedName;

/** v2 云端内置 API 配置。支持 free/paid 双线路。 */
public class BuiltinApiConfig {
    public Boolean enableChatApis;
    public Boolean enableSttApis;
    public Boolean enableTtsApis;
    @SerializedName(value = "chatTier", alternate = {"chat_tier"})
    public String chatTier;
    @SerializedName(value = "sttTier", alternate = {"stt_tier"})
    public String sttTier;
    @SerializedName(value = "ttsTier", alternate = {"tts_tier"})
    public String ttsTier;
    @SerializedName(value = "chatRouteId", alternate = {"chat_route_id"})
    public String chatRouteId;
    @SerializedName(value = "sttRouteId", alternate = {"stt_route_id"})
    public String sttRouteId;
    @SerializedName(value = "ttsRouteId", alternate = {"tts_route_id"})
    public String ttsRouteId;
    public RoutePolicy routePolicy;
    public BuiltinChatTierConfig chat;
    public BuiltinSttTierConfig stt;
    public BuiltinTtsTierConfig tts;
    public String mimoTtsVoice;
    /**
     * 文档 §8 伪代码在 builtin_api 上读取 billing_mode；
     * 文档 §6.3 又把它放在 pricing / billing 里，因此三处都解析并做兜底判断。
     */
    @SerializedName(value = "billing_mode", alternate = {"billingMode"})
    public String billingMode;
}
