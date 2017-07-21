package org.pyfi.beacon;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import com.google.android.gms.location.LocationRequest;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

public class wsService extends Service implements OnPreparedListener {

    String SERVER_MODE = "prod";
    String RELAY_ADDRESS = "192.168.0.10:5000";

    public static final String PREFS_NAME = "MyPrefsFile";
    double longitude = 0;
    double latitude = 0;
    long time = 0;
    float speed = 0;
    float accuracy = 0;
    float bearing = 0;
    boolean sound_stopped = true;
    boolean isLogin = false;
    String userName = "init";
    String token = "init";
    String user_token = "init";
    public static final String TAG = wsService.class.getSimpleName();
    private LocationRequest mLocationRequest;
    MediaPlayer mp;
    String io_server = "init";
    String macAddress = getWifiMacAddress();
    String hostName = "init";
    private PendingIntent alarmIntent;
    private AlarmManager alarms;
    private WifiManager wifi;
    public String connected_wifi = "init";
    Map<String, Integer> beacon_matrix = new HashMap<>();
    Map<String, Integer> prev_beacon_matrix = new HashMap<>();
    Map<String, Integer> delta_matrix = new HashMap<>();
    Map<String, Integer> recorded_location = new HashMap<>();

    String rssiString = "init";
    String status_string = "init";
    JSONObject status_obj = new JSONObject();

    private Timer timer;
    private TimerTask timerTask;
    private Handler handler = new Handler();
    boolean ws_connected = false;

    /** indicates whether onRebind should be used */
    boolean mAllowRebind;
    public Socket mSocket;
    SharedPreferences hash_map;

    /** Called when the service is being created. */
    @Override
    public void onCreate() {

        super.onCreate();
        Intent intentOnAlarm = new Intent(
                LaunchReceiver.ACTION_PULSE_SERVER_ALARM);
        alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmIntent = PendingIntent.getBroadcast(this, 0, intentOnAlarm, 0);
        alarms.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
                10*1000, alarmIntent);



        try {
            connect_to_io();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        mp = MediaPlayer.create(this, R.raw.led);
        String locationProvider = LocationManager.NETWORK_PROVIDER;
        mLocationRequest = LocationRequest.create() // Create the LocationRequest object
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(10 * 1000)        // 10 seconds, in milliseconds
                .setFastestInterval(1 * 1000); // 1 second, in milliseconds
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(locationProvider, 0, 0, locationListener);

        hash_map = getSharedPreferences("HashMap", 0);
        Type type = new TypeToken<Map<String, Integer>>(){}.getType();
    }

