/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2016
 */

package com.choosemuse.example.libmuse;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import java.util.Timer;
import java.util.concurrent.atomic.AtomicReference;

import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Looper;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.bluetooth.BluetoothAdapter;


import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;


public class SecondScreen extends Activity implements OnClickListener {


    private final String TAG = "Muse Average";


    private MuseManagerAndroid manager; //Manager for methods of connection, etc.


    private Muse muse; //Headset object you are connecting to

    private ConnectionListener connectionListener; //Listens for connection to (Muse muse)


    private DataListener dataListener; //Listens for data (from EEG sensor, and returns values as a double)


    private final double[] eegBuffer = new double[6];   //Array of eeg values
    private boolean eegStale;
    //private final double[] alphaBuffer = new double[6];
    //private boolean alphaStale;
    //private final double[] accelBuffer = new double[3];
    //private boolean accelStale;


    boolean listenForPeaks = false; //Listen for peaks of the EEG values (peak in graph)
    double lowValues[] = {3000,3000,3000,3000}; //Starting them at EEG values they couldn't possibly reach
    double highValues[] = {0,0,0,0};


    private final Handler handler = new Handler(); //Handles the tick of the program (in 60hz)


    private ArrayAdapter<String> spinnerAdapter; //Handles the spinner item which holds the muse to connect to

    Double[] averageEegs = new Double[4]; //Array of the eeg values

    int eegCountSize = 0; // Divide each value in the array by this on getAverage button press
    boolean paused = false; // Refreshing the eeg buffers
    boolean pressed = false; // Refreshing averages buffe

    private boolean dataTransmission = true; //If data is flowing from muse --> android device

    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>(); //Used for file writing

    private final AtomicReference<Handler> fileHandler = new AtomicReference<>(); // Used for file writing


    //Main code
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // We need to set the context on MuseManagerAndroid before we can do anything.
        // This must come before other LibMuse API calls as it also loads the library.
        manager = MuseManagerAndroid.getInstance();
        manager.setContext(this);

        Log.i(TAG, "LibMuse version=" + LibmuseVersion.instance().getString());

        WeakReference<SecondScreen> weakActivity =
                new WeakReference<SecondScreen>(this);
        // Register a listener to receive connection state changes.
        connectionListener = new ConnectionListener(weakActivity);
        // Register a listener to receive data from a Muse.
        dataListener = new DataListener(weakActivity);
        // Register a listener to receive notifications of what Muse headbands
        // we can connect to.
        manager.setMuseListener(new MuseL(weakActivity));

        // Muse 2016 (MU-02) headbands use Bluetooth Low Energy technology to
        // simplify the connection process.  This requires access to the COARSE_LOCATION
        // or FINE_LOCATION permissions.  Make sure we have these permissions before
        // proceeding.
        ensurePermissions();

        // Load and initialize our UI.
        createUI();

        // Start up a thread for asynchronous file operations.
        // This is only needed if you want to do File I/O.
        fileThread.start();

