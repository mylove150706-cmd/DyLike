// Phase 1 验证 shader：把画面染红，证明 Media3 setVideoEffects 在 DyLike 现有
// TextureRenderView/SurfaceRenderView 上能跑通。
// 验证通过后会替换为 unsharp_mask（Phase 2）和 adaptive_sharpen（Phase 3）。
//
// 重要：Media3 的 vertex_shader_transformation_es2 输出的 varying 是 vTexSamplingCoord（不是 vTexCoord）。
// Fragment shader 必须匹配这个名字，否则 link 失败。

#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;

void main() {
    vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
    // 强制把 R 通道拉满，画面整体变红
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);
}
