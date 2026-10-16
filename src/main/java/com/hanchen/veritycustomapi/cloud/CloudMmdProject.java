package com.hanchen.veritycustomapi.cloud;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

public final class CloudMmdProject {
    public String id;
    public String name;
    public String type;
    public String format;
    public long version;
    @SerializedName("file_size") public long fileSize;
    public String sha256;
    @SerializedName("download_url") public String downloadUrl;
    @SerializedName("thumbnail_url") public String thumbnailUrl;
    @SerializedName("is_public") @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false) public int isPublic;
    @SerializedName("is_owner") @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false) public int isOwner;
    @SerializedName("is_global") @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false) public int isGlobal;
    @SerializedName("can_toggle") @JsonAdapter(value = CloudBooleanAdapter.class, nullSafe = false) public int canToggle;
    @SerializedName("created_at") public String createdAt;
    @SerializedName("updated_at") public String updatedAt;
}
