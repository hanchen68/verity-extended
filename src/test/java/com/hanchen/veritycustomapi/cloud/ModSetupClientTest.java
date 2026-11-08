package com.hanchen.veritycustomapi.cloud;
import com.google.gson.*;
public final class ModSetupClientTest {
    public static void main(String[] args){
        ModSetupClient client=new ModSetupClient("https://verity.xrstudio.cn/api/v1/getconfig",10);
        JsonObject uri=new JsonObject();uri.addProperty("verification_uri_complete","https://verity.xrstudio.cn/cloud/connect.php?code=ABCD-EFGH");
        check(client.verificationUri(uri).getHost().equals("verity.xrstudio.cn"));
        for(String u:new String[]{"http://verity.xrstudio.cn/cloud/connect.php","https://evil.example/cloud/connect.php","https://verity.xrstudio.cn@evil.example/cloud/connect.php","https://verity.xrstudio.cn:8443/cloud/connect.php","https://verity.xrstudio.cn/redirect"}){
            uri.addProperty("verification_uri_complete",u);boolean rejected=false;try{client.verificationUri(uri);}catch(RuntimeException e){rejected=true;}check(rejected);
        }
        boolean invalid=false;try{new ModSetupClient("http://localhost/api/v1/getconfig",10);}catch(IllegalArgumentException e){invalid=true;}check(invalid);
        JsonObject sel=new JsonObject();JsonObject voice=new JsonObject();voice.addProperty("voice_id","白桦");sel.add("tts",voice);
        JsonObject body=ModSetupClient.preferenceBody(3,sel);check(body.get("scope").getAsString().equals("device"));check(!body.get("replace").getAsBoolean());check(body.get("expected_version").getAsInt()==3);
        voice.addProperty("voice_id","changed");check(body.getAsJsonObject("selections").getAsJsonObject("tts").get("voice_id").getAsString().equals("白桦"));
        JsonObject saved=JsonParser.parseString("{\"version\":4}").getAsJsonObject();
        check(ModSetupClient.appliedVersion(saved,JsonParser.parseString("{\"preferences_applied\":{\"scope\":\"device\",\"version\":4}}").getAsJsonObject()));
        check(!ModSetupClient.appliedVersion(saved,JsonParser.parseString("{\"preferences_applied\":{\"scope\":\"account\",\"version\":4}}").getAsJsonObject()));
        check(!ModSetupClient.appliedVersion(saved,JsonParser.parseString("{\"preferences_applied\":{\"scope\":\"device\",\"version\":3}}").getAsJsonObject()));
        check(!ModSetupClient.appliedVersion(saved,new JsonObject()));
        System.out.println("ModSetupClient URI, device scope, partial update and version checks passed");
    }
    private static void check(boolean value){if(!value)throw new AssertionError("setup contract check failed");}
}
