package co.nz.airshare.airshareclient;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.w3c.dom.Text;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    LocationManager locationManager;
    LocationListener locationListener;
    Boolean isTracking = false;
    Button trackingButton;
    TextView latTextView;
    TextView longTextView;
    TextView accTextView;
    TextView altTextView;

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startService(new Intent(this, AirshareLocationService.class));

        trackingButton = findViewById(R.id.trackingButton);
        latTextView = findViewById(R.id.latTextView);
        longTextView = findViewById(R.id.longTextView);
        accTextView = findViewById(R.id.accTextView);
        altTextView = findViewById(R.id.altTextView2);

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
            if (isTracking) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000, 0,locationListener );
                Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastKnownLocation != null) updateLocationInfo(lastKnownLocation);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && isTracking) {
            startListening();
        }
    }

    public void startButtonClicked(View view) {
        if (isTracking != true) {
            isTracking = true;
            trackingButton.setText("Stop");
            trackingButton.setBackgroundColor(Color.parseColor("#FFB935"));
            startListening();
        } else {
            isTracking = false;
            trackingButton.setText("Start");
            trackingButton.setBackgroundColor(Color.parseColor("#29A3D0"));
            stopListening();
        }
    }

    public void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000, 0,locationListener );
        }
    }

    public void stopListening() {
        locationManager.removeUpdates(locationListener);
    }

    public void updateLocationInfo(Location location) {

        Log.i("lat: ", Double.toString(location.getLatitude()));
        Log.i("long: ", Double.toString(location.getLongitude()));

        latTextView.setText(String.format(Locale.getDefault(),"Latitude: %.5f", location.getLatitude()));
        longTextView.setText(String.format(Locale.getDefault(),"Longitude: %.5f", location.getLongitude()));
        accTextView.setText(String.format(Locale.getDefault(),"Accuracy: %s", location.getAccuracy()));
        altTextView.setText(String.format(Locale.getDefault(),"Altitude: %s", location.getAltitude()));
    }
}
