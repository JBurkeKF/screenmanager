package fishnoodle.screenmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

public class ScreenManagerBootReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive( final Context context, final Intent intent )
    {
        if ( context != null && intent != null && TextUtils.equals( intent.getAction(), Intent.ACTION_BOOT_COMPLETED ) )
        {
            ScreenManagerService.startServiceIfNotRunning( context );
        }
    }
}
