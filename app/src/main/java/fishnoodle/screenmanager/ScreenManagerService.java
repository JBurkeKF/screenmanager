package fishnoodle.screenmanager;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

public class ScreenManagerService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener
{
    public final static String ACTION_REQUEST_DISPLAY_STATE = "fishoodle.screenmanager.ACTION_REQUEST_DISPLAY_STATE";

    private final static String WAKE_LOCK_TAG = "KF Screen Manager: Wake Lock";

    private final static int NOTIFICATION_SERVICE_ID = 120;

    public final static String ACTION_UPDATE = "fishnoodle.screenmanager.ACTION_UPDATE";

    private final static int LIGHT_SENSOR_INTENT_REQUEST_CODE = 401;
    private final static int LIGHT_SENSOR_CHECK_TIME_MS = 5000;

    private final static String TIME_DAY = "day";
    private final static String TIME_NIGHT = "night";
    private final static String TIME_ALL = "all";

    private Handler handler;

    private boolean enabled = false;
    private boolean enabledOnBattery = false;
    private String timeOfDay = TIME_DAY;
    private float lightThresholdMin = 4.0f;
    private float lightThresholdMax = 7.0f;
    private float lightTestIntervalS = 1800.0f;

    private boolean hasWakeLock = false;
    private PowerManager.WakeLock currentWakeLock = null;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();
    private boolean batteryReceiverRegistered = false;
    private boolean batteryTestRunning = false;

    private final ScreenStateReceiver screenStateReceiver = new ScreenStateReceiver();
    private boolean screenStateReceiverRegistered = false;

    private boolean alarmsScheduled = false;
    private PendingIntent lightSensorAlarm = null;

    private SensorManager sensorManager;
    private boolean lightSensorEnabled = false;
    private boolean lightTestEnabled = false;
    private long lastTestCheckTimeMS = 0;
    private long lastTestCheckStartTimeMS = 0;
    private ArrayList<LightSensorData> lightSensorData = new ArrayList<LightSensorData>();
    private long lastLightSensorCheckMS = 0;
    private float currentLightSensor = 0.0f;
    private boolean isDay = false;
    private boolean isDayInitialized = false;

    private boolean showLockScreenNotification = false;
    private boolean setLockScreenNotification = false;

    @Override
    public void onCreate()
    {
        super.onCreate();

        handler = new Handler( getMainLooper() );

        sensorManager = (SensorManager) getSystemService( Context.SENSOR_SERVICE );

        final SharedPreferences prefs = getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.registerOnSharedPreferenceChangeListener( this );

        onSharedPreferenceChanged( prefs, null );

        startServiceInForeground();
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        if ( intent != null )
        {
            if ( TextUtils.equals( intent.getAction(), ACTION_UPDATE ) )
            {
                setLightSensorEnabled( true );

                SysLog.writeD( "Update wake lock from random update" );

                updateScreenWakeLockState();
            }
            else if ( TextUtils.equals( intent.getAction(), ACTION_REQUEST_DISPLAY_STATE ) )
            {
                sendCurrentState();
            }
        }

        super.onStartCommand( intent, flags, startId );

        return START_STICKY;
    }

    @Override
    public IBinder onBind( Intent intent )
    {
        return null;
    }

    @Override
    public void onDestroy()
    {
        SysLog.writeD( "ScreenManagerService is being destroyed" );

        final SharedPreferences prefs = getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.unregisterOnSharedPreferenceChangeListener( this );

        enabled = false;

        rescheduleAlarms( true );

        super.onDestroy();

        updateBatteryReceiverState( true );
        updateScreenStateReceiverState( true );
        setLightSensorEnabled( false );

        releaseCurrentWakeLock();

        SysLog.writeD( "ScreenManagerService sending restart broadcast" );

        final Intent restartServiceIntent = new Intent( this, RestartScreenManagerReceiver.class );

        sendBroadcast( restartServiceIntent );
    }

