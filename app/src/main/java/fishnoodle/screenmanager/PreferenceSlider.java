package fishnoodle.screenmanager;

import java.util.Locale;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.DialogPreference;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;

public class PreferenceSlider extends DialogPreference
{
	private TextView currentValueTextView = null;

	private float minValue;
	private float maxValue;
	private String minLabel;
	private String maxLabel;
	private float interval;

	private float currentValue;
	private float currentValue2;

	public static float[] getRangeFromString( final String range )
	{
		final float[] rangeResult = new float[2];
		rangeResult[0] = 0.0f;
		rangeResult[1] = 0.0f;

		getRangeFromString( range, rangeResult );

		return rangeResult;
	}

	public static boolean getRangeFromString( final String range, final float[] rangeResult )
	{
		if ( rangeResult != null && rangeResult.length > 1 && !TextUtils.isEmpty( range ) )
		{
			final String[] parts = range.split( "-" );

			if ( parts.length > 1 )
			{
				try
				{
					rangeResult[0] = Float.parseFloat( parts[0] );
					rangeResult[1] = Float.parseFloat( parts[1] );

					return true;
				}
				catch ( final Exception e )
				{
				}
			}
		}

		return false;
	}

	public PreferenceSlider( final Context context )
	{
		this( context, null );
	}

	public PreferenceSlider( final Context context, final AttributeSet attribSet )
	{
		super( context, attribSet );

		if ( attribSet != null )
		{
			final TypedArray a = context.obtainStyledAttributes( attribSet, R.styleable.smPreferenceSlider, 0, 0 );

			minValue = a.getFloat( R.styleable.smPreferenceSlider_min, 0.0f );
			maxValue = a.getFloat( R.styleable.smPreferenceSlider_max, 0.0f );

			minLabel = a.getString( R.styleable.smPreferenceSlider_minLabel );
			maxLabel = a.getString( R.styleable.smPreferenceSlider_maxLabel );

			interval = a.getFloat( R.styleable.smPreferenceSlider_interval, 1.0f );

			a.recycle();
		}

		setPositiveButtonText( android.R.string.yes );
		setNegativeButtonText( android.R.string.cancel );

		setDialogLayoutResource( R.layout.preference_slider );
	}

	@Override
	protected Object onGetDefaultValue( final TypedArray ta, final int index )
	{
		String defaultValue = ta.getString( index );

		if ( TextUtils.isEmpty( defaultValue ) )
		{
			defaultValue = "0-0";
		}

		return defaultValue;
	}

	@Override
	protected void onSetInitialValue( final boolean restoreValue, final Object defaultValue )
	{
		String value = getValue();

		if ( restoreValue )
		{
			value = getPersistedString( value );
		}
		else
		{
			value = (String) defaultValue;

			persistString( value );
		}

		setValue( value );
	}

	public void setMinValue( final float minValue )
	{
		this.minValue = minValue;
	}

	public void setMaxValue( final float maxValue )
	{
		this.maxValue = maxValue;
	}

	public void setMinLabel( final String minLabel )
	{
		this.minLabel = minLabel;
	}

	public void setMinLabel( final int minLabelID )
	{
		minLabel = getContext().getString( minLabelID );
	}

	public void setMaxLabel( final String maxLabel )
	{
		this.maxLabel = maxLabel;
	}

	public void setMaxLabel( final int maxLabelID )
	{
		maxLabel = getContext().getString( maxLabelID );
	}

	@Override
	protected void onBindDialogView( final View view )
	{
		super.onBindDialogView( view );

		final SeekBar seekBar = (SeekBar) view.findViewById( R.id.pref_slider_seek_bar );
		final SeekBar seekBar2 = (SeekBar) view.findViewById( R.id.pref_slider_seek_bar_2 );

		final int intervals = (int) Math.ceil( ( maxValue - minValue ) / interval );

		seekBar.setMax( intervals );
		seekBar.setProgress( Math.min( intervals, (int) Math.ceil( ( currentValue - minValue ) / interval ) ) ); // because starting point of the bar is always 0
		seekBar.setOnSeekBarChangeListener( seekBarListener );

		seekBar2.setMax( intervals );
		seekBar2.setProgress( Math.min( intervals, (int) Math.ceil( ( currentValue2 - minValue ) / interval ) ) ); // because starting point of the bar is always 0
		seekBar2.setOnSeekBarChangeListener( seekBarListener2 );

		final TextView min = (TextView) view.findViewById( R.id.pref_slider_min_value );
		final TextView min2 = (TextView) view.findViewById( R.id.pref_slider_min_value_2 );
		final TextView max = (TextView) view.findViewById( R.id.pref_slider_max_value );
		final TextView max2 = (TextView) view.findViewById( R.id.pref_slider_max_value_2 );

		min.setText( minLabel );
		min2.setText( minLabel );
		max.setText( maxLabel );
		max2.setText( maxLabel );

		currentValueTextView = (TextView) view.findViewById( R.id.pref_slider_value );

		setCurrentValueView();
	}

