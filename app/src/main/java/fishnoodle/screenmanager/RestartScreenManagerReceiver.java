package fishnoodle.screenmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RestartScreenManagerReceiver extends BroadcastReceiver
{
	@Override
	public void onReceive( Context context, Intent intent )
	{
		SysLog.writeD( "RestartScreenManagerReceiver attempting to restart service" );

		ScreenManagerService.startServiceIfNotRunning( context );
	}
}
