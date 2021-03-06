package fishnoodle.screenmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;

public class SettingsActivity extends Activity implements DialogInterface.OnDismissListener
{
    public final static String ACTION_DISPLAY_STATE = "fishnoodle.screenmanager.ACTION_DISPLAY_STATE";
    public final static String INTENT_EXTRA_STATE = "screen_manager_state";

    private final static String SAVE_STATE_DIALOG_OPEN = "save_state_dialog_open";

    private final DisplayStateReceiver displayStateReceiver = new DisplayStateReceiver();

    private Dialog stateDialog = null;

    private Intent startServiceIntent = null;

    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );

        if ( savedInstanceState == null )
        {
            getFragmentManager().beginTransaction().replace( android.R.id.content, new SettingsFragment() ).commit();
        }
        else
        {
            final boolean stateDialogOpen = savedInstanceState.getBoolean( SAVE_STATE_DIALOG_OPEN, false );

            if ( stateDialogOpen )
            {
                final Intent serviceIntent = new Intent( this, ScreenManagerService.class );
                serviceIntent.setAction( ScreenManagerService.ACTION_REQUEST_DISPLAY_STATE );

                startService( serviceIntent );
            }
        }

        final IntentFilter filter = new IntentFilter( ACTION_DISPLAY_STATE );

        registerReceiver( displayStateReceiver, filter );

        startServiceIntent = ScreenManagerService.startServiceIfNotRunning( this );
    }

    @Override
    protected void onDestroy()
    {
        unregisterReceiver( displayStateReceiver );

        if ( stateDialog != null )
        {
            stateDialog.dismiss();
            stateDialog = null;
        }

        if ( startServiceIntent != null )
        {
            stopService( startServiceIntent );
        }

        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState( Bundle outState )
    {
        super.onSaveInstanceState( outState );

        outState.putBoolean( SAVE_STATE_DIALOG_OPEN, stateDialog != null );
    }

    @Override
    public void onDismiss( DialogInterface dialogInterface )
    {
        if ( dialogInterface == stateDialog )
        {
            stateDialog = null;
        }
    }

    private class DisplayStateReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive( Context context, Intent intent )
        {
            if ( intent != null && TextUtils.equals( intent.getAction(), ACTION_DISPLAY_STATE ) )
            {
                if ( stateDialog != null )
                {
                    stateDialog.dismiss();
                }

                String state = intent.getStringExtra( INTENT_EXTRA_STATE );

                if ( state == null )
                {
                    state = "";
                }

                stateDialog = new AlertDialog.Builder( SettingsActivity.this )
                    .setTitle( "Service State" )
                    .setMessage( state )
                    .setPositiveButton( android.R.string.ok, null )
                    .create();

                stateDialog.setOnDismissListener( SettingsActivity.this );

                stateDialog.show();
            }
        }
    }
}
