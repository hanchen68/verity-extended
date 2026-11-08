package com.hanchen.veritycustomapi.story;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import java.util.function.Supplier;

/** Optional channel; client actions contain no stage, player UUID or progress to trust. */
public final class StoryNetwork {
    private static final String VERSION="1";
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OllamaProxyMod.MOD_ID,"story"),()->VERSION,
            v->VERSION.equals(v)||NetworkRegistry.ABSENT.equals(v)||NetworkRegistry.ACCEPTVANILLA.equals(v),
            v->VERSION.equals(v)||NetworkRegistry.ABSENT.equals(v)||NetworkRegistry.ACCEPTVANILLA.equals(v));
    private static boolean registered;
    private static final java.util.UUID LIMITER_SESSION=java.util.UUID.randomUUID();
    private StoryNetwork(){}
    public static synchronized void register(){
        if(registered)return;
        CHANNEL.registerMessage(0,Action.class,Action::write,Action::read,StoryNetwork::action,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,State.class,State::write,State::read,StoryNetwork::state,Optional.of(NetworkDirection.PLAY_TO_CLIENT));registered=true;
    }
    public static boolean supported(Connection connection){return registered&&connection!=null&&CHANNEL.isRemotePresent(connection);}
    public static void request(String action){if(registered)CHANNEL.sendToServer(new Action(action));}
    public static void send(ServerPlayer player,String message){send(player,message,"",false);}
    public static void send(ServerPlayer player,String message,String line,boolean open){
        if(!supported(player.connection.connection))return;
        StoryData.Entry e=StoryData.get(player.serverLevel()).find(player.getUUID());StoryProgress p=e==null?new StoryProgress():e.progress;
        CompoundTag t=new CompoundTag();t.putString("stage",p.stage.name());t.putBoolean("active",p.active);t.putBoolean("paused",p.paused);t.putInt("seconds",p.togetherSeconds);t.putDouble("walked",p.walked);t.putString("response",p.response);t.putString("message",message);
        t.putString("line",line);t.putBoolean("open_dialog",open);t.putInt("companion_id",-1);
        if(e!=null){t.putString("dimension",e.dimension);t.putDouble("anchor_x",e.anchorX);t.putDouble("anchor_y",e.anchorY);t.putDouble("anchor_z",e.anchorZ);try{var entity=p.companion.isBlank()?com.hanchen.veritycustomapi.network.ServerVerityRegistry.findForPlayer(player):player.serverLevel().getEntity(java.util.UUID.fromString(p.companion));if(entity!=null&&entity instanceof varmite.verity.entity.verity.VerityEntity v&&com.hanchen.veritycustomapi.network.ServerVerityRegistry.owns(player,v))t.putInt("companion_id",entity.getId());}catch(IllegalArgumentException ignored){}}
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new State(t));
    }
    private static void action(Action packet,Supplier<NetworkEvent.Context> supplier){NetworkEvent.Context ctx=supplier.get();ServerPlayer player=ctx.getSender();ctx.setPacketHandled(true);if(player==null)return;ctx.enqueueWork(()->{
        long now=player.serverLevel().getGameTime();net.minecraft.nbt.CompoundTag session=player.getPersistentData();
        String limiter=packet.action.equals("status")?"verity_story_last_status":"verity_story_last_packet";
        if(session.hasUUID("verity_story_limiter_session")&&session.getUUID("verity_story_limiter_session").equals(LIMITER_SESSION)&&session.contains(limiter)&&now-session.getLong(limiter)<5)return;
        session.putUUID("verity_story_limiter_session",LIMITER_SESSION);session.putLong(limiter,now);
        StoryService.act(player,packet.action);
    });}
    private static void state(State packet,Supplier<NetworkEvent.Context> supplier){NetworkEvent.Context ctx=supplier.get();ctx.setPacketHandled(true);ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->StoryClientState.accept(packet.tag)));}
    public record Action(String action){static void write(Action p,FriendlyByteBuf b){b.writeUtf(p.action,16);}static Action read(FriendlyByteBuf b){return new Action(b.readUtf(16));}}
    public record State(CompoundTag tag){static void write(State p,FriendlyByteBuf b){b.writeNbt(p.tag);}static State read(FriendlyByteBuf b){CompoundTag tag=b.readNbt();return new State(tag==null?new CompoundTag():tag);}}
}
