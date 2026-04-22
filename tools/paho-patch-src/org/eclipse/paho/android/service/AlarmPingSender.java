package org.eclipse.paho.android.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttPingSender;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;

class AlarmPingSender implements MqttPingSender {
    private static final String TAG = "AlarmPingSender";

    private ClientComms comms;
    private final MqttService service;
    private BroadcastReceiver alarmReceiver;
    private PendingIntent pendingIntent;
    private volatile boolean hasStarted;

    AlarmPingSender(MqttService service) {
        if (service == null) {
            throw new IllegalArgumentException("Neither service nor client can be null.");
        }
        this.service = service;
    }

    @Override
    public void init(ClientComms comms) {
        this.comms = comms;
        this.alarmReceiver = new AlarmReceiver();
    }

    @Override
    public void start() {
        String action = "MqttService.pingSender." + getClientId();
        Log.d(TAG, "Register alarmreceiver to MqttService" + action);
        service.registerReceiver(alarmReceiver, new IntentFilter(action));

        Intent intent = new Intent(action);
        pendingIntent = PendingIntent.getBroadcast(service, 0, intent, pendingIntentFlags());
        schedule(comms.getKeepAlive());
        hasStarted = true;
    }

    @Override
    public void stop() {
        Log.d(TAG, "Unregister alarmreceiver to MqttService" + getClientId());
        if (!hasStarted) {
            return;
        }
        if (pendingIntent != null) {
            AlarmManager alarmManager = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
            }
        }
        hasStarted = false;
        try {
            service.unregisterReceiver(alarmReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void schedule(long delayInMilliseconds) {
        long nextAlarmInMilliseconds = System.currentTimeMillis() + delayInMilliseconds;
        Log.d(TAG, "Schedule next alarm at" + nextAlarmInMilliseconds);
        AlarmManager alarmManager = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null || pendingIntent == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.d(TAG, "Alarm schedule using inexact alarm because exact alarm permission is unavailable, next:" + delayInMilliseconds);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarmInMilliseconds, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d(TAG, "Alarm scheule using setExactAndAllowWhileIdle, next:" + delayInMilliseconds);
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarmInMilliseconds, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Log.d(TAG, "Alarm scheule using setExact, delay:" + delayInMilliseconds);
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextAlarmInMilliseconds, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextAlarmInMilliseconds, pendingIntent);
        }
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private String getClientId() {
        IMqttAsyncClient client = comms.getClient();
        return client == null ? "unknown" : client.getClientId();
    }

    private final class AlarmReceiver extends BroadcastReceiver {
        private PowerManager.WakeLock wakelock;
        private final String wakeLockTag = "MqttService.client." + getClientId();

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Sending Ping at:" + System.currentTimeMillis());
            PowerManager powerManager = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag);
                wakelock.acquire();
            }

            IMqttToken token = comms.checkForActivity(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    releaseWakeLock();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    releaseWakeLock();
                }
            });

            if (token == null) {
                releaseWakeLock();
            }
        }

        private void releaseWakeLock() {
            if (wakelock != null && wakelock.isHeld()) {
                wakelock.release();
            }
        }
    }
}
