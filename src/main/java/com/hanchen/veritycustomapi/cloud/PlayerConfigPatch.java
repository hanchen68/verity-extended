package com.hanchen.veritycustomapi.cloud;

import com.google.gson.*;
import java.util.*;

/** Sparse typed edits; never serialize read-only metadata or masked credential objects. */
public final class PlayerConfigPatch {
    private final JsonObject changes=new JsonObject();
    public void put(String path,JsonElement value){
        String[] parts=path.split("\\.");JsonObject at=changes;
        for(int i=0;i<parts.length-1;i++){if(!at.has(parts[i]))at.add(parts[i],new JsonObject());at=at.getAsJsonObject(parts[i]);}
        at.add(parts[parts.length-1],value.deepCopy());
    }
    public void remove(String path){String[] parts=path.split("\\.");JsonObject at=changes;for(int i=0;i<parts.length-1;i++){if(!at.has(parts[i])||!at.get(parts[i]).isJsonObject())return;at=at.getAsJsonObject(parts[i]);}at.remove(parts[parts.length-1]);}
    public JsonElement value(String path){return get(changes,path);}
    public boolean empty(){return !meaningful(changes);}
    private static boolean meaningful(JsonObject object){for(var e:object.entrySet()){if(!e.getValue().isJsonObject()||meaningful(e.getValue().getAsJsonObject()))return true;}return false;}
    /**
     * Changes with these trailing keys are device preferences (which model/voice is chosen),
     * not configuration. They must go through preferences, never create a config version.
     */
    private static final java.util.Set<String> PREFERENCE_LEAVES =
            java.util.Set.of("route_id", "voice_id");

    /** Copy of the patch with preference-only leaves removed; empty result means no config change. */
    public JsonObject without(java.util.Set<String> leaves) {
        return strip(changes.deepCopy(), leaves);
    }

    private static JsonObject strip(JsonObject node, java.util.Set<String> leaves) {
        for (String key : new java.util.ArrayList<>(node.keySet())) {
            JsonElement value = node.get(key);
            if (value.isJsonObject()) {
                JsonObject child = strip(value.getAsJsonObject(), leaves);
                if (child.size() == 0) node.remove(key); else node.add(key, child);
            } else if (leaves.contains(key)) {
                node.remove(key);
            }
        }
        return node;
    }

    public JsonObject body(int version){if(version<0)throw new IllegalArgumentException("version required");JsonObject b=changes.deepCopy();b.addProperty("expected_version",version);b.addProperty("scope","device");return b;}
    public void secret(String path,String input){
        if(input==null||input.isBlank()||input.equals("__SAVED__")){remove(path);return;}
        remove(clearPath(path));put(path,new JsonPrimitive(input));
    }
    public void clearSecret(String path){remove(path);put(clearPath(path),new JsonPrimitive(true));}
    private static String clearPath(String path){int dot=path.lastIndexOf('.');return path.substring(0,dot+1)+"clear_"+path.substring(dot+1);}
    public void clear(){changes.entrySet().clear();}
    public static JsonElement get(JsonObject root,String path){JsonElement at=root;for(String p:path.split("\\.")){if(!at.isJsonObject()||!at.getAsJsonObject().has(p))return JsonNull.INSTANCE;at=at.getAsJsonObject().get(p);}return at;}
    /** Verify exact saved version, explicit source and changed custom fields against runtime delivery. */
    public static boolean delivered(JsonObject saved,JsonObject runtime,JsonObject patch){
        JsonElement v=get(saved,"config.version");if(v.isJsonNull()||!runtime.has("version")||v.getAsInt()!=runtime.get("version").getAsInt())return false;
        Map<String,String> segments=Map.of("chat","llm","stt","stt","tts","tts");
        Map<String,String> enable=Map.of("chat","enableChatApis","stt","enableSttApis","tts","enableTtsApis");
        Map<String,String> names=Map.ofEntries(Map.entry("api_provider","apiProvider"),Map.entry("api_url","apiUrl"),Map.entry("api_key","apiKey"),Map.entry("request_timeout","requestTimeout"),Map.entry("request_format","requestFormat"),Map.entry("auth_type","authType"),Map.entry("response_field","responseField"),Map.entry("sample_rate","sampleRate"),Map.entry("vosk_model_path","voskModelPath"),Map.entry("api_model","apiModel"),Map.entry("api_voice","apiVoice"));
        JsonElement services=get(patch,"services");if(services.isJsonObject())for(var svc:services.getAsJsonObject().entrySet()){
            if(!segments.containsKey(svc.getKey()))return false;
            JsonObject body=svc.getValue().getAsJsonObject();String segment=segments.get(svc.getKey());
            if(body.has("source")){
                String source=body.get("source").getAsString();JsonElement enabled=get(runtime,"builtin_api."+enable.get(svc.getKey()));
                if(enabled.isJsonNull()||enabled.getAsBoolean()!=source.equals("builtin"))return false;
                if(!source.equals("builtin")&&!svc.getKey().equals("chat")){
                    String mode=source.equals("local")?"local":svc.getKey().equals("stt")?"api":"online";
                    if(!new JsonPrimitive(mode).equals(get(runtime,segment+".mode")))return false;
                }
            }
            if(body.has("custom"))for(var field:body.getAsJsonObject("custom").entrySet()){
                if(field.getKey().equals("clear_api_key")){JsonElement key=get(runtime,segment+".apiKey");if(field.getValue().getAsBoolean()&&!key.isJsonNull()&&!key.getAsString().isBlank())return false;continue;}
                String name=names.getOrDefault(field.getKey(),field.getKey());if(!field.getValue().equals(get(runtime,segment+"."+name)))return false;
            }
        }
        JsonElement advanced=get(patch,"advanced");if(advanced.isJsonObject())for(var group:advanced.getAsJsonObject().entrySet())for(var field:group.getValue().getAsJsonObject().entrySet()){
            String name=field.getKey();if(name.equals("translate_model"))name="translateModel";if(name.equals("translate_url"))name="translateUrl";if(name.equals("translate_api_key"))name="translateApiKey";
            JsonElement overrides=get(runtime,"overrides");JsonElement actual=overrides.isJsonObject()?overrides.getAsJsonObject().get(group.getKey()+"."+name):null;
            if(field.getKey().equals("clear_translate_api_key")){if(field.getValue().getAsBoolean()&&overrides.isJsonObject()){JsonElement key=overrides.getAsJsonObject().get("voice_language.translateApiKey");if(key!=null&&!key.isJsonNull()&&!key.getAsString().isBlank())return false;}}
            else if(actual==null||!field.getValue().equals(actual))return false;
        }
        return true;
    }
}