    public void onSharedPreferenceChanged( final SharedPreferences prefs, final String key )
    {
        final boolean updateAll = TextUtils.isEmpty( key );
        final String prefEnabledKey = getString( R.string.pref_enabled );
        final String prefEnabledOnBatteryKey = getString( R.string.pref_enabled_on_battery );
        final String prefTimeOfDayKey = getString( R.string.pref_timeofday );
        final String prefLightThresholdKey = getString( R.string.pref_light_threshold );
        final String prefLightIntervalKey = getString( R.string.pref_light_interval );
        final String prefLockScreenNotificationKey = getString( R.string.pref_lock_screen_notification );

        final float oldLightThresholdMin = lightThresholdMin;
        final float oldLightThresholdMax = lightThresholdMax;
        final float oldLightTestIntervalS = lightTestIntervalS;

        boolean needsRestart = false;

        if ( updateAll || TextUtils.equals( key, prefEnabledKey ) )
        {
            enabled = prefs.getBoolean( prefEnabledKey, getResources().getBoolean( R.bool.pref_enabled_default ) );
        }

        if ( updateAll || TextUtils.equals( key, prefEnabledOnBatteryKey ) )
        {
            enabledOnBattery = prefs.getBoolean( prefEnabledOnBatteryKey, getResources().getBoolean(  R.bool.pref_enabled_on_battery_default ) );
        }

        if ( updateAll || TextUtils.equals( key, prefTimeOfDayKey ) )
        {
            timeOfDay = prefs.getString( prefTimeOfDayKey, getString( R.string.pref_timeofday_default ) );
        }

        if ( updateAll || TextUtils.equals( key, prefLightThresholdKey ) )
        {
            final float[] range = PreferenceSlider.getRangeFromString( prefs.getString( prefLightThresholdKey, getString( R.string.pref_light_threshold_default ) ) );

            if ( range[0] > range[1] )
            {
                lightThresholdMin = range[1];
                lightThresholdMax = range[0];
            }
            else
            {
                lightThresholdMin = range[0];
                lightThresholdMax = range[1];
            }
        }

        if ( updateAll || TextUtils.equals( key, prefLightIntervalKey ) )
        {
            try
            {
                lightTestIntervalS = Float.parseFloat( prefs.getString( prefLightIntervalKey, getString( R.string.pref_light_interval_default ) ) );
            }
            catch ( Exception e )
            {
                lightTestIntervalS = 1800.0f;
            }
        }

        if (updateAll || TextUtils.equals( key, prefLockScreenNotificationKey ) )
        {
            final boolean newLockScreenNotfication = prefs.getBoolean( prefLockScreenNotificationKey, getResources().getBoolean( R.bool.pref_lock_screen_notification_default ) );

            if ( newLockScreenNotfication != showLockScreenNotification )
            {
                if ( setLockScreenNotification )
                {
                    needsRestart = true;
                }

                showLockScreenNotification = newLockScreenNotfication;
            }
        }

        SysLog.writeD( "Update wake lock from shared prefs" );

        if ( needsRestart )
        {
            stopForeground( true );
            stopSelf();
        }
        else
        {
            handler.post( new Runnable()
            {
                public void run()
                {
                    if ( oldLightThresholdMin != lightThresholdMin ||
                            oldLightThresholdMax != lightThresholdMax ||
                            oldLightTestIntervalS != lightTestIntervalS )
                    {
                        updateLightTest();
                        setLightSensorEnabled( true );
                        rescheduleAlarms( false );
                    }

                    updateBatteryReceiverState( false );
                    updateScreenStateReceiverState( false );
                    updateScreenWakeLockState();
                }
            } );
        }
    }

    @Override
    public void onSensorChanged( final SensorEvent event )
    {
        final float newValue = event.values[0];

        synchronized ( this )
        {
            if ( lightSensorEnabled )
            {
                final LightSensorData data = new LightSensorData();
                data.value = newValue;
                data.timeMS = SystemClock.elapsedRealtime() - lastTestCheckTimeMS;
                lastTestCheckTimeMS = SystemClock.elapsedRealtime();

                lightSensorData.add( data );
            }
        }
    }

    @Override
    public void onAccuracyChanged( final Sensor sensor, final int accuracy )
    {
    }

    public static Intent startServiceIfNotRunning( final Context context )
    {
        final String packageName = context.getPackageName();
        boolean isServiceRunning = false;

        final ActivityManager am = (ActivityManager) context.getSystemService( Context.ACTIVITY_SERVICE );
        final List<ActivityManager.RunningServiceInfo> rsiList = am.getRunningServices( 100 );

        for ( final ActivityManager.RunningServiceInfo rsi : rsiList )
        {
            if ( TextUtils.equals( packageName, rsi.process ) )
            {
                isServiceRunning = true;
            }
        }

        final Intent serviceIntent = new Intent( context, ScreenManagerService.class );

        if ( !isServiceRunning )
        {
            context.startService( serviceIntent );
        }

        return serviceIntent;
    }

