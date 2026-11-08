package com.hanchen.veritycustomapi.cloud;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.config.ModConfig;

/** Local facts only. A model string or credit balance cannot prove remote service health. */
public final class CloudReadiness {
    private CloudReadiness(){}
    public static String describe(String key){
        if(key==null||key.isBlank())return "未连接。可使用网页配对码或浏览器登录。";
        if(ConfigManager.isCloudAuthFailed())return "授权已失效，需要重新连接。";
        if(!ModConfig.CLOUD_ENABLE.get())return "登录已保存，当前使用本机设置。";
        if(!ConfigManager.isCloudConfigLoaded())return "已连接，尚未读取网页设置。";
        return "已连接，网页设置已读取；是否能正常对话需手动测试。";
    }
}
