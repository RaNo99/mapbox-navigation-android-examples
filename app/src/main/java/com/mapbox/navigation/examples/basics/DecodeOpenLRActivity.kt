package com.mapbox.navigation.examples.basics

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Value
import com.mapbox.common.TileDataDomain
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.common.TileStoreOptions
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.GeoJson
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.ResourceOptions
import com.mapbox.maps.Style
import com.mapbox.maps.TilesetDescriptorOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.base.route.RouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.eh.MatchableOpenLr
import com.mapbox.navigation.base.trip.model.eh.OpenLRStandard.TOM_TOM
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.examples.R
import com.mapbox.navigation.examples.databinding.MapboxActivityOpenLrBinding
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLine
import openlr.binary.ByteArray
import openlr.binary.OpenLRBinaryDecoder
import openlr.binary.impl.LocationReferenceBinaryImpl

@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class DecodeOpenLRActivity : AppCompatActivity() {

    private lateinit var binding: MapboxActivityOpenLrBinding

    /**
     * Mapbox Maps entry point obtained from the [MapView].
     * You need to get a new reference to this object whenever the [MapView] is recreated.
     */
    private lateinit var mapboxMap: MapboxMap

    private lateinit var tileStore: TileStore
    private lateinit var offlineManager: OfflineManager

    /**
     * Mapbox Navigation entry point. There should only be one instance of this object for the app.
     * You can use [MapboxNavigationProvider] to help create and obtain that instance.
     */
    private lateinit var mapboxNavigation: MapboxNavigation

    /**
     * Generates updates for the [routeLineView] with the geometries and properties of the routes that should be drawn on the map.
     */
    private lateinit var routeLineApi: MapboxRouteLineApi

    /**
     * Draws route lines on the map based on the data from the [routeLineApi]
     */
    private lateinit var routeLineView: MapboxRouteLineView

    /**
     * Used to execute camera transitions based on the data generated by the [viewportDataSource].
     * This includes transitions from route overview to route following and continuously updating the camera as the location changes.
     */
    private lateinit var navigationCamera: NavigationCamera

    /**
     * Produces the camera frames based on the location and routing data for the [navigationCamera] to execute.
     */
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource

    /**
     * Gets notified whenever the tracked routes change.
     *
     * A change can mean:
     * - routes get changed with [MapboxNavigation.setRoutes]
     * - routes annotations get refreshed (for example, congestion annotation that indicate the live traffic along the route)
     * - driver got off route and a reroute was executed
     */
    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.routes.isNotEmpty()) {
            // generate route geometries and render them
            val routeLines = routeUpdateResult.routes.map { RouteLine(it, null) }
            routeLineApi.setRoutes(
                routeLines
            ) { value ->
                mapboxMap.getStyle()?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }

            // update the camera position to account for the new route
            viewportDataSource.onRouteChanged(routeUpdateResult.routes.first())
            viewportDataSource.evaluate()
        } else {
            // remove the route line and route arrow from the map
            val style = mapboxMap.getStyle()
            if (style != null) {
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(
                        style,
                        value
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MapboxActivityOpenLrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val mapboxToken = getString(R.string.mapbox_access_token)

        tileStore = TileStore.create().apply {
            setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.MAPS,
                Value(mapboxToken)
            )
            setOption(
                TileStoreOptions.MAPBOX_ACCESS_TOKEN,
                TileDataDomain.NAVIGATION,
                Value(mapboxToken)
            )
        }
        val mapboxMapOptions = MapInitOptions(this)
        val resourceOptions = ResourceOptions.Builder()
            .accessToken(mapboxToken)
            .tileStore(tileStore)
            .build()
        mapboxMapOptions.resourceOptions = resourceOptions
        val mapView = MapView(this, mapboxMapOptions)
        binding.mapViewContainer.addView(mapView)
        mapboxMap = mapView.getMapboxMap()
        offlineManager = OfflineManager(resourceOptions)

        // initialize Mapbox Navigation
        mapboxNavigation = MapboxNavigationProvider.create(
            NavigationOptions.Builder(this.applicationContext)
                .accessToken(mapboxToken)
                .routingTilesOptions(RoutingTilesOptions.Builder().tileStore(tileStore).build())
                .build()
        )

        // initialize route line, the withRouteLineBelowLayerId is specified to place
        // the route line below road labels layer on the map
        // the value of this option will depend on the style that you are using
        // and under which layer the route line should be placed on the map layers stack
        val mapboxRouteLineOptions = MapboxRouteLineOptions.Builder(this)
            .withRouteLineBelowLayerId("road-label")
            .build()
        routeLineApi = MapboxRouteLineApi(mapboxRouteLineOptions)
        routeLineView = MapboxRouteLineView(mapboxRouteLineOptions)

        // initialize Navigation Camera
        viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
        navigationCamera = NavigationCamera(
            mapboxMap,
            mapView.camera,
            viewportDataSource
        )

        // load map style
        mapboxMap.loadStyleUri(Style.MAPBOX_STREETS)

        binding.decodeButton.setOnClickListener {
            binding.decodeButton.hide()
            decodeRoute()
        }
    }

    override fun onStart() {
        super.onStart()
        mapboxNavigation.registerRoutesObserver(routesObserver)
    }

    override fun onStop() {
        super.onStop()
        // unregister event listeners to prevent leaks or unnecessary resource consumption
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapboxNavigation.onDestroy()
    }

    private fun decodeRoute() {
        val openlr =
            "C/uS0iXwhRpzC/73/cAbbwQAOv86G2kAACD/+htpBAFe/8UbaQYCNf+xG2kFAYH/YxttKfQX/SgbdTzsC/9FG3ol+tAGJBtvJwA="

        buildViaMapMatching(openlr)

        buildUsingLrps(openlr)
    }

    private fun buildUsingLrps(openlrText: String) {
        val binaryDecoder = OpenLRBinaryDecoder()
        val byteArray = openlr.binary.ByteArray(Base64.decode(openlrText, Base64.DEFAULT))
        val locationReferenceBinary = LocationReferenceBinaryImpl("", byteArray)
        val rawLocationReference = binaryDecoder.decodeData(locationReferenceBinary)

        val referencePoints = rawLocationReference.locationReferencePoints
        val points = referencePoints.map {
            Point.fromLngLat(it.longitudeDeg, it.latitudeDeg)
        }
        val bearings = referencePoints.map {
            Bearing.builder().angle(it.bearing).degrees(10.0).build()
        }

        mapboxNavigation.requestRoutes(
            RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(this)
                .coordinatesList(points)
                .waypointIndicesList(listOf(0, points.size - 1))
                .bearingsList(bearings)
                .build(),
            object : RouterCallback {
                override fun onRoutesReady(
                    routes: List<DirectionsRoute>,
                    routerOrigin: RouterOrigin
                ) {
                    mapboxNavigation.setRoutes(routes)
                    navigationCamera.requestNavigationCameraToOverview()
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    // no impl
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    // no impl
                }
            }
        )
    }

    private fun buildViaMapMatching(openlr: String) {
        val navigationDescription = mapboxNavigation.tilesetDescriptorFactory.getLatest()
        tileStore.loadTileRegion(
            "dublin",
            TileRegionLoadOptions.Builder()
                .geometry(FeatureCollection.fromJson(DUBLIN).features()!![0].geometry())
                .descriptors(listOf(navigationDescription))
                .build(),
            {
                Log.d("Tile", "Progress: $it")
            }
        ) {

            if (it.isError) {
                Log.d("Tile", "error loading tiles: ${it.error}")
                return@loadTileRegion
            }

            mapboxNavigation.roadObjectMatcher.apply {
                registerRoadObjectMatcherObserver { result ->
                    if (result.isValue) {
                        val roadObject = result.value!!
                    } else {
                        Log.e("DecodeOpenLRActivity", "can't match, ${result.error}")
                    }
                }
                matchOpenLRObjects(
                    listOf(
                        MatchableOpenLr("open-lr-example", openlr, TOM_TOM)
                    ),
                    useOnlyPreloadedTiles = true
                )
            }
        }
    }
}


private val DUBLIN = "{\n" +
        "  \"type\": \"FeatureCollection\",\n" +
        "  \"features\": [\n" +
        "    {\n" +
        "      \"type\": \"Feature\",\n" +
        "      \"properties\": {},\n" +
        "      \"geometry\": {\n" +
        "        \"type\": \"Polygon\",\n" +
        "        \"coordinates\": [\n" +
        "          [\n" +
        "            [\n" +
        "              -6.756591796875,\n" +
        "              53.07092720421678\n" +
        "            ],\n" +
        "            [\n" +
        "              -5.6085205078125,\n" +
        "              53.07092720421678\n" +
        "            ],\n" +
        "            [\n" +
        "              -5.6085205078125,\n" +
        "              53.60391440806693\n" +
        "            ],\n" +
        "            [\n" +
        "              -6.756591796875,\n" +
        "              53.60391440806693\n" +
        "            ],\n" +
        "            [\n" +
        "              -6.756591796875,\n" +
        "              53.07092720421678\n" +
        "            ]\n" +
        "          ]\n" +
        "        ]\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}"