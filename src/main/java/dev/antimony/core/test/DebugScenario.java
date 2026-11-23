package dev.antimony.core.test;

import dev.antimony.AntimonyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic demos used by the automated screenshot runs. Enabled with
 * {@code -Dantimony.demo=true} (general smoke test) or
 * {@code -Dantimony.demo=render} (renderer regression scene); it only ever runs
 * on the client and never ships behaviour changes unless the property is set.
 */
public final class DebugScenario {
    private DebugScenario() {
    }

    public static boolean enabled() {
        return dev.antimony.AntimonyMod.flag("antimony.demo") || renderTest() || weatherTest()
                || entityTest();
    }

    public static boolean renderTest() {
        return "render".equals(System.getProperty("antimony.demo"));
    }

    /** Torch/glowstone light propagation regression scene. */
    public static boolean lightTest() {
        return "light".equals(System.getProperty("antimony.demo"));
    }

    public static boolean weatherTest() {
        return "weather".equals(System.getProperty("antimony.demo"));
    }

    /** Sky regression scene: clear horizon view at noon, then at dawn. */
    public static boolean skyTest() {
        return "sky".equals(System.getProperty("antimony.demo"));
    }

    /** Guard-screen regression: opens the compatibility screen so a screenshot captures it. */
    public static boolean guardTest() {
        return "guard".equals(System.getProperty("antimony.demo"));
    }

    /**
     * Entity overlay regression: a zombie in front of a locked camera, first
     * burning (animated fire overlay) and then repeatedly damaged (red flash).
     * Both effects are driven by the vanilla entity shader's UV1 overlay, which
     * the software rasterizer used to ignore.
     */
    public static boolean entityTest() {
        return "entity".equals(System.getProperty("antimony.demo"));
    }

    /**
     * Save-and-quit regression: at frame 90 replicates the exact sequence of
     * PauseScreen.onDisconnect ("Save and Quit to Title"): level.disconnect,
     * Minecraft.disconnect(GenericMessageScreen("menu.savingLevel")), then the
     * TitleScreen. This is the user-reported stale-GDI-card repro: the
     * "Saving world" notice painted by our own GUI pass must keep the overlay
     * alive while it shows, and ScreenPresenter.frameTick must hide the card
     * on the very frame the software renderer stops owning the screen.
     */
    public static boolean savetest() {
        return "savetest".equals(System.getProperty("antimony.demo"));
    }

    /** Save-and-quit probe stage: 0=play, 1=pause menu open, 2=disconnected. */
    private static int savetestPhase;

    /**
     * Save-and-quit probe driver. Action frames need a live session; the
     * probe logging must keep running after the world is gone, which is the
     * whole point (proving the overlay is hidden once the renderer stops).
     */
    private static void tickSavetest(Minecraft mc, long frame) {
        // Phase gate, not bare frame checks: Minecraft.disconnect runs a
        // NESTED runTick loop (waiting for the integrated server to shut
        // down) which re-enters GameRenderer.render -> this method while
        // the frame counter has not advanced yet; without the gate the
        // disconnect sequence would recurse into itself forever.
        if (savetestPhase == 0 && frame >= 90 && mc.level != null && mc.player != null) {
            savetestPhase = 1;
            AntimonyMod.LOG.info("[Antimony] savetest: opening the pause screen (save-and-quit available)");
            mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
        } else if (savetestPhase == 1 && frame >= 180 && mc.level != null && mc.player != null) {
            savetestPhase = 2;
            // Exact PauseScreen.onDisconnect sequence (local server branch).
            AntimonyMod.LOG.info("[Antimony] savetest: replicating PauseScreen.onDisconnect");
            mc.level.disconnect();
            mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
                    net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
            AntimonyMod.LOG.info("[Antimony] savetest: disconnect returned, level={} overlayGone={}",
                    mc.level == null ? "null" : "still set",
                    dev.antimony.core.present.ScreenPresenter.overlayGoneForProbe());
        }
        if (frame >= 240 && frame % 60 == 0) {
            AntimonyMod.LOG.info("[Antimony] savetest probe frame {}: level={} player={} screen={} overlayGone={}",
                    frame,
                    mc.level == null ? "null" : "set",
                    mc.player == null ? "null" : "set",
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                    dev.antimony.core.present.ScreenPresenter.overlayGoneForProbe());
        }
    }

