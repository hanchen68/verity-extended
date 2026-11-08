package com.hanchen.veritycustomapi.story;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-save, per-player progress. No static player state can leak into another world. */
public final class StoryData extends SavedData {
    public static final String NAME="verity_extended_story";
    public static final class Entry {
        public final StoryProgress progress=new StoryProgress();
        public double anchorX,anchorY,anchorZ,lastX,lastY,lastZ;
        public String dimension="";
        // Session-only samples are intentionally not persisted.
        public boolean sampled,pendingRejoin;
        public long lastAction=-100;
    }
    private final Map<UUID,Entry> players=new HashMap<>();
    public static StoryData get(ServerLevel level){return level.getServer().overworld().getDataStorage().computeIfAbsent(StoryData::load,StoryData::new,NAME);}
    public Entry find(UUID id){return players.get(id);}
    public Entry create(UUID id){Entry e=players.get(id);if(e==null){e=new Entry();players.put(id,e);setDirty();}return e;}
    public void reset(UUID id){players.remove(id);setDirty();}
    public static StoryData load(CompoundTag tag){
        StoryData data=new StoryData();ListTag list=tag.getList("players",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){CompoundTag t=list.getCompound(i);if(!t.hasUUID("player"))continue;Entry e=new Entry();StoryProgress p=e.progress;
            try{p.stage=StoryProgress.Stage.valueOf(t.getString("stage"));}catch(IllegalArgumentException ex){continue;}
            p.active=t.getBoolean("active");p.paused=t.getBoolean("paused");p.wentAway=t.getBoolean("away");
            p.togetherSeconds=Math.max(0,Math.min(3600,t.getInt("seconds")));double walked=t.getDouble("walked");p.walked=Double.isFinite(walked)?Math.max(0,Math.min(24,walked)):0;
            p.response=t.getString("response");p.companion=t.getString("companion");e.dimension=t.getString("dimension");
            e.anchorX=t.getDouble("x");e.anchorY=t.getDouble("y");e.anchorZ=t.getDouble("z");data.players.put(t.getUUID("player"),e);
        }return data;
    }
    @Override public CompoundTag save(CompoundTag tag){
        tag.putInt("schema",1);ListTag list=new ListTag();
        for(var item:players.entrySet()){Entry e=item.getValue();StoryProgress p=e.progress;CompoundTag t=new CompoundTag();t.putUUID("player",item.getKey());t.putString("stage",p.stage.name());t.putBoolean("active",p.active);t.putBoolean("paused",p.paused);t.putBoolean("away",p.wentAway);t.putInt("seconds",p.togetherSeconds);t.putDouble("walked",p.walked);t.putString("response",p.response);t.putString("companion",p.companion);t.putString("dimension",e.dimension);t.putDouble("x",e.anchorX);t.putDouble("y",e.anchorY);t.putDouble("z",e.anchorZ);list.add(t);}
        tag.put("players",list);return tag;
    }
}
