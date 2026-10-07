package social.hotmess.android.ui.components

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Venue

/** OpenFreeMap's free vector style: no API key needed. */
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE = "venues"
private const val LAYER = "venue-pins"

/**
 * A map of the venues with a pin each, framed to fit them. Tapping a pin opens the venue;
 * tapping anywhere else calls [onMapTap] when there is one.
 */
@Composable
fun VenueMap(
    venues: List<Venue>,
    onVenue: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMapTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accent = tokens.accent.toArgb()
    val stroke = tokens.surfaceRaised.toArgb()
    val currentOnVenue = rememberUpdatedState(onVenue)
    val currentOnMapTap = rememberUpdatedState(onMapTap)
    val pins = venues.mapNotNull { venue -> venue.coordinate?.let { venue.id to LatLng(it.latitude, it.longitude) } }

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { view ->
        view.getMapAsync { map ->
            map.uiSettings.setRotateGesturesEnabled(false)
            val features = pins.map { (id, point) ->
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).also { it.addStringProperty("id", id) }
            }
            val collection = FeatureCollection.fromFeatures(features)
            val style = map.style
            if (style == null || !style.isFullyLoaded) {
                map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { loaded ->
                    loaded.addSource(GeoJsonSource(SOURCE, collection))
                    loaded.addLayer(
                        CircleLayer(LAYER, SOURCE).withProperties(
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleColor(accent),
                            PropertyFactory.circleStrokeColor(stroke),
                            PropertyFactory.circleStrokeWidth(2f),
                        ),
                    )
                    map.addOnMapClickListener { latLng ->
                        val screen: PointF = map.projection.toScreenLocation(latLng)
                        val id = map.queryRenderedFeatures(screen, LAYER).firstOrNull()?.getStringProperty("id")
                        val onTap = currentOnMapTap.value
                        when {
                            id != null -> currentOnVenue.value(id)
                            onTap != null -> onTap()
                        }
                        id != null || onTap != null
                    }
                    frame(map, pins.map { it.second })
                }
            } else {
                style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(collection)
                frame(map, pins.map { it.second })
            }
        }
    }
}

private fun frame(map: MapLibreMap, points: List<LatLng>) {
    when (points.size) {
        0 -> Unit
        1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14.0))
        else -> map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 64))
    }
}
