package fishnoodle.screenmanager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;

import fishnoodle.skymanager.SkyManager;

public class ScreenManagerService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener, LocationListener
{
    public final static String ACTION_REQUEST_DISPLAY_STATE = "fishoodle.screenmanager.ACTION_REQUEST_DISPLAY_STATE";

    private final static String WAKE_LOCK_TAG = "KF Screen Manager";

    private final static String ACTION_UPDATE = "fishnoodle.screenmanager.ACTION_UPDATE";

    private final static int SUNRISE_INTENT_REQUEST_CODE = 401;
    private final static int SUNSET_INTENT_REQUEST_CODE = 402;

    private final static String TIME_DAY = "day";
    private final static String TIME_NIGHT = "night";
    private final static String TIME_ALL = "all";

    private final static int SUNRISE_24_HOUR_DEFAULT = 8;
    private final static int SUNSET_24_HOUR_DEFAULT = 19;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat( "yyyy-MM-dd h:mm:ss a z", Locale.US );

    private Handler handler;

    private boolean enabled = false;
    private boolean enabledOnBattery = false;
    private String timeOfDay = TIME_DAY;

    private boolean hasWakeLock = false;
    private PowerManager.WakeLock currentWakeLock = null;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();
    private boolean batteryReceiverRegistered = false;

    private final ScreenStateReceiver screenStateReceiver = new ScreenStateReceiver();
    private boolean screenStateReceiverRegistered = false;

    private final double[] latitudeLongitude = new double[2];
    private boolean hasValidLatitudeLongitude = false;

    private final Calendar sunriseCalendar = Calendar.getInstance();
    private final Calendar sunsetCalendar = Calendar.getInstance();

    private boolean alarmsScheduled = false;
    private PendingIntent sunriseAlarmIntent = null;
    private PendingIntent sunsetAlarmIntent = null;

    @Override
    public void onCreate()
    {
        super.onCreate();

        handler = new Handler( getMainLooper() );

        final SharedPreferences prefs = getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.registerOnSharedPreferenceChangeListener( this );

        refreshLocation();

        onSharedPreferenceChanged( prefs, null );
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        if ( intent != null )
        {
            if ( TextUtils.equals( intent.getAction(), ACTION_UPDATE ) )
            {
                refreshLocation();

                updateScreenWakeLockState();
            }
            else if ( TextUtils.equals( intent.getAction(), ACTION_REQUEST_DISPLAY_STATE ) )
            {
                sendCurrentState();
            }
        }

        return super.onStartCommand( intent, flags, startId );
    }

    @Override
    public IBinder onBind( Intent intent )
    {
        return null;
    }

    @Override
    public void onDestroy()
    {
        final SharedPreferences prefs = getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.unregisterOnSharedPreferenceChangeListener( this );

        enabled = false;

        rescheduleAlarms( true );

        super.onDestroy();

        updateBatteryReceiverState( true );
        updateScreenStateReceiverState( true );

        releaseCurrentWakeLock();
    }

    public void onSharedPreferenceChanged( final SharedPreferences prefs, final String key )
    {
        final boolean updateAll = TextUtils.isEmpty( key );
        final String prefEnabledKey = getString( R.string.pref_enabled );
        final String prefEnabledOnBatteryKey = getString( R.string.pref_enabled_on_battery );
        final String prefTimeOfDayKey = getString( R.string.pref_timeofday );

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

        handler.post( new Runnable()
            {
                public void run()
                {
                    updateBatteryReceiverState( false );
                    updateScreenStateReceiverState( false );
                    updateScreenWakeLockState();
                }
            } );
    }

    @Override
    public void onLocationChanged( final Location location )
    {
        handler.post( new Runnable()
            {
                public void run()
                {
                    boolean updated = false;

                    synchronized ( ScreenManagerService.this )
                    {
                        final LocationManager lm = (LocationManager) getSystemService( Context.LOCATION_SERVICE );

                        if ( lm != null )
                        {
                            lm.removeUpdates( ScreenManagerService.this );
                        }

                        if ( location != null )
                        {
                            latitudeLongitude[0] = location.getLatitude();
                            latitudeLongitude[1] = location.getLongitude();

                            hasValidLatitudeLongitude = true;

                            updated = true;
                        }
                    }

                    if ( updated )
                    {
                        updateScreenWakeLockState();
                    }
                }
            } );
    }

