package co.nz.airshare.airshareclient;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubMessageResult;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    Context context;

    String connString = BuildConfig.ConnectionString;

    private JSONArray trackerData = new JSONArray();
    private String msgStr;
    private Message sendMessage;
    private String lastException;
    private boolean isIOTHubRunning = false;

    private DeviceClient client;

    IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;

    private int msgSentCount = 0;
    private int receiptsConfirmedCount = 0;
    private int sendFailuresCount = 0;
    private int msgReceivedCount = 0;
    private int sendMessagesInterval = 2000;

    private final Handler handler = new Handler();
    private Thread sendThread;

    private static final int METHOD_SUCCESS = 200;
    public static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private Double latitude;
    private Double longitude;
    private Double altitude;
    private float accuracy;
    private Boolean isTracking = false;
    private String flightID = null;

    Button trackingBtn;
    Button resetButton;
    TextView latTextView;
    TextView longTextView;
    TextView accTextView;
    TextView altTextView;
    TextView statusTextView;
    EditText flightIDText;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = getApplicationContext();

        trackingBtn = findViewById(R.id.trackingButton);
        resetButton = findViewById(R.id.resetButton);
        statusTextView = findViewById(R.id.statusTextView);

        startService(new Intent(this, AirshareAlwaysOnService.class));
        loadActivity();
    }

    protected void loadActivity() {
        if (!isNetworkAvailable()) {
            resetButton.setVisibility(View.VISIBLE);
            trackingBtn.setVisibility(View.INVISIBLE);
            statusTextView.setText("No Internet connection!");
        } else {
            resetButton.setVisibility(View.INVISIBLE);
            trackingBtn.setVisibility(View.VISIBLE);
            statusTextView.setText("Press the button to start the transmission");
            locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    updateLocationInfo(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {

                }

                @Override
                public void onProviderEnabled(String provider) {

                }

                @Override
                public void onProviderDisabled(String provider) {

                }
            };

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            } else{
                if (isTracking) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locationListener);
            }
            flightIDText = findViewById(R.id.flightIDText);
            latTextView = findViewById(R.id.latTextView);
            longTextView = findViewById(R.id.longTextView);
            accTextView = findViewById(R.id.accTextView);
            altTextView = findViewById(R.id.altTextView2);
        }
    }

    public void checkInternetConnection(View view) {
        loadActivity();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void stopIoTHubCommunication()
    {
        new Thread(new Runnable() {
            public void run()
            {
                try
                {
                    sendThread.interrupt();
                    client.closeNow();
                    System.out.println("Shutting down...");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            enableTrackerBtn(true);
                        }
                    });
                }
                catch (Exception e)
                {
                    lastException = "Exception while closing IoTHub connection: " + e;
                    handler.post(exceptionRunnable);
                }
            }
        }).start();

        isIOTHubRunning = false;
    }

    private void startIoTHubCommunication()
    {
        sendThread = new Thread(new Runnable() {
            public void run()
            {
                try
                {
                    initClient();
                    for(;;)
                    {
                        sendMessages();
                        Thread.sleep(sendMessagesInterval);
                    }
                }
                catch (InterruptedException e)
                {
                    return;
                }
                catch (Exception e)
                {
                    lastException = "Exception while opening IoTHub connection: " + e;
                    handler.post(exceptionRunnable);
                }
            }
        });

        sendThread.start();

        isIOTHubRunning = true;
    }

    Runnable updateRunnable = new Runnable() {
        public void run() {}
    };

    Runnable exceptionRunnable = new Runnable() {
        public void run() {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setMessage(lastException);
            builder.show();
            System.out.println(lastException);
        }
    };

    Runnable methodNotificationRunnable = new Runnable() {
        public void run() {
            Context context = getApplicationContext();
            CharSequence text = "Set Send Messages Interval to " + sendMessagesInterval + "ms";
            int duration = Toast.LENGTH_LONG;

            Toast toast = Toast.makeText(context, text, duration);
            toast.show();
        }
    };

    private void sendMessages() throws JSONException {
        msgStr = "{" + "\"flightID\":" + String.format(Locale.getDefault(),"%s",flightID) + ", " +
                "\"latitude\":" + String.format(Locale.getDefault(),"%s",latitude) + ", " +
                "\"longitude\":" + String.format(Locale.getDefault(),"%s",longitude) +
                ", \"altitude\":" + String.format(Locale.getDefault(),"%s",altitude) + "}";

        try
        {
            sendMessage = new Message(msgStr);
            sendMessage.setMessageId(java.util.UUID.randomUUID().toString());
            System.out.println("Message Sent: " + msgStr);
            EventCallback eventCallback = new EventCallback();
            client.sendEventAsync(sendMessage, eventCallback, msgSentCount);
            msgSentCount++;
            handler.post(updateRunnable);
        }
        catch (Exception e)
        {
            trackerData.put(msgStr);
            System.err.println("Exception while sending event: " + e.getMessage());
        }
    }

    private void initClient() throws URISyntaxException, IOException
    {
        client = new DeviceClient(connString, protocol);

        try
        {
            client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallbackLogger(), new Object());
            client.open();
            MessageCallback callback = new MessageCallback();
            client.setMessageCallback(callback, null);
            client.subscribeToDeviceMethod(new TrackerDeviceMethodCallback(), getApplicationContext(), new DeviceMethodStatusCallBack(), null);
        }
        catch (Exception e)
        {
            System.err.println("Exception while opening IoTHub connection: " + e);
            client.closeNow();
            System.out.println("Shutting down...");
        }
    }

    class EventCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            Integer i = context instanceof Integer ? (Integer) context : 0;
            System.out.println("IoT Hub responded to message " + i.toString()
                    + " with status " + status.name());

            if((status == IotHubStatusCode.OK) || (status == IotHubStatusCode.OK_EMPTY))
            {
                receiptsConfirmedCount++;
            }
            else
            {
                sendFailuresCount++;
            }
        }
    }

    class MessageCallback implements com.microsoft.azure.sdk.iot.device.MessageCallback
    {
        public IotHubMessageResult execute(Message msg, Object context)
        {
            System.out.println(
                    "Received message with content: " + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET));
            msgReceivedCount++;

            return IotHubMessageResult.COMPLETE;
        }
    }

    protected static class IotHubConnectionStatusChangeCallbackLogger implements IotHubConnectionStatusChangeCallback
    {
        @Override
        public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext)
        {
            System.out.println();
            System.out.println("CONNECTION STATUS UPDATE: " + status);
            System.out.println("CONNECTION STATUS REASON: " + statusChangeReason);
            System.out.println("CONNECTION STATUS THROWABLE: " + (throwable == null ? "null" : throwable.getMessage()));
            System.out.println();

            if (throwable != null)
            {
                throwable.printStackTrace();
            }

            if (status == IotHubConnectionStatus.DISCONNECTED)
            {
                //connection was lost, and is not being re-established. Look at provided exception for
                // how to resolve this issue. Cannot send messages until this issue is resolved, and you manually
                // re-open the device client
                System.out.println("Network disconnected");
            }
            else if (status == IotHubConnectionStatus.DISCONNECTED_RETRYING)
            {
                //connection was lost, but is being re-established. Can still send messages, but they won't
                // be sent until the connection is re-established
                System.out.println("Reconnect in progress");
            }
            else if (status == IotHubConnectionStatus.CONNECTED)
            {
                //Connection was successfully re-established. Can send messages.
                System.out.println("Reconnected");
            }
        }
    }

    protected class TrackerDeviceMethodCallback implements com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback
    {
        @Override
        public DeviceMethodData call(String methodName, Object methodData, Object context)
        {
            DeviceMethodData deviceMethodData ;
            try {
                switch (methodName) {
                    case "setSendMessagesInterval": {
                        int status = method_setSendMessagesInterval(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                        break;
                    }
                    default: {
                        int status = method_default(methodData);
                        deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                    }
                }
            }
            catch (Exception e)
            {
                int status = METHOD_THROWS;
                deviceMethodData = new DeviceMethodData(status, "Method Throws " + methodName);
            }
            return deviceMethodData;
        }
    }

    private int method_setSendMessagesInterval(Object methodData) throws UnsupportedEncodingException, JSONException
    {
        String payload = new String((byte[])methodData, "UTF-8").replace("\"", "");
        JSONObject obj = new JSONObject(payload);
        sendMessagesInterval = obj.getInt("sendInterval");
        handler.post(methodNotificationRunnable);
        return METHOD_SUCCESS;
    }

    private int method_default(Object data)
    {
        System.out.println("invoking default method for this device");
        // Insert device specific code here
        return METHOD_NOT_DEFINED;
    }

    protected class DeviceMethodStatusCallBack implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            System.out.println("IoT Hub responded to device method operation with status " + status.name());
        }
    }

    public void startButtonClicked(View view) {

        //Disable the button until server response is received
        enableTrackerBtn(false);
        flightID = flightIDText.getText().toString();

        if (isTracking != true && flightID != null) {
            startListening();
        } else {
            stopListening();
        }

        changeTrackingStatus();
    }

    private void enableTrackerBtn(Boolean state) {
        trackingBtn.setEnabled(state);
    }

    private void trackingBtnChange(String status, String color) {
        trackingBtn.setText(status);
        trackingBtn.setBackgroundColor(Color.parseColor(color));
    }

    //Disable Android buttons
    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onPause() {
        super.onPause();

        ActivityManager activityManager = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);

        activityManager.moveTaskToFront(getTaskId(), 0);
    }

    public void changeTrackingStatus() {
        isTracking = !isTracking;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && isTracking) {
            startListening();
        }
    }

    public void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            trackingBtnChange("Stop", "#FFB935");
            flightIDText.setVisibility(View.INVISIBLE);
            statusTextView.setVisibility(View.VISIBLE);
            statusTextView.setText("Locking down GPS coordinates & Connecting to server...");
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000, 0,locationListener );
        }
    }

    public void stopListening() {
        trackingBtnChange("Start", "#29A3D0");
        statusTextView.setVisibility(View.VISIBLE);
        flightIDText.setVisibility(View.VISIBLE);
        statusTextView.setText("Press the button to start the transmission");
        locationManager.removeUpdates(locationListener);

        if (isIOTHubRunning) stopIoTHubCommunication();
    }

    public void updateLocationInfo(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        altitude = location.getAltitude();
        accuracy = location.getAccuracy();

        if (latitude != null && !isIOTHubRunning) startIoTHubCommunication();

        statusTextView.setVisibility(View.INVISIBLE);
        enableTrackerBtn(true);
        latTextView.setText(String.format(Locale.getDefault(),"Latitude: %.5f", latitude));
        longTextView.setText(String.format(Locale.getDefault(),"Longitude: %.5f", longitude));
        accTextView.setText(String.format(Locale.getDefault(),"Accuracy: %s", accuracy));
        altTextView.setText(String.format(Locale.getDefault(),"Altitude: %s", altitude));
    }

}
