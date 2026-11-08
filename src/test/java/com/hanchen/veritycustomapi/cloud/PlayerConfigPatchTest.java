package com.hanchen.veritycustomapi.cloud;
import com.google.gson.*;
public final class PlayerConfigPatchTest {
 public static void main(String[] args){
  PlayerConfigPatch p=new PlayerConfigPatch();check(p.empty());
  p.secret("services.chat.custom.api_key","");check(p.empty());
  p.put("services.chat.custom.model",new JsonPrimitive("model-a"));JsonObject b=p.body(7);check(!b.getAsJsonObject("services").getAsJsonObject("chat").has("source"));check(PlayerConfigPatch.get(b,"services.chat.custom.api_key").isJsonNull());
  p.secret("services.chat.custom.api_key","test-secret");check(!p.value("services.chat.custom.api_key").isJsonNull());p.clearSecret("services.chat.custom.api_key");check(p.value("services.chat.custom.api_key").isJsonNull());check(p.value("services.chat.custom.clear_api_key").getAsBoolean());
  p.secret("services.chat.custom.api_key","replacement");check(p.value("services.chat.custom.clear_api_key").isJsonNull());
  p.secret("services.chat.custom.api_key","");check(p.value("services.chat.custom.api_key").isJsonNull());
  JsonObject saved=json("{\"config\":{\"version\":8}}");
  JsonObject runtime=json("{\"version\":8,\"llm\":{\"model\":\"model-a\"},\"builtin_api\":{\"enableChatApis\":false}}");
  check(PlayerConfigPatch.delivered(saved,runtime,p.body(7)));
  runtime.addProperty("version",9);check(!PlayerConfigPatch.delivered(saved,runtime,p.body(7)));runtime.addProperty("version",8);
  p.put("services.chat.source",new JsonPrimitive("custom"));check(PlayerConfigPatch.delivered(saved,runtime,p.body(7)));runtime.getAsJsonObject("builtin_api").addProperty("enableChatApis",true);check(!PlayerConfigPatch.delivered(saved,runtime,p.body(7)));
  p.clear();check(p.empty());p.secret("advanced.voice_language.translate_api_key","__SAVED__");check(p.empty());
  // 🔴 偏好（选了哪个模型/音色）不能算配置改动，否则每换一次音色云端就多一个版本。
  java.util.Set<String> pref=java.util.Set.of("route_id","voice_id");
  p.clear();p.put("services.tts.route_id",new JsonPrimitive("v1"));check(p.without(pref).size()==0);
  p.clear();p.put("services.tts.voice_id",new JsonPrimitive("xiaoyu"));check(p.without(pref).size()==0);
  p.clear();p.put("services.tts.custom.api_model",new JsonPrimitive("m"));check(p.without(pref).size()>0);
  p.clear();p.put("services.chat.source",new JsonPrimitive("custom"));check(p.without(pref).size()>0);
  p.clear();p.put("services.tts.route_id",new JsonPrimitive("v1"));p.put("services.tts.custom.api_key",new JsonPrimitive("sk"));
  JsonObject stripped=p.without(pref);check(stripped.has("services")&&stripped.getAsJsonObject("services").getAsJsonObject("tts").size()==1);
  // 去掉偏好后剩余内容才是版本变更依据；纯偏好改动不得触发配置提交。
  System.out.println("PlayerConfigPatch sparse updates, secret tri-state, source and delivery checks passed");
 }
 private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
 private static void check(boolean b){if(!b)throw new AssertionError("player-config contract failure");}
}