    public static void tick(Minecraft mc, long frame) {
        if (guardTest()) {
            // Open a TitleScreen so the real ScreenEvent.Init.Post path runs; the
            // guard is expected to replace it with its own screen by itself.
            if (frame == 30 && mc.screen == null) {
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            }
            return;
        }
        if (savetest()) {
            tickSavetest(mc, frame);
            return;
        }
        if (!enabled() || mc.level == null || mc.player == null) {
            return;
        }
        if (renderTest()) {
            tickRenderTest(mc, frame);
            return;
        }
        if (entityTest()) {
            tickEntityTest(mc, frame);
            return;
        }
        if (lightTest()) {
            tickLightTest(mc, frame);
            return;
        }
        if (weatherTest()) {
            tickWeatherTest(mc, frame);
            return;
        }
        if (skyTest()) {
            tickSkyTest(mc, frame);
            return;
        }
        tickSmokeTest(mc, frame);
    }

    /**
     * Sky check: stand high above the ground looking at the horizon, first at
     * noon (disc gradient / fog colour / clear colour must meet without a seam)
     * and then at dawn, where the sunrise glow fan and the darker sky make any
     * mismatch obvious.
     */
    private static void tickSkyTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        if (frame == 3) {
            prepareServerScene(mc, true);
        }
        if (frame < 5) {
            return;
        }
        // Four phases: noon looking up (sun), noon at the horizon (disc/fog
        // seam), midnight looking up (moon + stars), dawn at the horizon (glow).
        long time;
        float pitch;
        if (frame < 60) {
            time = 6000L;
            pitch = -55.0F;
        } else if (frame < 120) {
            time = 6000L;
            pitch = 0.0F;
        } else if (frame < 180) {
            time = 18000L;
            pitch = -55.0F;
        } else {
            time = 23200L;
            pitch = 0.0F;
        }
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.overworld().setDayTime(time);
        }
        mc.level.setDayTime(time);
        player.setXRot(pitch);
        player.setYRot(90.0F);
    }

    /** Rain / snow / cloud regression scene: stormy sky, fixed camera. */
    private static void tickWeatherTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        if (frame == 3) {
            prepareServerScene(mc, true);
        }
        if (frame == 10) {
            try {
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    server.overworld().setWeatherParameters(0, 12000, true, false);
                    AntimonyMod.LOG.info("[Antimony] weather test: rain started");
                }
            } catch (Throwable t) {
                AntimonyMod.LOG.warn("[Antimony] weather test setup failed", t);
            }
        }
        if (frame >= 20) {
            player.setXRot(-28.0F);
            player.xRotO = -28.0F;
            float yaw = (frame * 0.6F) % 360.0F;
            player.setYRot(yaw);
            player.yRotO = yaw;
        }
        if (frame % 40 == 0) {
            AntimonyMod.LOG.info("[Antimony] weather test frame {} rain={} weather={} cloudQuads={} rainQuads={}",
                    frame, mc.level.getRainLevel(1.0F), mc.level.isRaining(),
                    dev.antimony.core.render.CloudPass.lastQuads(),
                    dev.antimony.core.render.WeatherPass.lastQuads());
        }
    }

    /** Regression scene: dripleaf, head, campfire, items, debug overlays. */
    private static void tickRenderTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        if (mc.level != null) {
            mc.level.setRainLevel(0.0F);
            mc.level.setThunderLevel(0.0F);
        }
        ClientLevel level = mc.level;
        if (frame == 3) {
            // Move out of the ocean spawn: the saved test world puts the
            // player at y=-60, where underwater fog hides everything.
            prepareServerScene(mc, true);
        }
        if (frame == 12) {
            prepareServerScene(mc, false);
            mc.options.hideGui = false;
            AntimonyMod.LOG.info("[Antimony] render test scene built at {}", player.blockPosition().toShortString());
        }
        if (frame >= 40 && frame <= 220 && !mc.options.hideGui) {
            float yaw = ((frame - 40) * 3.0F) % 360.0F;
            player.setYRot(yaw);
            player.yRotO = yaw;
            player.setXRot(0.0F);
            player.xRotO = 0.0F;
        }
        if (frame >= 225 && frame <= 245) {
            float pitch = (frame - 225) * 3.0F;
            player.setXRot(pitch);
            player.xRotO = pitch;
            player.setYRot(180.0F);
            player.yRotO = 180.0F;
        }
        if (frame >= 255 && frame <= 275) {
            float pitch = -((frame - 255) * 3.0F);
            player.setXRot(pitch);
            player.xRotO = pitch;
            player.setYRot(0.0F);
            player.yRotO = 0.0F;
        }
        if (frame == 280) {
            player.setXRot(0.0F);
            player.xRotO = 0.0F;
            player.setYRot(0.0F);
            player.yRotO = 0.0F;
        }
        // Cloud check: look up at the sky for a while so the screenshots show
        // the cloud layer (it used to be missing entirely).
        if (frame >= 500 && frame <= 600) {
            player.setXRot(-32.0F);
            player.xRotO = -32.0F;
            float yaw = (frame - 500) * 1.2F;
            player.setYRot(yaw);
            player.yRotO = yaw;
        }
        if (frame == 601) {
            player.setXRot(0.0F);
            player.xRotO = 0.0F;
        }
        if (frame == 30) {
            giveTestItems(player);
            AntimonyMod.LOG.info("[Antimony] render test: test items given");
        }
        if (frame == 320) {
            giveTestItems(player);
            AntimonyMod.LOG.info("[Antimony] render test: inventory filled");
        }
        if (frame >= 320 && frame < 420) {
            if (mc.screen == null) {
                mc.setScreen(new InventoryScreen(player));
            }
        } else if (mc.screen instanceof InventoryScreen) {
            mc.setScreen(null);
        }
        if (frame == 430) {
            mc.getEntityRenderDispatcher().setRenderHitBoxes(true);
            mc.debugRenderer.switchRenderChunkborder();
            AntimonyMod.LOG.info("[Antimony] render test: F3+B hitboxes and F3+G chunk borders enabled");
        }
        if (frame == 470) {
            mc.getEntityRenderDispatcher().setRenderHitBoxes(false);
            mc.debugRenderer.switchRenderChunkborder();
        }
        if (frame >= 20 && frame <= 500 && frame % 40 == 0) {
            AntimonyMod.LOG.info("[Antimony] render test frame {} pos={} yaw={} pitch={} particles={}",
                    frame, player.blockPosition().toShortString(), player.getYRot(), player.getXRot(),
                    dev.antimony.core.render.ParticlePass.lastCount());
        }
    }

    private static int demoZombieId = -1;

    /**
     * Spawns one zombie four blocks east of the player, aims the camera at its
     * chest and then drives the two overlay effects directly on the client
     * entity: frames 20..139 burn (fire overlay), frames 140+ pulse
     * {@code hurtTime} (red flash) every 40 frames. Screenshots taken from the
     * run script can be diffed to prove the overlay is animated/tinted.
     */
    private static void tickEntityTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        if (frame == 3) {
            prepareServerScene(mc, true);
            return;
        }
        if (frame == 12) {
            prepareServerScene(mc, false);
            mc.options.hideGui = false;
            try {
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    BlockPos base = player.blockPosition();
                    // Roof over the stage: a zombie in direct daylight catches
                    // fire on the server, which syncs the on-fire flag back to
                    // the client and would mask the "no fire" phase.
                    for (int dx = 1; dx <= 7; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            level.setBlock(base.offset(dx, 4, dz), Blocks.STONE.defaultBlockState(), 3);
                        }
                    }
                    net.minecraft.world.entity.monster.Zombie zombie =
                            net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
                    if (zombie != null) {
                        zombie.moveTo(base.getX() + 4.5, base.getY(), base.getZ() + 0.5, 180.0F, 0.0F);
                        zombie.setNoAi(true);
                        zombie.setPersistenceRequired();
                        level.addFreshEntity(zombie);
                        demoZombieId = zombie.getId();
                        AntimonyMod.LOG.info("[Antimony] entity test: zombie #{} spawned at {}",
                                demoZombieId, zombie.blockPosition().toShortString());
                    }
                }
            } catch (Throwable t) {
                AntimonyMod.LOG.warn("[Antimony] entity test spawn failed", t);
            }
        }
        if (frame < 20 || demoZombieId < 0) {
            return;
        }
        net.minecraft.world.entity.Entity zombie = mc.level.getEntity(demoZombieId);
        if (!(zombie instanceof net.minecraft.world.entity.LivingEntity living)) {
            return;
        }
        // Lock the camera onto the zombie's chest so the mob sits dead centre
        // of every screenshot (the runner crops that box for the pixel diff).
        double dx = zombie.getX() - player.getX();
        double dz = zombie.getZ() - player.getZ();
        double dy = zombie.getY() + 1.1 - (player.getY() + player.getEyeHeight());
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(pitch);
        player.xRotO = pitch;

        boolean burning = frame < 140;
        zombie.setRemainingFireTicks(burning ? 200 : 0);
        if (!burning && (frame - 140) % 40 == 0) {
            living.hurtTime = 10;
            living.hurtDuration = 10;
        }
        if (frame % 20 == 0) {
            AntimonyMod.LOG.info("[Antimony] entity test frame {} burning={} onFire={} hurtTime={} fireTicks={} pos={}",
                    frame, burning, zombie.isOnFire(), living.hurtTime, zombie.getRemainingFireTicks(),
                    zombie.blockPosition().toShortString());
        }
        if (frame == 100) {
            // F2 regression: vanilla reads the (now unused) GL render target, so
            // ScreenshotMixin must hand back the software framebuffer instead.
            try {
                var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
                int centre = image.getPixelRGBA(image.getWidth() / 2, image.getHeight() / 2);
                AntimonyMod.LOG.info("[Antimony] screenshot probe: {}x{} centre={}",
                        image.getWidth(), image.getHeight(), String.format("%08X", centre));
                image.close();
            } catch (Throwable t) {
                AntimonyMod.LOG.warn("[Antimony] screenshot probe failed", t);
            }
        }
        if (frame == 60 || frame == 64) {
            // Proves the block atlas copy really is being refreshed (animated
            // fire/water), independent of particle motion in the screenshots.
            var atlas = dev.antimony.core.render.IrisGlTextures.blockAtlas();
            if (atlas != null) {
                long hash = 0L;
                for (int i = 0; i < atlas.pixels.length; i += 13) {
                    hash = hash * 1099511628211L + atlas.pixels[i];
                }
                int glId = ((net.minecraft.client.renderer.texture.TextureAtlas) Minecraft.getInstance().getTextureManager()
                        .getTexture(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)).getId();
                var mirror = dev.antimony.gl.hook.GlTexMirror.mirror(glId);
                long mhash = 0L;
                if (mirror != null) {
                    for (int i = 0; i < mirror.pixels.length; i += 13) {
                        mhash = mhash * 1099511628211L + mirror.pixels[i];
                    }
                }
                AntimonyMod.LOG.info("[Antimony] atlas probe frame {}: {}x{} hash={} mirrorGlId={} mirrorHash={} uploads={} absorbed={} forwarded={} byId: {}",
                        frame, atlas.width, atlas.height, Long.toHexString(hash), glId,
                        mirror == null ? "none" : Long.toHexString(mhash),
                        dev.antimony.gl.hook.GlTexMirror.mirroredUploads(),
                        dev.antimony.gl.hook.GlTexMirror.absorbedUploads(),
                        dev.antimony.gl.hook.GlTexMirror.forwardedUploads(),
                        dev.antimony.gl.hook.GlTexMirror.uploadAttribution());
                if (mirror != null && frame == 64) {
                    // Decisive diff: mirror vs driver-side atlas (direct GL
                    // read-back, bypassing our hook pipeline).
                    try {
                        int prev = org.lwjgl.opengl.GL11.glGetInteger(
                                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
                        java.nio.IntBuffer rb = org.lwjgl.system.MemoryUtil.memAllocInt(mirror.pixels.length);
                        try {
                            org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, rb);
                        } finally {
                            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prev);
                        }
                        int diff = 0;
                        int maxD = 0;
                        int firstDiff = -1;
                        for (int i = 0; i < mirror.pixels.length; i++) {
                            // memAllocInt is little-endian: glGetTexImage RGBA
                            // bytes land in one int as ABGR - same packing
                            // IrisGlTextures.argb() consumes.
                            int drv = rb.get(i);
                            int a = (drv >>> 24) & 0xFF, r = drv & 0xFF, g = (drv >>> 8) & 0xFF, b = (drv >>> 16) & 0xFF;
                            int argb = (a << 24) | (r << 16) | (g << 8) | b;
                            if (argb != mirror.pixels[i]) {
                                diff++;
                                if (firstDiff < 0) {
                                    firstDiff = i;
                                }
                                maxD = Math.max(maxD,
                                        Math.abs(((argb >>> 16) & 0xFF) - ((mirror.pixels[i] >>> 16) & 0xFF)));
                            }
                        }
                        AntimonyMod.LOG.info("[Antimony] atlas mirror-vs-driver: diff={} maxRDelta={} firstAt={} (x={} y={})",
                                diff, maxD, firstDiff, firstDiff < 0 ? -1 : firstDiff % atlas.width,
                                firstDiff < 0 ? -1 : firstDiff / atlas.width);
                    } catch (Throwable t) {
                        AntimonyMod.LOG.warn("[Antimony] mirror diff failed", t);
                    }
                }
            }
        }
    }

    /** Torch/glowstone light propagation regression scene. */
    private static void tickLightTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        if (frame == 3) {
            prepareServerScene(mc, true);
            // Night: sky light contributes ~nothing to the lightmap, so the
            // warm block-light pool around each torch is unmistakable (in
            // daylight sky 15 masks the gradient completely).
            var server = mc.getSingleplayerServer();
            if (server != null) {
                server.overworld().setDayTime(18000L);
            }
        }
        if (frame == 12) {
            prepareServerScene(mc, false);
            mc.options.hideGui = false;
            AntimonyMod.LOG.info("[Antimony] light test: platform built at {}", player.blockPosition().toShortString());
        }
        if (frame == 30) {
            // A single torch on a wide dark platform: block light 14 at the
            // torch must fade 13, 12, ... 0 with distance - an unmistakable
            // warm pool at night. (An earlier version spaced torches two
            // blocks apart, whose overlapping pools made the whole platform
            // light 12-13 and hid the gradient.)
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var level = server.overworld();
                BlockPos base = player.blockPosition();
                level.setBlock(base.offset(3, 0, -1), Blocks.TORCH.defaultBlockState(), 3);
                AntimonyMod.LOG.info("[Antimony] light test: single torch placed at {}", base.offset(3, 0, -1).toShortString());
            }
        }
        // Fixed camera looking slightly down over the torch.
        if (frame >= 20) {
            player.setXRot(18.0F);
            player.xRotO = 18.0F;
            player.setYRot(180.0F);
            player.yRotO = 180.0F;
        }
        if (frame == 60 || frame == 120 || frame == 200) {
            ClientLevel level = mc.level;
            BlockPos base = player.blockPosition();
            // Sample the row in front of the torch: expect a clean gradient
            // 14 (at torch) down to 0 (12 blocks away, far corner).
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i <= 13; i++) {
                sb.append(' ').append(i).append('=')
                        .append(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, base.offset(i, 0, -1)));
            }
            AntimonyMod.LOG.info("[Antimony] light test frame {} blockLight gradient row z-1 (x=2..13):{}{}",
                    frame, sb, level.getChunkSource().getLightEngine().hasLightWork() ? " (updates pending!)" : "");
        }
        if (frame >= 20 && frame <= 260 && frame % 40 == 0) {
            AntimonyMod.LOG.info("[Antimony] light test frame {} pos={}", frame, player.blockPosition().toShortString());
        }
    }

    /**
     * Teleports the player to a clear sky platform and/or builds the test
     * scene on the integrated server. Client-side setBlock calls are reverted
     * by the server when the player has no permission, which is why the earlier
     * scene never appeared.
     */
    private static void prepareServerScene(Minecraft mc, boolean teleportOnly) {
        try {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            net.minecraft.server.level.ServerLevel level = server.overworld();
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return;
            }
            net.minecraft.server.level.ServerPlayer player = players.get(0);
            if (teleportOnly) {
                BlockPos base = new BlockPos(player.getBlockX(), 128, player.getBlockZ());
                player.teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
                level.setDayTime(6000L);
                level.setWeatherParameters(12000, 0, false, false);
                return;
            }
            BlockPos base = player.blockPosition();
            for (int dx = -7; dx <= 7; dx++) {
                for (int dz = -7; dz <= 7; dz++) {
                    level.setBlock(base.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    for (int dy = 0; dy <= 5; dy++) {
                        level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            // 濠电姰鍨归悥銏ゅ礃椤忎礁浜鹃柣鎴ｆ閸ㄥ倿鏌涚仦鍓р姇婵☆垰锕弻? zero-thickness double-sided leaf model.
            level.setBlock(base.offset(0, 0, -3), Blocks.BIG_DRIPLEAF.defaultBlockState(), 3);
            // 闂備胶绮竟鏇㈠疾濞戙埄鏁婄€广儱妫楃欢鐐烘煙缂併垹娅橀柡? translucent skin + hat layer (entity_translucent, no cull).
            level.setBlock(base.offset(3, 0, 0), Blocks.PLAYER_HEAD.defaultBlockState(), 3);
            // 闂備浇袙閸嬫捇鏌曟竟顖氬€荤壕? animated fire texture + smoke particles.
            BlockState campfire = Blocks.CAMPFIRE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, true);
            level.setBlock(base.offset(0, 0, 3), campfire, 3);
            // Cutout block items for icon comparison.
            level.setBlock(base.offset(-3, 0, 0), Blocks.OAK_SIGN.defaultBlockState(), 3);
            level.setBlock(base.offset(-3, 0, 2), Blocks.WHITE_BANNER.defaultBlockState(), 3);
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] render test server setup failed", t);
        }
    }

    private static void giveTestItems(LocalPlayer player) {
        ItemStack[] stacks = {
                new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.WHITE_BANNER),
                new ItemStack(Items.OAK_SIGN),
                new ItemStack(Items.APPLE),
                new ItemStack(Items.FILLED_MAP),
                new ItemStack(Items.SHIELD),
                new ItemStack(Items.PLAYER_HEAD),
                new ItemStack(Items.BIG_DRIPLEAF),
                new ItemStack(Items.CAMPFIRE)
        };
        for (int i = 0; i < stacks.length; i++) {
            player.getInventory().setItem(i, stacks[i]);
        }
        // Select the sword: the first-person hand should then show a sword, so
        // a wrong floating item is easy to tell apart from world geometry.
        player.getInventory().selected = 0;
    }

    private static void tickSmokeTest(Minecraft mc, long frame) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (frame >= 20 && frame <= 110 && frame % 2 == 0) {
            for (int i = 0; i < 4; i++) {
                double x = player.getX() + (level.random.nextDouble() - 0.5) * 2.5;
                double y = player.getY() + 1.2 + level.random.nextDouble();
                double z = player.getZ() + (level.random.nextDouble() - 0.5) * 2.5;
                if (frame < 60) {
                    level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.01, 0.0);
                } else {
                    level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0.0, 0.01, 0.0);
                }
            }
        }
        if (frame == 100) {
            BlockPos pos = player.blockPosition();
            var biome = level.getBiome(pos).value();
            AntimonyMod.LOG.info("[Antimony] demo: biome={} precipitation={} rain={}",
                    level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("?"),
                    biome.hasPrecipitation(), level.getRainLevel(1.0F));
        }
        if (frame == 130) {
            AntimonyMod.LOG.info("[Antimony] demo: rain level now {}", level.getRainLevel(1.0F));
        }
        // Inventory exercise: open the vanilla inventory for a stretch of
        // frames so automated screenshots capture layout, item icons and
        // highlight behaviour with the software rasterizer.
        if (frame >= 150 && frame < 260) {
            if (mc.screen == null) {
                mc.setScreen(new InventoryScreen(mc.player));
            }
        } else if (mc.screen instanceof InventoryScreen) {
            mc.setScreen(null);
        }
        // Block-update visibility check: place a glowstone pillar two blocks
        // in front of the camera, remove it again shortly after. Exercises
        // ChunkUpdateHooks -> remeshSection end to end.
        if (frame == 300) {
            BlockPos base = player.blockPosition().relative(player.getDirection(), 2);
            for (int i = 1; i <= 3; i++) {
                mc.level.setBlock(base.above(i), Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
            AntimonyMod.LOG.info("[Antimony] demo: placed glowstone pillar at {}", base.toShortString());
        }
        if (frame == 380) {
            BlockPos base = player.blockPosition().relative(player.getDirection(), 2);
            for (int i = 1; i <= 3; i++) {
                mc.level.removeBlock(base.above(i), false);
            }
            AntimonyMod.LOG.info("[Antimony] demo: removed glowstone pillar at {}", base.toShortString());
        }
        if (frame == 430) {
            AntimonyMod.LOG.info("[Antimony] demo: mesh count after updates: {}", level.getChunkSource().getLoadedChunksCount());
        }
    }
}