    @SuppressWarnings( "NewApi" )
    private void startServiceInForeground()
    {
        if ( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O )
        {
            final NotificationManager notificationManager = (NotificationManager) getSystemService( Context.NOTIFICATION_SERVICE );
            final String channelID = "ScreenManagerChannel";
            final NotificationChannel channel = new NotificationChannel( channelID, "Screen Manager Service", NotificationManager.IMPORTANCE_NONE );
            channel.setLockscreenVisibility( Notification.VISIBILITY_SECRET );

            notificationManager.createNotificationChannel( channel );

            final Intent settingsActivityIntent = new Intent( this, SettingsActivity.class );
            final PendingIntent settingsActivityPendingIntent = PendingIntent.getActivity( this, 120, settingsActivityIntent, 0 );

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder( this, channelID );
            notificationBuilder.setOngoing( true );
            notificationBuilder.setSmallIcon( R.drawable.ic_launcher );
            notificationBuilder.setCategory( Notification.CATEGORY_SERVICE );
            notificationBuilder.setVisibility( showLockScreenNotification ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_SECRET );
            notificationBuilder.setPriority( NotificationCompat.PRIORITY_LOW );
            notificationBuilder.setContentIntent( settingsActivityPendingIntent );

            Notification notification = notificationBuilder.build();

            startForeground( NOTIFICATION_SERVICE_ID, notification );

            setLockScreenNotification = true;
        }
    }

    @SuppressWarnings( "deprecation" )
    private synchronized void updateScreenWakeLockState()
    {
        updateLightTest();
        rescheduleAlarms( false );
        updateDay();

        SysLog.writeD( "Current state:\n" + getCurrentState() );

        boolean shouldWakeLock = enabled && isScreenOn();

        //SysLog.writeD( "Wake lock check 1: " + shouldWakeLock );

        if ( shouldWakeLock && !enabledOnBattery )
        {
            shouldWakeLock = !isOnBattery();
        }

        //SysLog.writeD( "Wake lock check 2: " + shouldWakeLock );

        if ( shouldWakeLock && TextUtils.equals( timeOfDay, TIME_DAY ) )
        {
            // Wake lock only during the day
            shouldWakeLock = isDay;

        }
        else if ( shouldWakeLock && TextUtils.equals( timeOfDay, TIME_NIGHT ) )
        {
            // Wake lock only during the night
            shouldWakeLock = !isDay;
        }

        //SysLog.writeD( "Wake lock check 3: " + shouldWakeLock );

        if ( shouldWakeLock != hasWakeLock )
        {
            if ( shouldWakeLock )
            {
                releaseCurrentWakeLock();

                final PowerManager pm = (PowerManager) getSystemService( Context.POWER_SERVICE );
                currentWakeLock = pm.newWakeLock( PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, WAKE_LOCK_TAG );
                currentWakeLock.acquire();
                hasWakeLock = true;
            }
            else
            {
                releaseCurrentWakeLock();

                hasWakeLock = false;
            }
        }
    }

    private synchronized void updateBatteryReceiverState( final boolean shutdown )
    {
        final boolean shouldEnableBatteryReceiver = !enabledOnBattery && enabled;

        if ( shouldEnableBatteryReceiver != batteryReceiverRegistered || shutdown )
        {
            if ( shouldEnableBatteryReceiver && !shutdown )
            {
                final IntentFilter filter = new IntentFilter( Intent.ACTION_POWER_CONNECTED );
                filter.addAction( Intent.ACTION_POWER_DISCONNECTED );

                registerReceiver( batteryReceiver, filter );
                batteryReceiverRegistered = true;
            }
            else
            {
                if ( batteryReceiverRegistered )
                {
                    unregisterReceiver( batteryReceiver );
                    batteryReceiverRegistered = false;
                }
            }
        }
    }

