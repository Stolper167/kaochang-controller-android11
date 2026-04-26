package cn.niuyannet.kaochang.android.adapter;

import android.content.Context;
import android.util.AttributeSet;

import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer;

public class MyVideoPlayer extends StandardGSYVideoPlayer {

    public MyVideoPlayer(Context context) {
        super(context);
    }

    public MyVideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void hideChrome() {
        setViewShowState(mTopContainer, INVISIBLE);
        setViewShowState(mBottomContainer, INVISIBLE);
        setViewShowState(mStartButton, INVISIBLE);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        setViewShowState(mThumbImageViewLayout, INVISIBLE);
        setViewShowState(mBottomProgressBar, INVISIBLE);
        setViewShowState(mLockScreen, GONE);
    }

    @Override
    public void changeUiToPreparingShow() {
        super.changeUiToPreparingShow();
        hideChrome();
    }

    @Override
    public void changeUiToPauseShow() {
        super.changeUiToPauseShow();
        hideChrome();
    }

    @Override
    public void changeUiToError() {
        super.changeUiToError();
        hideChrome();
    }

    @Override
    public void changeUiToCompleteShow() {
        super.changeUiToCompleteShow();
        hideChrome();
    }

    @Override
    public void changeUiToPlayingBufferingShow() {
        super.changeUiToPlayingBufferingShow();
        hideChrome();
    }

    @Override
    public void changeUiToPrepareingClear() {
        super.changeUiToPrepareingClear();
        hideChrome();
    }

    @Override
    public void changeUiToPlayingClear() {
        super.changeUiToPlayingClear();
        hideChrome();
    }

    @Override
    public void changeUiToPauseClear() {
        super.changeUiToPauseClear();
        hideChrome();
    }

    @Override
    public void changeUiToPlayingBufferingClear() {
        super.changeUiToPlayingBufferingClear();
        hideChrome();
    }

    @Override
    public void changeUiToClear() {
        super.changeUiToClear();
        hideChrome();
    }

    @Override
    public void changeUiToCompleteClear() {
        super.changeUiToCompleteClear();
        hideChrome();
    }

    @Override
    public void changeUiToPlayingShow() {
        super.changeUiToPlayingShow();
        hideChrome();
    }

    @Override
    public void changeUiToNormal() {
        super.changeUiToNormal();
        hideChrome();
    }

    @Override
    public void onVideoPause() {
        super.onVideoPause();
        mPauseBeforePrepared = false;
    }

    @Override
    protected void touchDoubleUp() {
        // Disable double-tap pause to match the legacy player behavior.
    }
}
