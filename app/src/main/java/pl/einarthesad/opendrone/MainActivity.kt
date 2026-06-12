package pl.einarthesad.opendrone

import android.app.AlertDialog
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var recButton: Button
    private lateinit var shotButton: Button
    private lateinit var setButton: Button
    private lateinit var settings: AppSettings

    private var receiver: DroneVideoReceiver? = null
    private var recorder: RecorderController? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    private val decoding = AtomicBoolean(false)
    private val fallbackDroneIp = "192.168.4.153"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keepScreenOn()
        bindToWifiIfPossible()
        settings = AppSettings(this)

        buildUi()
        fullscreen()

        recorder = RecorderController(
            context = this,
            onStatus = { text ->
                runOnUiThread {
                    statusView.text = text
                }
            }
        )

        startReceiver()
    }

    private fun startReceiver() {
        receiver?.stop()

        val config = ConnectionConfig(
            droneIp = detectDroneIp(),
            localVideoPort = settings.localVideoPort,
            droneVideoPort = settings.droneVideoPort
        )

        statusView.text = "Connecting ${config.droneIp}:${config.droneVideoPort}"

        receiver = DroneVideoReceiver(
            config = config,
            onFrame = { jpg ->
                latestJpeg = jpg

                if (recorder?.wantsJpegFrames() == true) {
                    recorder?.addFrame(jpg, null)
                }

                decodeForPreview(jpg)
            },
            onStatus = { text ->
                runOnUiThread {
                    statusView.text = text
                }
            }
        )

        receiver?.start()
    }

    override fun onDestroy() {
        receiver?.stop()
        recorder?.discard()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            fullscreen()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        imageView = ImageView(this)
        imageView.setBackgroundColor(Color.BLACK)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.adjustViewBounds = false

        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.setBackgroundColor(0x66000000)
        statusView.textSize = 14f
        statusView.text = "Starting..."
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6))

        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        statusParams.gravity = Gravity.TOP or Gravity.START
        statusParams.setMargins(dp(12), dp(12), dp(12), dp(12))

        root.addView(statusView, statusParams)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER
        controls.setPadding(dp(8), dp(8), dp(8), dp(8))
        controls.setBackgroundColor(0x44000000)

        recButton = Button(this)
        recButton.text = "REC"
        recButton.textSize = 16f
        recButton.setOnClickListener {
            toggleRecording()
        }

        shotButton = Button(this)
        shotButton.text = "SHOT"
        shotButton.textSize = 16f
        shotButton.setOnClickListener {
            takeSnapshot()
        }

        val buttonParams = LinearLayout.LayoutParams(
            dp(116),
            dp(58)
        )
        buttonParams.setMargins(0, dp(6), 0, dp(6))

        controls.addView(recButton, buttonParams)
        controls.addView(shotButton, buttonParams)

        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        controlsParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        controlsParams.setMargins(dp(8), dp(8), dp(20), dp(8))

        root.addView(controls, controlsParams)

        val leftControls = LinearLayout(this)
        leftControls.orientation = LinearLayout.VERTICAL
        leftControls.gravity = Gravity.CENTER
        leftControls.setPadding(dp(8), dp(8), dp(8), dp(8))
        leftControls.setBackgroundColor(0x44000000)

        setButton = Button(this)
        setButton.text = "SET"
        setButton.textSize = 16f
        setButton.setOnClickListener {
            showSettingsDialog()
        }

        leftControls.addView(setButton, buttonParams)

        val leftParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        leftParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        leftParams.setMargins(dp(20), dp(8), dp(8), dp(8))

        root.addView(leftControls, leftParams)

        setContentView(root)
    }

    private fun toggleRecording() {
        val rec = recorder ?: return

        if (rec.isRecording) {
            recButton.text = "REC"
            rec.stopAndSave()
        } else {
            rec.start(
                firstJpeg = latestJpeg,
                firstBitmap = latestBitmap,
                videoFormat = settings.videoFormat
            )

            if (rec.isRecording) {
                recButton.text = "STOP"
                statusView.text = "Recording..."
            }
        }
    }

    private fun takeSnapshot() {
        val jpg = latestJpeg
        val bitmap = latestBitmap
        val format = settings.snapshotFormat

        if (jpg == null) {
            statusView.text = "No frame yet"
            return
        }

        if (format == SnapshotFormat.PNG && bitmap == null) {
            statusView.text = "No preview frame yet"
            return
        }

        thread(name = "SaveSnapshot") {
            try {
                val name = if (format == SnapshotFormat.JPEG) {
                    MediaStoreSaver.saveSnapshot(this, jpg)
                } else {
                    MediaStoreSaver.saveSnapshot(this, bitmap ?: return@thread, format)
                }

                runOnUiThread {
                    statusView.text = "Saved $name"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Snapshot error: ${e.message}"
                }
            }
        }
    }

    private fun decodeForPreview(jpg: ByteArray) {
        if (!decoding.compareAndSet(false, true)) {
            return
        }

        thread(name = "JpegDecode") {
            try {
                val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)

                if (bmp != null) {
                    latestBitmap = bmp

                    if (recorder?.wantsJpegFrames() != true) {
                        recorder?.addFrame(null, bmp)
                    }

                    runOnUiThread {
                        imageView.setImageBitmap(bmp)

                        val rec = recorder?.isRecording == true
                        val recText = if (rec) " REC" else ""
                        statusView.text = "${bmp.width}x${bmp.height}$recText"
                    }
                }
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun showSettingsDialog() {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(10), dp(20), 0)

        val localPortInput = portInput(settings.localVideoPort)
        val dronePortInput = portInput(settings.droneVideoPort)
        val snapshotGroup = radioGroup(
            listOf("JPEG" to SnapshotFormat.JPEG.name, "PNG" to SnapshotFormat.PNG.name),
            settings.snapshotFormat.name
        )
        val videoGroup = radioGroup(
            listOf("AVI" to VideoFormat.AVI.name, "MP4" to VideoFormat.MP4.name),
            settings.videoFormat.name
        )

        content.addView(label("Local receive port"))
        content.addView(localPortInput)
        content.addView(label("Drone command port"))
        content.addView(dronePortInput)
        content.addView(label("Snapshots"))
        content.addView(snapshotGroup)
        content.addView(label("Videos"))
        content.addView(videoGroup)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newLocalPort = parsePort(localPortInput.text.toString())
                val newDronePort = parsePort(dronePortInput.text.toString())

                if (newLocalPort == null || newDronePort == null) {
                    statusView.text = "Ports must be 1-65535"
                    return@setOnClickListener
                }

                val portsChanged = newLocalPort != settings.localVideoPort ||
                    newDronePort != settings.droneVideoPort

                settings.localVideoPort = newLocalPort
                settings.droneVideoPort = newDronePort
                settings.snapshotFormat = SnapshotFormat.valueOf(selectedTag(snapshotGroup))
                settings.videoFormat = VideoFormat.valueOf(selectedTag(videoGroup))

                if (portsChanged) {
                    startReceiver()
                } else {
                    statusView.text = "Settings saved"
                }

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun portInput(value: Int): EditText {
        val input = EditText(this)

        input.setText(value.toString())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.selectAll()

        return input
    }

    private fun label(text: String): TextView {
        val view = TextView(this)

        view.text = text
        view.setTextColor(Color.BLACK)
        view.textSize = 14f
        view.setPadding(0, dp(10), 0, 0)

        return view
    }

    private fun radioGroup(options: List<Pair<String, String>>, selected: String): RadioGroup {
        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL

        for ((text, value) in options) {
            val button = RadioButton(this)
            button.text = text
            button.tag = value
            button.id = View.generateViewId()
            group.addView(button)

            if (value == selected) {
                group.check(button.id)
            }
        }

        return group
    }

    private fun selectedTag(group: RadioGroup): String {
        val button = group.findViewById<RadioButton>(group.checkedRadioButtonId)

        return button.tag.toString()
    }

    private fun parsePort(text: String): Int? {
        val port = text.toIntOrNull() ?: return null

        if (port < 1 || port > 65535) return null

        return port
    }

    private fun detectDroneIp(): String {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)

            for (network: Network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue

                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

                val props = cm.getLinkProperties(network) ?: continue

                for (route in props.routes) {
                    val gateway = route.gateway

                    if (route.isDefaultRoute && gateway is Inet4Address) {
                        return gateway.hostAddress ?: fallbackDroneIp
                    }
                }

                for (route in props.routes) {
                    val gateway = route.gateway

                    if (gateway is Inet4Address) {
                        return gateway.hostAddress ?: fallbackDroneIp
                    }
                }
            }
        } catch (_: Exception) {
        }

        return fallbackDroneIp
    }

    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun fullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.decorView.windowInsetsController ?: return

            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun bindToWifiIfPossible() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)

            for (network: Network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(network)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
