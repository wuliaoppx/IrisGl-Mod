// Antimony phase-6 GL shim coremod.
//
// Rewrites direct LWJGL OpenGL calls inside Iris classes (org.lwjgl.opengl.GLxx.glXxx)
// into calls to dev.antimony.gl.shim.GlShim, so a mod that bypasses GlStateManager
// is routed into the CPU compatibility layer instead of hitting a real GL driver.
// GlShim forwards to real LWJGL unless -Dantimony.glcompat=on, so report/off
// sessions keep native behaviour; the rewrite itself is unconditional and
// invisible to them.
//
// JS coremod contract (net.neoforged.coremod 7.0.3, verified against bytecode):
//   initializeCoreMod() -> { "<transformer name>": {
//       target: { type: "CLASS",                 // TargetType.byName is case-sensitive
//                 names: function () -> { key: "<internal class name>", ... } },
//       transformer: function (ClassNode) -> ClassNode
//   } }
// IMPORTANT: "transformer" is read from the ENTRY, not from inside "target"
// (CoreMod.initialize: entry -> "target" -> "type"/"names"; entry -> "transformer").
// Nesting transformer inside target stores a null function that NPEs on the
// first transformed class load (see docs section 18).
// The names map values must be PLAIN class-name strings (lambda$buildCore$0
// does checkcast java/lang/String).

var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');

// Allowlist of Iris classes that touch org/lwjgl/opengl directly (instance scan,
// build/iris-gl-classes.txt). Modlauncher only transforms classes named here;
// over-approving costs nothing because unlisted classes never register a target
// and listed ones without GL calls pass through untouched. Extending to other
// direct-GL mods = append entries (or prefixes via shouldTransform).
var GL_CLASSES = [
    'net/irisshaders/iris/compat/dh/DHCompatInternal',
    'net/irisshaders/iris/compat/dh/DhFrameBufferWrapper',
    'net/irisshaders/iris/compat/dh/IrisGenericRenderProgram',
    'net/irisshaders/iris/compat/dh/IrisLodRenderProgram',
    'net/irisshaders/iris/compat/dh/LodRendererEvents$10',
    'net/irisshaders/iris/compat/dh/LodRendererEvents$13',
    'net/irisshaders/iris/compat/sodium/mixin/MixinGlRenderDevice',
    'net/irisshaders/iris/gl/GLDebug$KHRDebugState',
    'net/irisshaders/iris/gl/GLDebug',
    'net/irisshaders/iris/gl/IrisRenderSystem$DSAARB',
    'net/irisshaders/iris/gl/IrisRenderSystem$DSAUnsupported',
    'net/irisshaders/iris/gl/IrisRenderSystem',
    'net/irisshaders/iris/gl/blending/AlphaTestFunction',
    'net/irisshaders/iris/gl/blending/BlendModeFunction',
    'net/irisshaders/iris/gl/buffer/ShaderStorageBuffer',
    'net/irisshaders/iris/gl/buffer/ShaderStorageBufferHolder',
    'net/irisshaders/iris/gl/framebuffer/GlFramebuffer',
    'net/irisshaders/iris/gl/image/GlImage',
    'net/irisshaders/iris/gl/image/ImageBinding',
    'net/irisshaders/iris/gl/program/ComputeProgram',
    'net/irisshaders/iris/gl/program/Program',
    'net/irisshaders/iris/gl/program/ProgramSamplers',
    'net/irisshaders/iris/gl/program/ProgramUniforms$Builder',
    'net/irisshaders/iris/gl/program/ProgramUniforms',
    'net/irisshaders/iris/gl/sampler/GlSampler',
    'net/irisshaders/iris/gl/sampler/SamplerLimits',
    'net/irisshaders/iris/gl/shader/GlShader',
    'net/irisshaders/iris/gl/shader/ProgramCreator',
    'net/irisshaders/iris/gl/shader/ShaderType',
    'net/irisshaders/iris/gl/shader/ShaderWorkarounds',
    'net/irisshaders/iris/gl/shader/StandardMacros',
    'net/irisshaders/iris/gl/texture/DepthBufferFormat',
    'net/irisshaders/iris/gl/texture/DepthCopyStrategy$Gl20CopyTexture',
    'net/irisshaders/iris/gl/texture/DepthCopyStrategy$Gl30BlitFbCombinedDepthStencil',
    'net/irisshaders/iris/gl/texture/DepthCopyStrategy$Gl43CopyImage',
    'net/irisshaders/iris/gl/texture/DepthCopyStrategy',
    'net/irisshaders/iris/gl/texture/GlTexture',
    'net/irisshaders/iris/gl/texture/InternalTextureFormat',
    'net/irisshaders/iris/gl/texture/PixelFormat',
    'net/irisshaders/iris/gl/texture/PixelType',
    'net/irisshaders/iris/gl/texture/TextureType',
    'net/irisshaders/iris/gl/texture/TextureUploadHelper',
    'net/irisshaders/iris/mixin/MixinBooleanState',
    'net/irisshaders/iris/mixin/MixinGlStateManager_DepthColorOverride',
    'net/irisshaders/iris/mixin/MixinGlStateManager_FramebufferBinding',
    'net/irisshaders/iris/mixin/MixinLevelRenderer',
    'net/irisshaders/iris/mixin/MixinProgramType',
    'net/irisshaders/iris/mixin/MixinRenderTarget',
    'net/irisshaders/iris/mixin/MixinShaderInstance',
    'net/irisshaders/iris/mixin/integrationtest/MixinRenderTarget_StencilBufferTest',
    'net/irisshaders/iris/pathways/CenterDepthSampler',
    'net/irisshaders/iris/pathways/colorspace/ColorSpaceComputeConverter',
    'net/irisshaders/iris/pathways/colorspace/ColorSpaceFragmentConverter',
    'net/irisshaders/iris/pbr/TextureInfoCache$TextureInfo',
    'net/irisshaders/iris/pbr/format/TextureFormat',
    'net/irisshaders/iris/pbr/util/TextureManipulationUtil',
    'net/irisshaders/iris/pipeline/CompositeRenderer',
    'net/irisshaders/iris/pipeline/CustomTextureManager',
    'net/irisshaders/iris/pipeline/FinalPassRenderer',
    'net/irisshaders/iris/pipeline/IrisRenderingPipeline',
    'net/irisshaders/iris/pipeline/PipelineManager',
    'net/irisshaders/iris/pipeline/programs/ExtendedShader',
    'net/irisshaders/iris/pipeline/programs/GlUniformMatrix3f',
    'net/irisshaders/iris/pipeline/programs/SodiumPrograms',
    'net/irisshaders/iris/pipeline/programs/SodiumShader',
    'net/irisshaders/iris/shadows/ShadowCompositeRenderer',
    'net/irisshaders/iris/shadows/ShadowRenderTargets',
    'net/irisshaders/iris/shadows/ShadowRenderer',
    'net/irisshaders/iris/targets/ClearPassCreator',
    'net/irisshaders/iris/targets/DepthTexture',
    'net/irisshaders/iris/targets/RenderTarget',
    'net/irisshaders/iris/targets/RenderTargets',
    'net/irisshaders/iris/targets/backed/NativeImageBackedCustomTexture',
    'net/irisshaders/iris/targets/backed/NoiseTexture',
    'net/irisshaders/iris/targets/backed/SingleColorTexture',
    'net/irisshaders/iris/uniforms/FogUniforms',
    'net/irisshaders/iris/uniforms/custom/cached/BooleanCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Float2VectorCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Float3VectorCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Float4MatrixCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Float4VectorCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/FloatCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Int2VectorCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/Int3VectorCachedUniform',
    'net/irisshaders/iris/uniforms/custom/cached/IntCachedUniform'
];

