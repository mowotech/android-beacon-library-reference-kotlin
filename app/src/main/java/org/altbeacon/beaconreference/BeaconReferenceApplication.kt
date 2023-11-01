package org.altbeacon.beaconreference

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BeaconReferenceApplication: Application() {
    // the region definition is a wildcard that matches all beacons regardless of identifiers.
    // if you only want to detect beacons with a specific UUID, change the id1 parameter to
    // a UUID like Identifier.parse("2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6")
    var region = Region("all-beacons", null, null, null)
    private var mediaPlayer: MediaPlayer? = null
    private var beaconRangeAgeMillis: Int = 10000
    private var beaconSignalStrengthThreshold: Int = -90

    private var foundLocation: Location? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()

        val beaconManager = BeaconManager.getInstanceForApplication(this)
        BeaconManager.setDebug(true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // By default the AndroidBeaconLibrary will only find AltBeacons.  If you wish to make it
        // find a different type of beacon, you must specify the byte layout for that beacon's
        // advertisement with a line like below.  The example shows how to find a beacon with the
        // same byte layout as AltBeacon but with a beaconTypeCode of 0xaabb.  To find the proper
        // layout expression for other beacon types, do a web search for "setBeaconLayout"
        // including the quotes.
        //
        //beaconManager.getBeaconParsers().clear();
        //beaconManager.getBeaconParsers().add(new BeaconParser().
        //        setBeaconLayout("m:0-1=4c00,i:2-24v,p:24-24"));


        // By default the AndroidBeaconLibrary will only find AltBeacons.  If you wish to make it
        // find a different type of beacon like Eddystone or iBeacon, you must specify the byte layout
        // for that beacon's advertisement with a line like below.
        //
        // If you don't care about AltBeacon, you can clear it from the defaults:
        //beaconManager.getBeaconParsers().clear()

        // Uncomment if you want to block the library from updating its distance model database
        //BeaconManager.setDistanceModelUpdateUrl("")

        // The example shows how to find iBeacon.
        val parser = BeaconParser().
        setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
        parser.setHardwareAssistManufacturerCodes(arrayOf(0x004c).toIntArray())
        beaconManager.beaconParsers.add(parser)

        // enabling debugging will send lots of verbose debug information from the library to Logcat
        // this is useful for troubleshooting problmes
        // BeaconManager.setDebug(true)


        // The BluetoothMedic code here, if included, will watch for problems with the bluetooth
        // stack and optionally:
        // - power cycle bluetooth to recover on bluetooth problems
        // - periodically do a proactive scan or transmission to verify the bluetooth stack is OK
        // BluetoothMedic.getInstance().legacyEnablePowerCycleOnFailures(this) // Android 4-12 only
        // BluetoothMedic.getInstance().enablePeriodicTests(this, BluetoothMedic.SCAN_TEST + BluetoothMedic.TRANSMIT_TEST)

        setupBeaconScanning()
    }

    fun setupBeaconScanning() {
        val beaconManager = BeaconManager.getInstanceForApplication(this)

        // By default, the library will scan in the background every 5 minutes on Android 4-7,
        // which will be limited to scan jobs scheduled every ~15 minutes on Android 8+
        // If you want more frequent scanning (requires a foreground service on Android 8+),
        // configure that here.
        // If you want to continuously range beacons in the background more often than every 15 mintues,
        // you can use the library's built-in foreground service to unlock this behavior on Android
        // 8+.   the method below shows how you set that up.
        try {
            setupForegroundService()
        }
        catch (e: SecurityException) {
            // On Android TIRAMUSU + this security exception will happen
            // if location permission has not been granted when we start
            // a foreground service.  In this case, wait to set this up
            // until after that permission is granted
            Log.d(TAG, "Not setting up foreground service scanning until location permission granted by user")
            return
        }
        //beaconManager.setEnableScheduledScanJobs(false);
        //beaconManager.setBackgroundBetweenScanPeriod(0);
        //beaconManager.setBackgroundScanPeriod(1100);

        beaconManager.backgroundBetweenScanPeriod = 15000
        beaconManager.backgroundScanPeriod = 1100
        beaconManager.foregroundBetweenScanPeriod = 15000
        beaconManager.foregroundScanPeriod = 1100

        // Ranging callbacks will drop out if no beacons are detected
        // Monitoring callbacks will be delayed by up to 25 minutes on region exit
        // beaconManager.setIntentScanningStrategyEnabled(true)

        // The code below will start "monitoring" for beacons matching the region definition at the top of this file
        beaconManager.startMonitoring(region)
        beaconManager.startRangingBeacons(region)
        // These two lines set up a Live Data observer so this Activity can get beacon data from the Application class
        val regionViewModel = BeaconManager.getInstanceForApplication(this).getRegionViewModel(region)
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        regionViewModel.regionState.observeForever( centralMonitoringObserver)
        // observer will be called each time a new list of beacons is ranged (typically ~1 second in the foreground)
        regionViewModel.rangedBeacons.observeForever( centralRangingObserver)

    }

    fun setupForegroundService() {
        val builder = Notification.Builder(this, "BeaconReferenceApp")
        builder.setSmallIcon(R.drawable.ic_stat_onesignal_default)
        builder.setContentTitle("Scanning for Beacons")
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)
        val channel =  NotificationChannel("beacon-ref-notification-id",
            "My Notification Name", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "My Notification Channel Description"
        val notificationManager =  getSystemService(
                Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel);
        builder.setChannelId(channel.getId());
        Log.d(TAG, "Calling enableForegroundServiceScanning")
        BeaconManager.getInstanceForApplication(this).enableForegroundServiceScanning(builder.build(), 456);
        Log.d(TAG, "Back from enableForegroundServiceScanning")
    }

    val centralMonitoringObserver = Observer<Int> { state ->
        if (state == MonitorNotifier.OUTSIDE) {
            Log.d(TAG, "outside beacon region: "+region)
        }
        else {
            Log.d(TAG, "inside beacon region: "+region)
            sendNotification()
        }
    }

    val centralRangingObserver = Observer<Collection<Beacon>> { beacons ->
        val rangeAgeMillis = System.currentTimeMillis() - (beacons.firstOrNull()?.lastCycleDetectionTimestamp ?: 0)
        if (rangeAgeMillis < beaconRangeAgeMillis) {
            Log.d(MainActivity.TAG, "Ranged: ${beacons.count()} beacons")
            for (beacon: Beacon in beacons) {
                Log.d(TAG, "$beacon about ${beacon.distance} meters away")

                if (beacon.rssi >= beaconSignalStrengthThreshold) {
                    Log.d(TAG, "Beacon is close enough to connect to: ${beacon.bluetoothAddress}")

                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { location: Location? ->
                            foundLocation = location
                            sendLocation(beacon.bluetoothAddress)
                        }
                }
            }
        }
        else {
            Log.d(TAG, "Ignoring stale ranged beacons from $rangeAgeMillis millis ago")
        }
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

    private fun convertSpeed(metersPerSecond: Double): Double {
        return metersPerSecond * 3.6
    }

    fun convertTimeInMillisToTimestamp(timeInMillis: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timeInMillis))
    }
    private fun sendLocation(deviceMac: String) {
        mediaPlayer = MediaPlayer.create(this, R.raw.send_location)
        mediaPlayer?.start()

        val volleyRequest = VolleyRequest(this)
        val url = "http://81.171.29.53/api/v1/app/telemetry/telemetry"

        val location = foundLocation ?: return

        val lat = location.latitude
        val long = location.longitude
        val timeInMillis = location.time
        val gpsSpeedMps = location.speed.toDouble()
        val speedKmph = convertSpeed(gpsSpeedMps)

        val formattedSpeed = "%.2f".format(speedKmph)
        val identification = getSharedPreferences(this, "identification", "") as String

        //Log.d(TAG, "requestLocation response: ${lat.toString()} | ${long.toString()}")

        val jsonBody = JSONObject()
        jsonBody.put("beacon_id", identification)
        jsonBody.put("latitude", lat.toString())
        jsonBody.put("longitude", long.toString())
        jsonBody.put("lat_lng_accuracy", location.accuracy.toString())
        jsonBody.put("speed", formattedSpeed)
        jsonBody.put("speed_accuracy", location.speedAccuracyMetersPerSecond.toString())
        jsonBody.put("bearing", location.bearing.toString())
        jsonBody.put("bearing_accuracy", location.bearingAccuracyDegrees.toString())
        jsonBody.put("time_point_picked", convertTimeInMillisToTimestamp(timeInMillis))
        jsonBody.put("macble", deviceMac)

        volleyRequest.sendPostRequest(url, jsonBody,
            { response ->
                Log.d(TAG, "requestLocation response: $response")
            },
            { error ->
                Log.d(TAG, "requestLocation error: $error")
            }
        )
    }

    private fun sendNotification() {
        val builder = NotificationCompat.Builder(this, "beacon-ref-notification-id")
            .setContentTitle("Beacon Reference Application")
            .setContentText("A beacon is nearby.")
            .setSmallIcon(R.drawable.ic_stat_onesignal_default)
        val stackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntent(Intent(this, MainActivity::class.java))
        val resultPendingIntent = stackBuilder.getPendingIntent(
            0,
            PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(resultPendingIntent)
        val channel =  NotificationChannel("beacon-ref-notification-id",
            "My Notification Name", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "My Notification Channel Description"
        val notificationManager =  getSystemService(
            Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel);
        builder.setChannelId(channel.getId());
        notificationManager.notify(1, builder.build())
    }

    companion object {
        val SHARED_PREFERENCES_KEY = "okoDriveSharedPreferences"
        val TAG = "BeaconReference"
    }
}