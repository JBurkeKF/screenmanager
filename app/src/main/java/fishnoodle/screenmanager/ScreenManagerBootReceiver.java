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
        //SysLog.writeD("Start ScreenManager from boot complete receiver" );
        if ( context != null && intent != null && TextUtils.equals( intent.getAction(), Intent.ACTION_BOOT_COMPLETED ) )
        {
            ScreenManagerBootService.enqueueWork( context, intent );
        }
    }
}
