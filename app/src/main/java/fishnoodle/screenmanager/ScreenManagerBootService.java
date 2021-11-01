package fishnoodle.screenmanager;

import android.content.Context;
import android.content.Intent;
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