package dev.antimony.core.test;

import dev.antimony.gl.glsl.CpuImage;
import dev.antimony.gl.glsl.IrisRenderTargetHistory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Nonzero, two-frame CPU persistence contract, independent of Minecraft and a GL context. */
public final class IrisRenderTargetHistoryProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void value(CpuImage image, float expected, String message) {
        check(Math.abs(image.rgba[0] - expected) < 0.00001f, message + ": " + image.rgba[0]);
    }
    public static void main(String[] args) {
        Map<Integer, CpuImage> images = new HashMap<>();
        List<IrisRenderTargetHistory.Copy> copies = new ArrayList<>();
        for (int rt : new int[]{2,3,5,6}) {
            CpuImage main = new CpuImage(2,2), alt = new CpuImage(2,2);
            main.clear(rt + .125f, 2, 3, 1); alt.clear(rt + .875f, 4, 5, 1);
            images.put(rt * 10, main); images.put(rt * 10 + 1, alt);
            // RT2 is clear=true: the actual Iris swap list omits it.
            if (rt != 2) copies.add(new IrisRenderTargetHistory.Copy(rt*10+1,rt*10));
        }
        for (int frame = 1; frame <= 2; frame++) {
            var transaction = new IrisRenderTargetHistory.Transaction(copies, images);
            Set<Integer> written = new HashSet<>();
            for (int rt : new int[]{3,5,6}) {
                CpuImage main = images.get(rt*10), alt = images.get(rt*10+1);
                float old = rt + .125f + (frame-1)*10;
                value(main, old, "frame " + frame + " reads old main RT" + rt);
                alt.clear(old + 10, 7, 8, 1); written.add(rt*10+1);
                value(main, old, "final must still see old main before commit RT" + rt);
                check(images.get(rt*10) == main && images.get(rt*10+1) == alt, "texture identity changed");
            }
            check(transaction.commit(images, written), "complete frame commit failed");
            for (int rt : new int[]{3,5,6}) value(images.get(rt*10), rt+.125f+frame*10, "alt to main RT"+rt);
            value(images.get(20), 2.125f, "clear RT2 excluded from swaps");
        }
        var failed = new IrisRenderTargetHistory.Transaction(copies, images);
        images.get(30).clear(999,0,0,1); // A partial pass may have written a persistent destination.
        images.get(31).clear(888,0,0,1);
        check(!failed.commit(images, Set.of(31,51)), "missing RT6 source accepted");
        value(images.get(50),25.125f,"invalid source partially published copies");
        failed.rollback(images);
        value(images.get(30),23.125f,"failed frame old history not restored");
        var resized = new IrisRenderTargetHistory.Transaction(copies, images);
        images.put(61,new CpuImage(1,1));
        check(!resized.commit(images,Set.of(31,51,61)),"resize mismatch accepted");
        resized.rollback(images);
        check(IrisRenderTargetHistory.uniqueWritten(new int[]{30,31},Set.of(31))==31,"unique CPU write fallback");
        check(IrisRenderTargetHistory.uniqueWritten(new int[]{30,31},Set.of(30,31))==0,"ambiguous pair guessed");
        check(IrisRenderTargetHistory.uniqueWritten(new int[]{30,31},Set.of())==0,"unwritten allocation accepted");
        Map<Integer, CpuImage> reuse = new HashMap<>();
        var pooled = new IrisRenderTargetHistory.Transaction(List.of(new IrisRenderTargetHistory.Copy(31,30)), images, reuse);
        CpuImage pooledSnapshot = reuse.get(30); pooled.rollback(images);
        var pooledAgain = new IrisRenderTargetHistory.Transaction(List.of(new IrisRenderTargetHistory.Copy(31,30)), images, reuse);
        check(reuse.get(30) == pooledSnapshot, "history snapshot not reused"); pooledAgain.rollback(images);
        Map<Integer,CpuImage> detailed = new HashMap<>();
        for (int id = 1; id <= 3; id++) {
            CpuImage image = new CpuImage(2,2);
            for (int component = 0; component < image.rgba.length; component++) image.rgba[component] = id*100+component*.125f;
            detailed.put(id,image);
        }
        float[] expected = detailed.get(1).rgba.clone();
        var ordered = new IrisRenderTargetHistory.Transaction(List.of(
            new IrisRenderTargetHistory.Copy(1,1), new IrisRenderTargetHistory.Copy(1,2),
            new IrisRenderTargetHistory.Copy(2,3)), detailed);
        check(ordered.commit(detailed,Set.of(1,2)),"ordered self-copy chain failed");
        check(java.util.Arrays.equals(detailed.get(2).rgba,expected),"RGBA multi-pixel copy changed components");
        check(java.util.Arrays.equals(detailed.get(3).rgba,expected),"ordered chain used stale intermediate");
        var absent = new IrisRenderTargetHistory.Transaction(List.of(new IrisRenderTargetHistory.Copy(1,2)),detailed);
        detailed.remove(1);
        check(!absent.commit(detailed,Set.of(1)),"absent source accepted despite valid stamp");
        absent.rollback(detailed);
        System.out.println("PASS nonuniform RGBA multi-pixel copy, ordered self-copy/chain, actually removed source, pooled snapshots");
        System.out.println("PASS RT3/5/6 nonzero two frames: old main -> new alt -> final -> main copy; stable IDs; clear RT2 excluded");
        System.out.println("PASS invalid/absent source prevalidation, partial-frame rollback, resize dimensions, deterministic CPU fallback");
    }
}
