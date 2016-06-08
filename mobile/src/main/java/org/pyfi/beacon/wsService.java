package org.pyfi.beacon;

import android.app.IntentService;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

/*import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_10;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
*/

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import com.google.android.gms.location.LocationRequest;
import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


public class wsService extends Service implements OnPreparedListener {
    public static final String PREFS_NAME = "MyPrefsFile";

    double longitude = 0;
    double latitude = 0;
    long time = 0;
    float speed = 0;
    float accuracy = 0;
    float bearing = 0;
    boolean sound_stopped = true;
    String userName = "init";
    String token = "init";
    public static final String TAG = wsService.class.getSimpleName();
    private LocationRequest mLocationRequest;
    MediaPlayer mp;


    /** indicates how to behave if the service is killed */
    int mStartMode;


    /** indicates whether onRebind should be used */
    boolean mAllowRebind;
    private Socket mSocket;
    {
        try {
            mSocket = IO.socket("http://68.12.126.213:5000");
        } catch (URISyntaxException e) {}

    }

    /** Called when the service is being created. */
    @Override
    public void onCreate() {
        Log.d(TAG, "-- Starting wsService --");
        mSocket.on("png_test", onCommand);
        mSocket.on(Socket.EVENT_DISCONNECT,onDisconnect);
        mSocket.on(Socket.EVENT_CONNECT,onConnect);
        //String message = "{\"token\":\"" + token + "\"}";
        //mSocket.emit("png_test");
        //new LongOperation().execute("test");
        ws_connect();
        mp = MediaPlayer.create(this, R.raw.led);
        String locationProvider = LocationManager.NETWORK_PROVIDER;
        mLocationRequest = LocationRequest.create() // Create the LocationRequest object
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(10 * 1000)        // 10 seconds, in milliseconds
                .setFastestInterval(1 * 1000); // 1 second, in milliseconds
        // Acquire a reference to the system Location Manager
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(locationProvider, 0, 0, locationListener);
        startTimer();
        startTimer2();
    }

    Timer timer;
    TimerTask timerTask;
    final Handler handler = new Handler();
    public void startTimer() {
        //set a new Timer
        timer = new Timer();

        //initialize the TimerTask's job
        initializeTimerTask();

        //schedule the timer, after the first 5000ms the TimerTask will run every 10000ms
        timer.schedule(timerTask, 60000, 60000); //
    }
    public void initializeTimerTask() {

        timerTask = new TimerTask() {
            public void run() {

                //use a handler to run a toast that shows the current timestamp
                handler.post(new Runnable() {
                    public void run() {
                        //get the current timeStamp
                        Calendar calendar = Calendar.getInstance();
                        send_ping();
                        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd:MMMM:yyyy HH:mm:ss a");
                        final String strDate = simpleDateFormat.format(calendar.getTime());
                    }
                });
            }
        };
    }

    Timer timer2;
    TimerTask timerTask2;
    final Handler handler2 = new Handler();
    public void startTimer2() {
        //set a new Timer
        timer2 = new Timer();

        //initialize the TimerTask's job
        initializeTimerTask2();

        //schedule the timer, after the first 5000ms the TimerTask will run every 10000ms
        timer2.schedule(timerTask2, 1000, 5000); //
    }
    Map<String, Integer> beacon_matrix = new HashMap<>();
    Map<String, Integer> prev_beacon_matrix = new HashMap<>();
    Map<String, Integer> delta_matrix = new HashMap<>();
    public void initializeTimerTask2() {
        timerTask2 = new TimerTask() {
            public void run() {
                handler2.post(new Runnable() {
                    public void run() {
                        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
                        wifi.startScan();
                        for (int i = 0; i < wifi.getScanResults().size(); i++){
                            //Log.i(TAG,"<< ----- PREVIOUS ----- >> " + wifi.getScanResults().get(i).BSSID + "  " + beacon_matrix.get(wifi.getScanResults().get(i).BSSID));
                            //Log.i(TAG,"<< ----- CURRENT ----- >> " + wifi.getScanResults().get(i).BSSID + "  " + wifi.getScanResults().get(i).level);
                            int delta_value = 0;
                            if (beacon_matrix.get(wifi.getScanResults().get(i).BSSID) != null) {
                                delta_value = wifi.getScanResults().get(i).level - beacon_matrix.get(wifi.getScanResults().get(i).BSSID);
                            }
                            beacon_matrix.put(wifi.getScanResults().get(i).BSSID, wifi.getScanResults().get(i).level);
                            delta_matrix.put(wifi.getScanResults().get(i).BSSID, delta_value);
                        }
                        rssiString = "\n\n\n\ndelta matrix\n";
                        printMatrix(delta_matrix);
                        //subtract_matrix(beacon_matrix,prev_beacon_matrix);
                        prev_beacon_matrix = beacon_matrix;
                        //Log.i(TAG, rssiString);
                    }
                });
            }
        };
    }

