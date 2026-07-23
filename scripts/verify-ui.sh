#!/bin/bash
# ============================================================
# verify-ui.sh — 后台播放 UI 回归验证脚本
#
# 用途: 用 adb 自动验证后台播放/通知栏/PiP 等场景是否有明显回归
# 设备要求: 真机(USB调试) 或 模拟器,已安装 betaDebug APK
#
# 用法:
#   ./scripts/verify-ui.sh [serial]
#   # serial 可选,如 emulator-5554 或 AMRF026520000611
#   # 不传则用第一个连接的设备
#
# 退出码: 0=全部通过, 1=有失败项
# ============================================================

set -euo pipefail

# --- 配置 ---
PKG="me.lingci.dy.player.debug"
LONG_VIDEO_ACT="$PKG/me.lingci.dy.player.ui.long_video.LongVideoActivity"
NOTIF_ID=1001
WAIT_SHORT=3   # 秒
WAIT_MED=6     # 秒
WAIT_LONG=10   # 秒

# --- 解析参数 ---
if [ -n "${1:-}" ]; then
    ADB="adb -s $1"
    SERIAL="$1"
else
    ADB="adb"
    SERIAL=""
fi

# --- 颜色输出 ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS=0
FAIL=0

log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }
log_info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# --- 辅助函数 ---

