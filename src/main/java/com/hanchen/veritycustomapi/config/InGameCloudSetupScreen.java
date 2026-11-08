package com.hanchen.veritycustomapi.config;

import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.*;
import com.hanchen.veritycustomapi.cloud.*;
import com.hanchen.veritycustomapi.client.ClientEventHandler;
import com.hanchen.veritycustomapi.client.ScreenBackgrounds;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Connection only. Both authorization routes converge on sync, not on a second settings editor. */
public final class InGameCloudSetupScreen extends Screen {
    public enum Route { CHOOSE, PAIR, BROWSER, CONNECTED }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Verity-Connect");t.setDaemon(true);return t;});
    private final Screen parent;
    private Route route;
    private ModSetupClient api;
    private String key="",pairDraft="",deviceCode="",userCode="",message="选择你熟悉的连接方式。",address;
    private URI verify;
    private EditBox codeBox;
    private boolean active,busy,syncing,synced,error,reauthorizing;
    private int generation,interval=5,scroll;
    private long expiresAt,nextPoll,syncStarted;
    public InGameCloudSetupScreen(Screen parent){this(parent,Route.CHOOSE);}
    public InGameCloudSetupScreen(Screen parent,Route route){super(Component.literal("连接与云端服务"));this.parent=parent;this.route=route;}
    private int left(){return Math.max(12,(width-Math.min(490,width-24))/2);}
    private int span(){return width-2*left();}
    @Override protected void init(){
        active=true;address=ConfigManager.getCloudApiUrl();
        try{if(api==null)api=new ModSetupClient(address,ConfigManager.getCloudTimeoutSeconds());}catch(RuntimeException e){message="云端地址无效，请在高级设置中检查。";error=true;}
        key=ConfigManager.getCloudApiKey();if(!key.isBlank()&&!reauthorizing)route=Route.CONNECTED;
        controls();
    }
    private Button button(int y,String label,Runnable run){Button b=addRenderableWidget(VerityUiWidgets.secondaryButton(font,left(),y,span(),20,Component.literal(label),ignored->run.run()));b.active=!busy&&!syncing;return b;}
    private void controls(){
        clearWidgets();codeBox=null;int y=50;
        if(route==Route.CHOOSE){
            button(y,"网页已配好 / 我有 6 位配对码",()->change(Route.PAIR));
            button(y+28,"浏览器登录 / 在游戏中选择服务",()->change(Route.BROWSER));
            button(y+56,"不用云端，填写自己的 API",()->minecraft.setScreen(NativeOllamaProxyConfigScreen.openPage(parent,1)));
        }else if(route==Route.PAIR){
            codeBox=VerityUiWidgets.roundedEditBox(font,left(),y,span(),20,Component.literal("网页生成的 6 位配对码"));codeBox.setMaxLength(32);codeBox.setValue(pairDraft);codeBox.setHint(Component.literal("在此粘贴网页生成的 6 位码"));codeBox.setResponder(v->pairDraft=v);codeBox.setEditable(!busy);addRenderableWidget(codeBox);
            button(y+28,"连接并读取网页设置",this::exchange);
            int half=(span()-4)/2;Button open=addRenderableWidget(VerityUiWidgets.secondaryButton(font,left(),y+56,half,20,Component.literal("打开云端网页"),b->openPortal()));open.active=!busy;
            Button paste=addRenderableWidget(VerityUiWidgets.secondaryButton(font,left()+half+4,y+56,half,20,Component.literal("粘贴配对码"),b->{pairDraft=minecraft.keyboardHandler.getClipboard().trim();codeBox.setValue(pairDraft);}));paste.active=!busy;
        }else if(route==Route.BROWSER){
            if(verify==null)button(y,"获取登录链接",this::begin);
            else{button(y,"打开浏览器，登录并允许连接",()->open(verify));button(y+28,"复制链接（浏览器打不开时使用）",()->{minecraft.keyboardHandler.setClipboard(verify.toString());message="链接已复制。可在本机浏览器或手机中打开。";});button(y+56,"重新获取登录链接",this::begin);}
        }else{
            button(y,syncing?"正在读取网页设置…":synced?"设置已读取 · 完成连接":"重新读取网页设置",()->{if(synced)onClose();else sync();});
            button(y+28,"选择模型、音色或自定义 API",()->minecraft.setScreen(new PlayerServiceConfigScreen(this)));
            button(y+56,"测试连接（手动，可消耗额度）",()->minecraft.setScreen(new ConfigHealthCheckScreen(this,false)));
        }
        int bottom=height-28;int half=(span()-4)/2;
        Button back=addRenderableWidget(VerityUiWidgets.secondaryButton(font,left(),bottom,half,20,Component.literal(route==Route.CONNECTED?"重新连接 / 更换账户":route==Route.CHOOSE?"返回":"换一种连接方式"),b->{if(route==Route.CONNECTED){reauthorizing=true;change(Route.CHOOSE);}else if(route==Route.CHOOSE)onClose();else change(Route.CHOOSE);}));back.active=!busy&&!syncing;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,left()+half+4,bottom,half,20,Component.literal("关闭"),b->onClose())).active=true;
    }
    private void change(Route next){generation++;route=next;deviceCode="";verify=null;userCode="";error=false;scroll=0;message=next==Route.PAIR?"① 网页选好配置  ② 生成配对码  ③ 粘贴并连接":next==Route.BROWSER?"① 获取链接  ② 浏览器允许连接  ③ 回游戏等待":"选择一种方式即可，不用两种都做。";controls();}
    private void openPortal(){try{URI u=URI.create(address);URI portal=new URI(u.getScheme(),null,u.getHost(),u.getPort(),"/",null,null);open(portal);}catch(Exception e){message="无法打开网页，请检查云端地址。";error=true;}}
    private void open(URI uri){try{Util.getPlatform().openUri(uri);}catch(RuntimeException e){minecraft.keyboardHandler.setClipboard(uri.toString());message="无法打开浏览器，链接已复制，请手动打开。";}}
    private void exchange(){
        String code=normalizePairingCode(pairDraft);if(code.length()!=6){error=true;message="需要网页生成的 6 位配对码，当前是 "+code.length()+" 位。不是 API 密钥，也不是游戏里的登录确认码。";return;}
        message="正在兑换配对码，请稍候…";error=false;
        work(()->{CloudConfigFetcher.ExchangeResult result=new CloudConfigFetcher().exchangeCode(address,code,ConfigManager.getCloudTimeoutSeconds());if(!result.success()||result.apiKey()==null||result.apiKey().isBlank())throw new java.io.IOException(result.error()==null?"配对失败，请在网页重新生成配对码。":result.error());JsonObject out=new JsonObject();out.addProperty("api_key",result.apiKey());out.addProperty("config_id",result.configId());return out;},data->authorized(data,false));
    }
    public static String normalizePairingCode(String text){if(text==null)return "";StringBuilder b=new StringBuilder();for(char c:text.trim().toCharArray()){if(Character.isWhitespace(c)||c=='\u200B'||c=='\uFEFF')continue;if(c>='\uFF01'&&c<='\uFF5E')c=(char)(c-0xFEE0);b.append(c);}return b.toString();}
    private void begin(){deviceCode="";verify=null;generation++;message="正在获取浏览器登录链接…";error=false;
        work(()->api.requestDeviceCode(CloudCredentialStore.getInstance().clientId(),OllamaProxyMod.version()),data->{verify=api.verificationUri(data);deviceCode=str(data,"device_code");userCode=str(data,"user_code");interval=Math.max(5,num(data,"interval",5));expiresAt=Util.getMillis()+Math.min(600,Math.max(1,num(data,"expires_in",600)))*1000L;nextPoll=Util.getMillis()+interval*1000L;message="请打开浏览器，登录并点击允许，然后回到游戏。这里会自动等候结果。";});
    }
    private void authorized(JsonObject data,boolean device){
        String token=str(data,"api_key");if(token.isBlank()||(device&&!token.matches("sk_[0-9a-fA-F]{64}")))throw new IllegalArgumentException("登录结果不完整，请重新连接。");
        CloudConfigManager.stop();ConfigManager.clearCloudConfig();CloudCredentialStore.getInstance().saveAuthorization(token,str(data,"config_id"));ModConfig.CLOUD_API_KEY.set("");ModConfig.CLOUD_ENABLE.set(true);ConfigManager.saveCommonConfig();key=token;reauthorizing=false;pairDraft="";deviceCode="";verify=null;route=Route.CONNECTED;ClientEventHandler.beginCloudAuthGuard();sync();
    }
    private void sync(){
        if(syncing||key.isBlank())return;ModConfig.CLOUD_ENABLE.set(true);ConfigManager.saveCommonConfig();syncing=true;synced=false;syncStarted=Util.getMillis();message="连接已保存，正在读取网页设置。不会修改网页配置，也不会自动测试。";controls();int serial=generation;String account=key;
        ClientEventHandler.syncCloudConfigNow(ok->minecraft.execute(()->{if(!active||serial!=generation||minecraft.screen!=this||!account.equals(ConfigManager.getCloudApiKey()))return;syncing=false;synced=ok;error=!ok;message=ok?"网页设置已读取。已在网页配好的用户可以直接返回游戏；需要修改时再选择模型。":"账户已连接，但网页设置未能读取。请重新读取，不必反复兑换配对码。";controls();}));
    }
    @Override public void tick(){
        if(codeBox!=null)codeBox.tick();
        if(syncing&&Util.getMillis()-syncStarted>65000){syncing=false;generation++;error=true;message="读取超时，登录已保存。可以重新读取或返回。";controls();}
        if(route!=Route.BROWSER||deviceCode.isBlank()||busy)return;long now=Util.getMillis();if(now>=expiresAt){deviceCode="";verify=null;error=true;message="登录链接已过期，请重新获取。";controls();return;}if(now<nextPoll)return;nextPoll=now+interval*1000L;String code=deviceCode;
        work(()->api.pollDeviceToken(code),data->{String status=str(data,"status");if(status.equals("approved"))authorized(data,true);else if(status.equals("pending")||status.equals("slow_down")){interval=Math.max(interval,Math.max(5,num(data,"interval",interval)));nextPoll=Util.getMillis()+interval*1000L;}else{deviceCode="";verify=null;error=true;message="浏览器未允许连接或登录已取消。可重新获取链接，或换用配对码。";}});
    }
    private interface Job{JsonObject run()throws Exception;}
    private void work(Job job,Consumer<JsonObject> done){if(busy||api==null)return;busy=true;int serial=generation;controls();IO.execute(()->{JsonObject value=null;Exception failure=null;try{value=job.run();}catch(Exception e){failure=e;}JsonObject result=value;Exception problem=failure;minecraft.execute(()->{if(!active||serial!=generation)return;busy=false;try{if(problem!=null){error=true;message=errorText(problem);nextPoll=Util.getMillis()+15000;}else done.accept(result);}catch(Exception e){error=true;message="未能完成本地保存，请重试并检查配置目录权限。";}controls();});});}
    private String errorText(Exception problem){if(problem instanceof ModSetupClient.ApiException e){if(e.status==429)return "请求过于频繁，请稍候再试。";if(e.reason.startsWith("device_code_")){deviceCode="";verify=null;return "登录链接已失效，请重新获取。";}if(e.status==401||e.status==403)return "授权未完成或已失效，请重新登录；不要把 API 密钥当成配对码。";return "服务暂时未返回结果（"+e.status+"），请稍后重试。";}return route==Route.PAIR?"配对未完成。请检查 6 位码是否过期，以及云端网络是否正常。":"网络暂时不可达，请检查连接后再试。";}
    @Override public void removed(){active=false;generation++;busy=false;syncing=false;}
    @Override public void onClose(){deviceCode="";minecraft.setScreen(parent);}
    @Override public boolean mouseScrolled(double x,double y,double delta){scroll=Math.max(0,scroll+(delta<0?2:-2));return true;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        ScreenBackgrounds.draw(this,g);int l=left(),w=span();g.drawString(font,"连接与云端服务",l,14,VerityUiTheme.TEXT,false);
        String subtitle=switch(route){case CHOOSE->"只选一种连接方式";case PAIR->"网页先配好 → 粘贴配对码 → 读取设置";case BROWSER->"获取链接 → 浏览器允许 → 回到游戏";case CONNECTED->synced?"已连接 · 网页设置已读取":"已连接 · 未经实际对话测试";};g.drawString(font,font.plainSubstrByWidth(subtitle,w),l,31,VerityUiTheme.MUTED,false);
        String detail=message;if(route==Route.BROWSER&&verify!=null)detail="浏览器确认码："+userCode+"（无需填回游戏） · 链接剩余 "+Math.max(0,(expiresAt-Util.getMillis())/1000)+" 秒。 "+message;
        var lines=font.split(Component.literal((busy?"正在请求，请稍候。 ":"")+detail),w);int top=height>=240?138:height-54;int visible=Math.max(1,(height-40-top)/11);scroll=Math.min(scroll,Math.max(0,lines.size()-visible));g.enableScissor(l,top,l+w,height-36);for(int i=0;i<visible&&scroll+i<lines.size();i++)g.drawString(font,lines.get(scroll+i),l,top+i*11,error?VerityUiTheme.DANGER:VerityUiTheme.MUTED,false);g.disableScissor();super.render(g,mx,my,partial);
    }
    private static String str(JsonObject o,String key){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";}
    private static int num(JsonObject o,String key,int fallback){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsInt():fallback;}
}
