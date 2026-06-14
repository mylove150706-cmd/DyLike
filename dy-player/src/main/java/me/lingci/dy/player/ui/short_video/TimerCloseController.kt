package me.lingci.dy.player.ui.short_video

import android.os.CountDownTimer
import androidx.fragment.app.FragmentActivity
import me.lingci.dy.player.R
import me.lingci.dy.player.view.ShortVideoControlView

/**
 * 短视频定时关闭控制器。
 *
 * 定时器需要同时更新播放控制条和设置弹窗状态，因此从 Activity 中拆出为独立对象，
 * Activity 只转发生命周期和最终关闭动作。
 */
class TimerCloseController(
    private val activity: FragmentActivity,
    private val settingsDialog: ShortSettingsDialog,
    private val activeControlView: () -> ShortVideoControlView?,
    private val closeApp: () -> Unit
) {

    private var countDownTimer: CountDownTimer? = null
    private var remainingMillis: Long = 0L
    private var selectedMinutes: Int = 0

    /** 显示定时关闭弹窗，并根据用户选择启动或取消倒计时。 */
    fun showDialog() {
        val timerDialog = TimerCloseDialog.newInstance(selectedMinutes)
        timerDialog.onTimerSelected { minutes ->
            if (minutes > 0) {
                selectedMinutes = minutes
                start(minutes)
            } else {
                selectedMinutes = 0
                cancel()
            }
        }
        if (!timerDialog.isAdded) {
            timerDialog.show(activity.supportFragmentManager, timerDialog.tag)
        }
    }

    /** Activity 回到前台后按剩余时间恢复倒计时显示。 */
    fun onResume() {
        if (remainingMillis > 0) {
            val remainingMinutes = (remainingMillis / 60_000L).toInt().coerceAtLeast(1)
            start(remainingMinutes)
        }
    }

    /** Activity 进入后台时暂停 CountDownTimer，保留剩余时间供恢复。 */
    fun onPause() {
        countDownTimer?.cancel()
    }

    /** 页面销毁时彻底取消倒计时并清空 UI 状态。 */
    fun release() {
        cancel()
    }

    /** 启动新的分钟级倒计时。 */
    private fun start(minutes: Int) {
        cancel()
        remainingMillis = minutes * 60_000L
        countDownTimer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                updateDisplay(millisUntilFinished)
            }

            override fun onFinish() {
                remainingMillis = 0
                selectedMinutes = 0
                updateDisplay(0)
                closeApp()
            }
        }.start()
        updateDisplay(remainingMillis)
    }

    /** 取消倒计时并同步控制条/设置弹窗显示为关闭状态。 */
    private fun cancel() {
        countDownTimer?.cancel()
        countDownTimer = null
        remainingMillis = 0
        selectedMinutes = 0
        updateDisplay(0)
    }

    /** 把同一份剩余时间同步到当前播放页控制条和设置弹窗。 */
    private fun updateDisplay(millisUntilFinished: Long) {
        activeControlView()?.updateTimerCloseDisplay(millisUntilFinished)
        val statusText = if (millisUntilFinished > 0) {
            val min = millisUntilFinished / 60_000
            val sec = (millisUntilFinished / 1000) % 60
            String.format("%d:%02d", min, sec)
        } else {
            activity.getString(R.string.timer_off)
        }
        settingsDialog.updateTimerCloseStatus(statusText)
    }
}
