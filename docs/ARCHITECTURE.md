# IrisGL — 架构说明

> 把 Minecraft 1.21.1 (NeoForge) 的整个 OpenGL 渲染管线替换成 1980 年代
> SGI IRIS GL 风格的单线程软件光栅化器的整活模组。

## 总体架构

```
GameRenderer.render (Mixin HEAD, cancellable)
        │  取代整帧
        ▼
IrisGlRenderer ────────── 640×360 帧缓冲, 每帧调度所有通道
        │
        ├── WorldRenderer      地形 + 天空
        │     ├── SectionMesher    用 vanilla BlockRenderDispatcher 烘焙区块 mesh
        │     ├── SectionMesh      回放: IRIS GL 立即模式四边形
        │     ├── EntityPass       vanilla EntityRenderDispatcher / BlockEntity 渲染
        │     ├── ParticlePass     vanilla ParticleEngine 粒子
        │     ├── WeatherPass      雨/雪
        │     └── HandPass         第一人称手部/手持物品
        ├── GuiPass            vanilla GuiGraphics → HUD/聊天/热栏/Toast
        └── OverlayWindow      Win32 分层窗口把帧贴到游戏窗口上
```

## IRIS GL 层 (`dev.irisgl.irisgl`)

`IrisGl` 是对标 SGI IRIS GL 4.0 C API 的静态门面：`winset`, `RGBmode`,
`cpack/c3f/c4f`, `perspective/ortho/lookat`, `pushmatrix/popmatrix`,
`bgnpolygon/endpolygon`, `v2f/v3f/t2f/l2f`, `zbuffer`, `blendfunction`,
`fog`, `scissor`……命名与调用顺序尽量贴合 1990 年的 IRIS GL 参考手册。

- **IrisGlContext** — 立即模式状态机：矩阵栈、当前颜色/UV/光照坐标、雾、
  深度、混合、剪裁。顶点入栈，`endpolygon()` 时按扇形三角化。
- **IrisGlRasterizer** — 单线程扫描线三角光栅化器：近平面裁剪
  (`z + w >= 0`)、透视校正 UV/光照插值、深度缓冲、alpha 测试、
  SRC_ALPHA 混合、雾、线/点图元。
- **IrisGlFramebuffer / IrisGlTexture** — CPU 侧 0xAARRGGBB 像素缓冲。

## 纹理捕获 (`IrisGlTextures`)

- **方块图集 / 光照图**：从 `SpriteContents.originalImage` /
  `LightTexture.lightPixels` 的 NativeImage 直接拷贝（零 GL 参与）。
- **其余纹理**（GUI 图集、字体页、雨雪等）：`glGetTexImage` 回读。
  字体页是单通道 `GL_RED`/`GL_R8`，按 "红通道 = alpha、白色墨水" 重建。
- 字体页是动态上传的，按 1 秒间隔刷新缓存。

## vanilla 渲染器的复用

所有"内容"仍由 vanilla 生成，我们只截获顶点：

| 通道 | vanilla 入口 | 截获方式 |
|---|---|---|
| 地形 | `BlockRenderDispatcher.renderBatched/renderLiquid` | `MeshBuffer`(VertexConsumer) → IRIS GL |
| 实体/方块实体 | `EntityRenderDispatcher` / `BlockEntityRenderDispatcher` | `IrisGlBufferSource` 按 RenderType 分批 |
| 粒子 | `Particle.render(VertexConsumer, Camera, t)` | 反射读 `ParticleEngine.particles` 队列 |
| 手部 | `ItemInHandRenderer.renderHandsWithItems` | 顶点本就在相机空间，视图矩阵置 identity |
| GUI | `GuiGraphics` 全部绘制调用 | `IrisGlBufferSource` + `BufferUploader` Mixin |
| 立即模式 | `BufferUploader.drawWithShader(MeshData)` | Mixin 拦截，按 VertexFormat 解析顶点 |

`RenderSystem` Mixin 镜像 `setShaderTexture` / `setShaderColor` /
`enableScissor`，让软件光栅化器知道当前绑定的纹理与着色。

## Win32 覆盖窗口 (`dev.irisgl.win32`)

手写 JNA core 绑定（不依赖 jna-platform）：`CreateWindowExA`
(`WS_EX_LAYERED|WS_EX_TRANSPARENT|WS_EX_TOPMOST|WS_EX_NOACTIVATE|
WS_EX_TOOLWINDOW`) + `CreateDIBSection`(top-down BGRA) +
`UpdateLayeredWindow`。帧缓冲按 `0xFF000000 | pixel` 预乘后呈现。
仅当 MC 窗口持焦点时呈现（`-Dirisgl.forceOverlay=true` 可强制）。

## 帧流程

1. `IrisGl.beginFrame(640, 360)` 复位状态、清屏。
2. 天空渐变（`level.getSkyColor`，随雨变暗）。
3. `perspective(fov, 16:9)` + 旋转-only `lookat`；所有世界顶点按相机相对坐标提交。
4. 区块 mesh（不透明+镂空 → 半透明按距离排序），雾按渲染距离。
5. 实体 → 方块实体 → 粒子 → 天气 → 手部。
6. `GuiPass`：正交投影重画整个 vanilla GUI。
7. `OVERLAY.present()` 把 640×360 帧推到 Win32 覆盖窗。

## 已知取舍（整活向）

- 单线程软件光栅化，~150k 四边形/帧，帧率个位数——这是特性不是 bug。
- 渲染距离 4 区块；云、全局光照管线、后处理均未复刻。
- GUI 高分缩放按 640×360 的 `guiScaled` 处理。
- 遇到异常自动回退到 vanilla GL 渲染（`IrisGlRenderer.failed()`）。

## 调试开关

| 系统属性 | 作用 |
|---|---|
| `-Dirisgl.demo=true` | 自动演示：生成火焰/烟粒子、100 帧后强制下雨 |
| `-Dirisgl.debug.camera=overview` | 俯视相机 |
| `-Dirisgl.debug.raster=true` | 光栅化器逐三角形日志 |
| `-Dirisgl.forceOverlay=true` | 焦点不在 MC 时也呈现覆盖窗 |
