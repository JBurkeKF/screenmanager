package fishnoodle.screenmanager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.v4.app.JobIntentService;

public class ScreenManagerBootService extends JobIntentService
{
    private static final int BOOT_SERVICE_JOB_ID = 101;

    public static void enqueueWork( Context context, Intent work )
    {
        //SysLog.writeD( "Enqueue ScreenManager job service" );
        enqueueWork( context, ScreenManagerBootService.class, BOOT_SERVICE_JOB_ID, work );
    }

    @Override
    protected void onHandleWork( Intent intent )
    {
        final SharedPreferences prefs = getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        final String prefEnabledKey = getString( R.string.pref_enabled );

        if ( !prefs.getBoolean( prefEnabledKey, getResources().getBoolean( R.bool.pref_enabled_default ) ) )
        {
            SysLog.writeD("Skip launching ScreenManager job service because it's disabled" );

            return;
        }

        //SysLog.writeD("Run ScreenManager job service" );
        final Intent serviceIntent = new Intent( this, ScreenManagerService.class );

        if ( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O )
        {
            startForegroundService( serviceIntent );
        }
        else
        {
            startService( serviceIntent );
        }
    }
}