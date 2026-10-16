package com.hanchen.veritycustomapi.cloud;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;

/**
 * 云端皮肤数据模型
 */
public class CloudSkin {
    public long id;
    /** 皮肤标识（唯一，用于存储） */
    public String name;
    /** 显示名称 */
    @SerializedName("display_name")
    public String displayName;
    /** ZIP 下载地址 */
    @SerializedName("download_url")
    public String downloadUrl;
    /** 缩略图 URL（可选） */
    @SerializedName("thumbnail_url")
    public String thumbnailUrl;
    @SerializedName("file_size")
    public long fileSize;
    @SerializedName("is_public")
    @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
    public int isPublic;
    @SerializedName("is_owner")
    @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
    public int isOwner;
    @SerializedName("is_global")
    @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
    public int isGlobal;
    @SerializedName("can_toggle")
    @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false)
    public int canToggle;
    @SerializedName("created_at")
    public String createdAt;
}

/**
 * 云端皮肤列表响应
 */
class CloudSkinListResponse {
    public int code;
    public String message;
    public CloudSkinData data;

    boolean isSuccess() {
        return code == 0 && data != null;
    }
}

class CloudSkinData {
    public CloudSkin[] skins;
}
