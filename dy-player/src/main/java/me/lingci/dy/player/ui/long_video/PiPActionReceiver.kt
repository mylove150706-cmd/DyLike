package me.lingci.dy.player.ui.long_video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PiP 小窗操作按钮常量定义。
 *
 * 定义系统 PiP 窗口中 RemoteAction 按钮使用的广播动作和请求码。
 * 实际广播由 [LongVideoActivity] 内部注册的接收器处理，
 * 本类仅作为常量容器，不处理广播逻辑。
 *
 * 支持的动作：
 * - [ACTION_PREV]：上一集
 * - [ACTION_PLAY_PAUSE]：播放/暂停切换
 * - [ACTION_NEXT]：下一集
 */
class PiPActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 实际处理由 LongVideoActivity 内部注册的 pipActionReceiver 完成
        // 此类仅作为常量容器存在，不处理广播
    }

    companion object {
        /** 上一集动作 */
        const val ACTION_PREV = "me.lingci.dy.player.pip.ACTION_PREV"
        /** 播放/暂停切换动作 */
        const val ACTION_PLAY_PAUSE = "me.lingci.dy.player.pip.ACTION_PLAY_PAUSE"
        /** 下一集动作 */
        const val ACTION_NEXT = "me.lingci.dy.player.pip.ACTION_NEXT"

        /** RemoteAction 请求码 */
        const val REQUEST_PREV = 1
        const val REQUEST_PLAY_PAUSE = 2
        const val REQUEST_NEXT = 3
    }
}
