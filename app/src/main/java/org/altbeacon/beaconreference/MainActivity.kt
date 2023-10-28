package org.altbeacon.beaconreference

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.MonitorNotifier
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.altbeacon.beacon.permissions.BeaconScanPermissionsActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    lateinit var beaconListView: ListView
    lateinit var beaconCountTextView: TextView
    lateinit var monitoringButton: Button
    lateinit var rangingButton: Button
    lateinit var beaconReferenceApplication: BeaconReferenceApplication
    var alertDialog: AlertDialog? = null
    val context: Context = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        beaconReferenceApplication = application as BeaconReferenceApplication

        // Set up a Live Data observer for beacon data
        val regionViewModel = BeaconManager.getInstanceForApplication(this).getRegionViewModel(beaconReferenceApplication.region)
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        regionViewModel.regionState.observe(this, monitoringObserver)
        // observer will be called each time a new list of beacons is ranged (typically ~1 second in the foreground)
        regionViewModel.rangedBeacons.observe(this, rangingObserver)
        rangingButton = findViewById<Button>(R.id.rangingButton)
        monitoringButton = findViewById<Button>(R.id.monitoringButton)
        beaconListView = findViewById<ListView>(R.id.beaconList)
        beaconCountTextView = findViewById<TextView>(R.id.beaconCount)
        beaconCountTextView.text = "No beacons detected"
        beaconListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, arrayOf("--"))

        val scanQRButton: Button = findViewById(R.id.scanQR)
        scanQRButton.setOnClickListener {
            requestDriver()
        }

        val editTextIdentification: TextView = findViewById(R.id.driverIdentification)
        editTextIdentification.text = getSharedPreferences(context, "driverName", "-").toString()
    }

    private fun requestDriver() {
        val volleyRequest = VolleyRequest(this)
        val uuid = "631eaeaf-d0b3-11ea-80e9-005056b06a89"
        val url = "http://81.171.29.53/api/v1/app/user-profile/check-uuid"

        val jsonBody = JSONObject()
        jsonBody.put("uuid", uuid)

        volleyRequest.sendPostRequest(url, jsonBody,
            { response ->
                Log.d(TAG, "requestDriver response: $response")
                parseDriverIdentification(uuid, response)
            },
            { error ->
                Log.d(TAG, "requestDriver error: $error")
            }
        )
    }

    private fun parseDriverIdentification(uuid: String, response: JSONObject) {
        try {
            val driverObject = response.getJSONObject("driver")
            val firstName = driverObject.getString("first_name")
            val lastName = driverObject.getString("last_name")
            val middleName = driverObject.getString("middle_name")
            val fullName = "$lastName $firstName $middleName"

            setSharedPreferences(context, "identification", uuid)
            setSharedPreferences(context, "driverName", fullName)

            val editTextIdentification: TextView = findViewById(R.id.driverIdentification)
            editTextIdentification.text = fullName

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        super.onPause()
    }
    override fun onResume() {
        Log.d(TAG, "onResume")
        super.onResume()
        // You MUST make sure the following dynamic permissions are granted by the user to detect beacons
        //
        //    Manifest.permission.BLUETOOTH_SCAN
        //    Manifest.permission.BLUETOOTH_CONNECT
        //    Manifest.permission.ACCESS_FINE_LOCATION
        //    Manifest.permission.ACCESS_BACKGROUND_LOCATION // only needed to detect in background
        //
        // The code needed to get these permissions has become increasingly complex, so it is in
        // its own file so as not to clutter this file focussed on how to use the library.

        if (!BeaconScanPermissionsActivity.allPermissionsGranted(this,
                true)) {
            val intent = Intent(this, BeaconScanPermissionsActivity::class.java)
            intent.putExtra("backgroundAccessRequested", true)
            startActivity(intent)
        }
        else {
            // All permissions are granted now.  In the case where we are configured
            // to use a foreground service, we will not have been able to start scanning until
            // after permissions are graned.  So we will do so here.
            if (BeaconManager.getInstanceForApplication(this).monitoredRegions.size == 0) {
                (application as BeaconReferenceApplication).setupBeaconScanning()
            }
        }
    }

    val monitoringObserver = Observer<Int> { state ->
        var dialogTitle = "Beacons detected"
        var dialogMessage = "didEnterRegionEvent has fired"
        var stateString = "inside"
        if (state == MonitorNotifier.OUTSIDE) {
            dialogTitle = "No beacons detected"
            dialogMessage = "didExitRegionEvent has fired"
            stateString == "outside"
            beaconCountTextView.text = "Outside of the beacon region -- no beacons detected"
            beaconListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, arrayOf("--"))
        }
        else {
            beaconCountTextView.text = "Inside the beacon region."
        }
        Log.d(TAG, "monitoring state changed to : $stateString")
        val builder =
            AlertDialog.Builder(this)
        builder.setTitle(dialogTitle)
        builder.setMessage(dialogMessage)
        builder.setPositiveButton(android.R.string.ok, null)

        //alertDialog?.dismiss()
        //alertDialog = builder.create()
        //alertDialog?.show()
    }

    val rangingObserver = Observer<Collection<Beacon>> { beacons ->
        Log.d(TAG, "Ranged: ${beacons.count()} beacons")
        if (BeaconManager.getInstanceForApplication(this).rangedRegions.size > 0) {
            beaconCountTextView.text = "Ranging enabled: ${beacons.count()} beacon(s) detected"
            beaconListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                beacons
                    .sortedBy { it.distance }
                    .map { "${it.id1}\nid2: ${it.id2} id3:  rssi: ${it.rssi}\nest. distance: ${it.distance} m" }.toTypedArray())
        }
    }

    fun rangingButtonTapped(view: View) {
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        if (beaconManager.rangedRegions.size == 0) {
            beaconManager.startRangingBeacons(beaconReferenceApplication.region)
            rangingButton.text = "Stop Ranging"
            beaconCountTextView.text = "Ranging enabled -- awaiting first callback"
        }
        else {
            beaconManager.stopRangingBeacons(beaconReferenceApplication.region)
            rangingButton.text = "Start Ranging"
            beaconCountTextView.text = "Ranging disabled -- no beacons detected"
            beaconListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, arrayOf("--"))
        }
    }

    fun monitoringButtonTapped(view: View) {
        var dialogTitle = ""
        var dialogMessage = ""
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        if (beaconManager.monitoredRegions.size == 0) {
            beaconManager.startMonitoring(beaconReferenceApplication.region)
            dialogTitle = "Beacon monitoring started."
            dialogMessage = "You will see a dialog if a beacon is detected, and another if beacons then stop being detected."
            monitoringButton.text = "Stop Monitoring"

        }
        else {
            beaconManager.stopMonitoring(beaconReferenceApplication.region)
            dialogTitle = "Beacon monitoring stopped."
            dialogMessage = "You will no longer see dialogs when beacons start/stop being detected."
            monitoringButton.text = "Start Monitoring"
        }
        val builder =
            AlertDialog.Builder(this)
        builder.setTitle(dialogTitle)
        builder.setMessage(dialogMessage)
        builder.setPositiveButton(android.R.string.ok, null)

        //alertDialog?.dismiss()
        //alertDialog = builder.create()
        //alertDialog?.show()

    }

    private fun getSharedPreferences(context: Context, key: String, defaultValue: Any): Any? {
        val sharedPreferences = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

        when (defaultValue) {
            is String -> return sharedPreferences.getString(key, defaultValue)
            is Int -> return sharedPreferences.getInt(key, defaultValue)
            is Boolean -> return sharedPreferences.getBoolean(key, defaultValue)
        }

        return null
    }

    private fun setSharedPreferences(context: Context, key: String, value: Any) {
        val sharedPreferences = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }

        editor.apply()
    }

    companion object {
        val TAG = "MainActivity"
        val SHARED_PREFERENCES_KEY = "okoDriveSharedPreferences"
        val PERMISSION_REQUEST_BACKGROUND_LOCATION = 0
        val PERMISSION_REQUEST_BLUETOOTH_SCAN = 1
        val PERMISSION_REQUEST_BLUETOOTH_CONNECT = 2
        val PERMISSION_REQUEST_FINE_LOCATION = 3
    }

}
