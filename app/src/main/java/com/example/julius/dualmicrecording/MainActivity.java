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

import android.view.KeyEvent;

public class MainActivity extends AppCompatActivity {

    private static final int RECORDER_SAMPLERATE = 44100,
            RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO,
            RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;
    String filePath1, filePath2;
    int min_buffer_length,
            BufferElements2Rec = 4096*2, // number of shorts not bytes
            BytesPerElement = 2,
            maxLag = 25,  //max possible lag between two samples in terms of number of frames (e.g. 17 samples of lag for phone length 0.13m and sampling rate 44.1khz)
            threadSleepTime = 200; //time in ms
    double phoneLength = 0.134, //separation between two microphones in meters
            angle = 0,
            soundVelocity = 343; //velocity of sound in air in m/s
    TextView txtViewAngle;

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
        enableButtons(true);
        min_buffer_length = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);
        recorder = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, min_buffer_length * 40);

        recorder.startRecording();
        if (NoiseSuppressor.isAvailable()){
            NoiseSuppressor ns=NoiseSuppressor.create(recorder.getAudioSessionId());
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

    //write the recorded data to files
//    void writeDataToFiles() {
//        // Write the output audio in byte
//        filePath1 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music/mic" + counter1 + "." + counter2 + ".pcm";
//        filePath2 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Music/camcorder" + counter1 + "." + counter2 + ".pcm";
//        short sData[] = new short[BufferElements2Rec];
//        short sData1[] = new short[BufferElements2Rec / 2];
//        short sData2[] = new short[BufferElements2Rec - BufferElements2Rec / 2];
//        FileOutputStream os1 = null;
//        try {
//            os1 = new FileOutputStream(filePath1);
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }
//        FileOutputStream os2 = null;
//        try {
//            os2 = new FileOutputStream(filePath2);
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }
//        while (isRecording) {
//            // gets the voice output from microphone to byte format
//            recorder.read(sData, 0, BufferElements2Rec);
//            for (int i = 0; i < BufferElements2Rec; i++) {
//                if (i % 2 == 0)
//                    sData1[i / 2] = sData[i];
//                else
//                    sData2[(i - 1) / 2] = sData[i];
//            }
//            try {
//                byte bData1[] = short2byte(sData1);
//                byte bData2[] = short2byte(sData2);
//                os1.write(bData1, 0, BufferElements2Rec / 2 * BytesPerElement);
//                os2.write(bData2, 0, BufferElements2Rec / 2 * BytesPerElement);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//        try {
//            os1.close();
//            os2.close();
//            counter2 += 1;
//            if (counter2 > number_of_samples) {
//                counter2 = 0;
//                counter1 += 1;
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

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
        float sData1[] = new float[BufferElements2Rec / 2],
                sData2[] = new float[BufferElements2Rec / 2];
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new FileOutputStream(outputLogFilePath, true));
        } catch (Exception e) {
            Log.e("Error", e.getMessage());
        }
        if (isRecording) {
            // gets the voice output from microphone to byte format
            System.out.println("\nnumber of shorts read: " + recorder.read(sData, 0, BufferElements2Rec));
            if (isAudible(sData)) {
                for (int i = 0; i < BufferElements2Rec; i++) {
                    if (i % 2 == 0)
                        sData1[i / 2] = (float) sData[i];
                    else
                        sData2[(i - 1) / 2] = (float) sData[i];
                }
                float[] corrArray = findCorrelation(sData2, sData1, maxLag);//correlation wrt to camcorder i.e. keep sData2 (data corresponding to camcorder) fixed and shift the other one to find lag.
                float max = corrArray[0]; //maximum correlation value
                int maxIndex = 0;  //index of maximum correlation value
                for (int i = 1; i < corrArray.length; i++) {
                    if (corrArray[i] > max) {
                        max = corrArray[i];
                        maxIndex = i;
                    }
                }
                int lag = mapIndexToLag(maxIndex);
                angle = findAngleFromLag(lag);
                writer.println(new Date() + "," + lag + "," + angle);
                System.out.println("Max lag : " + lag + "\t angle is :" + angle + "\n");
            } else {
                System.out.println("data not audible");
            }
        }
        writer.close();
        return angle;
    }

    //find out lag from index of maximum correlation. The correlation array contains negative lag, zero lag and postiive lag in same order.
    private int mapIndexToLag(int maxIndex) {
        if (maxIndex == maxLag) {
            return 0;
        } else
            return maxIndex - maxLag;
    }

    //returns angle corresponding to the lag given. Angle is measure wrt to camcorder and is clockwise.
    double findAngleFromLag(int lag) {
        //assumption is that correlation is measured wrt to camcorder signal i.e. camcorder signal is fixed and mic signal is delayed to check
        if (lag <= maxLag && lag >= -maxLag) { //lag can't be more (less) than 17 (-17) for a phone length of 0.134 and sampling rate of 44100 hz
            double timeDelay = (double) lag / RECORDER_SAMPLERATE;
            double angle = Math.toDegrees(Math.acos((timeDelay * soundVelocity) / phoneLength));
            //angle = lag < 0 ? angle : 180 - angle;
            return Math.round(angle);
        } else {
            return -1;
        }
    }

    private void processAudioData() {
        //writeDataToFiles();
        while (isRecording) {
            try {
                Thread.sleep(threadSleepTime);
            } catch (Exception e) {
                Log.e("Thread Error", e.getMessage().toString());
            }
            angle = findAngle();
            if ((int) angle >= 0) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((TextView) findViewById(R.id.txtViewAngle)).setText(Double.toString(angle));
                    }
                });
            }

        }
//        float[]input1=new float[]{1,2,3};
//        float[]input2=new float[]{4,5, 6};
//        float[]output=findCorrelation(input1, input2, 2);
//        int i =1;
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
    public float[] findCorrelation(float[] input1, float[] input2, int maxLag) {
        //this array contains the final correlation values for all lags. First maxLag number of elements are for negative lag followed by 0 lag and then positive lag.
        float[] correlationArray = new float[2 * maxLag + 1];
        int outputArrayIndex = 0;
        //negative lag
        for (int count = maxLag; count > 0; count--) {
            int j = count;
            for (int i = 0; i < input1.length - count; i++) {
                correlationArray[outputArrayIndex] += input1[i] * input2[j];
                j++;
            }
            outputArrayIndex++;
        }
        //zero lag
        for (int i = 0; i < input1.length; i++) {
            correlationArray[outputArrayIndex] += input1[i] * input2[i];
        }
        outputArrayIndex++;

        //positive lag
        for (int count = 1; count <= maxLag; count++) {
            int j = 0;
            for (int i = count; i < input1.length; i++) {
                correlationArray[outputArrayIndex] += input1[i] * input2[j];
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
