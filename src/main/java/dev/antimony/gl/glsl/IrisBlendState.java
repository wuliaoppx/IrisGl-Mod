package dev.antimony.gl.glsl;

import java.util.*;

/** Detached effective Iris blend state, indexed by physical fragment-output location. */
public final class IrisBlendState {
    private IrisBlendState() { }
    public static List<TerrainFragmentDraw.Blend> capture(Object shader,int outputs,TerrainFragmentDraw.Blend inherited){
        Objects.requireNonNull(shader);Objects.requireNonNull(inherited);
        if(outputs<1||outputs>32)throw new IllegalArgumentException("Fragment output count");
        Object override=IrisSamplerBridge.field(shader,"blendModeOverride");
        TerrainFragmentDraw.Blend base=override==null?inherited:mode(IrisSamplerBridge.field(override,"blendMode"));
        List<TerrainFragmentDraw.Blend> result=new ArrayList<>(Collections.nCopies(outputs,base));
        Object overrides=IrisSamplerBridge.field(shader,"bufferBlendOverrides");
        if(!(overrides instanceof Iterable<?> values))throw new IllegalStateException("Missing effective Iris buffer-blend state");
        Set<Integer> seen=new HashSet<>();
        for(Object value:values){
            Object slot=IrisSamplerBridge.field(value,"drawBuffer");
            if(!(slot instanceof Integer location)||location<0||!seen.add(location))throw new IllegalStateException("Invalid/duplicate Iris output blend location");
            // Iris retains overrides for declared but unused output locations too.
            if(location<outputs)result.set(location,mode(IrisSamplerBridge.field(value,"blendMode")));
        }
        return List.copyOf(result);
    }
    private static TerrainFragmentDraw.Blend mode(Object value){
        if(value==null)return TerrainFragmentDraw.Blend.OFF;
        return new TerrainFragmentDraw.Blend(true,number(value,"srcRgb"),number(value,"dstRgb"),number(value,"srcAlpha"),number(value,"dstAlpha"));
    }
    private static int number(Object value,String field){
        Object number=IrisSamplerBridge.field(value,field);
        if(!(number instanceof Integer result))throw new IllegalStateException("Missing Iris blend factor "+field);
        return result;
    }
}