    public void connect_to_io() throws URISyntaxException {
        if (SERVER_MODE.equals("dev")) {
            //url ="http://pyfi.org/get_ip?server_type=dev";
            io_server = RELAY_ADDRESS;
        }
        if (SERVER_MODE.equals("prod")) {
            //url ="http://pyfi.org/get_ip?server_type=prod";
            io_server = RELAY_ADDRESS;
        }
        try {
            mSocket = IO.socket("http://"+io_server+"");
            mSocket.connect();
            Log.d(TAG, "-- Starting wsService --" + io_server);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        mSocket.on(Socket.EVENT_DISCONNECT,onDisconnect);
        mSocket.on(Socket.EVENT_CONNECT,onConnect);
        mSocket.on("login",login);
        mSocket.on("link device",link_device);
        //mSocket.on("get token",onToken);
        mSocket.on("ping audio",ping_audio);
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        userName = settings.getString("username", "Please enter a username");
        user_token = settings.getString("user_token", "no user_token");
        token = settings.getString("token", "no token");
        if (!token.equals("no device_token")) {
            link_device();
            Log.i(TAG, "<<<<---- " + userName + ":" + token + " ---->>> ");
        } else {
            Log.i(TAG, "<<<<---- no token ---->>> ");
        }
    }

    Thread thread = new Thread(new Runnable(){
        @Override
        public void run() {
            try {
                InetAddress netHost = InetAddress.getLocalHost();
                hostName = netHost.getHostName();
            } catch (UnknownHostException e) {
                hostName = "";
            }
        }
    });

    public void set_zone() {
        String zone = "{\"wifi\":\"" + connected_wifi
                + "\", \"token\":\"" + token
                + "\", \"mac\":\"" + macAddress
                + "\"}";
        try {
            JSONObject data = new JSONObject(zone);
            mSocket.emit("set zone", data);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Log.i(TAG, "<<<<---- set_zone ----->>> ");
    }
    public void attempt_login(String user, String password) {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("username", user);
        editor.commit();

        try {
            JSONObject login_obj = new JSONObject("{\"device_type\":['mobile']}");
            login_obj.put("username",user);
            login_obj.put("password",password);
            login_obj.put("mac",macAddress);
            mSocket.emit("login", login_obj);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Log.i(TAG, "<<<<---- set username ----->>> " + user);
    }

    private Emitter.Listener login = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            try {
                JSONObject data = (JSONObject) args[0];
                token = data.getString("token");
                user_token = data.getString("user_token");
                userName = data.getString("username");
                SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString("username", userName);
                editor.putString("token", token);
                editor.putString("user_token", user_token);
                editor.commit();
                Log.i(TAG, "<<<<---- login ----->>> " + token);
                link_device();
            } catch (JSONException e) {
                Log.i(TAG, "<<<<---- ERROR ----->>> " + e);
                return;
            }
        }
    };

    public void send_status(JSONObject status_obj) {
        if (mSocket != null) {
            try {
                Log.i(TAG, "<<<<---- sending status ---->>> ");
                Intent local = new Intent();
                local.setAction("com.hello.action");

                status_string = status_obj.toString();
                local.putExtra("status", status_string);
                this.sendBroadcast(local);

                JSONObject data = new JSONObject();
                data.put("mac",macAddress);
                data.put("hostname",hostName);
                data.put("email",userName);
                data.put("device_type","['mobile']");
                data.put("token",token);
                data.put("status",status_obj);
                mSocket.emit("set status", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        /*wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifi.startScan();
        int match_count = 0;
        delta_matrix.clear();
        for (int i = 0; i < wifi.getScanResults().size(); i++){
            int delta_value = 0;
            //if (recorded_location.get(wifi.getScanResults().get(i).BSSID) != null) {
            //if (wifi.getScanResults().get(i).level < -75) {
            //delta_value = wifi.getScanResults().get(i).level - recorded_location.get(wifi.getScanResults().get(i).BSSID);
            //delta_matrix.put(wifi.getScanResults().get(i).BSSID, delta_value);
            //match_count++;
            //}
            //}
            beacon_matrix.put(wifi.getScanResults().get(i).BSSID, wifi.getScanResults().get(i).level);
        }
        //int confidence = match_count * 100 / recorded_location.size();
        rssiString = "\n\n\n\ndelta matrix [" + match_count + "]\n";
        printMatrix(delta_matrix);
        rssiString += "\n\nrecorded matrix\n";
        prev_beacon_matrix = beacon_matrix;*/
    }

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

    Gson gson = new Gson();


    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            ws_connected = true;
            link_device();
            Log.i(TAG, "<<<<---- !! RECONNECTING !! ----->>> ");
        }
    };

    private Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            ws_connected = false;
            Log.i(TAG, "<<<<---- !! DISCONNECTED !! ----->>> ");
        }
    };