	@Override
	protected Parcelable onSaveInstanceState()
	{
		final Parcelable superState = super.onSaveInstanceState();

		final SavedState savedState = new SavedState( superState );

		savedState.value = currentValue;
		savedState.value2 = currentValue2;

		return savedState;
	}

	@Override
	protected void onRestoreInstanceState( final Parcelable state )
	{
		if ( state == null || !state.getClass().equals( SavedState.class ) )
		{
			// Didn't save state for us in onSaveInstanceState
			super.onRestoreInstanceState( state );

			return;
		}

		final SavedState savedState = (SavedState) state;

		super.onRestoreInstanceState( savedState.getSuperState() );

		currentValue = savedState.value;
		currentValue2 = savedState.value2;
	}

	@Override
	public void onDialogClosed( final boolean positiveResult )
	{
		final String value = getValue();

		currentValueTextView = null;

		if ( positiveResult )
		{
			if ( callChangeListener( value ) )
			{
				if ( shouldPersist() )
				{
					persistString( value );
				}
			}
		}
		else
		{
			setValue( getPersistedString( value ) );
		}
	}

	private void setValue( final String value )
	{
		final float[] range = getRangeFromString( value );

		if ( range[0] > range[1] )
		{
			currentValue = range[1];
			currentValue2 = range[0];
		}
		else
		{
			currentValue = range[0];
			currentValue2 = range[1];
		}
	}

	private String getValue()
	{
		if ( currentValue > currentValue2 )
		{
			return getRangeFormat( currentValue2, currentValue );
		}

		return getRangeFormat( currentValue, currentValue2 );
	}

	private void setCurrentValueView()
	{
		if ( currentValueTextView != null )
		{
			if ( currentValue > currentValue2 )
			{
				currentValueTextView.setText( getRangeFormatDisplay( currentValue2, currentValue ) );
			}
			else
			{
				currentValueTextView.setText( getRangeFormatDisplay( currentValue, currentValue2 ) );
			}
		}
	}

	private String getRangeFormat( final float min, final float max )
	{
		return String.format( Locale.US, "%s-%s", String.valueOf( min ), String.valueOf( max ) );
	}

	private String getRangeFormatDisplay( final float min, final float max )
	{
		return String.format( Locale.US, "%s - %s", String.valueOf( min ), String.valueOf( max ) );
	}

	private SeekBar.OnSeekBarChangeListener seekBarListener = new SeekBar.OnSeekBarChangeListener()
	{
		@Override
		public void onProgressChanged( final SeekBar seekBar, final int progress, final boolean fromUser )
		{
			currentValue = Math.min( maxValue, minValue + progress * interval );

			setCurrentValueView();
		}

		@Override
		public void onStartTrackingTouch( final SeekBar seekBar )
		{
		}

		@Override
		public void onStopTrackingTouch( final SeekBar seekBar )
		{
		}
	};

	private SeekBar.OnSeekBarChangeListener seekBarListener2 = new SeekBar.OnSeekBarChangeListener()
	{
		@Override
		public void onProgressChanged( final SeekBar seekBar, final int progress, final boolean fromUser )
		{
			currentValue2 = Math.min( maxValue, minValue + progress * interval );

			setCurrentValueView();
		}

		@Override
		public void onStartTrackingTouch( final SeekBar seekBar )
		{
		}

		@Override
		public void onStopTrackingTouch( final SeekBar seekBar )
		{
		}
	};

	// Based on code from Android android.preference.ListPreference
	private static class SavedState extends BaseSavedState
	{
		public float value;
		public float value2;

		public SavedState( final Parcel source )
		{
			super( source );
			value = source.readFloat();
			value2 = source.readFloat();
		}

		public SavedState( final Parcelable superState )
		{
			super( superState );
		}

		@Override
		public void writeToParcel( final Parcel dest, final int flags )
		{
			super.writeToParcel( dest, flags );
			dest.writeFloat( value );
			dest.writeFloat( value2 );
		}

		@SuppressWarnings( "unused" )
		public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>()
			{
				public SavedState createFromParcel( final Parcel in )
				{
					return new SavedState( in );
				}

				public SavedState[] newArray( final int size )
				{
					return new SavedState[size];
				}
			};
	}
}
