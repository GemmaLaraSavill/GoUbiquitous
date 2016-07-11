/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nanodegree.gemma.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private Bitmap weatherIcon;
    private String mHighTemp;
    private String mLowTemp;
    private int mIconId;
    private GoogleApiClient mGoogleApiClient;



    @Override
    public Engine onCreateEngine() {
        // TODO provide my watch face implementation
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.OnConnectionFailedListener,
            GoogleApiClient.ConnectionCallbacks {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        private Paint mBackgroundPaint;
        private Paint mTimeTextPaint;
        private Paint mDateTextPaint;
        private Paint mHighTempTextPaint;
        private Paint mLowTempTextPaint;
        private Paint mWelcomeTextPaint;

        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        int mTapCount;

        // keylines values for canvas writing
        float mTimeXOffset;
        float mTimeYOffset;
        float mDateXOffset;
        float mDateYOffset;
        float mWeatherXOffset;
        float mWeatherYOffset;
        float mLogoXOffset;
        float mLogoYOffset;
        float mWelcomeXOffset;
        float mWelcomeYOffset;

        // related to date and it's format
        Date today;
        SimpleDateFormat formatter;
        String pattern = "EEE, MMM d yyyy";
        Locale currentLocale;

        // Bitmaps
        private Bitmap logoIcon;

        private String welcomeTxt;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // initialize comms with the app
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            // initialize the watch face
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            // initialize temperature values as null
            mHighTemp = null;
            mLowTemp = null;

            logoIcon = BitmapFactory.decodeResource(resources, R.drawable.ic_logo);
            welcomeTxt = resources.getString(R.string.waiting_message);

            // initial keyline values for formatting, commented out ones are now set in onApplyWindowInsets() so that different values are given for square and round watches
//            mTimeXOffset = resources.getDimension(R.dimen.time_x_offset);
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);
//            mDateXOffset = resources.getDimension(R.dimen.date_x_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
//            mWeatherXOffset = resources.getDimension(R.dimen.weather_x_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.weather_y_offset);
//            mLogoXOffset = resources.getDimension(R.dimen.logo_x_offset);
            mLogoYOffset = resources.getDimension(R.dimen.logo_y_offset);
//            mWelcomeXOffset = resources.getDimension(R.dimen.welcome_x_offset);
            mWelcomeYOffset = resources.getDimension(R.dimen.welcome_y_offset);

            // initializing paint objects
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mHighTempTextPaint = new Paint();
            mHighTempTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mLowTempTextPaint = new Paint();
            mLowTempTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mWelcomeTextPaint = new Paint();
            mWelcomeTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            // text sizes
            float timeTextSize = resources.getDimension(R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            float weatherTextSize = resources.getDimension(R.dimen.weather_text_size);
            float welcomeTextSize = resources.getDimension(R.dimen.welcome_text_size);
            mTimeTextPaint.setTextSize(timeTextSize);
            mDateTextPaint.setTextSize(dateTextSize);
            mHighTempTextPaint.setTextSize(weatherTextSize);
            mLowTempTextPaint.setTextSize(weatherTextSize);
            mWelcomeTextPaint.setTextSize(welcomeTextSize);

            // text weights
            mDateTextPaint.setAlpha(150);
            mLowTempTextPaint.setAlpha(150);
            mWelcomeTextPaint.setAlpha(200);

            // related to time
            mTime = new Time();
            // related to date
            currentLocale = getResources().getConfiguration().locale;
            formatter = new SimpleDateFormat(pattern, currentLocale);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            // the watch face became visible or invisible
            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            // update keylines and text sizes according to watch shape
            mTimeXOffset = resources.getDimension(isRound
                    ? R.dimen.time_x_offset_round : R.dimen.time_x_offset);
            mDateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            mWeatherXOffset = resources.getDimension(isRound
                    ? R.dimen.weather_x_offset_round : R.dimen.weather_x_offset);
            mLogoXOffset= resources.getDimension(isRound
                    ? R.dimen.logo_x_offset_round : R.dimen.logo_x_offset);
            mWelcomeXOffset = resources.getDimension(isRound
                    ? R.dimen.welcome_x_offset_round : R.dimen.welcome_x_offset);
//            float timeTextSize = resources.getDimension(isRound
//                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            // TODO get device features (burn-in, low-bit ambient
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            // the time changed
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            // the wearable switched between modes
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            // TODO see if I want to provide more data on tap, if not disable in on create
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // draw the time, always
            mTime.setToNow();
            String timeText = String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            //Log.d("Watchface", "Time "+timeText);
            canvas.drawText(timeText, mTimeXOffset, mTimeYOffset, mTimeTextPaint);

            // draw the date, only in active mode
            if (!isInAmbientMode()) {
                today = new Date();
                String dateText = formatter.format(today);
                //Log.d("Watchface", "Date "+dateText);
                canvas.drawText(dateText, mDateXOffset, mDateYOffset, mDateTextPaint);
            }

            // draw wheather temperatures, only in active mode
            if (mHighTemp != null && mLowTemp != null) {
                Log.d("Watchface", "newHighTemp "+mHighTemp);
                Log.d("Watchface", "newLowTemp "+mLowTemp);
                canvas.drawText(mHighTemp+" "+mLowTemp, mWeatherXOffset, mWeatherYOffset, mDateTextPaint);
                canvas.drawBitmap(weatherIcon, mLogoXOffset, mLogoYOffset, null);
            } else {
                // No weather info from app yet, so let's display the Sunshine logo and a welcome text
                if(!isInAmbientMode()) {
                    canvas.drawBitmap(logoIcon, mLogoXOffset, mLogoYOffset, null);
                    canvas.drawText(welcomeTxt, mWelcomeXOffset, mWelcomeYOffset, mWelcomeTextPaint);
                }
                //Log.d("Wathface", "Temperature values not received yet");
            }
//            canvas.drawBitmap(weatherIcon, 90, 90, null); // Or use a Paint if you need it
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.i("Watchface", "Called onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i("Watchface", "Called onConnectionSuspended");

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d("WatchFace", "called onDataChanged");

            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals("/weather")) {
                        mHighTemp = dataMap.getString("high");
                        mLowTemp = dataMap.getString("low");
                        mIconId = dataMap.getInt("icon");
                        weatherIcon = BitmapFactory.decodeResource(SunshineWatchFace.this.getResources(), mIconId);

                        Log.d("WatchFace", "Received new weather");
                        Log.d("WatchFace", "new high "+mHighTemp);
                        Log.d("WatchFace", "new low "+mLowTemp);
                        Log.d("WatchFace", "new icon "+mIconId);
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.i("Watchface", "Called onConnectionFailed "+connectionResult);
        }
    }
}
