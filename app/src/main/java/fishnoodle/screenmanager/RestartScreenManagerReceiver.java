package fishnoodle.screenmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class RestartScreenManagerReceiver extends BroadcastReceiver
{
	@Override
	public void onReceive( Context context, Intent intent )
	{
		boolean enabled = true;

		if ( context != null )
		{
			final SharedPreferences prefs = context.getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
			final String prefEnabledKey = context.getString( R.string.pref_enabled );

			enabled = prefs.getBoolean( prefEnabledKey, context.getResources().getBoolean( R.bool.pref_enabled_default ) );
		}

		SysLog.writeD( "RestartScreenManagerReceiver attempting to restart service, enabled [" + enabled + "]" );

		if ( enabled )
		{
			ScreenManagerService.startServiceIfNotRunning( context );
		}
	}
}
