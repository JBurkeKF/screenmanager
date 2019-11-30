package fishnoodle.screenmanager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Locale;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

public class SysLog
{
	public final static int LEVEL_VERBOSE = 0;
	public final static int LEVEL_ALWAYS  = 1;
	public final static int LEVEL_WARNING = 2;
	
	static PrintWriter outFile = null;
	static public boolean writeToSD = false;
	static public boolean storeHistory = false;
	
	private final static int NUM_STORED_LINES = 50;
	static String[] storedLines = new String[NUM_STORED_LINES];
	
	private static void initialize()
	{
		try {
		    File root = Environment.getExternalStorageDirectory();
		    if( root.canWrite() )
		    {
		    	Calendar rightNow = Calendar.getInstance();
				long year = rightNow.get( Calendar.YEAR );
				long month = rightNow.get( Calendar.MONTH ) + 1;
		    	long day = rightNow.get( Calendar.DAY_OF_MONTH );
			    long hour = rightNow.get( Calendar.HOUR_OF_DAY );
			    long minutes = rightNow.get( Calendar.MINUTE );
			    long seconds = rightNow.get( Calendar.SECOND );

				String date = String.format( Locale.ENGLISH, "%04d-%02d-%02d_%02d.%02d.%02d", year, month, day, hour, minutes, seconds );
		    	
		        File gpxfile = new File( root, VersionDefinition.TAG + "_" + date + ".log" );
		        FileWriter gpxwriter = new FileWriter( gpxfile );
		        outFile = new PrintWriter( gpxwriter );
		        outFile.write( "\n\n----- Initiating Log To Storage Session -----\n" );
		        outFile.write( "----- Start Time: " + date + " -----\n\n" );
		    }
		} catch (IOException e) {
		    Log.e( "LogToSD", "Could not write to file: " + e.getMessage());
		}
	}
	
	/***
	 * Shows up as Verbose in the log, but will be added to history regardless of whether storeHistory is enabled.
	 * @param text
	 */
	public static void writeHistory( String text )
	{
		write( LEVEL_VERBOSE, text, true );
	}
	
	public static void writeV( String text )
	{
		write( LEVEL_VERBOSE, text, false );
	}
	
	public final static void writeV( final String format, final Object... formatParams )
	{
		writeV( String.format(Locale.US, format, formatParams) );
	}
	
	public static void writeD( String text )
	{
		write( LEVEL_ALWAYS, text, false );
	}
	
	public final static void writeD( final String format, final Object... formatParams )
	{
		writeD( String.format(Locale.US, format, formatParams) );
	}
	
	public static void writeW( String text )
	{
		write( LEVEL_WARNING, text, false );
	}
	
	public final static void writeW( final String format, final Object... formatParams )
	{
		writeW( String.format(Locale.US, format, formatParams) );
	}
	
	private static void write( int level, final String text, boolean forceStoreHistory )
	{
		storeLine( text, forceStoreHistory );
		
		if( level == LEVEL_VERBOSE )
			Log.v( VersionDefinition.TAG, text);
		else if( level == LEVEL_WARNING )
			Log.w( VersionDefinition.TAG, text );
		else
			Log.d( VersionDefinition.TAG, text );
		
		if( writeToSD )
		{
			if( outFile == null )
				initialize();
		
	    	Calendar rightNow = Calendar.getInstance();
			long year = rightNow.get( Calendar.YEAR );
			long month = rightNow.get( Calendar.MONTH ) + 1;
			long day = rightNow.get( Calendar.DAY_OF_MONTH );
			long hour = rightNow.get( Calendar.HOUR_OF_DAY );
		    long minutes = rightNow.get( Calendar.MINUTE );
		    long seconds = rightNow.get( Calendar.SECOND );
		    long ms = rightNow.get( Calendar.MILLISECOND );

			String time = String.format( Locale.ENGLISH, "%04d-%02d-%02d %02d:%02d:%02d.%03d", year, month, day, hour, minutes, seconds, ms );
	
		    outFile.write( time + "\t" + text + "\n" );
		    outFile.flush();
		}
	}
	
	public static void shutdown() // doesn't really matter unless you're using the SD functionality
	{
		if( writeToSD && outFile != null )
			outFile.close();
	}
	
	
	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	
	public static void clearHistory()
	{
		for( int i=0; i<storedLines.length; i++ )
		{
			storedLines[i] = null;
		}
	}

	private static void storeLine( final String text, boolean forceStoreHistory )
	{
		if( !storeHistory && !forceStoreHistory )
			return;
		
		String prevLine = text;
		for( int i=0; i<storedLines.length; i++ )
		{
			String thisLine = storedLines[i];
			storedLines[i] = prevLine;
			prevLine = thisLine;
		}
		
		// the last one falls off the list, which is what we want
		storedLinesDirty = true;
	}
	
	private static boolean storedLinesDirty = false;
	private static String storedLinesCombined = "";
	private static String getStoredLines()
	{
		if( !storedLinesDirty )
			return storedLinesCombined;
		
		storedLinesCombined = "";
		for( int i=0; i<storedLines.length; i++ )
		{
			String line = storedLines[i];
			if( line != null )
			{
				storedLinesCombined += line + "\n";
			}
		}
		
		storedLinesDirty = false;
		return storedLinesCombined;
	}
}
