package net.osmand.plus.activities

import android.content.Intent
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.osmand.data.LatLon
import net.osmand.data.PointDescription
import net.osmand.plus.helpers.GoogleMapsUrlParser

object GoogleMapsIntentHandler {

    @JvmStatic
    fun handleIncomingMapIntent(activity: MapActivity, intent: Intent?) {
        if (intent == null) return

        val sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        activity.lifecycleScope.launch {
            val parser = GoogleMapsUrlParser(activity)
            val route = parser.parse(sharedText)

            val app = activity.app
            val targets = app.targetPointsHelper

            // Clear any existing search/settings UI
            activity.fragmentsHelper.dismissSettingsScreens()
            activity.fragmentsHelper.closeQuickSearch()
            activity.supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            activity.dashboard.hideDashboard()

            // 1. Resolve and set Destination (To)
            val destination = route.destination
            if (destination != null) {
                val latLon = LatLon(destination.latitude, destination.longitude)
                val destName = route.destinationRaw ?: "Shared Destination"
                val point = PointDescription(PointDescription.POINT_TYPE_LOCATION, destName)
                
                // 1. Set destination point
                targets.navigateToPoint(latLon, true, -1, point)

                // 2. Resolve and set Origin if present
                val origin = route.origin
                if (origin != null) {
                    val origLatLon = LatLon(origin.latitude, origin.longitude)
                    val origName = route.originRaw ?: "Shared Origin"
                    val origPoint = PointDescription(PointDescription.POINT_TYPE_LOCATION, origName)
                    targets.setStartPoint(origLatLon, true, origPoint)
                }

                // 3. Center map on destination
                val mapView = activity.getMapView()
                mapView.setLatLon(destination.latitude, destination.longitude)
                mapView.setIntZoom(16)

                // 4. Enter Route Planning / Navigation Preparation Mode
                activity.getMapActions().enterRoutePlanningMode(null, null)
            }
        }
    }
}
