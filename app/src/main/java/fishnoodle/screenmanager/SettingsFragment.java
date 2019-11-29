package fishnoodle.screenmanager;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

public class SettingsFragment extends PreferenceFragment
{
    @Override
    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );

        final PreferenceManager pm = getPreferenceManager();

        pm.setSharedPreferencesName( VersionDefinition.SHARED_PREFS_NAME );
        pm.setSharedPreferencesMode( VersionDefinition.SHARED_PREFS_MODE );
        addPreferencesFromResource( R.xml.settings );

        if ( VersionDefinition.DEBUG_SERVICE_STATE )
        {
            final Preference p = new Preference( getActivity() );
            p.setKey( "pref_request_display_state" );
            p.setTitle( "Display Current State" );

            p.setOnPreferenceClickListener( new Preference.OnPreferenceClickListener()
                {
                    @Override
                    public boolean onPreferenceClick( Preference preference )
                    {
                        final Intent serviceIntent = new Intent( getActivity(), ScreenManagerService.class );
                        serviceIntent.setAction( ScreenManagerService.ACTION_REQUEST_DISPLAY_STATE );

                        getActivity().startService( serviceIntent );

                        return false;
                    }
                } );

            getPreferenceScreen().addPreference( p );
        }
    }
}
