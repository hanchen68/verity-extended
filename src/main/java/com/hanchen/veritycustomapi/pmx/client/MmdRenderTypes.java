package com.hanchen.veritycustomapi.pmx.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MmdRenderTypes extends RenderStateShard {
    private static final Map<ResourceLocation, RenderType> TRANSLUCENT_TRIANGLES = new ConcurrentHashMap<>();

    private MmdRenderTypes() {
        super("customsoul_mmd_render_types", () -> { }, () -> { });
    }

    static RenderType translucentTriangles(ResourceLocation texture) {
        return TRANSLUCENT_TRIANGLES.computeIfAbsent(texture, key -> RenderType.create(
                "customsoul_mmd_triangles_" + Integer.toUnsignedString(key.hashCode(), 36),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                RenderType.MEDIUM_BUFFER_SIZE,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(key, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .createCompositeState(true)));
    }
}