    private synchronized void updateScreenStateReceiverState( final boolean shutdown )
    {
        final boolean shouldEnableScreenStateReceiver = enabled;

        if ( shouldEnableScreenStateReceiver != screenStateReceiverRegistered || shutdown )
        {
            if ( shouldEnableScreenStateReceiver && !shutdown )
            {
                final IntentFilter filter = new IntentFilter();
                filter.addAction( Intent.ACTION_SCREEN_ON );
                filter.addAction( Intent.ACTION_SCREEN_OFF );

                registerReceiver( screenStateReceiver, filter );
                screenStateReceiverRegistered = true;
            }
            else
            {
                if ( screenStateReceiverRegistered )
                {
                    unregisterReceiver( screenStateReceiver );
                    screenStateReceiverRegistered = false;
                }
            }
        }
    }

    private synchronized void setLightSensorEnabled( final boolean enabled )
    {
        final boolean shouldEnable = enabled && lightTestEnabled;

        if ( shouldEnable != lightSensorEnabled )
        {
            if ( shouldEnable )
            {
                if ( sensorManager != null )
                {
                    final Sensor lightSensor = sensorManager.getDefaultSensor( Sensor.TYPE_LIGHT );

                    if ( lightSensor != null )
                    {
                        sensorManager.registerListener( this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL );

                        lightSensorEnabled = true;
                        lastTestCheckTimeMS = SystemClock.elapsedRealtime();
                        lastTestCheckStartTimeMS = lastTestCheckTimeMS;
                        lightSensorData.clear();

                        // Cancel existing alarms because we're running a check now
                        rescheduleAlarms( true );

                        handler.postDelayed( finalizeLightSensorValue, LIGHT_SENSOR_CHECK_TIME_MS );
                    }
                }
            }
            else
            {
                handler.removeCallbacks( finalizeLightSensorValue );

                if ( sensorManager != null )
                {
                    sensorManager.unregisterListener( this );
                }

                lightSensorEnabled = false;
            }
        }
    }

    private synchronized void releaseCurrentWakeLock()
    {
        if ( currentWakeLock != null )
        {
            if ( currentWakeLock.isHeld() )
            {
                currentWakeLock.release();
            }

            currentWakeLock = null;
            hasWakeLock = false;
        }
    }

    private synchronized boolean isOnBattery()
    {
        final IntentFilter filter = new IntentFilter( Intent.ACTION_BATTERY_CHANGED );
        final Intent batteryIntent = registerReceiver( null, filter );

        final int status = batteryIntent.getIntExtra( BatteryManager.EXTRA_STATUS, -1 );
        final int pluggedStatus = batteryIntent.getIntExtra( BatteryManager.EXTRA_PLUGGED, -1 );

        if ( batteryTestRunning && pluggedStatus >= BatteryManager.BATTERY_PLUGGED_AC )
        {
            batteryTestRunning = false;
        }

        SysLog.writeD( "Check battery status [" + status + "], plugged status [" + pluggedStatus + "]" );
        SysLog.writeD( "Battery test running [" + batteryTestRunning + "]" );

        //return status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL;
        return pluggedStatus < BatteryManager.BATTERY_PLUGGED_AC;
    }

    @SuppressWarnings( "deprecation" )
    private synchronized boolean isScreenOn()
    {
        final PowerManager pm = (PowerManager) getSystemService( Context.POWER_SERVICE );

        if ( pm != null )
        {
            return pm.isScreenOn();
        }

        return false;
    }

    private synchronized void updateLightTest()
    {
        final boolean oldLightTestEnabled = lightTestEnabled;

        lightTestEnabled = enabled &&
            !TextUtils.equals( timeOfDay, TIME_ALL ) &&
            ( enabledOnBattery || !isOnBattery() ) &&
            isScreenOn();

        if ( !oldLightTestEnabled && lightTestEnabled )
        {
            setLightSensorEnabled( true );
        }

        if ( !lightTestEnabled )
        {
            setLightSensorEnabled( false );
            rescheduleAlarms( true );
        }
    }

    private synchronized void updateDay()
    {
        if ( !isDayInitialized )
        {
            isDayInitialized = true;

            isDay = currentLightSensor >= lightThresholdMin;
        }
        else
        {
            if ( isDay )
            {
                isDay = currentLightSensor >= lightThresholdMin;
            }
            else
            {
                isDay = currentLightSensor >= lightThresholdMax;
            }
        }
    }

