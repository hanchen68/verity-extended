package com.hanchen.veritycustomapi.cloud;

import com.google.gson.annotations.SerializedName;

public class RouteDto {
    public String tier;
    @SerializedName(value = "route_id", alternate = {"routeId"})
    public String routeId;
    public String name;
    public Boolean enabled;
    public Integer priority;
    @SerializedName(value = "autoFailover", alternate = {"auto_failover"})
    public Boolean autoFailover;
    @SerializedName(value = "failoverAfterErrors", alternate = {"failover_after_errors"})
    public Integer failoverAfterErrors;
    @SerializedName(value = "healthCheckIntervalSeconds", alternate = {"health_check_interval_seconds"})
    public Integer healthCheckIntervalSeconds;
    @SerializedName(value = "apiProvider", alternate = {"api_provider", "provider"})
    public String apiProvider;
    @SerializedName(value = "apiUrl", alternate = {"api_url", "base_url"})
    public String apiUrl;
    @SerializedName(value = "apiKey", alternate = {"api_key"})
    public String apiKey;
    public String model;
    /** 文档 §4.1：线路所属分组，例如 Aistudio-Gemini-2。 */
    @SerializedName(value = "route_group", alternate = {"routeGroup", "group"})
    public String routeGroup;
    @SerializedName(value = "apiModel", alternate = {"api_model"})
    public String apiModel;
    @SerializedName(value = "apiVoice", alternate = {"api_voice"})
    public String apiVoice;
    @SerializedName(value = "requestFormat", alternate = {"request_format"})
    public String requestFormat;
    @SerializedName(value = "authType", alternate = {"auth_type"})
    public String authType;
    @SerializedName(value = "responseField", alternate = {"response_field"})
    public String responseField;
}
