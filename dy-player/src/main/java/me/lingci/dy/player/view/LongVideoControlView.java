package me.lingci.dy.player.view;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.lingci.dy.player.databinding.LayoutLongVideoControlViewBinding;
import me.lingci.lib.base.util.SpBase;
import me.lingci.lib.player.danmaku.PlayerInitializer;
import me.lingci.lib.player.listener.OnLongVideoListener;
import me.lingci.lib.player.listener.OnPlayNextListener;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.L;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 长视频控制视图
 */
public class LongVideoControlView extends FrameLayout implements IControlComponent {

    private final LayoutLongVideoControlViewBinding mBinding;
    private final SpBase mSpBase = new SpBase(getContext());

    private ControlWrapper mControlWrapper;

    private boolean mIsDragging = false;

    private OnLongVideoListener mOnLongVideoListener;
    private OnPlayNextListener mOnPlayNextListener;

    private boolean defaultFull = true;

    private final BatteryReceiver mBatteryReceiver;
    // 是否注册BatteryReceiver
    private boolean mIsRegister;

    public LongVideoControlView(@NonNull Context context) {
        super(context);
    }

    public LongVideoControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LongVideoControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mBinding = LayoutLongVideoControlViewBinding.inflate(LayoutInflater.from(getContext()), this, true);
        setVisibility(GONE);
        mBinding.ivBack.setOnClickListener(v -> {
            PlayerUtils.scanForActivity(getContext()).finish();
        });
        mBinding.playBtn.setOnClickListener(v -> {
            mControlWrapper.togglePlay();
        });
        mBinding.ivRotate.setOnClickListener(v -> {
            mControlWrapper.toggleFullScreen(PlayerUtils.scanForActivity(getContext()));
        });
        mBinding.playSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                String progressText = PlayerUtils.stringForTime((int) newPosition) + " / " + PlayerUtils.stringForTime((int) duration);
                mBinding.tvProgress.setText(progressText);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long duration = mControlWrapper.getDuration();
                long newPosition = (long) ((double)
                        duration * mBinding.playSeekbar.getProgress() / mBinding.playSeekbar.getMax());
                mControlWrapper.seekTo(newPosition);
                mIsDragging = false;
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });
        mBinding.cbDmShow.setSelected(mSpBase.getShowDm());
        mBinding.cbDmShow.setOnClickListener(v -> {
            boolean selected = !mBinding.cbDmShow.isSelected();
            mSpBase.setShowDm(selected);
            mBinding.cbDmShow.setSelected(selected);
            mBinding.ivDmConf.setVisibility(selected ? VISIBLE : GONE);
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onDmShow(selected);
            }
        });
        mBinding.ivDmConf.setVisibility(mSpBase.getShowDm() ? VISIBLE : GONE);
        mBinding.ivDmConf.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onConfDm();
            }
        });
        mBinding.ivVideoConf.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onConfVideo();
            }
        });
        mBinding.ivScreenshot.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onScreenshot();
            }
        });
        mBinding.ivMediaInfo.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onShowMediaInfo();
            }
        });
        mBinding.tvSelectDm.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onSelectDm();
            }
        });
        mBinding.tvSelectEp.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onShowEpisodeSelect();
            }
        });
        mBinding.tvDmTrack.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onShowDmTrack();
            }
        });
        mBinding.ivDmList.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onShowDmList();
            }
        });
        mBinding.ivSubtitleAudio.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onShowTrackPanel();
            }
        });
        mBinding.tvPlaySpeed.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onSpeedChange();
            }
        });
        // 手动进入画中画按钮
        mBinding.ivPip.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onEnterPiP();
            }
        });
        // 系统不支持 PiP 时隐藏按钮
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            if (!pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                mBinding.ivPip.setVisibility(GONE);
            }
        } else {
            mBinding.ivPip.setVisibility(GONE);
        }
        mBinding.ivPlayPrevious.setOnClickListener(v -> {
            if (mOnPlayNextListener != null) {
                mOnPlayNextListener.onPreviousPlay();
            }
        });
        mBinding.ivPlayNext.setOnClickListener(v -> {
            if (mOnPlayNextListener != null) {
                mOnPlayNextListener.onNextPlay();
            }
        });
        mBinding.ivPlaySync.setOnClickListener(v -> {
            if (mOnLongVideoListener != null) {
                mOnLongVideoListener.onTimeSync();
                showTips("同步弹幕时间...");
            }
        });
        boolean showSmooth = mSpBase.getShowSmooth();
        mBinding.ivSmoothCurve.setSelected(showSmooth);
        mBinding.smoothCurve.setVisibility(showSmooth ? VISIBLE : GONE);
        mBinding.ivSmoothCurve.setOnClickListener(v -> {
            boolean isSelect = !v.isSelected();
            mBinding.ivSmoothCurve.setSelected(isSelect);
            mBinding.smoothCurve.setVisibility(isSelect ? VISIBLE : GONE);
            mSpBase.setShowSmooth(isSelect);
        });
        mBatteryReceiver = new BatteryReceiver(mBinding.tvBattery);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mIsRegister) {
            getContext().unregisterReceiver(mBatteryReceiver);
            mIsRegister = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mIsRegister) {
            getContext().registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            mIsRegister = true;
        }
    }

    public void setTitle(String name) {
        mBinding.tvTitle.setText(name);
    }

    public void useTrack() {
        mBinding.ivSubtitleAudio.setVisibility(VISIBLE);
    }

    public void unTrack() {
        mBinding.ivSubtitleAudio.setVisibility(GONE);
    }

    public void updateCurveData(float[] data) {
        mBinding.smoothCurve.setDataPoints(data);
    }

    private void goBack() {
        if (defaultFull || !mControlWrapper.isFullScreen()) {
            PlayerUtils.scanForActivity(getContext()).finish();
        } else {
            mControlWrapper.toggleFullScreen(PlayerUtils.scanForActivity(getContext()));
        }
    }

    public void setFull(boolean full) {
        this.defaultFull = full;
        if (full) {
            PlayerUtils.scanForActivity(getContext()).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            PlayerUtils.scanForActivity(getContext()).setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    public void showTips(String tips) {
        mBinding.tvTips.setText(tips);
        mBinding.tvTips.setVisibility(VISIBLE);
        mBinding.tvTips.postDelayed(() -> {
            mBinding.tvTips.clearComposingText();
            mBinding.tvTips.setVisibility(GONE);
        }, 2000);
    }

    public void showSubTips(String tips) {
        mBinding.tvSubTips.setText(tips);
        mBinding.tvSubTips.setVisibility(VISIBLE);
        mBinding.tvSubTips.postDelayed(() -> {
            mBinding.tvSubTips.clearComposingText();
            mBinding.tvSubTips.setVisibility(GONE);
        }, 1500);
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (isVisible) {
            mBinding.topContainer.setVisibility(VISIBLE);
            mBinding.bottomContainer.setVisibility(VISIBLE);
            mBinding.centerContainer.setVisibility(VISIBLE);
            mBinding.endContainer.setVisibility(VISIBLE);
            mBinding.tvTime.setText(PlayerUtils.getCurrentSystemTime());
        } else {
            mBinding.topContainer.setVisibility(GONE);
            mBinding.bottomContainer.setVisibility(GONE);
            mBinding.centerContainer.setVisibility(GONE);
            mBinding.endContainer.setVisibility(GONE);
        }
        if (anim != null) {
            mBinding.topContainer.startAnimation(anim);
            mBinding.bottomContainer.startAnimation(anim);
            mBinding.centerContainer.startAnimation(anim);
            mBinding.endContainer.startAnimation(anim);
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        switch (playState) {
            case VideoView.STATE_IDLE:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                setVisibility(GONE);
                mBinding.playSeekbar.setProgress(0);
                mBinding.playSeekbar.setSecondaryProgress(0);
                break;
            case VideoView.STATE_PLAYING:
                L.e("STATE_PLAYING " + hashCode());
                setVisibility(VISIBLE);
                onVisibilityChanged(false, null);
                //mBinding.playBtn.setVisibility(GONE);
                mBinding.playBtn.setSelected(true);
                mControlWrapper.startProgress();
                //mControlWrapper.startFadeOut();
                break;
            case VideoView.STATE_PAUSED:
                L.e("STATE_PAUSED " + hashCode());
                mBinding.playBtn.setSelected(false);
                //mBinding.playBtn.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PREPARED:
                L.e("STATE_PREPARED " + hashCode());
                break;
            case VideoView.STATE_ERROR:
                L.e("STATE_ERROR " + hashCode());
                Toast.makeText(getContext(), me.lingci.lib.player.ui.R.string.dkplayer_error_message, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        mBinding.tvTime.setText(PlayerUtils.getCurrentSystemTime());
        switch (playerState) {
            case VideoView.PLAYER_FULL_SCREEN:
                mBinding.cbDmShow.setVisibility(VISIBLE);
                mBinding.tvSelectDm.setVisibility(VISIBLE);
                mBinding.ivPlaySync.setVisibility(VISIBLE);
                mBinding.ivSmoothCurve.setVisibility(VISIBLE);
                break;
            case VideoView.PLAYER_NORMAL:
            default:
                mBinding.cbDmShow.setVisibility(GONE);
                mBinding.tvSelectDm.setVisibility(GONE);
                mBinding.ivPlaySync.setVisibility(GONE);
                mBinding.ivSmoothCurve.setVisibility(GONE);
                break;
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mOnLongVideoListener != null) {
            mOnLongVideoListener.onVideoProgress(duration, position);
        }
        if (mIsDragging) {
            return;
        }
        if (getVisibility() == GONE) {
            return;
        }
        if (duration > 0) {
            mBinding.playSeekbar.setEnabled(true);
            int pos = (int) (((double) position / duration) * mBinding.playSeekbar.getMax());
            mBinding.playSeekbar.setProgress(pos, true);
            String progressText = PlayerUtils.stringForTime(position) + " / " + PlayerUtils.stringForTime(duration);
            mBinding.tvProgress.setText(progressText);
            mBinding.smoothCurve.changeProgress(position / PlayerInitializer.Danmu.INSTANCE.getCurveTime());
        } else {
            mBinding.playSeekbar.setEnabled(false);
        }
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        onVisibilityChanged(!isLocked, null);
    }

    public void setOnLongVideoListener(OnLongVideoListener onLongVideoListener) {
        this.mOnLongVideoListener = onLongVideoListener;
    }

    public void setOnPlayNextListener(OnPlayNextListener onPlayNextListener) {
        this.mOnPlayNextListener = onPlayNextListener;
    }

    private static class BatteryReceiver extends BroadcastReceiver {
        private final TextView pow;

        public BatteryReceiver(TextView pow) {
            this.pow = pow;
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            int current = extras.getInt("level");// 获得当前电量
            int total = extras.getInt("scale");// 获得总电量
            int percent = current * 100 / total;
            pow.setText(percent + "%");
        }
    }

}