    private synchronized void rescheduleAlarms( final boolean forceCancel )
    {
        final boolean shouldScheduleAlarms = lightTestEnabled && lightTestIntervalS > 0.0f && !lightSensorEnabled;

        if ( shouldScheduleAlarms && !forceCancel )
        {
            final AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );

            if ( alarmsScheduled )
            {
                if ( lightSensorAlarm != null )
                {
                    am.cancel( lightSensorAlarm );
                    lightSensorAlarm = null;
                }
            }

            final Intent lightSensorIntent = new Intent( this, ScreenManagerService.class );
            lightSensorIntent.setAction( ACTION_UPDATE );

            lightSensorAlarm = PendingIntent.getService( this, LIGHT_SENSOR_INTENT_REQUEST_CODE, lightSensorIntent, PendingIntent.FLAG_CANCEL_CURRENT );

            boolean scheduledAlarm = false;
            final long currentTimeMS = SystemClock.elapsedRealtime();
            final long targetTimeMS = lastLightSensorCheckMS + (long) ( lightTestIntervalS * 1000.0f );

            if ( targetTimeMS > currentTimeMS )
            {
                am.set( AlarmManager.ELAPSED_REALTIME, targetTimeMS, lightSensorAlarm );
                scheduledAlarm = true;
            }
            else
            {
                lightSensorAlarm = null;

                handler.post( new Runnable()
                    {
                        public void run()
                        {
                            setLightSensorEnabled( true );
                        }
                    } );
            }

            alarmsScheduled = scheduledAlarm;
        }
        else
        {
            if ( alarmsScheduled )
            {
                final AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );

                if ( lightSensorAlarm != null )
                {
                    am.cancel( lightSensorAlarm );
                    lightSensorAlarm = null;
                }

                alarmsScheduled = false;
            }
        }
    }

    private void sendCurrentState()
    {
        if ( !VersionDefinition.DEBUG_SERVICE_STATE )
        {
            return;
        }

        final String currentState = getCurrentState();

        final Intent displayStateIntent = new Intent( SettingsActivity.ACTION_DISPLAY_STATE );
        displayStateIntent.putExtra( SettingsActivity.INTENT_EXTRA_STATE, currentState );

        sendBroadcast( displayStateIntent );
    }

    private synchronized String getCurrentState()
    {
        String currentState = "";

        currentState += "Enabled: " + enabled + "\n";
        currentState += "Enabled on battery: " + enabledOnBattery + "\n";
        currentState += "Time of day: " + timeOfDay + "\n";
        currentState += "Light threshold: " + lightThresholdMin + " (lx) - " + lightThresholdMax + " (lx)\n";
        currentState += "Light test interval: " + lightTestIntervalS + "s\n\n";

        currentState += "Currently has wake lock: " + hasWakeLock + "\n\n";

        currentState += "Battery receiver registered: " + batteryReceiverRegistered + "\n";
        currentState += "Is on battery: " + isOnBattery() + "\n\n";

        currentState += "Screen state receiver registered: " + screenStateReceiverRegistered + "\n";
        currentState += "Is screen on: " + isScreenOn() + "\n\n";

        currentState += "Light sensor active: " + lightSensorEnabled + "\n";
        currentState += "Light testing enabled: " + lightTestEnabled + "\n";
        currentState += "Light sensor value: " + currentLightSensor + " (lx)\n";
        currentState += "Light sensor check time: " + ( Math.max( 0.0f, Math.min( LIGHT_SENSOR_CHECK_TIME_MS, SystemClock.elapsedRealtime() - lastTestCheckStartTimeMS ) ) / 1000.0f ) + "s/" + ( LIGHT_SENSOR_CHECK_TIME_MS / 1000.0f ) + "s\n";
        currentState += "Last raw light value: " + ( lightSensorData.size() > 0 ? lightSensorData.get( lightSensorData.size() - 1 ).value : 0.0f ) + "\n";
        currentState += "Light sensor detects day: " + isDay + "\n";
        currentState += "Next light sensor check: " + ( lightTestIntervalS > 0.0f ? ( Math.max( 0.0f, ( lastLightSensorCheckMS + lightTestIntervalS * 1000.0f - SystemClock.elapsedRealtime() ) / 1000.0f ) + "s" ) : "continuous" ) + "\n";
        currentState += "Alarms schedule: " + alarmsScheduled;

        return currentState;
    }

    private Runnable finalizeLightSensorValue = new Runnable()
    {
        public void run()
        {
            synchronized ( ScreenManagerService.this )
            {
                long totalTimeMS = 0;
                final long finalTimeMS = SystemClock.elapsedRealtime() - lastTestCheckTimeMS;

                for ( int i = 0; i < lightSensorData.size(); i++ )
                {
                    totalTimeMS += lightSensorData.get( i ).timeMS;
                }

                if ( lightSensorData.size() > 0 )
                {
                    totalTimeMS += finalTimeMS;
                }

                if ( totalTimeMS > 0 )
                {
                    for ( int i = 0; i < lightSensorData.size(); i++ )
                    {
                        final LightSensorData data = lightSensorData.get( i );

                        if ( i < lightSensorData.size() - 1 )
                        {
                            data.weight = (float) lightSensorData.get( i + 1 ).timeMS / (float) totalTimeMS;
                        }
                        else
                        {
                            data.weight = Math.max( 0.0f, Math.min( 1.0f, (float) finalTimeMS / (float) totalTimeMS ) );
                        }
                    }
                }

                float weightedAverage = 0.0f;

                for ( int i = 0; i < lightSensorData.size(); i++ )
                {
                    final LightSensorData data = lightSensorData.get( i );

                    weightedAverage += data.value * data.weight;
                }

                currentLightSensor = weightedAverage;

                lastLightSensorCheckMS = SystemClock.elapsedRealtime();

                SysLog.writeD( "Update wake lock from light" );

                handler.post( new Runnable()
                    {
                        public void run()
                        {
                            updateScreenWakeLockState();
                        }
                    } );

                if ( lightTestIntervalS > 0.0f )
                {
                    setLightSensorEnabled( false );

                    rescheduleAlarms( false );
                }
                else
                {
                    lastTestCheckTimeMS = SystemClock.elapsedRealtime();
                    lastTestCheckStartTimeMS = lastTestCheckTimeMS;

                    if ( lightSensorData.size() > 0 )
                    {
                        final LightSensorData data = new LightSensorData();
                        final LightSensorData oldData = lightSensorData.get( lightSensorData.size() - 1 );

                        data.value = oldData.value;

                        lightSensorData.clear();

                        lightSensorData.add( data );
                    }
                    else
                    {
                        lightSensorData.clear();
                    }

                    handler.postDelayed( finalizeLightSensorValue, LIGHT_SENSOR_CHECK_TIME_MS );
                }
            }
        }
    };

    private Runnable batteryTestRunnable = new Runnable()
    {
        public void run()
        {
            boolean runAgain = false;

            updateScreenWakeLockState();

            synchronized ( ScreenManagerService.this )
            {
                if ( batteryTestRunning )
                {
                    runAgain = true;
                }
            }

            if ( runAgain )
            {
                handler.post( batteryTestRunnable );
            }
        }
    };

    private class BatteryReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive( final Context context, final Intent intent )
        {
            if ( intent != null )
            {
                if ( TextUtils.equals( intent.getAction(), Intent.ACTION_POWER_CONNECTED ) ||
                    TextUtils.equals( intent.getAction(), Intent.ACTION_POWER_DISCONNECTED ) )
                {
                    SysLog.writeD( "Update wake lock from battery action [" + intent.getAction() + "]" );

                    synchronized ( ScreenManagerService.this )
                    {
                        if ( TextUtils.equals( intent.getAction(), Intent.ACTION_POWER_CONNECTED ) )
                        {
                            batteryTestRunning = true;
                        }
                        else
                        {
                            batteryTestRunning = false;
                        }
                    }

                    handler.post( batteryTestRunnable );
                }
            }
        }
    }

    private class ScreenStateReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive( final Context context, final Intent intent )
        {
            if ( intent != null )
            {
                if ( TextUtils.equals( intent.getAction(), Intent.ACTION_SCREEN_ON ) ||
                    TextUtils.equals( intent.getAction(), Intent.ACTION_SCREEN_OFF ) )
                {
                    SysLog.writeD( "Update wake lock from screen action [" + intent.getAction() + "]" );

                    handler.post( new Runnable()
                        {
                            public void run()
                            {
                                updateScreenWakeLockState();
                            }
                        } );
                }
            }
        }
    }

    private class LightSensorData
    {
        public float value;
        public long timeMS;
        public float weight;
    }
}