// Owners whose static gl*/ngl* methods are rewritten. LWJGL exposes the same
// method on many versioned facades (GL11..GL46C, ARB*, KHR*, AMD*, EXT*, NV*);
// matching by name + any lwjgl-gl facade keeps the shim to one method per
// semantic call. nglShaderSource is included: Iris' ShaderWorkarounds calls
// GL20C.nglShaderSource directly (bytecode-verified, the pack's only ngl*
// call site) and the source must reach GlShim for the CPU shader pipeline.
function isGlOwner(owner) {
    if (owner === null || owner.indexOf('org/lwjgl/opengl/') !== 0) {
        return false;
    }
    // 'org/lwjgl/opengl/'.length == 17 (the old indexOf('GL')===16 check was
    // off-by-one and matched nothing).
    var facade = owner.substring(17);
    return facade.indexOf('GL') === 0 || facade.indexOf('ARB') === 0
        || facade.indexOf('KHR') === 0 || facade.indexOf('AMD') === 0
        || facade.indexOf('EXT') === 0 || facade.indexOf('NV') === 0;
}

// Method names eligible for rewrite: gl* always; nglShaderSource specifically
// (pointer-variant source submission - the entry point for shader capture).
function isRewritableName(name) {
    if (name.indexOf('gl') === 0) {
        return true;
    }
    return name.indexOf('nglShaderSource') === 0;
}

function shouldTransform(className) {
    // Never rewrite inside vanilla/blaze3d/antimony: those calls are intercepted
    // by our GlStateManager mixins; rewriting them would double-redirect and loop.
    if (className.indexOf('com/mojang/blaze3d/') === 0
        || className.indexOf('net/minecraft/') === 0
        || className.indexOf('dev/antimony/') === 0) {
        return false;
    }
    for (var i = 0; i < GL_CLASSES.length; i++) {
        if (className === GL_CLASSES[i]) {
            return true;
        }
    }
    return false;
}

function initializeCoreMod() {
    var namesMap = {};
    for (var i = 0; i < GL_CLASSES.length; i++) {
        namesMap['c' + i] = GL_CLASSES[i]; // plain string values, per contract
    }
    return {
        'antimonyGlShim': {
            target: {
                type: 'CLASS',
                names: function () {
                    return namesMap;
                }
            },
            transformer: function (node) {
                if (!shouldTransform(node.name)) {
                    return node;
                }
                var changed = 0;
                var methods = node.methods;
                for (var m = 0; m < methods.size(); m++) {
                    var insns = methods.get(m).instructions;
                    for (var k = 0; k < insns.size(); k++) {
                        var insn = insns.get(k);
                        if (!(insn instanceof MethodInsnNode)) {
                            continue;
                        }
                        if (insn.getOpcode() !== Opcodes.INVOKESTATIC
                            || !isGlOwner(insn.owner)
                            || !isRewritableName(insn.name)) {
                            continue;
                        }
                        // Rewrite: same name, same descriptor, into GlShim.
                        // GlShim keeps the real call available for report/off.
                        insn.owner = 'dev/antimony/gl/shim/GlShim';
                        changed++;
                    }
                }
                if (changed > 0) {
                    ASMAPI.log('INFO', 'coremod rewrote ' + changed
                        + ' GL call(s) in ' + node.name);
                }
                return node;
            }
        }
    };
}