# 获取已安装的视频文件路径(取第一个)
find_test_video() {
    local video=$($ADB shell ls /sdcard/Movies/*.mp4 2>/dev/null | head -1 | tr -d '\r')
    if [ -z "$video" ]; then
        video="/sdcard/Movies/test.mp4"
    fi
    echo "$video"
}

# 清空 logcat
clear_logcat() {
    $ADB logcat -c 2>/dev/null || true
}

# 检查 logcat 中是否有特定关键字
check_logcat() {
    local keyword="$1"
    local count=$($ADB logcat -d 2>/dev/null | grep -c "$keyword" || true)
    echo "$count"
}

# 检查通知是否存在
check_notification_exists() {
    local count=$($ADB shell dumpsys notification 2>/dev/null | grep -c "$PKG" || true)
    echo "$count"
}

# 获取通知中的 progress 值
get_notification_progress() {
    $ADB shell dumpsys notification 2>/dev/null | grep -o 'progress=[0-9]*' | head -1 | grep -o '[0-9]*' || echo "0"
}

# --- 前置检查 ---

log_info "=== 前置检查 ==="

# 检查设备连接
if ! $ADB get-state &>/dev/null; then
    log_fail "设备未连接"
    exit 1
fi
log_pass "设备已连接"

# 检查 app 是否安装
if ! $ADB shell pm path $PKG &>/dev/null; then
    log_fail "App 未安装: $PKG"
    exit 1
fi
log_pass "App 已安装"

# 强制停止 app(确保干净状态)
$ADB shell am force-stop $PKG
sleep 1
log_info "已强制停止 app"

# 找测试视频
VIDEO=$(find_test_video)
log_info "测试视频: $VIDEO"

# ============================================================
# 测试 1: 长视频播放不崩溃
# ============================================================
echo ""
log_info "=== 测试 1: 长视频播放不崩溃 ==="

clear_logcat
$ADB shell am start -a android.intent.action.VIEW -d "file://$VIDEO" -t "video/mp4" -n "$LONG_VIDEO_ACT" >/dev/null 2>&1
sleep $WAIT_MED

# 检查是否有 FATAL/崩溃
FATAL_COUNT=$(check_logcat "FATAL EXCEPTION")
if [ "$FATAL_COUNT" -eq 0 ]; then
    log_pass "长视频播放无崩溃"
else
    log_fail "长视频播放崩溃 (FATAL EXCEPTION x$FATAL_COUNT)"
fi

# 检查播放器是否在播放
STATE_COUNT=$(check_logcat "STATE_PLAYING")
if [ "$STATE_COUNT" -gt 0 ]; then
    log_pass "播放器已进入播放状态"
else
    log_fail "播放器未进入播放状态"
fi

# ============================================================
# 测试 2: 按 HOME 后台播放通知出现
# ============================================================
echo ""
log_info "=== 测试 2: 按 HOME 后台播放通知出现 ==="

clear_logcat
$ADB shell input keyevent KEYCODE_HOME
sleep $WAIT_MED

# 检查通知是否存在
NOTIF_COUNT=$(check_notification_exists)
if [ "$NOTIF_COUNT" -gt 0 ]; then
    log_pass "后台播放通知已出现"
else
    log_fail "后台播放通知未出现"
fi

# 检查通知是否有 actions(按钮)
ACTION_COUNT=$($ADB shell dumpsys notification 2>/dev/null | grep -c "actions=" || true)
if [ "$ACTION_COUNT" -gt 0 ]; then
    log_pass "通知包含操作按钮 (actions)"
else
    log_fail "通知未包含操作按钮"
fi

# ============================================================
# 测试 3: setRenderer 无崩溃
# ============================================================
echo ""
log_info "=== 测试 3: setRenderer 无崩溃 ==="

# 检查 logcat 中是否有 setRenderer 异常
RENDERER_CRASH=$(check_logcat "setRenderer has already been called")
if [ "$RENDERER_CRASH" -eq 0 ]; then
    log_pass "GlRenderView setRenderer 无重复调用崩溃"
else
    log_fail "GlRenderView setRenderer 崩溃 (x$RENDERER_CRASH)"
fi

# 检查是否有 InflateException(布局 inflate 失败)
INFLATE_CRASH=$(check_logcat "InflateException")
if [ "$INFLATE_CRASH" -eq 0 ]; then
    log_pass "通知布局 inflate 无异常"
else
    log_fail "通知布局 inflate 异常 (x$INFLATE_CRASH)"
fi

# ============================================================
# 测试 4: 通知进度条更新
# ============================================================
echo ""
log_info "=== 测试 4: 通知进度条更新 ==="

PROG1=$(get_notification_progress)
log_info "当前进度: $PROG1"
sleep $WAIT_SHORT
PROG2=$(get_notification_progress)
log_info "3秒后进度: $PROG2"

if [ "$PROG2" -gt "$PROG1" ]; then
    log_pass "通知进度条实时更新 ($PROG1 → $PROG2)"
else
    log_fail "通知进度条未更新 ($PROG1 → $PROG2)"
fi

# ============================================================
# 测试 5: 通知暂停按钮生效
# ============================================================
echo ""
log_info "=== 测试 5: 通知暂停按钮生效 ==="

clear_logcat
# 模拟点击暂停(发广播)
$ADB shell am broadcast -a me.lingci.dy.player.playback.PAUSE --include-stopped-packages -p $PKG >/dev/null 2>&1
sleep $WAIT_SHORT

# 检查是否收到暂停广播
PAUSE_COUNT=$(check_logcat "playPauseReceiver onReceive.*PAUSE\|MediaSession onPause")
if [ "$PAUSE_COUNT" -gt 0 ]; then
    log_pass "通知暂停按钮生效"
else
    log_fail "通知暂停按钮未生效"
fi

# ============================================================
# 测试 6: 通知播放按钮生效
# ============================================================
echo ""
log_info "=== 测试 6: 通知播放按钮生效 ==="

clear_logcat
$ADB shell am broadcast -a me.lingci.dy.player.playback.PLAY --include-stopped-packages -p $PKG >/dev/null 2>&1
sleep $WAIT_SHORT

PLAY_COUNT=$(check_logcat "playPauseReceiver onReceive.*PLAY\|MediaSession onPlay")
if [ "$PLAY_COUNT" -gt 0 ]; then
    log_pass "通知播放按钮生效"
else
    log_fail "通知播放按钮未生效"
fi

# ============================================================
# 测试 7: 后退15秒生效
# ============================================================
echo ""
log_info "=== 测试 7: 后退15秒按钮生效 ==="

clear_logcat
$ADB shell am broadcast -a me.lingci.dy.player.playback.REWIND --include-stopped-packages -p $PKG >/dev/null 2>&1
sleep $WAIT_SHORT

REWIND_COUNT=$(check_logcat "seekRelative.*-15000")
if [ "$REWIND_COUNT" -gt 0 ]; then
    log_pass "后退15秒按钮生效"
else
    log_fail "后退15秒按钮未生效"
fi

# ============================================================
# 测试 8: 截图保存
# ============================================================
echo ""
log_info "=== 测试 8: 截图保存 ==="

SCREENSHOT_DIR="screenshots"
mkdir -p "$SCREENSHOT_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
SCREENSHOT="$SCREENSHOT_DIR/verify_$TIMESTAMP.png"

if $ADB exec-out screencap -p > "$SCREENSHOT" 2>/dev/null; then
    SIZE=$(wc -c < "$SCREENSHOT")
    if [ "$SIZE" -gt 1000 ]; then
        log_pass "截图已保存: $SCREENSHOT ($SIZE bytes)"
    else
        log_fail "截图文件过小 ($SIZE bytes),可能截取失败"
    fi
else
    log_fail "截图失败"
fi

# ============================================================
# 测试 9: 通知关闭按钮停止播放
# ============================================================
echo ""
log_info "=== 测试 9: 通知关闭按钮停止播放 ==="

clear_logcat
$ADB shell am broadcast -a me.lingci.dy.player.playback.CLOSE --include-stopped-packages -p $PKG >/dev/null 2>&1
sleep $WAIT_SHORT

# 检查 Service 是否停止
SERVICE_COUNT=$($ADB shell dumpsys activity services $PKG 2>/dev/null | grep -c "PlaybackService" || true)
if [ "$SERVICE_COUNT" -eq 0 ]; then
    log_pass "关闭按钮停止了后台播放 Service"
else
    # Service 可能还在销毁中,再等一下
    sleep 2
    SERVICE_COUNT=$($ADB shell dumpsys activity services $PKG 2>/dev/null | grep -c "PlaybackService" || true)
    if [ "$SERVICE_COUNT" -eq 0 ]; then
        log_pass "关闭按钮停止了后台播放 Service"
    else
        log_fail "关闭后 Service 仍在运行"
    fi
fi

# ============================================================
# 汇总
# ============================================================
echo ""
echo "=========================================="
echo -e "  ${GREEN}通过: $PASS${NC}  ${RED}失败: $FAIL${NC}"
echo "=========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
else
    exit 0
fi