    @Override
    public void onStatusChanged( final String provider, final int status, final Bundle extras )
    {
    }

    @Override
    public void onProviderEnabled( final String provider )
    {
    }

    @Override
    public void onProviderDisabled( final String provider )
    {
    }

    public static void startServiceIfNotRunning( final Context context )
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

        if ( !isServiceRunning )
        {
            final Intent serviceIntent = new Intent( context, ScreenManagerService.class );
            context.startService( serviceIntent );
        }
    }

    @SuppressWarnings( "deprecation" )
    private synchronized void updateScreenWakeLockState()
    {
        updateLocation();
        updateSunriseSunset();
        rescheduleAlarms( false );

        boolean shouldWakeLock = enabled && isScreenOn();

        if ( shouldWakeLock && !enabledOnBattery )
        {
            shouldWakeLock = !isOnBattery();
        }

        if ( shouldWakeLock && TextUtils.equals( timeOfDay, TIME_DAY ) )
        {
            // Wake lock only during the day
            final Calendar calendar = Calendar.getInstance();

            shouldWakeLock = calendar.after( sunriseCalendar ) && calendar.before( sunsetCalendar );

        }
        else if ( shouldWakeLock && TextUtils.equals( timeOfDay, TIME_NIGHT ) )
        {
            // Wake lock only during the night
            final Calendar calendar = Calendar.getInstance();

            shouldWakeLock = calendar.before( sunriseCalendar ) && calendar.after( sunsetCalendar );
        }

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

    private void refreshLocation()
    {
        if ( TextUtils.equals( timeOfDay, TIME_ALL ) )
        {
            return;
        }

        handler.post( new Runnable()
            {
                public void run()
                {
                    final LocationManager lm = (LocationManager) getSystemService( Context.LOCATION_SERVICE );

                    if ( lm != null )
                    {
                        final Criteria criteria = new Criteria();
                        criteria.setAccuracy( Criteria.ACCURACY_COARSE );

                        final String provider = lm.getBestProvider( criteria, true );

                        if ( !TextUtils.isEmpty( provider ) )
                        {
                            try
                            {
                                lm.requestLocationUpdates( provider, 0, 0, ScreenManagerService.this );
                            }
                            catch ( Exception e )
                            {
                            }
                        }
                    }
                }
            } );
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

        return status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL;
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

    private synchronized void updateLocation()
    {
        if ( TextUtils.equals( timeOfDay, TIME_ALL ) )
        {
            return;
        }

        final LocationManager lm = (LocationManager) getSystemService( Context.LOCATION_SERVICE );

        if ( lm != null )
        {
            final List<String> providers = lm.getProviders( true );
            Location l = null;

            for ( int i = providers.size() - 1; i >= 0; i-- )
            {
                l = lm.getLastKnownLocation( providers.get( i ) );

                if ( l != null )
                {
                    break;
                }
            }

            if ( l != null )
            {
                latitudeLongitude[0] = l.getLatitude();
                latitudeLongitude[1] = l.getLongitude();

                hasValidLatitudeLongitude = true;
            }
        }
    }

    private synchronized void updateSunriseSunset()
    {
        if ( TextUtils.equals( timeOfDay, TIME_ALL ) )
        {
            return;
        }

        if ( hasValidLatitudeLongitude )
        {
            final long currentTimeMS = System.currentTimeMillis();

            sunsetCalendar.setTimeZone( TimeZone.getDefault() );
            sunsetCalendar.setTimeInMillis( SkyManager.GetSunset( latitudeLongitude[0], latitudeLongitude[1] ).getTimeInMillis() );

            // Mark sunset as half an hour afterwards
            sunsetCalendar.setTimeInMillis( sunsetCalendar.getTimeInMillis() + ( 30 * 60 * 1000 ) );

            sunriseCalendar.setTimeZone( TimeZone.getDefault() );

            // After sunset, so calculate tomorrow morning's sunrise instead
            if ( sunsetCalendar.getTimeInMillis() <= currentTimeMS )
            {
                final Calendar tomorrow = Calendar.getInstance();
                tomorrow.setTimeInMillis( tomorrow.getTimeInMillis() + ( 24 * 60 * 60 * 1000 ) );

                sunriseCalendar.setTimeInMillis( SkyManager.GetSunrise( latitudeLongitude[0], latitudeLongitude[1], tomorrow ).getTimeInMillis() );

                // Mark sunrise as half an hour before
                sunriseCalendar.setTimeInMillis( sunriseCalendar.getTimeInMillis() - ( 30 * 60 * 1000 ) );
            }
            else
            {
                sunriseCalendar.setTimeInMillis( SkyManager.GetSunrise( latitudeLongitude[0], latitudeLongitude[1] ).getTimeInMillis() );

                // Mark sunrise as half an hour before
                sunriseCalendar.setTimeInMillis( sunriseCalendar.getTimeInMillis() - ( 30 * 60 * 1000 ) );

                // It's before sunrise, so we want yesterday's sunset
                if ( sunriseCalendar.getTimeInMillis() >= currentTimeMS )
                {
                    final Calendar yesterday = Calendar.getInstance();
                    yesterday.setTimeInMillis( yesterday.getTimeInMillis() - ( 24 * 60 * 60 * 1000 ) );

                    sunsetCalendar.setTimeInMillis( SkyManager.GetSunset( latitudeLongitude[0], latitudeLongitude[1], yesterday ).getTimeInMillis() );

                    // Mark sunset as half an hour afterwards
                    sunsetCalendar.setTimeInMillis( sunsetCalendar.getTimeInMillis() + ( 30 * 60 * 1000 ) );
                }
            }
        }
        else
        {
            final long currentTimeMS = System.currentTimeMillis();

            sunsetCalendar.setTimeZone( TimeZone.getDefault() );
            sunsetCalendar.setTimeInMillis( currentTimeMS );
            sunsetCalendar.set( Calendar.HOUR_OF_DAY, SUNSET_24_HOUR_DEFAULT );
            sunsetCalendar.set( Calendar.MINUTE, 0 );
            sunsetCalendar.set( Calendar.SECOND, 0 );
            sunsetCalendar.set( Calendar.MILLISECOND, 0 );

            sunriseCalendar.setTimeZone( TimeZone.getDefault() );

            // After sunset, so calculate tomorrow morning's sunrise instead
            if ( sunsetCalendar.getTimeInMillis() <= currentTimeMS )
            {
                sunriseCalendar.setTimeInMillis( sunsetCalendar.getTimeInMillis() + ( 24 * 60 * 60 * 1000 ) );
            }
            else
            {
                sunriseCalendar.setTimeInMillis( currentTimeMS );

                // It's before sunrise, so we want yesterday's sunset
                if ( sunriseCalendar.getTimeInMillis() >= currentTimeMS )
                {
                    sunsetCalendar.setTimeInMillis( sunsetCalendar.getTimeInMillis() - ( 24 * 60 * 60 * 1000 ) );
                    sunsetCalendar.set( Calendar.HOUR_OF_DAY, SUNSET_24_HOUR_DEFAULT );
                    sunsetCalendar.set( Calendar.MINUTE, 0 );
                    sunsetCalendar.set( Calendar.SECOND, 0 );
                    sunsetCalendar.set( Calendar.MILLISECOND, 0 );
                }
            }

            sunriseCalendar.set( Calendar.HOUR_OF_DAY, SUNRISE_24_HOUR_DEFAULT );
            sunriseCalendar.set( Calendar.MINUTE, 0 );
            sunriseCalendar.set( Calendar.SECOND, 0 );
            sunriseCalendar.set( Calendar.MILLISECOND, 0 );
        }
    }

    private synchronized void rescheduleAlarms( final boolean shutdown )
    {
        final boolean shouldScheduleAlarms = !TextUtils.equals( timeOfDay, TIME_ALL ) && enabled;

        if ( shouldScheduleAlarms && !shutdown )
        {
            final AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );

            if ( alarmsScheduled )
            {
                if ( sunriseAlarmIntent != null )
                {
                    am.cancel( sunriseAlarmIntent );
                    sunriseAlarmIntent = null;
                }

                if ( sunsetAlarmIntent != null )
                {
                    am.cancel( sunsetAlarmIntent );
                    sunsetAlarmIntent = null;
                }
            }

            final Intent sunriseIntent = new Intent( this, ScreenManagerService.class );
            sunriseIntent.setAction( ACTION_UPDATE );

            sunriseAlarmIntent = PendingIntent.getService( this, SUNRISE_INTENT_REQUEST_CODE, sunriseIntent, PendingIntent.FLAG_CANCEL_CURRENT );

            final Intent sunsetIntent = new Intent( this, ScreenManagerService.class );
            sunsetIntent.setAction( ACTION_UPDATE );

            sunsetAlarmIntent = PendingIntent.getService( this, SUNSET_INTENT_REQUEST_CODE, sunsetIntent, PendingIntent.FLAG_CANCEL_CURRENT );

            boolean scheduledAlarm = false;
            final long currentTimeMS = System.currentTimeMillis();

            if ( sunriseCalendar.getTimeInMillis() > currentTimeMS )
            {
                am.set( AlarmManager.RTC, sunriseCalendar.getTimeInMillis(), sunriseAlarmIntent );
                scheduledAlarm = true;
            }
            else
            {
                sunriseAlarmIntent = null;
            }

            if ( sunsetCalendar.getTimeInMillis() > currentTimeMS )
            {
                am.set( AlarmManager.RTC, sunsetCalendar.getTimeInMillis(), sunsetAlarmIntent );
                scheduledAlarm = true;
            }
            else
            {
                sunsetAlarmIntent = null;
            }

            alarmsScheduled = scheduledAlarm;
        }
        else
        {
            if ( alarmsScheduled )
            {
                final AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );

                if ( sunriseAlarmIntent != null )
                {
                    am.cancel( sunriseAlarmIntent );
                    sunriseAlarmIntent = null;
                }

                if ( sunsetAlarmIntent != null )
                {
                    am.cancel( sunsetAlarmIntent );
                    sunsetAlarmIntent = null;
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

        String currentState = "";

        currentState += "Enabled: " + enabled + "\n";
        currentState += "Enabled on battery: " + enabledOnBattery + "\n";
        currentState += "Time of day: " + timeOfDay + "\n\n";

        currentState += "Currently has wake lock: " + hasWakeLock + "\n\n";

        currentState += "Battery receiver registered: " + batteryReceiverRegistered + "\n";
        currentState += "Is on battery: " + isOnBattery() + "\n\n";

        currentState += "Screen state receiver registered: " + screenStateReceiverRegistered + "\n";
        currentState += "Is screen on: " + isScreenOn() + "\n\n";

        currentState += "Has valid location: " + hasValidLatitudeLongitude + "\n";
        currentState += "Latitude: " + latitudeLongitude[0] + "\n";
        currentState += "Longitude: " + latitudeLongitude[1] + "\n\n";

        currentState += "Current time: " + getCalendarString( Calendar.getInstance() ) + "\n";
        currentState += "Sunrise time -30 min: " + getCalendarString( sunriseCalendar ) + "\n";
        currentState += "Sunset time +30 min: " + getCalendarString( sunsetCalendar ) + "\n";
        currentState += "Alarms schedule: " + alarmsScheduled;

        final Intent displayStateIntent = new Intent( SettingsActivity.ACTION_DISPLAY_STATE );
        displayStateIntent.putExtra( SettingsActivity.INTENT_EXTRA_STATE, currentState );

        sendBroadcast( displayStateIntent );
    }

    private String getCalendarString( final Calendar calendar )
    {
        return dateFormat.format( new Date( calendar.getTimeInMillis() ) );
    }

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
}