    public void subtract_matrix(Map matrix, Map matrix2) {
        Iterator it = matrix.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            //Log.d(TAG, " << ----- Current Matrix ----- >>" + pair.getKey() + "  " + pair.getValue());
            rssiString += "\n" + pair.getKey() + "  " + pair.getValue() + "   " + matrix2.get(pair.getKey());
        }
        Iterator it2 = matrix2.entrySet().iterator();
        while (it2.hasNext()) {
            Map.Entry pair2 = (Map.Entry)it2.next();
            //Log.d(TAG, " << ----- PREVIOUS Matrix ----- >>" + pair2.getKey() + "  " + pair2.getValue());
            rssiString += "\n" + pair2.getKey() + "  " + pair2.getValue() + "   " + matrix2.get(pair2.getKey());
        }
    }

    public void printMatrix(Map matrix) {
        Iterator it = matrix.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            Log.d(TAG,"printMatrix | " + pair.getKey() + "   " + pair.getValue());
            rssiString += "\n" + pair.getKey() + "  " + pair.getValue();
        }
    }
    /*public void printMatrix(Map matrix) {
        Iterator it = matrix.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            Log.d(TAG,"printMatrix | " + pair.getKey() + "   " + pair.getValue());
        }
    }


    public void subtract_matrix(Map matrix1, Map matrix2) {
        Iterator it = matrix1.entrySet().iterator();
        Log.d(TAG, "subtract_matrix");
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            it.remove(); // avoids a ConcurrentModificationException
            Iterator it2 = matrix2.entrySet().iterator();
            Log.d(TAG, "subtract_matrix");
            while (it2.hasNext()) {
                Map.Entry pair2 = (Map.Entry)it2.next();
                int delta_value = (int)pair.getValue() - (int)pair2.getValue();
                Log.d(TAG, "DELTA VALUE | " + String.valueOf(delta_value));
                delta_matrix.put(pair2.getKey().toString(), (int)pair.getValue() - (int)pair2.getValue());
                //it2.remove(); // avoids a ConcurrentModificationException
            }
        }
    }*/

    String rssiString = "init";
    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        wsService getService() {
            // Return this instance of LocalService so clients can call public methods
            return wsService.this;
        }
    }

    /** method for clients */
    public String getRssi() {
        return rssiString;
    }


    public void send_ping() {
        String message = "{\"mac\":\"" + getWifiMacAddress() + "\","
                + "\"token\":\"" + token + "\"}";
        mSocket.emit("png_test", message);
        Log.i(TAG, "<<<<---- SENDING PING ----->>> ");
    }

    private void ws_connect() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        userName = settings.getString("username", "Please enter a username");
        token = settings.getString("token", "no token");
        String message = "{\"mac\":\"" + getWifiMacAddress() + "\","
                + "\"token\":\"" + token + "\"}";
        mSocket.connect();
        mSocket.emit("link_mobile", message);
        Log.i(TAG, "<<<<---- LINK MOBILE ----->>> ");
        mSocket.on("to_mobile", onCommand);
        mSocket.on("png_test", onCommand);
    }

    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Log.i(TAG, "<<<<---- !! RECONNECTING !! ----->>> ");
            ws_connect();
        }
    };
    private Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Log.i(TAG, "<<<<---- !! DISCONNECTED !! ----->>> ");
            //ws_connect();
        }
    };

    private Emitter.Listener onCommand = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            JSONObject data = (JSONObject)args[0];
            String command = null;
            String png = null;
            try {
                command = data.getString("command");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Log.i(TAG, "<<<<---- RECEIVING COMMAND ----->>> " + command);
            if (command.equals("ping_audio_start")) {
                AudioManager audioManager =
                        (AudioManager)getSystemService(Context.AUDIO_SERVICE);

                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,95,1);
                start_sound();
            }
            if (command.equals("ping_audio_stop")) {
                stop_sound();
            }
            if (command.equals("ping_gps")) {
                Log.i(TAG, "<<<<---- RESPONDING GPS REQUEST ----->>> ");
                mSocket.emit("from_mobile", gps_string);
            }
            if (command.equals("ping")) {
                Log.i(TAG, "<<<<---- received ping ----->>> ");
            }
        }
    };

    String gps_string = "HELLO FROM DROID";
    private void handleNewLocation(Location location) {
        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        ScanResult result0 = wifi.getScanResults().get(0);
        String ssid0 = result0.SSID;
        int rssi0 = result0.level;
        String rssiString0 = String.valueOf(rssi0);
        Log.i(TAG, "<<<<---- RSSI ---->>> " + ssid0 + ":" + rssiString0);
        String macAddress = getWifiMacAddress();
        longitude = location.getLongitude();
        latitude = location.getLatitude();
        time = location.getTime();
        speed = location.getSpeed();
        accuracy = location.getAccuracy();
        bearing = location.getBearing();
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        userName = settings.getString("username","does not exist");
        token = settings.getString("token","does not exist");
        gps_string = "{ \"mac\":\"" + macAddress
                + "\", \"userName\":\"" + userName
                + "\", \"token\":\"" + token
                + "\", \"time\":\"" + time
                + "\", \"longitude\":\"" + longitude
                + "\", \"latitude\":\"" + latitude
                + "\", \"speed\":\"" + speed
                + "\", \"accuracy\":\"" + accuracy
                + "\", \"bearing\":\"" + bearing
                + "\"}";
        mSocket.emit("from_mobile", gps_string);
        Log.i(TAG, "<<<<---- SENDING GPS DATA ---->>> ");

        //mSocket.emit("link_mobile", gps_string);
        //Log.i(TAG, "<<<<---- LINK MOBILE (gps) ----->>> ");
    }

    // Define a listener that responds to location updates
    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            handleNewLocation(location);
        }
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {}
    };

    public void start_sound() {
        Log.i(TAG, "<<<<---- START PING ----->>>");
        mp.release();
        mp = MediaPlayer.create(this, R.raw.led);
        mp.setLooping(true);
        mp.start();
        mp.setVolume(1, 1);
        sound_stopped = false;

    }
    public void stop_sound() {
        Log.i(TAG, "<<<<---- STOP PING ----->>> ");
        mp.stop();
        sound_stopped = true;
    }

    public static String getWifiMacAddress() {
        try {
            String interfaceName = "wlan0";
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.getName().equalsIgnoreCase(interfaceName)){
                    continue;
                }

                byte[] mac = intf.getHardwareAddress();
                if (mac==null){
                    return "";
                }

                StringBuilder buf = new StringBuilder();
                for (byte aMac : mac) {
                    buf.append(String.format("%02X:", aMac));
                }
                if (buf.length()>0) {
                    buf.deleteCharAt(buf.length() - 1);
                }
                return buf.toString();
            }
        } catch (Exception ex) { } // for now eat exceptions
        return "";
    }

    public void onPrepared(MediaPlayer player) {
        player.start();
    }
    /** The service is starting, due to a call to startService() */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /** A client is binding to the service with bindService() */
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** Called when all clients have unbound with unbindService() */
    @Override
    public boolean onUnbind(Intent intent) {
        return mAllowRebind;
    }

    /** Called when a client is binding to the service with bindService()*/
    @Override
    public void onRebind(Intent intent) {

    }

    /** Called when The service is no longer used and is being destroyed */
    @Override
    public void onDestroy() {

    }
}