// [SPIKE] 验证 user shader hook 是否真的在 mpv Android 管线里跑
// 用最简单的 LUMA hook，把 R 通道强制为 1.0，画面整体变红。
// 如果挂上后画面变红 → shader 管线通了
// 如果挂上后画面无变化 → shader 在 Android 上根本没被调用
//
// 这是 mpv user shader 格式，必须用 HOOK/TEXTURE/SIZE/OFFSET/SAVE 指令

//!HOOK LUMA
//!BIND HOOKED
//!DESC [SPIKE] red tint verification - turns image red

vec4 hook() {
    vec4 c = HOOKED_texOff(0);
    // 把 R 通道强制拉满，画面应该明显变红
    c.r = 1.0;
    return c;
}

//!HOOK CHROMA
//!BIND HOOKED
//!DESC [SPIKE] red tint on chroma too (ensure both planes get hit)

vec4 hook() {
    vec4 c = HOOKED_texOff(0);
    // Cb 通道拉低（推向红色）
    c.r = 0.0;
    return c;
}

//!HOOK RGB
//!BIND HOOKED
//!DESC [SPIKE] nuclear red - if all else fails, this should turn screen pure red

vec4 hook() {
    vec4 c = HOOKED_texOff(0);
    // 终极验证：直接把整张图变成纯红
    return vec4(1.0, 0.0, 0.0, c.a);
}