    private Emitter.Listener link_device = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            if (isLogin) {
                Intent i = new Intent(getApplicationContext(), HomeActivity.class);
                i.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
            Log.i(TAG, "<<<<---- link_device ----->>> ");
        }
    };

    private Emitter.Listener ping_audio = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            JSONObject data = (JSONObject)args[0];
            String command = null;
            try {
                command = data.getString("command");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Log.i(TAG, "<<<<---- RECEIVING COMMAND ----->>> " + command);
            if (command.equals("start")) {
                AudioManager audioManager =
                        (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,95,1);
                start_sound();
            }
            if (command.equals("stop")) {
                stop_sound();
            }
        }
    };

    public void link_device() {
        try {
            JSONObject device_obj = new JSONObject("{\"device_type\":['mobile']}");
            device_obj.put("user_token",user_token);
            device_obj.put("username",userName);
            device_obj.put("hostname",hostName);
            device_obj.put("token",token);
            device_obj.put("mac",macAddress);
            mSocket.emit("link device", device_obj);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    private void handleNewLocation(Location location) {
        TelephonyManager telephonyManager = (TelephonyManager)this.getSystemService(Context.TELEPHONY_SERVICE);
        //Map<String, Integer> current_wifi = new HashMap<>();
        int cell_signal_level = 0;
        // for example value of first element
        try {
            CellInfoLte cellinfolte = (CellInfoLte)telephonyManager.getAllCellInfo().get(0);
            CellSignalStrengthLte cellinfoLte = cellinfolte.getCellSignalStrength();
            cell_signal_level = cellinfoLte.getDbm();
        } catch (Exception ex) { } // for now eat exceptions

        /*for (int i = 0; i < wifi.getScanResults().size(); i++){
            Log.i(TAG,wifi.getScanResults());
            current_wifi.put(wifi.getScanResults().get(i).BSSID, wifi.getScanResults().get(i).level);
        }*/
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo ();
        connected_wifi  = info.getSSID().replace("\"","");
        longitude = location.getLongitude();
        latitude = location.getLatitude();
        time = location.getTime();
        speed = location.getSpeed();
        accuracy = location.getAccuracy();
        bearing = location.getBearing();
        status_string = "{\"time\":\"" + time
                + "\", \"cell_signal_level\":\"" + cell_signal_level
                + "\", \"cell_type\":\"" + cell_signal_level
                + "\", \"connected_wifi\":\"" + connected_wifi
                //+ "\", \"wifi_scan\":\"" + wifi.getScanResults().toString()
                + "\", \"longitude\":\"" + longitude
                + "\", \"latitude\":\"" + latitude
                + "\", \"speed\":\"" + speed
                + "\", \"accuracy\":\"" + accuracy
                + "\", \"bearing\":\"" + bearing
                + "\"}";
        try {
            status_obj = new JSONObject(status_string);

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

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


    private void stopTimer(){
        if(timer != null){
            timer.cancel();
            timer.purge();
        }
    }

    private void startTimer(){
        timer = new Timer();
        timerTask = new TimerTask() {
            public void run() {
                handler.post(new Runnable() {
                    public void run(){
                        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        Intent batteryStatus = registerReceiver(null, ifilter);
                        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        float batteryPct = level / (float)scale;

                        try {
                            status_obj.put("battery",batteryPct);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                        if (ws_connected) {
                            send_status(status_obj);
                        } else {
                            /*try {
                                connect_to_io();
                            } catch (URISyntaxException e) {
                                e.printStackTrace();
                            }*/
                        }

                    }
                });
            }
        };
        timer.schedule(timerTask, 8000, 8000);
    }

        /*public class MyWakefulReceiver extends WakefulBroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            // Start the service, keeping the device awake while the service is
            // launching. This is the Intent to deliver to the service.
            Intent service = new Intent(context, MyIntentService.class);
            startWakefulService(context, service);
        }
    }

    public class MyIntentService extends IntentService {
        public static final int NOTIFICATION_ID = 1;
        private NotificationManager mNotificationManager;
        NotificationCompat.Builder builder;
        public MyIntentService() {
            super("MyIntentService");
        }
        @Override
        protected void onHandleIntent(Intent intent) {
            Bundle extras = intent.getExtras();
            // Do the work that requires your app to keep the CPU running.
            // ...
            // Release the wake lock provided by the WakefulBroadcastReceiver.
            MyWakefulReceiver.completeWakefulIntent(intent);
        }
    }*/

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
            //Log.d(TAG,"printMatrix | " + pair.getKey() + "   " + pair.getValue());
            //rssiString += "\n" + pair.getKey() + "  " + pair.getValue();
            rssiString += "  " + pair.getValue();
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
    public void onPrepared(MediaPlayer player) {
        player.start();
    }
    /** The service is starting, due to a call to startService() */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopTimer();
        startTimer();
        thread.start();
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