        // Start our asynchronous updates of the UI.
        handler.post(tickUi);handler.post(tickUi);
    }

    protected void onPause() {
        super.onPause();
        // It is important to call stopListening when the Activity is paused
        // to avoid a resource leak from the LibMuse library.
        manager.stopListening();
    }

    public boolean isBluetoothEnabled() {
        return BluetoothAdapter.getDefaultAdapter().isEnabled();
    }

    public void resetAverageEegs(){
        for (int i = 0; i < averageEegs.length; i++){
            averageEegs[i] = 0.0;
        }
    }

    @Override
    public void onClick(View v) {

        if (v.getId() == R.id.buttonRefresh) { //Check if the button is called "refresh" (XML id)
            manager.stopListening();
            manager.startListening(); //refresh the listener

        }

        else if(v.getId() == R.id.button_low_high) {
            Log.v(TAG,"PUSHED BOI");
            AlertDialog.Builder builder = new AlertDialog.Builder(SecondScreen.this);
            builder.setTitle("Lowest/Highest Average: ");
            builder.setMessage("This averages are: " );
            final double lowAverage = (lowValues[0] + lowValues[1] + lowValues[2] + lowValues[3]) / 4;
            final double highAverage = (highValues[0] + highValues[1] + highValues[2] + highValues[3]) / 4;
            builder.setPositiveButton("Highest Value", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Toast.makeText(SecondScreen.this, Double.toString(highAverage), Toast.LENGTH_LONG).show();
                }
            });
            builder.setNegativeButton("Lowest value", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Toast.makeText(SecondScreen.this, Double.toString(lowAverage), Toast.LENGTH_LONG).show();
                }
            });
            AlertDialog alert = builder.create();
            alert.show();
        }

        else if(v.getId() == R.id.getAverage){
            if (!pressed) {

                pressed = true;
                for (int i = 0; i < averageEegs.length; i++) {
                    averageEegs[i] /= eegCountSize;
                }
                TextView average1 = (TextView) findViewById(R.id.average1);
                TextView average2 = (TextView) findViewById(R.id.average2);
                TextView average3 = (TextView) findViewById(R.id.average3);
                TextView average4 = (TextView) findViewById(R.id.average4);


                average1.setText("Average EEG 1:          " + Double.toString(averageEegs[0]));
                average2.setText("Average EEG 2:          " + Double.toString(averageEegs[1]));
                average3.setText("Average EEG 3:          " + Double.toString(averageEegs[2]));
                average4.setText("Average EEG 4:          " + Double.toString(averageEegs[3]));
            }
        }

        else if (v.getId() == R.id.buttonConnect) {

            //Stop listening because we've already found the Muse headsets
            manager.stopListening();

            List<Muse> availableMuses = manager.getMuses();
            Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner2);

            // Check that we actually have something to connect to.
            if (availableMuses.size() < 1 || musesSpinner.getAdapter().getCount() < 1) {
                Log.w(TAG, "There is nothing to connect to");
            } else {

                CountDownTimer timer = new CountDownTimer(3000, 1000) { //3 seconds in future, count by 1 second

                    @Override
                    public void onTick(long millisUntilFinished) {
                        //Do nothing
                    }

                    @Override
                    public void onFinish() {
                        listenForPeaks = true;
                    }
                }.start();

                // Cache the Muse that the user has selected.
                muse = availableMuses.get(musesSpinner.getSelectedItemPosition());
                // Unregister all prior listeners and register our data listener to
                // receive the MuseDataPacketTypes we are interested in.  If you do
                // not register a listener for a particular data type, you will not
                // receive data packets of that type.
                muse.unregisterAllListeners();
                muse.registerConnectionListener(connectionListener);
                muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
                muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
                muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
                muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
                muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
                muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);

                // Initiate a connection to the headband and stream the data asynchronously.
                muse.runAsynchronously();
            }

        }

        else if (v.getId() == R.id.buttonDisconnect) {

            // The user has pressed the "Disconnect" button.
            // Disconnect from the selected Muse.
            if (muse != null) {
                muse.disconnect();
            }

        }
        else if (v.getId() == R.id.buttonPause) {

            if (muse != null) {
                pressed = false;
                paused = !paused; //Inverse truth value of pause
                if (!paused){   //Reset the eegCountSize to avoid averaging issues
                    eegCountSize = 0;
                    resetAverageEegs(); //Reset all averages
                }

                dataTransmission = !dataTransmission;
                muse.enableDataTransmission(dataTransmission);
            }
        }
    }

    private void ensurePermissions() {

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            // We don't have the ACCESS_COARSE_LOCATION permission so create the dialogs asking
            // the user to grant us the permission.

            DialogInterface.OnClickListener buttonListener =
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which){
                            dialog.dismiss();
                            ActivityCompat.requestPermissions(SecondScreen.this,
                                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                    0);
                        }
                    };

            // This is the context dialog which explains to the user the reason we are requesting
            // this permission.  When the user presses the positive (I Understand) button, the
            // standard Android permission dialog will be displayed (as defined in the button
            // listener above).
            AlertDialog introDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_dialog_title)
                    .setMessage(R.string.permission_dialog_description)
                    .setPositiveButton(R.string.permission_dialog_understand, buttonListener)
                    .create();
            introDialog.show();
        }
    }



    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
        spinnerAdapter.clear();
        for (Muse m : list) {
            spinnerAdapter.add(m.getName() + " - " + m.getMacAddress());
        }
    }


    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

        /*final ConnectionState current = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + current;
        Log.i(TAG, status);

        // Update the UI with the change in connection state.
        handler.post(new Runnable() {
            @Override
            public void run() {

                final TextView statusText = (TextView) findViewById(R.id.con_status2);
                statusText.setText(status);

                final MuseVersion museVersion = muse.getMuseVersion();
                final TextView museVersionText = (TextView) findViewById(R.id.version);
                // If we haven't yet connected to the headband, the version information
                // will be null.  You have to connect to the headband before either the
                // MuseVersion or MuseConfiguration information is known.
                if (museVersion != null) {
                    final String version = museVersion.getFirmwareType() + " - "
                            + museVersion.getFirmwareVersion() + " - "
                            + museVersion.getProtocolVersion();
                    museVersionText.setText(version);
                } else {
                    museVersionText.setText(R.string.undefined);
                }
            }
        });

        if (current == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Muse disconnected:" + muse.getName());
            // Save the data file once streaming has stopped.
            saveFile();
            // We have disconnected from the headband, so set our cached copy to null.
            this.muse = null;
        }*/
    }


    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        writeDataPacketToFile(p);

        // valuesSize returns the number of data values contained in the packet.
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
            /*case ACCELEROMETER:
                assert(accelBuffer.length >= n);
               // getAccelValues(p);
                accelStale = true;
                break;
            case ALPHA_RELATIVE:
                assert(alphaBuffer.length >= n);
                getEegChannelValues(alphaBuffer,p);
                alphaStale = true;
                break;*/
            case BATTERY:
            case DRL_REF:
            case QUANTIZATION:
            default:
                break;
        }
    }

    public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
    }


    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    }

    /*private void getAccelValues(MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.X);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.Y);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.Z);
    }*/


    //Create UI
    private void createUI() {
        setContentView(R.layout.activity_two);
        Button refreshButton = (Button) findViewById(R.id.buttonRefresh);
        refreshButton.setOnClickListener(this);
        Button connectButton = (Button) findViewById(R.id.buttonConnect);
        connectButton.setOnClickListener(this);
        Button disconnectButton = (Button) findViewById(R.id.buttonDisconnect);
        disconnectButton.setOnClickListener(this);

        Button pauseButton = (Button) findViewById(R.id.buttonPause);
        pauseButton.setOnClickListener(this);

        Button low_high = (Button) findViewById(R.id.button_low_high);
        low_high.setOnClickListener(this);

        Button getAverage = (Button) findViewById(R.id.getAverage); //Initialize the getAverage button
        getAverage.setOnClickListener(this);



        for (int i = 0; i < averageEegs.length; i++){ //Initializing all the values in the array to 0
            averageEegs[i] = 0.0;
        }


        spinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner2);
        musesSpinner.setAdapter(spinnerAdapter);
    }

    //Updates the UI
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            if (eegStale) {
                updateEeg();
            }
            handler.postDelayed(tickUi, 1000 / 60);
        }
    };

    //updates the eeg text boxes
    private void updateEeg() {


        for (int i = 0; i < averageEegs.length; i++){ //Update eeg values
            averageEegs[i] += eegBuffer[i];
        }
        eegCountSize ++; //For every update, increment this to get averages later

        if (listenForPeaks){       //If the countDown timer has enabled the listening for peak values
            for (int i = 0; i < lowValues.length; i++){
                if (eegBuffer[i] < lowValues[i]){
                    lowValues[i] = eegBuffer[i];    //Changing the minimum values in the array
                }
                if (eegBuffer[i] > highValues[i]){
                    highValues[i] = eegBuffer[i];   //Changing the maximum values in the array
                }
            }

        }

        TextView tp9 = (TextView)findViewById(R.id.eeg_tp9);
        TextView fp1 = (TextView)findViewById(R.id.eeg_af7);
        TextView fp2 = (TextView)findViewById(R.id.eeg_af8);
        TextView tp10 = (TextView)findViewById(R.id.eeg_tp10);
        tp9.setText("EEG Value 1:" + String.format("%6.2f", eegBuffer[0]));
        fp1.setText("EEG Value 1:" + String.format("%6.2f", eegBuffer[1]));
        fp2.setText("EEG Value :" + String.format("%6.2f", eegBuffer[2]));
        tp10.setText("EEG Value 4:" + String.format("%6.2f", eegBuffer[3]));
    }

    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler());
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File file = new File(dir, "new_muse_file.muse" );
            // MuseFileWriter will append to an existing file.
            // In this case, we want to start fresh so the file
            // if it exists.
            if (file.exists()) {
                file.delete();
            }
            Log.i(TAG, "Writing data to: " + file.getAbsolutePath());
            fileWriter.set(MuseFileFactory.getMuseFileWriter(file));
            Looper.loop();
        }
    };


    private void writeDataPacketToFile(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    fileWriter.get().addDataPacket(0, p);
                }
            });
        }
    }

    private void saveFile() {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override public void run() {
                    MuseFileWriter w = fileWriter.get();
                    // Annotation strings can be added to the file to
                    // give context as to what is happening at that point in
                    // time.  An annotation can be an arbitrary string or
                    // may include additional AnnotationData.
                    w.addAnnotationString(0, "Disconnected");
                    w.flush();
                    w.close();
                }
            });
        }
    }

    private void playMuseFile(String name) {

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);

        final String tag = "Muse File Reader";

        if (!file.exists()) {
            Log.w(tag, "file doesn't exist");
            return;
        }

        MuseFileReader fileReader = MuseFileFactory.getMuseFileReader(file);

        // Loop through each message in the file.  gotoNextMessage will read the next message
        // and return the result of the read operation as a Result.
        Result res = fileReader.gotoNextMessage();
        while (res.getLevel() == ResultLevel.R_INFO && !res.getInfo().contains("EOF")) {

            MessageType type = fileReader.getMessageType();
            int id = fileReader.getMessageId();
            long timestamp = fileReader.getMessageTimestamp();

            Log.i(tag, "type: " + type.toString() +
                    " id: " + Integer.toString(id) +
                    " timestamp: " + String.valueOf(timestamp));

            switch(type) {
                // EEG messages contain raw EEG data or DRL/REF data.
                // EEG derived packets like ALPHA_RELATIVE and artifact packets
                // are stored as MUSE_ELEMENTS messages.
                case EEG:
                case BATTERY:
                case ACCELEROMETER:
                case QUANTIZATION:
                case GYRO:
                case MUSE_ELEMENTS:
                    MuseDataPacket packet = fileReader.getDataPacket();
                    Log.i(tag, "data packet: " + packet.packetType().toString());
                    break;
                case VERSION:
                    MuseVersion version = fileReader.getVersion();
                    Log.i(tag, "version" + version.getFirmwareType());
                    break;
                case CONFIGURATION:
                    MuseConfiguration config = fileReader.getConfiguration();
                    Log.i(tag, "config" + config.getBluetoothMac());
                    break;
                case ANNOTATION:
                    AnnotationData annotation = fileReader.getAnnotation();
                    Log.i(tag, "annotation" + annotation.getData());
                    break;
                default:
                    break;
            }

            // Read the next message.
            res = fileReader.gotoNextMessage();
        }
    }

    class MuseL extends MuseListener {
        final WeakReference<SecondScreen> activityRef;

        MuseL(final WeakReference<SecondScreen> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<SecondScreen> activityRef;

        ConnectionListener(final WeakReference<SecondScreen> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<SecondScreen> activityRef;

        DataListener(final WeakReference<SecondScreen> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p, muse);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
    }
}
