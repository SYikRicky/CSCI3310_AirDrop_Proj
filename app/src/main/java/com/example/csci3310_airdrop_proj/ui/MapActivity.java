package com.example.csci3310_airdrop_proj.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;
import android.content.Intent;
import android.net.Uri;
import android.content.ActivityNotFoundException;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.csci3310_airdrop_proj.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays an OSMDroid offline-capable map.
 *
 * Launch modes:
 *  1. With EXTRA_LAT / EXTRA_LNG — centres on that point with a marker + walking route toggle.
 *  2. Without extras — shows current device location (from bottom nav Map tab).
 */
public class MapActivity extends AppCompatActivity {

    private static final String TAG = "MapActivity";
    private static final double ZOOM_MY_LOCATION = 19.0;
    private static final double ZOOM_SHARED_LOCATION = 19.5;

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_LABEL = "label";

    private MapView mapView;
    private MyLocationNewOverlay myLocationOverlay;
    private FusedLocationProviderClient fusedLocationClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private GeoPoint targetPoint;
    private Polyline routeOverlay;
    private Marker myMarker;
    private boolean routeVisible = false;
    private MaterialButton btnToggleRoute;
    private MaterialButton btnWhereAmI;
    private MaterialButton btnBackToLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(
                getExternalFilesDir(null));
        Configuration.getInstance().setOsmdroidTileCache(
                new java.io.File(getExternalFilesDir(null), "osmdroid/tiles"));

        setContentView(R.layout.activity_map);

