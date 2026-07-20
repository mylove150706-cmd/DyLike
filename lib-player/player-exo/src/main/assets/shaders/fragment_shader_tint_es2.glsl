// Phase 1 验证 shader：把画面染红，证明 Media3 setVideoEffects 在 DyLike 现有
// TextureRenderView/SurfaceRenderView 上能跑通。
// 验证通过后会替换为 unsharp_mask（Phase 2）和 adaptive_sharpen（Phase 3）。
//
// uniform sampler2D uTexSampler - Media3 提供的输入纹理（已转 RGB）
// varying vec2 vTexCoord - 由 vertex_shader_transformation_es2 输出

precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexCoord;

void main() {
    vec4 c = texture2D(uTexSampler, vTexCoord);
    // 强制把 R 通道拉满，画面整体变红
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);
}
