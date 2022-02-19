package fishnoodle.screenmanager;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.text.TextUtils;

public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener
{
    @Override
    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );

        final SharedPreferences prefs = getActivity().getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.registerOnSharedPreferenceChangeListener( this );

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
                        final SharedPreferences currentPrefs = getActivity().getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
                        final String prefEnabledKey = getString( R.string.pref_enabled );

                        if ( currentPrefs.getBoolean( prefEnabledKey, getResources().getBoolean( R.bool.pref_enabled_default ) ) )
                        {
                            final Intent serviceIntent = new Intent( getActivity(), ScreenManagerService.class );
                            serviceIntent.setAction( ScreenManagerService.ACTION_REQUEST_DISPLAY_STATE );

                            getActivity().startService( serviceIntent );
                        }
                        else
                        {
                            Activity activity = getActivity();

                            if ( activity instanceof SettingsActivity )
                            {
                                ( (SettingsActivity) activity ).showDisabledStateDialog();
                            }
                        }

                        return false;
                    }
                } );

            getPreferenceScreen().addPreference( p );

            final Preference p2 = new Preference( getActivity() );
            p2.setKey( "pref_request_update" );
            p2.setTitle( "Force Update Current State" );

            p2.setOnPreferenceClickListener( new Preference.OnPreferenceClickListener()
                {
                    @Override
                    public boolean onPreferenceClick( Preference preference )
                    {
                        final SharedPreferences currentPrefs = getActivity().getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
                        final String prefEnabledKey = getString( R.string.pref_enabled );

                        if ( currentPrefs.getBoolean( prefEnabledKey, getResources().getBoolean( R.bool.pref_enabled_default ) ) )
                        {
                            final Intent serviceIntent = new Intent( getActivity(), ScreenManagerService.class );
                            serviceIntent.setAction( ScreenManagerService.ACTION_UPDATE );

                            getActivity().startService( serviceIntent );
                        }

                        return false;
                    }
                } );

            getPreferenceScreen().addPreference( p2 );
        }
    }

    @Override
    public void onDestroy()
    {
        final SharedPreferences prefs = getActivity().getSharedPreferences( VersionDefinition.SHARED_PREFS_NAME, VersionDefinition.SHARED_PREFS_MODE );
        prefs.unregisterOnSharedPreferenceChangeListener( this );

        super.onDestroy();
    }

    public void onSharedPreferenceChanged( final SharedPreferences prefs, final String key )
    {
        final String prefEnabledKey = getString( R.string.pref_enabled );

        if ( TextUtils.equals( key, prefEnabledKey ) )
        {
            Activity activity = getActivity();

            if ( activity instanceof SettingsActivity )
            {
                if ( prefs.getBoolean( prefEnabledKey, getResources().getBoolean( R.bool.pref_enabled_default ) ) )
                {
                    ( (SettingsActivity) activity ).restartService();
                }
                else
                {
                    ( (SettingsActivity) activity ).serviceStopped();
                }
            }
        }
    }
}