        View root = findViewById(R.id.map_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        IMapController controller = mapView.getController();
        controller.setZoom(ZOOM_SHARED_LOCATION);

        myLocationOverlay = new MyLocationNewOverlay(
                new GpsMyLocationProvider(this), mapView);
        myLocationOverlay.enableMyLocation();
        mapView.getOverlays().add(myLocationOverlay);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        double lat = getIntent().getDoubleExtra(EXTRA_LAT, Double.NaN);
        double lng = getIntent().getDoubleExtra(EXTRA_LNG, Double.NaN);
        String label = getIntent().getStringExtra(EXTRA_LABEL);

        btnToggleRoute = findViewById(R.id.btn_toggle_route);
        btnWhereAmI = findViewById(R.id.btn_where_am_i);
        btnBackToLocation = findViewById(R.id.btn_back_to_location);

        btnWhereAmI.setOnClickListener(v -> goToMyLocationAndZoom());

        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            targetPoint = new GeoPoint(lat, lng);
            controller.setZoom(ZOOM_SHARED_LOCATION);
            controller.setCenter(targetPoint);

            Marker marker = new Marker(mapView);
            marker.setPosition(targetPoint);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(label != null ? label : getString(R.string.location_shared));
            mapView.getOverlays().add(marker);

            btnToggleRoute.setVisibility(View.VISIBLE);
            btnToggleRoute.setOnClickListener(v -> toggleRoute());
            btnBackToLocation.setVisibility(View.VISIBLE);
            btnBackToLocation.setOnClickListener(v -> goToSharedLocationAndZoom());

            MaterialButton btnOpenGoogleMaps = findViewById(R.id.btn_open_google_maps);
            btnOpenGoogleMaps.setVisibility(View.VISIBLE);
            btnOpenGoogleMaps.setOnClickListener(v -> {
                String uriStr = "geo:0,0?q=" + lat + "," + lng;
                if (label != null && !label.isEmpty()) {
                    uriStr += "(" + Uri.encode(label) + ")";
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
                intent.setPackage("com.google.android.apps.maps");
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    // Fallback to any app that can handle geo intents
                    Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
                    try {
                        startActivity(fallbackIntent);
                    } catch (ActivityNotFoundException ex) {
                        Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            goToMyLocationAndZoom();
        }

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());
    }

    private void toggleRoute() {
        if (routeVisible) {
            hideRoute();
        } else {
            showRoute();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void showRoute() {
        if (targetPoint == null) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, R.string.map_fetching_route, Toast.LENGTH_SHORT).show();

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location == null) {
                        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    GeoPoint myPoint = new GeoPoint(location.getLatitude(), location.getLongitude());

                    myMarker = new Marker(mapView);
                    myMarker.setPosition(myPoint);
                    myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                    myMarker.setTitle(getString(R.string.map_my_location));
                    mapView.getOverlays().add(myMarker);

                    fetchWalkingRoute(myPoint, targetPoint);
                });
    }

    private void hideRoute() {
        if (routeOverlay != null) {
            mapView.getOverlays().remove(routeOverlay);
            routeOverlay = null;
        }
        if (myMarker != null) {
            mapView.getOverlays().remove(myMarker);
            myMarker = null;
        }
        routeVisible = false;
        btnToggleRoute.setText(R.string.map_show_route);
        mapView.invalidate();
    }

    private void fetchWalkingRoute(GeoPoint from, GeoPoint to) {
        executor.execute(() -> {
            List<GeoPoint> routePoints = fetchOSRMRoute(from, to);

            runOnUiThread(() -> {
                if (routePoints == null || routePoints.isEmpty()) {
                    Toast.makeText(this, R.string.map_route_failed, Toast.LENGTH_SHORT).show();
                    List<GeoPoint> fallback = new ArrayList<>();
                    fallback.add(from);
                    fallback.add(to);
                    drawRoute(fallback);
                } else {
                    drawRoute(routePoints);
                }
            });
        });
    }

    /**
     * Calls OSRM public demo API for a walking route.
     * Falls back to null if no network or error.
     */
    private List<GeoPoint> fetchOSRMRoute(GeoPoint from, GeoPoint to) {
        try {
            String urlStr = String.format(java.util.Locale.US,
                    "https://router.project-osrm.org/route/v1/foot/%f,%f;%f,%f?overview=full&geometries=geojson",
                    from.getLongitude(), from.getLatitude(),
                    to.getLongitude(), to.getLatitude());

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "OSRM returned " + code);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray routes = json.getJSONArray("routes");
            if (routes.length() == 0) return null;

            JSONArray coords = routes.getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates");

            List<GeoPoint> points = new ArrayList<>();
            for (int i = 0; i < coords.length(); i++) {
                JSONArray coord = coords.getJSONArray(i);
                double lng = coord.getDouble(0);
                double lat = coord.getDouble(1);
                points.add(new GeoPoint(lat, lng));
            }
            return points;

        } catch (Exception e) {
            Log.w(TAG, "OSRM route fetch failed", e);
            return null;
        }
    }

    private void drawRoute(List<GeoPoint> points) {
        if (routeOverlay != null) {
            mapView.getOverlays().remove(routeOverlay);
        }

        routeOverlay = new Polyline();
        routeOverlay.setPoints(points);
        routeOverlay.getOutlinePaint().setColor(Color.parseColor("#1976D2"));
        routeOverlay.getOutlinePaint().setStrokeWidth(10f);
        mapView.getOverlays().add(routeOverlay);

        routeVisible = true;
        btnToggleRoute.setText(R.string.map_hide_route);

        if (points.size() >= 2) {
            BoundingBox box = BoundingBox.fromGeoPoints(points);
            mapView.zoomToBoundingBox(box.increaseByScale(1.3f), true);
        }

        mapView.invalidate();
    }

    @SuppressWarnings("MissingPermission")
    private void goToMyLocationAndZoom() {
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
                        IMapController c = mapView.getController();
                        c.setZoom(ZOOM_MY_LOCATION);
                        c.animateTo(myPoint);
                    } else {
                        Toast.makeText(this, R.string.location_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void goToSharedLocationAndZoom() {
        if (targetPoint == null) return;
        IMapController c = mapView.getController();
        c.setZoom(ZOOM_SHARED_LOCATION);
        c.animateTo(targetPoint);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
