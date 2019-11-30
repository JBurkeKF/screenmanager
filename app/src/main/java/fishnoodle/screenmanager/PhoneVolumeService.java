package fishnoodle.screenmanager;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

public class PhoneVolumeService extends Service
{
	private Handler handler;

	private boolean monitoringPhoneVolume = false;

	@Override
	public void onCreate()
	{
		super.onCreate();

		handler = new Handler( getMainLooper() );

		final IntentFilter phoneCallFilter = new IntentFilter( TelephonyManager.ACTION_PHONE_STATE_CHANGED );

		//registerReceiver( phoneCallReceiver, phoneCallFilter );
	}

	@Override
	public IBinder onBind( Intent intent )
	{
		return null;
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();

		//unregisterReceiver( phoneCallReceiver );

		handler.removeCallbacks( phoneVolumeMonitor );
	}

	private BroadcastReceiver phoneCallReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive( final Context context, final Intent intent )
			{
				if ( intent != null )
				{
					if ( TextUtils.equals( intent.getAction(), TelephonyManager.ACTION_PHONE_STATE_CHANGED ) )
					{
						final String state = intent.getStringExtra( TelephonyManager.EXTRA_STATE );

						SysLog.writeD( "Phone state changed to [" + state + "]" );

						boolean runPhoneVolumeMonitor = false;

						synchronized ( PhoneVolumeService.this )
						{
							if ( TextUtils.equals( state, TelephonyManager.EXTRA_STATE_RINGING ) && !monitoringPhoneVolume )
							{
								monitoringPhoneVolume = true;
								runPhoneVolumeMonitor = true;
							}
							else
							{
								monitoringPhoneVolume = false;
							}
						}

						if ( runPhoneVolumeMonitor )
						{
							phoneVolumeMonitor.run();
						}
					}
				}
			}
		};

	private Runnable phoneVolumeMonitor = new Runnable()
		{
			@Override
			public void run()
			{
				synchronized ( PhoneVolumeService.this )
				{
					if ( !monitoringPhoneVolume )
					{
						return;
					}
				}

				TelephonyManager telephonyManager = (TelephonyManager) getSystemService( Context.TELEPHONY_SERVICE );

				if ( telephonyManager != null )
				{
					if ( telephonyManager.getCallState() == TelephonyManager.CALL_STATE_RINGING )
					{
						AudioManager audioManager = (AudioManager) getSystemService( Context.AUDIO_SERVICE );

						if ( audioManager != null )
						{
							if ( audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL && audioManager.getMode() == AudioManager.MODE_RINGTONE )
							{
								SysLog.writeD( "Current ringer volume [" + audioManager.getStreamVolume( AudioManager.STREAM_RING ) + "] (max [" + audioManager.getStreamMaxVolume( AudioManager.STREAM_RING ) + "])" );
							}
						}
						else
						{
							SysLog.writeD( "Could not get AudioManager while phone ringing" );
						}

						handler.post( phoneVolumeMonitor );
					}
					else
					{
						synchronized ( PhoneVolumeService.this )
						{
							monitoringPhoneVolume = false;
						}
					}
				}
				else
				{
					synchronized ( PhoneVolumeService.this )
					{
						monitoringPhoneVolume = false;
					}
				}
			}
		};
}
