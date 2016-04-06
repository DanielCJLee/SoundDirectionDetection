package com.example.julius.dualmicrecording;

import android.app.Activity;
import android.content.Context;
import android.media.audiofx.NoiseSuppressor;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.media.MediaRecorder;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.os.Environment;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Date;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import android.view.KeyEvent;

public class MainActivity extends AppCompatActivity {

    private static final int RECORDER_SAMPLERATE = 44100,
            RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO,
            RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT,
            SMOOTHING_WINDOW = 10; //number of frames to consider for smoothening
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;
    String filePath1, filePath2;
    int min_buffer_length,
            BufferElements2Rec = 4096, // number of shorts not bytes
            BytesPerElement = 2,
            maxLag = 18,  //max possible lag between two samples in terms of number of frames (e.g
    // . 17 samples of lag for phone length 0.13m and sampling rate 44.1khz)
    threadSleepTime = 0; //time in ms
    double phoneLength = 0.14, //separation between two microphones in meters
            angle = 0,
            soundVelocity = 343; //velocity of sound in air in m/s
    double maxKey = 0.0;
    //Calender instance to get current time
    Calendar cal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enableButtons(false);
    }

    private void enableButton(int id, boolean isEnable) {
        findViewById(id).setEnabled(isEnable);
    }

    private void enableButtons(boolean isRecording) {
        enableButton(R.id.btnStart, !isRecording);
        enableButton(R.id.btnStop, isRecording);
    }

    public void startRecording(View view) {
        cal = Calendar.getInstance();
        enableButtons(true);
        min_buffer_length = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);
        recorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, min_buffer_length * 10);

        recorder.startRecording();
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor ns = NoiseSuppressor.create(recorder.getAudioSessionId());
            ns.setEnabled(true);
        }

        isRecording = true;
        recordingThread = new Thread(new Runnable() {
            public void run() {
                processAudioData();
            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    //convert short to byte
    private byte[] short2byte(short[] sData) {
        int shortArrsize = sData.length;
        byte[] bytes = new byte[shortArrsize * 2];
        for (int i = 0; i < shortArrsize; i++) {
            bytes[i * 2] = (byte) (sData[i] & 0x00FF);
            bytes[(i * 2) + 1] = (byte) (sData[i] >> 8);
            sData[i] = 0;
        }
        return bytes;
    }

    // write the recorded data to files
    void writeDataToFiles(short micData[], short camcorderData[]) {
        // Write the output audio in byte
        filePath1 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music/mic" + cal.getTime() + ".pcm";
        filePath2 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music/camcorder" + cal.getTime() + ".pcm";
        FileOutputStream outputStream1 = null,
                outputStream2 = null;
        try {
            outputStream1 = new FileOutputStream(filePath1, true);
            outputStream2 = new FileOutputStream(filePath2, true);

        } catch (Exception e) {
            Log.e("File Not Found", e.getMessage());
        }
        try {
            outputStream1.write(short2byte(micData));
            outputStream2.write(short2byte(camcorderData));
        } catch (Exception e) {
            Log.e("OutPutStream Error", e.getMessage());
        }

    }

    public static boolean isAudible(short[] data) {
        double rms = getRootMeanSquared(data);
        return (rms > 680 && 15500 > rms);
    }

    public static double getRootMeanSquared(short[] data) {
        double ms = 0;
        for (int i = 0; i < data.length; i++) {
            ms += data[i] * data[i];
        }
        ms /= data.length;
        return Math.sqrt(ms);
    }

    //separate stereo data for both microphones and process it further to find angle
    double findAngle() {
        String outputLogFilePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music/Log_Output.csv";
        short sData[] = new short[BufferElements2Rec];
        short micData[] = new short[BufferElements2Rec / 2],
                camcorderData[] = new short[BufferElements2Rec / 2];
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new FileOutputStream(outputLogFilePath, true));
        } catch (Exception e) {
            Log.e("Error", e.getMessage());
        }
        if (isRecording) {
            //read data from the recorder
            recorder.read(sData, 0, BufferElements2Rec);
            if (isAudible(sData)) {
                for (int i = 0; i < BufferElements2Rec; i++) {
                    if (i % 2 == 0)
                        camcorderData[i / 2] = sData[i];
                    else
                        micData[(i - 1) / 2] = sData[i];
                }
                //is magnitude of mic higher than camcorder
                boolean micMagHigher;
                //compare magnitude to find out which microphone is closer to the audio source
                micMagHigher = compareMagnitude(micData, camcorderData);
                System.out.println(micMagHigher);

                //correlation wrt to camcorder i.e. keep camcorderData (data corresponding to camcorder) fixed and shift the other one to find lag.
                float[] corrArray = findCorrelation(camcorderData, micData);
                //write data to files (for debugging purpose)
//                writeDataToFiles(micData, camcorderData);
                //find maximum value in correlation array taking into consideration whether only
                // negative lag has to be considered or only positive lag has to be considered
                // (input coming from magnitude comparison)
                int arrayStartIndex,
                        arrayStopIndex;
                if (!micMagHigher) {
                    //find max correlation within negative and zero lag
                    arrayStartIndex = 0;
                    arrayStopIndex = corrArray.length - maxLag;
                } else {
                    //positive lag
                    arrayStartIndex = maxLag + 1;
                    arrayStopIndex = corrArray.length;
                }

                float max = corrArray[arrayStartIndex]; //maximum correlation value
                int maxIndex = arrayStartIndex;  //index of maximum correlation value
                for (int i = arrayStartIndex + 1; i < arrayStopIndex; i++) {
                    if (corrArray[i] > max) {
                        max = corrArray[i];
                        maxIndex = i;
                    }
                }
                int lag = mapIndexToLag(maxIndex);
                //find angle wrt to camcorder
                angle = findAngleFromLag(lag);
                writer.println(new Date() + "," + lag + "," + angle);
                //System.out.println("Max lag : " + lag + "\t angle is :" + angle + "\n");
            } else {
                System.out.println("data not audible");
            }
        }
        writer.close();
        return angle;
    }

    //method to compare magnitude received by MIC wrt to Camcorder
    //return true if mic magnitude is higher than camcorder
    boolean compareMagnitude(short[] MICData, short[] camcorderData) {
        int arrayLen = MICData.length;
        long MICDataSum = 0,
                camcorderDataSum = 0;
        for (int i = 0; i < arrayLen; i++) {
            MICDataSum += Math.abs(MICData[i]);
            camcorderDataSum += Math.abs(camcorderData[i]);
        }
        return MICDataSum > camcorderDataSum;

    }


    //find out lag from index of maximum correlation. The correlation array contains negative lag, zero lag and postiive lag in same order.
    private int mapIndexToLag(int maxIndex) {
        return maxIndex - maxLag;
    }

    //returns angle corresponding to the lag given. Angle is measure wrt to camcorder and is clockwise.
    double findAngleFromLag(int lag) {
        //assumption is that correlation is measured wrt to camcorder signal i.e. camcorder
        // signal is fixed and mic signal is delayed to check lag
        if (lag <= maxLag && lag >= -maxLag) { //lag can't be more (less) than 17 (-17) for a phone length of 0.134 and sampling rate of 44100 hz
            double timeDelay = (double) lag / RECORDER_SAMPLERATE;
            timeDelay = Math.abs(timeDelay);
            double angle = Math.toDegrees(Math.acos((timeDelay * soundVelocity) / phoneLength));
            angle = lag > 0 ? angle : 180 - angle;
            return Math.round(angle);
        } else {
            return -1;
        }
    }

    private void processAudioData() {
        //writeDataToFiles();
        HashMap<Double, Integer> angles = new HashMap();
        int smoothCount = 0,
                maxValue = 0;
        while (isRecording) {
            angle = findAngle();
            if (smoothCount < SMOOTHING_WINDOW) {
                if (angles.containsKey(angle)) {
                    angles.put(angle, angles.get(angle) + 1);
                } else {
                    angles.put(angle, 1);
                }

                smoothCount++;
                continue;
            }
            for (Map.Entry<Double, Integer> entry : angles.entrySet()) {
                if (entry.getValue() > maxValue) {
                    maxValue = entry.getValue();
                    maxKey = entry.getKey();
                }
            }
            //update angle value on textview on UI
            if (maxKey >= 0) {
                final double maxKeyCopy = maxKey;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((TextView) findViewById(R.id.txtViewAngle)).setText(Double.toString(maxKeyCopy));
                    }
                });
            }
            smoothCount = 0;
            angles = new HashMap();
            maxValue = 0;
            maxKey = 0;

        }
    }

    public void stopRecording(View view) {
        // stops the recording activity
        enableButtons(false);
        if (null != recorder) {
            isRecording = false;
            recorder.stop();
            recorder.release();
            recorder = null;
            recordingThread = null;
        }
    }

    //returns correlation array
    //correlation is computed by fixing the first array and shifting elements (not actually shifting) of second array bounded by maxLag.
    public float[] findCorrelation(short[] camData, short[] micData) {
        //this array contains the final correlation values for all lags. First maxLag number of elements are for negative lag followed by 0 lag and then positive lag.
        float[] correlationArray = new float[2 * maxLag + 1];
        int outputArrayIndex = 0;
        //negative lag
        for (int count = maxLag; count > 0; count--) {
            int j = count;
            for (int i = 0; i < camData.length - count; i++) {
                correlationArray[outputArrayIndex] += camData[i] * micData[j];
                j++;
            }
            outputArrayIndex++;
        }
        //zero lag
        for (int i = 0; i < camData.length; i++) {
            correlationArray[outputArrayIndex] += camData[i] * micData[i];
        }
        outputArrayIndex++;

        //positive lag
        for (int count = 1; count <= maxLag; count++) {
            int j = 0;
            for (int i = count; i < camData.length; i++) {
                correlationArray[outputArrayIndex] += camData[i] * micData[j];
                j++;
            }
            outputArrayIndex++;
        }
        return correlationArray;
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

}
