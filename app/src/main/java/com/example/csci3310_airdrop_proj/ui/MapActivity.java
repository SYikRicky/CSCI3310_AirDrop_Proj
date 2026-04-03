package com.example.csci3310_airdrop_proj.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.csci3310_airdrop_proj.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

/**
 * Displays an OSMDroid offline-capable map.
 *
 * Launch modes:
 *  1. With EXTRA_LAT / EXTRA_LNG — centres on that point with a marker (from chat location bubble).
 *  2. Without extras — shows current device location (from bottom nav Map tab).
 */
public class MapActivity extends AppCompatActivity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_LABEL = "label";

    private MapView mapView;
    private MyLocationNewOverlay myLocationOverlay;
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(
                getExternalFilesDir(null));
        Configuration.getInstance().setOsmdroidTileCache(
                new java.io.File(getExternalFilesDir(null), "osmdroid/tiles"));

        setContentView(R.layout.activity_map);

        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        IMapController controller = mapView.getController();
        controller.setZoom(16.0);

        myLocationOverlay = new MyLocationNewOverlay(
                new GpsMyLocationProvider(this), mapView);
        myLocationOverlay.enableMyLocation();
        mapView.getOverlays().add(myLocationOverlay);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        double lat = getIntent().getDoubleExtra(EXTRA_LAT, Double.NaN);
        double lng = getIntent().getDoubleExtra(EXTRA_LNG, Double.NaN);
        String label = getIntent().getStringExtra(EXTRA_LABEL);

        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            GeoPoint target = new GeoPoint(lat, lng);
            controller.setCenter(target);

            Marker marker = new Marker(mapView);
            marker.setPosition(target);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(label != null ? label : getString(R.string.location_shared));
            mapView.getOverlays().add(marker);
        } else {
            centreOnMyLocation();
        }

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        ImageButton btnMyLocation = findViewById(R.id.btn_my_location);
        btnMyLocation.setOnClickListener(v -> centreOnMyLocation());
    }

    @SuppressWarnings("MissingPermission")
    private void centreOnMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        GeoPoint myPoint = new GeoPoint(
                                location.getLatitude(), location.getLongitude());
                        mapView.getController().animateTo(myPoint);
                    } else {
                        Toast.makeText(this, R.string.location_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }
}
