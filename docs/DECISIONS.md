# IrisGL — 决策记录

记录本项目开发过程中的关键技术决策（时间顺序）。

## D1. "irisGL" = SGI IRIS GL，不是 Iris Shaders

用户明确澄清：目标是复刻 1980 年代 SGI 的 IRIS GL API（整活向，不追求
真正可玩）。因此整条管线按 IRIS GL 4.0 风格设计（`bgnpolygon/v3f/cpack/
lookat` 等命名），而不是着色器 mod。

## D2. 单线程软件光栅化器，不做 GPU 混合方案

IRIS GL 是立即模式 API，语义上与可编程管线冲突。选择 CPU 扫描线光栅化
（透视校正插值、深度/混合/雾全部软件实现），640×360 内部分辨率。
性能很差但语义纯正——负优化正是这个 mod 的卖点。

## D3. Mixin 从 `GameRenderer.render` HEAD 整帧接管

比逐个替换渲染器更彻底：一处在帧头 cancel，后面自由调度自己的通道，
vanilla GL 路径完全不跑。`ReceivingLevelScreen` 和加载 Overlay 逐帧
强制清除，保证能进世界。

## D4. 复用 vanilla 渲染器生成内容，只截获顶点

`BlockRenderDispatcher`/`EntityRenderDispatcher`/`GuiGraphics`/
`Particle.render`/`ItemInHandRenderer` 原样调用，传入实现了
`VertexConsumer`/`MultiBufferSource` 的 CPU 缓冲。内容逻辑（模型、动画、
UV、光照坐标）全部免费获得，我们只负责"画"。

## D5. 视图矩阵 rotation-only + 相机相对顶点

最初把 eye 减两次（view 里一次、顶点里一次）导致全场空白。修正为：
view 矩阵只含旋转，所有世界几何按 `world - eye` 提交。手部是例外——
vanilla 的手部顶点已在相机空间，必须用 identity 视图。

## D6. 深度值 NDC 直存（不做 0.5+0.5 偏移会更直观，但最终选择存 0.5+0.5 后的窗口深度）

初版 NDC 深度没映射到 [0,1]，深度测试全灭、GUI 也画不出来。统一改为
`depth = ndcZ * 0.5 + 0.5` 后地形与 GUI 同时修复。

## D7. 纹理来源分两类

- 静态可取：方块图集、光照图从 NativeImage 字段直接拷贝（无需 GL）。
- 动态：GUI 图集/字体页/雨雪用 `glGetTexImage` 回读；字体页是
  GL_RED/GL_R8 单通道，按"红=alpha、白墨"重建，1 秒刷新。
  （教训：内部格式判断必须同时接受 GL_R8 与 GL_RED，MC 用的是非
  sized 格式。）

## D8. GUI 立即模式绘制用 BufferUploader Mixin 拦截

`GuiGraphics.blitSprite/fill`（Toast 背景、九宫格、准星等）不走
`MultiBufferSource`，而是 `Tesselator → BufferUploader.drawWithShader`。
一个 HEAD cancellable Mixin 按 `VertexFormat` 解析 MeshData 并回放进
IRIS GL，一次接管全部立即模式路径。

## D9. 呈现用独立 Win32 分层窗口（JNA 手写绑定）

用户选定方案：640×360 的 `UpdateLayeredWindow` 覆盖窗叠在 MC 窗口上，
不往 MC 窗口的 DC 里画。只用 jna core（5.14.0，MC 自带传递依赖），
`compileOnly` 引入，避免与 MC 的版本约束冲突。

## D10. 负优化定位：不加性能逃生门

帧率不设底线是用户的明确要求。不做多线程、不做脏矩形、不做 GPU 预算。
唯一让步是区块 mesh 缓存 + 每帧最多烘 3 个新区块（否则永远进不了世界）。

## D11. 验证 = 自动实测闭环

`run-game.ps1`：隔离 gameDir（`run/`）、classpath 去重、quickPlay 进
`TestWorld`、按日志标记（MOD_LOAD/HOOK/ACTIVE/FIRST_FRAME/SHOT）断言，
渲染器每 60/120/300/600 帧自动存截图到 `run/irisgl-shots/`。
所有渲染修复都靠截图对比闭环验证。

## D12. 许可与素材

MIT。只参考 SGI IRIS GL 手册与 irix-igl 的行为语义，不复制 GPL 代码，
不打包任何 Mojang 资产（运行时从游戏资源读取）。
