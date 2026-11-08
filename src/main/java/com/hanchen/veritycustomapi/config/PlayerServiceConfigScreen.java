package com.hanchen.veritycustomapi.config;

import com.google.gson.*;
import com.hanchen.veritycustomapi.*;
import com.hanchen.veritycustomapi.cloud.*;
import com.hanchen.veritycustomapi.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Device-scoped source, builtin model and custom API editor. Secrets remain write-only. */
public final class PlayerServiceConfigScreen extends Screen {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Verity-Player-Config");t.setDaemon(true);return t;});
    private static final String[] TABS={"chat","stt","tts","advanced"},TITLES={"对话","语音输入","语音输出","更多"};
    private static final List<String> COMMON=List.of("api_provider","api_url","api_key","model","api_model","api_voice","vosk_model_path");
    private static final Map<String,String> NAMES=Map.ofEntries(Map.entry("api_provider","服务商"),Map.entry("api_url","API 地址"),Map.entry("api_key","API 密钥"),Map.entry("model","模型"),Map.entry("request_timeout","超时（秒）"),Map.entry("request_format","请求格式"),Map.entry("auth_type","认证方式"),Map.entry("response_field","返回文本字段"),Map.entry("sample_rate","采样率"),Map.entry("vosk_model_path","离线模型路径"),Map.entry("api_model","模型"),Map.entry("api_voice","音色"),Map.entry("speed","语速"),Map.entry("volume","音量"),Map.entry("kind","朗读路线"),Map.entry("translate_model","翻译模型"),Map.entry("translate_url","翻译 API 地址"),Map.entry("translate_api_key","翻译密钥"));
    private record Field(String path,String name,JsonObject constraint,JsonElement current){}
    private final Screen parent;
    private final PlayerConfigPatch patch=new PlayerConfigPatch();
    private final Map<String,String> drafts=new HashMap<>();
    private final Set<String> invalidFields=new HashSet<>(),touched=new HashSet<>();
    private final List<EditBox> inputs=new ArrayList<>();
    private JsonObject document=new JsonObject(),catalog=new JsonObject(),selections=new JsonObject(),savedSelections=new JsonObject();
    private ModSetupClient api;
    private String key="",message="正在读取服务设置…",query="",filterSignature="";
    private boolean busy,activeScreen,loaded,showAdvanced,voices;
    private int tab,page,serial,preferenceVersion;
    private Button saveButton;
    private EditBox search;
    private volatile JsonObject pendingConfigReceipt,pendingChoiceReceipt;
    public PlayerServiceConfigScreen(Screen parent){super(Component.literal("服务与 API"));this.parent=parent;}
    private int left(){return Math.max(8,(width-Math.min(640,width-16))/2);}
    private int span(){return width-2*left();}
    private boolean builtin(){return tab<3&&currentSource().equals("builtin");}
    private int listTop(){return builtin()?112:100;}
    private int capacity(){return Math.max(1,(height-78-listTop())/(builtin()?24:40));}
    private boolean dirty(){return !patch.empty()||!touched.isEmpty();}
    @Override protected void init(){
        activeScreen=true;key=ConfigManager.getCloudApiKey();
        if(api==null)try{api=new ModSetupClient(ConfigManager.getCloudApiUrl(),ConfigManager.getCloudTimeoutSeconds());}catch(RuntimeException e){message="连接地址无效，请检查账户设置。";}
        controls();if(!loaded&&api!=null&&!key.isBlank()){loaded=true;load();}else if(key.isBlank())message="请先连接账户，再选择服务。";
    }
    private Button button(int x,int y,int w,int h,String label,Button.OnPress fn){Button b=addRenderableWidget(VerityUiWidgets.secondaryButton(font,x,y,Math.max(20,w),h,Component.literal(label),fn));b.active=!busy;return b;}
    private void controls(){
        clearWidgets();inputs.clear();search=null;int l=left(),w=span(),tw=(w-9)/4;
        for(int i=0;i<4;i++){int idx=i;button(l+i*(tw+3),36,tw,20,(tab==i?"> ":"")+TITLES[i],b->{tab=idx;page=0;query="";voices=false;controls();});}
        if(key.isBlank())button(l,68,w,24,"连接账户",b->minecraft.setScreen(new InGameCloudSetupScreen(this)));
        else if(tab<3){JsonObject svc=object(object(document,"services"),TABS[tab]);JsonArray sources=array(svc,"sources");int count=Math.max(1,sources.size()),bw=(w-(count-1)*4)/count,i=0;
            for(JsonElement e:sources){if(!e.isJsonObject())continue;JsonObject source=e.getAsJsonObject();String id=text(source,"id");
                Button choice=button(l+i++*(bw+4),62,bw,20,(currentSource().equals(id)?"[x] ":"[ ] ")+sourceName(id,text(source,"name")),b->{String path="services."+TABS[tab]+".source";if(id.equals(text(svc,"source")))patch.remove(path);else patch.put(path,new JsonPrimitive(id));page=0;query="";voices=false;message="来源已选择，保存后生效。";controls();});
                choice.active=!busy&&(!source.has("available")||bool(source,"available"));
            }
        }
        if(!key.isBlank()&&builtin())builtinControls(l,w);else if(!key.isBlank())fieldControls(l,w);
        int count=builtin()?options().size():visibleFields().size(),rows=capacity(),pages=Math.max(1,(count+rows-1)/rows);page=Math.max(0,Math.min(page,pages-1));
        int pager=height-52;
        if(tab==3&&!key.isBlank()){
            int half=(w-4)/2;
            button(l,62,half,20,"改用本机设置",b->confirm("改用本机设置？","关闭云端配置但保留账户登录。未保存的修改不会上传。",()->{CloudConfigManager.stop();ModConfig.CLOUD_ENABLE.set(false);ConfigManager.clearCloudConfig();ConfigManager.saveCommonConfig();ClientEventHandler.onConfigSaved();patch.clear();drafts.clear();touched.clear();minecraft.setScreen(NativeOllamaProxyConfigScreen.openPage(parent,1));}));
            button(l+half+4,62,half,20,"断开账户",b->confirm(com.hanchen.veritycustomapi.cloud.CloudServiceLore.disconnectTitle(),com.hanchen.veritycustomapi.cloud.CloudServiceLore.disconnectBody(),()->work(()->api.disconnect(key),d->{CloudConfigManager.stop();CloudCredentialStore.getInstance().clearAuthorization();ModConfig.CLOUD_API_KEY.set("");ModConfig.CLOUD_ENABLE.set(false);ConfigManager.clearCloudConfig();ConfigManager.saveCommonConfig();ClientEventHandler.onConfigSaved();patch.clear();drafts.clear();invalidFields.clear();touched.clear();key="";document=new JsonObject();catalog=new JsonObject();loaded=false;message="已断开账户。";com.hanchen.veritycustomapi.cloud.CloudServiceLore.onDisconnected(minecraft.player);})));
        }
        if(tab<3&&!builtin()&&!key.isBlank())button(l,pager,Math.min(112,w/3),20,showAdvanced?"收起更多参数":"更多参数",b->{showAdvanced=!showAdvanced;page=0;controls();});
        button(l+w-110,pager,52,20,"上一页",b->{page--;controls();}).active=!busy&&page>0;
        button(l+w-54,pager,54,20,"下一页",b->{page++;controls();}).active=!busy&&page+1<pages;
        int bw=(w-9)/4,bottom=height-27;
        button(l,bottom,bw,20,"返回",b->onClose()).active=true;
        button(l+bw+3,bottom,bw,20,"刷新",b->{if(dirty()||!drafts.isEmpty())confirm("放弃修改并刷新？","未保存的模型选择和 API 输入将丢弃。",this::load);else load();}).active=!busy&&!key.isBlank();
        button(l+2*(bw+3),bottom,bw,20,"测试连接",b->{if(dirty()||!drafts.isEmpty()){message="请先保存修改，再测试连接。";return;}minecraft.setScreen(new ConfigHealthCheckScreen(this,false));}).active=!busy&&!key.isBlank();
        saveButton=button(l+3*(bw+3),bottom,bw,20,"保存并应用",b->confirm("保存当前设备的设置？","只上传修改的项目；新密钥会加密保存。保存后可手动测试连接。",this::save));saveButton.active=!busy&&dirty()&&invalidFields.isEmpty();
        filterSignature=tab+"|"+query+"|"+voices;
    }
    private void builtinControls(int l,int w){
        int toggle=tab==2?76:0;
        search=VerityUiWidgets.roundedEditBox(font,l,88,Math.max(30,w-toggle-(toggle>0?4:0)),18,Component.literal(voices?"搜索音色":"搜索模型或服务商"));search.setMaxLength(80);search.setValue(query);search.setResponder(v->{query=v;page=0;});search.setEditable(!busy);search.setHint(Component.literal(voices?"搜索音色":"搜索模型 / 服务商"));addRenderableWidget(search);inputs.add(search);
        if(tab==2)button(l+w-76,88,76,18,voices?"选择模型":"选择音色",b->{voices=!voices;query="";page=0;controls();});
        List<JsonObject> opts=options();int rows=capacity();page=Math.max(0,Math.min(page,Math.max(0,(opts.size()-1)/rows)));int from=page*rows;
        for(int i=0;i<rows&&from+i<opts.size();i++){JsonObject opt=opts.get(from+i);boolean available=!opt.has("available")||bool(opt,"available");String id=text(opt,"id");
            String label=(isSelected(id)?"[x] ":"[ ] ")+optionName(opt)+(available?"":" · 不可用");
            Button choice=button(l,listTop()+i*24,w,20,font.plainSubstrByWidth(label,w-12),b->select(opt));choice.active=!busy&&available;
            String detail=text(opt,"model"),vendor=text(opt,"vendor");if(!vendor.isBlank())detail=vendor+(detail.isBlank()?"":" · "+detail);
            if(!available)detail=text(opt,"reason");else if(bool(opt,"requires_credits"))detail+=(detail.isBlank()?"":" · ")+"按云端规则计费";
            if(!detail.isBlank())choice.setTooltip(Tooltip.create(Component.literal(detail)));
        }
    }
    private void fieldControls(int l,int w){
        List<Field> fields=visibleFields();int rows=capacity();page=Math.max(0,Math.min(page,Math.max(0,(fields.size()-1)/rows)));int from=page*rows;
        for(int i=0;i<rows&&from+i<fields.size();i++){Field f=fields.get(from+i);int y=listTop()+i*40;JsonObject c=f.constraint();boolean secret=bool(c,"secret");JsonElement en=c.get("enum");
            if(en!=null&&en.isJsonArray()&&en.getAsJsonArray().size()>0){JsonArray values=en.getAsJsonArray();String current=value(f).isJsonNull()?"":value(f).getAsString();button(l,y,w,18,font.plainSubstrByWidth(displayValue(current),w-12),b->{int next=0;for(int n=0;n<values.size();n++)if(values.get(n).getAsString().equals(current))next=(n+1)%values.size();patch.put(f.path(),values.get(next));controls();});}
            else if("boolean".equals(text(c,"type"))){boolean current=!value(f).isJsonNull()&&value(f).getAsBoolean();button(l,y,w,18,current?"开启":"关闭",b->{patch.put(f.path(),new JsonPrimitive(!current));controls();});}
            else{int iw=secret?Math.max(30,w-56):w;EditBox box=VerityUiWidgets.roundedEditBox(font,l,y,iw,18,Component.literal(f.name()));box.setMaxLength(Math.min(4096,number(c,"max_length",512)));
                if(secret)box.setFormatter((content,index)->FormattedCharSequence.forward("*".repeat(content.length()),net.minecraft.network.chat.Style.EMPTY));
                String start=drafts.containsKey(f.path())?drafts.get(f.path()):secret?"":value(f).isJsonNull()?"":value(f).getAsString();box.setValue(start);
                if(secret)box.setHint(Component.literal(secretSet(f)?"已保存，留空不改":"填写 API 密钥"));
                box.setResponder(v->{drafts.put(f.path(),v);if(secret)patch.secret(f.path(),v);else updateField(f,v);if(saveButton!=null)saveButton.active=!busy&&dirty()&&invalidFields.isEmpty();});box.setEditable(!busy);addRenderableWidget(box);inputs.add(box);
                if(secret)button(l+w-52,y,52,18,"清除",b->confirmClear(f));
            }
        }
    }
    private static String sourceName(String id,String fallback){return switch(id){case "builtin"->"内置服务";case "custom"->"自定义 API";case "local"->"本机离线";default->fallback.isBlank()?id:fallback;};}
    private static String displayValue(String value){return switch(value){case "bearer"->"Bearer（常用）";case "multipart","multipart_form_data"->"表单上传（常用）";case "json"->"JSON";case "url_encoded"->"URL 编码";case "api_key_header"->"API Key 请求头";case "api_key_param"->"API Key 参数";case "custom_header"->"自定义请求头";default->value;};}
    private static String optionName(JsonObject opt){String name=text(opt,"name"),model=text(opt,"model");if(name.isBlank())name=model;else if(!model.isBlank()&&!name.contains(model))name+=" · "+model;return name.isBlank()?text(opt,"id"):name;}
    private JsonObject catalogService(){return object(object(catalog,"services"),TABS[Math.min(2,tab)]);}
    private List<JsonObject> options(){List<JsonObject> result=new ArrayList<>();String q=query.toLowerCase(Locale.ROOT);for(JsonElement e:array(catalogService(),voices&&tab==2?"voices":"options")){if(!e.isJsonObject())continue;JsonObject o=e.getAsJsonObject();if((optionName(o)+" "+text(o,"model")+" "+text(o,"vendor")+" "+text(o,"id")).toLowerCase(Locale.ROOT).contains(q))result.add(o);}return result;}
    private String selectionField(){return tab==2&&voices?"voice_id":"route_id";}
    private String selectedId(){String field=selectionField();String current=text(object(selections,TABS[Math.min(2,tab)]),field);if(current.isBlank())current=text(object(catalogService(),"effective"),field);if(current.isBlank()&&voices)current=text(catalogService(),"default_voice");return current;}
    private boolean isSelected(String id){return !id.isBlank()&&id.equals(selectedId());}
    private void select(JsonObject option){String svc=TABS[tab];if(isSelected(text(option,"id"))){message="这个选项已经选中，无需重复保存。";return;}JsonObject selected=object(selections,svc).deepCopy();selected.addProperty(selectionField(),text(option,"id"));selections.add(svc,selected);if(selected.equals(object(savedSelections,svc)))touched.remove(svc);else touched.add(svc);message="已选择 "+optionName(option)+"，保存后生效。";controls();}
    private String currentSource(){String svc=TABS[Math.min(2,tab)];JsonElement changed=patch.value("services."+svc+".source");return changed.isJsonNull()?text(object(object(document,"services"),svc),"source"):changed.getAsString();}
    private List<Field> fields(){List<Field> fields=new ArrayList<>();
        if(tab<3){JsonObject svc=object(object(document,"services"),TABS[tab]);if(currentSource().equals("builtin"))return fields;JsonObject custom=object(svc,"custom");
            for(var e:object(svc,"constraints").entrySet()){String name=e.getKey();JsonObject c=e.getValue().isJsonObject()?e.getValue().getAsJsonObject():new JsonObject();if(name.equals("source")||name.equals("mode")||name.startsWith("clear_")||bool(c,"client_managed"))continue;if(currentSource().equals("local")&&!name.equals("vosk_model_path")&&!name.equals("sample_rate"))continue;fields.add(new Field("services."+TABS[tab]+".custom."+name,NAMES.getOrDefault(name,name),c,custom.has(name)?custom.get(name):JsonNull.INSTANCE));}
        }else{JsonObject advanced=object(document,"advanced");for(String group:List.of("tts_route","voice_language")){JsonObject obj=object(advanced,group);for(var e:object(obj,"constraints").entrySet()){if(!e.getValue().isJsonObject()||e.getKey().startsWith("clear_"))continue;fields.add(new Field("advanced."+group+"."+e.getKey(),NAMES.getOrDefault(e.getKey(),e.getKey()),e.getValue().getAsJsonObject(),obj.has(e.getKey())?obj.get(e.getKey()):JsonNull.INSTANCE));}}}
        fields.sort(Comparator.comparingInt(f->{String name=f.path().substring(f.path().lastIndexOf('.')+1);int index=COMMON.indexOf(name);return index<0?COMMON.size():index;}));return fields;
    }
    private List<Field> visibleFields(){List<Field> result=new ArrayList<>();for(Field f:fields()){String name=f.path().substring(f.path().lastIndexOf('.')+1);if(tab==3||showAdvanced||currentSource().equals("local")||COMMON.contains(name)||bool(f.constraint(),"required"))result.add(f);}return result;}
    private JsonElement value(Field f){JsonElement v=patch.value(f.path());return v.isJsonNull()?f.current():v;}
    private boolean secretSet(Field f){return f.current().isJsonObject()&&bool(f.current().getAsJsonObject(),"set");}
    private void confirmClear(Field f){confirm("清除已保存的密钥？","保存时才会清除，可能导致此服务不可用。",()->{patch.clearSecret(f.path());drafts.remove(f.path());message="已标记清除，保存后生效。";controls();});}
    private void confirm(String title,String body,Runnable yes){minecraft.setScreen(new VerityConfirmScreen(this,ok->{minecraft.setScreen(this);if(ok)yes.run();},Component.literal(title),Component.literal(body)));}
    private void updateField(Field f,String v){try{String type=text(f.constraint(),"type");JsonElement val;if(type.equals("integer")){int n=Integer.parseInt(v);bounds(f,n);val=new JsonPrimitive(n);}else if(type.equals("number")){double n=Double.parseDouble(v);if(!Double.isFinite(n))throw new IllegalArgumentException();bounds(f,n);val=new JsonPrimitive(n);}else val=new JsonPrimitive(v);if(f.current().equals(val))patch.remove(f.path());else patch.put(f.path(),val);invalidFields.remove(f.path());}catch(RuntimeException e){invalidFields.add(f.path());patch.remove(f.path());message=f.name()+"：请检查格式和范围。";}}
    private void bounds(Field f,double n){if(f.constraint().has("min")&&n<f.constraint().get("min").getAsDouble()||f.constraint().has("max")&&n>f.constraint().get("max").getAsDouble())throw new IllegalArgumentException();}
    private void load(){work(()->{JsonObject bundle=new JsonObject();bundle.add("document",api.playerConfig(key));try{bundle.add("catalog",api.catalog(key));bundle.add("preferences",api.preferences(key));}catch(Exception e){bundle.remove("catalog");bundle.remove("preferences");bundle.addProperty("catalog_unavailable",true);}return bundle;},d->{document=object(d,"document");catalog=object(d,"catalog");JsonObject prefs=object(d,"preferences");preferenceVersion=number(prefs,"device_version",0);selections=object(prefs,"selections").deepCopy();savedSelections=selections.deepCopy();patch.clear();drafts.clear();invalidFields.clear();touched.clear();page=0;message=bool(d,"catalog_unavailable")?"内置列表暂时不可用，请刷新。自定义 API 仍可编辑。":"先选来源，再选模型或填写 API。保存后可测试连接。";});}
    private void save(){
        if(!invalidFields.isEmpty()){message="请先修正格式或范围错误的字段。";return;}
        // Validate hidden drafts too; collapsing advanced settings must not discard invalid edits.
        for(Field f:allFields())if(drafts.containsKey(f.path())&&!bool(f.constraint(),"secret")){String v=drafts.get(f.path());String type=text(f.constraint(),"type");try{if(type.equals("integer"))bounds(f,Integer.parseInt(v));if(type.equals("number")){double n=Double.parseDouble(v);if(!Double.isFinite(n))throw new IllegalArgumentException();bounds(f,n);}}catch(RuntimeException e){message="请修正 "+f.name()+" 后保存。";return;}}
        // Sources/custom settings are configuration; catalog selections already live separately.
        // Do not strip arbitrary leaves or claim that a client PUT controls server config identity.
        final String requestKey=key;
        final boolean configChanged=!patch.empty();
        final JsonObject body=configChanged?patch.body(number(object(document,"config"),"version",-1)):new JsonObject();
        final JsonObject choices=new JsonObject();for(String svc:touched)choices.add(svc,object(selections,svc).deepCopy());
        final int expected=preferenceVersion;
        if(!configChanged&&choices.size()==0){message="没有需要保存的改动。";controls();return;}
        pendingConfigReceipt=null;pendingChoiceReceipt=null;
        work(()->{JsonObject runtime;
            if(configChanged){JsonObject saved=api.savePlayerConfig(requestKey,body);pendingConfigReceipt=saved;runtime=api.getConfig(requestKey,CloudCredentialStore.getInstance().clientId(),OllamaProxyMod.version());if(!PlayerConfigPatch.delivered(saved,runtime,body))throw new java.io.IOException("delivery_mismatch");}
            if(!choices.entrySet().isEmpty()){JsonObject saved=api.savePreferences(requestKey,expected,choices);pendingChoiceReceipt=saved;runtime=api.getConfig(requestKey,CloudCredentialStore.getInstance().clientId(),OllamaProxyMod.version());if(!ModSetupClient.appliedVersion(saved,runtime))throw new java.io.IOException("preferences_not_applied");}
            runtime=api.getConfig(requestKey,CloudCredentialStore.getInstance().clientId(),OllamaProxyMod.version());
            if(pendingConfigReceipt!=null&&!PlayerConfigPatch.delivered(pendingConfigReceipt,runtime,body))throw new java.io.IOException("delivery_mismatch");
            if(pendingChoiceReceipt!=null&&!ModSetupClient.appliedVersion(pendingChoiceReceipt,runtime))throw new java.io.IOException("preferences_not_applied");
            JsonObject out=new JsonObject();out.add("runtime",runtime);out.add("document",api.playerConfig(requestKey));out.add("preferences",api.preferences(requestKey));return out;
        },d->{
            if(!requestKey.equals(ConfigManager.getCloudApiKey())){message="账户已改变，未应用旧响应。";return;}
            CloudConfig config=new CloudConfig();config.code=0;config.data=new Gson().fromJson(object(d,"runtime"),CloudConfig.Data.class);ModConfig.CLOUD_ENABLE.set(true);if(!ConfigManager.applyCloudConfig(config))throw new IllegalStateException();ConfigManager.saveCommonConfig();ClientEventHandler.onConfigSaved();
            String voice=text(object(choices,"tts"),"voice_id");if(!voice.isBlank()){boolean clone=false;for(JsonElement e:array(object(object(catalog,"services"),"tts"),"voices")){if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();if(text(o,"id").equals(voice))clone=text(o,"type").equals("clone");}}ConfigManager.saveMimoVoice(voice,clone);}
            document=object(d,"document");JsonObject prefs=object(d,"preferences");preferenceVersion=number(prefs,"device_version",preferenceVersion);selections=object(prefs,"selections").deepCopy();savedSelections=selections.deepCopy();patch.clear();drafts.clear();invalidFields.clear();touched.clear();message="已保存并应用。需要验证时，点击「测试连接」。";
        });
    }
    private List<Field> allFields(){int old=tab;List<Field> out=new ArrayList<>();try{for(int i=0;i<4;i++){tab=i;out.addAll(fields());}}finally{tab=old;}return out;}
    private interface Job{JsonObject run()throws Exception;}
    private void work(Job job,Consumer<JsonObject> done){if(busy||api==null)return;busy=true;int generation=serial;controls();IO.execute(()->{JsonObject value=null;Exception error=null;try{value=job.run();}catch(Exception e){error=e;}JsonObject result=value;Exception failure=error;minecraft.execute(()->{if(!activeScreen||generation!=serial)return;busy=false;
            // A two-stage save may commit the source before the model request fails.
            // Advance committed versions while retaining edits, so retry does not replay a stale version.
            if(failure!=null){
                if(pendingConfigReceipt!=null){int committed=number(object(pendingConfigReceipt,"config"),"version",-1);if(committed>=0)object(document,"config").addProperty("version",committed);}
                if(pendingChoiceReceipt!=null)preferenceVersion=number(pendingChoiceReceipt,"version",preferenceVersion);
            }
            pendingConfigReceipt=null;pendingChoiceReceipt=null;
            try{if(failure==null)done.accept(result);else if(failure instanceof ModSetupClient.ApiException a){message=switch(a.reason){case "custom_incomplete"->"请补齐地址、模型或密钥。";case "secret_empty"->"密钥留空代表不修改；删除请点「清除」。";case "version_conflict"->"其他设备已更新设置，请刷新后重试。";case "typed_override_conflict"->"存在重复连接设置，请先清理。";case "token_invalid","token_revoked"->"登录已失效，请返回重新连接。";default->"请求失败（"+a.status+"），请刷新查看。";};}else message="未能确认设置是否生效，修改已保留。请刷新查看后重试。";}catch(Exception e){message="本地应用失败，修改已保留，请刷新查看。";}controls();});});}
    @Override public void tick(){for(EditBox input:inputs)input.tick();String signature=tab+"|"+query+"|"+voices;if(!busy&&!signature.equals(filterSignature)){boolean focus=search!=null&&search.isFocused();controls();if(focus&&search!=null)setFocused(search);}}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(busy)return true;int count=builtin()?options().size():visibleFields().size();int max=Math.max(0,(count-1)/capacity());int next=Math.max(0,Math.min(max,page+(delta<0?1:-1)));if(next!=page){page=next;controls();}return true;}
    @Override public void removed(){activeScreen=false;serial++;busy=false;}
    @Override public void onClose(){if(busy){confirm("离开正在处理的设置页？","已发出的保存可能仍会在云端完成；重新打开后请刷新确认，不要立即重复提交。",()->minecraft.setScreen(parent));return;}if(dirty()||!drafts.isEmpty())confirm("放弃未保存修改？","未保存的模型选择和 API 输入将丢弃。",()->{patch.clear();drafts.clear();touched.clear();minecraft.setScreen(parent);});else minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        ScreenBackgrounds.draw(this,g);int l=left(),w=span();g.drawString(font,"服务与 API",l,12,VerityUiTheme.TEXT,false);g.drawString(font,key.isBlank()?"未连接账户":dirty()?"有未保存修改 · 仅当前设备":"当前设备的服务配置",l,24,VerityUiTheme.MUTED,false);
        if(!key.isBlank()&&builtin()){if(options().isEmpty())g.drawString(font,font.plainSubstrByWidth(query.isBlank()?"暂无可选项，请刷新或检查账户可用服务。":"没有匹配项，请换个关键词。",w),l,listTop()+5,VerityUiTheme.MUTED,false);}
        else if(!key.isBlank()){List<Field> fields=visibleFields();int from=page*capacity();            for(int i=0;i<capacity()&&from+i<fields.size();i++){Field f=fields.get(from+i);String name=f.name()+(bool(f.constraint(),"required")?"（必填）":"");if(bool(f.constraint(),"secret"))name+=secretSet(f)?" · 已保存，留空不改":" · 未设置";g.drawString(font,font.plainSubstrByWidth(name,w),l,listTop()-11+i*40,invalidFields.contains(f.path())?VerityUiTheme.DANGER:VerityUiTheme.TEXT,false);}
            if(fields.isEmpty())g.drawString(font,"此来源无需填写连接参数。",l,listTop(),VerityUiTheme.MUTED,false);
        }
        int count=builtin()?options().size():visibleFields().size();g.drawString(font,(page+1)+" / "+Math.max(1,(count+capacity()-1)/capacity()),l+Math.max(0,w-155),height-46,VerityUiTheme.MUTED,false);g.drawString(font,font.plainSubstrByWidth(busy?"正在处理…":message,Math.max(20,w)),l,height-68,VerityUiTheme.MUTED,false);super.render(g,mx,my,partial);
    }
    private static JsonObject object(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static String text(JsonObject o,String k){return o!=null&&o.has(k)&&!o.get(k).isJsonNull()&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():"";}
    private static int number(JsonObject o,String k,int fallback){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsInt():fallback;}
    private static boolean bool(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()&&o.get(k).isJsonPrimitive()&&o.get(k).getAsBoolean();}
}
