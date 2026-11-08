package com.hanchen.veritycustomapi.cloud;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Blocking protocol client, called exclusively from setup's worker thread. */
public final class ModSetupClient {
    private final URI config,base;
    private final int timeout;
    public ModSetupClient(String url,int seconds){
        config=URI.create(url.trim());
        if(!"https".equalsIgnoreCase(config.getScheme())||config.getHost()==null||config.getUserInfo()!=null||config.getQuery()!=null||config.getFragment()!=null||!config.getPath().endsWith("/getconfig"))throw new IllegalArgumentException("secure getconfig URL required");
        base=config.resolve(config.getPath().substring(0,config.getPath().lastIndexOf('/')+1));timeout=Math.max(1000,Math.min(30000,seconds*1000));
    }
    public JsonObject requestDeviceCode(String id,String version)throws IOException {JsonObject b=new JsonObject();b.addProperty("device_id",id);b.addProperty("device_name","Minecraft · "+System.getProperty("os.name","Desktop"));b.addProperty("mod_version",version);return request("POST","mod/device/code","",b);}
    public JsonObject pollDeviceToken(String code)throws IOException {JsonObject b=new JsonObject();b.addProperty("device_code",code);return request("POST","mod/device/token","",b);}
    public JsonObject catalog(String key)throws IOException{return request("GET","mod/catalog",key,null);}
    public JsonObject preferences(String key)throws IOException{return request("GET","mod/preferences",key,null);}
    public JsonObject playerConfig(String key)throws IOException{return request("GET","mod/player-config",key,null);}
    public JsonObject savePlayerConfig(String key,JsonObject patch)throws IOException{return request("PUT","mod/player-config",key,patch);}
    public JsonObject status(String key)throws IOException{return request("GET","mod/status",key,null);}
    public JsonObject disconnect(String key)throws IOException{return request("POST","mod/disconnect",key,new JsonObject());}
    public JsonObject savePreferences(String key,int version,JsonObject selections)throws IOException{return request("PUT","mod/preferences",key,preferenceBody(version,selections));}
    public static JsonObject preferenceBody(int version,JsonObject selections){
        if(version<0)throw new IllegalArgumentException("invalid preference version");
        JsonObject b=new JsonObject();b.addProperty("scope","device");b.addProperty("expected_version",version);b.addProperty("replace",false);b.add("selections",selections.deepCopy());return b;
    }
    public static boolean appliedVersion(JsonObject saved,JsonObject config){
        if(!saved.has("version")||!config.has("preferences_applied")||!config.get("preferences_applied").isJsonObject())return false;
        JsonObject a=config.getAsJsonObject("preferences_applied");
        return a.has("scope")&&"device".equals(a.get("scope").getAsString())&&a.has("version")&&saved.get("version").getAsInt()==a.get("version").getAsInt();
    }
    public JsonObject getConfig(String key,String clientId,String version)throws IOException{JsonObject b=new JsonObject();b.addProperty("client_id",clientId);b.addProperty("mod_version",version);b.addProperty("billing_protocol_version",2);return request("POST","getconfig",key,b);}
    public URI verificationUri(JsonObject data){
        URI u=URI.create(data.get("verification_uri_complete").getAsString());
        if(!"https".equalsIgnoreCase(u.getScheme())||!config.getHost().equalsIgnoreCase(u.getHost())||u.getPort()!=config.getPort()||u.getUserInfo()!=null||u.getFragment()!=null||!"/cloud/connect.php".equals(u.getPath()))throw new IllegalArgumentException("untrusted verification URL");
        return u;
    }
    private JsonObject request(String method,String path,String key,JsonObject body)throws IOException{
        HttpURLConnection c=(HttpURLConnection)base.resolve(path).toURL().openConnection();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
            if(!key.isBlank())c.setRequestProperty("Authorization","Bearer "+key);
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();
            if(in==null)throw new ApiException(status,"empty_response");
            byte[] bytes;try(in){bytes=in.readNBytes(2*1024*1024+1);}if(bytes.length>2*1024*1024)throw new IOException("response_too_large");
            JsonObject envelope;try{envelope=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException e){throw new ApiException(status,"invalid_response");}
            JsonObject data=envelope.has("data")&&envelope.get("data").isJsonObject()?envelope.getAsJsonObject("data"):new JsonObject();
            if(status!=200||!envelope.has("code")||envelope.get("code").getAsInt()!=0){String reason=data.has("reason")&&!data.get("reason").isJsonNull()?data.get("reason").getAsString():"request_failed";if(!reason.matches("[a-z_]{1,64}"))reason="request_failed";throw new ApiException(status,reason);}
            return data;
        }finally{c.disconnect();}
    }
    public static final class ApiException extends IOException {
        public final int status; public final String reason;
        public ApiException(int status,String reason){super("HTTP "+status+" / "+reason);this.status=status;this.reason=reason;}
    }
}
