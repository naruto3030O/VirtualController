package com.example.virtualcontroller

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity :
    AppCompatActivity(),
    SensorEventListener {

    private lateinit var prefs: SharedPreferences

    private companion object {
        const val PREFS_NAME = "virtual_controller_prefs"
        const val KEY_ROLL_OFFSET = "roll_offset"
        const val KEY_PITCH_OFFSET = "pitch_offset"
        const val KEY_CALIBRATED = "has_calibrated"
        const val KEY_IP = "pc_ip"
        const val KEY_XBOX_MODE = "xbox_mode"
        const val KEY_WHEEL_MODE = "wheel_mode"
    }

    // =========================================================
    // SENSOR / NETWORK
    // =========================================================

    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null

    private var socket: DatagramSocket? = null
    private var pcAddress: InetAddress? = null

    private val port = 5005

    @Volatile private var running = false

    // =========================================================
    // DRIVING STATE
    // =========================================================

    @Volatile private var steering = 0.0
    @Volatile private var gas = 0.0
    @Volatile private var reverse = 0.0

    private var rollOffset = 0.0
    private var pitchOffset = 0.0

    @Volatile private var lastRoll = 0.0
    @Volatile private var lastPitch = 0.0

    // =========================================================
    // XBOX STATE
    // =========================================================

    @Volatile private var isXboxMode = false

    @Volatile private var lx = 0.0
    @Volatile private var ly = 0.0
    @Volatile private var rx = 0.0
    @Volatile private var ry = 0.0
    @Volatile private var lt = 0.0
    @Volatile private var rt = 0.0

    private val pressedButtons =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // =========================================================
    // UI
    // =========================================================

    private lateinit var leftStick: JoystickView
    private lateinit var rightStick: JoystickView
    private lateinit var leftTrigger: TriggerView
    private lateinit var rightTrigger: TriggerView

    private lateinit var xboxPanel: View
    private lateinit var drivingPanel: View
    private lateinit var body: View

    private lateinit var settingsOverlay: View
    private lateinit var btnMenu: ImageButton

    private lateinit var steeringArc: SteeringArcView
    private lateinit var throttleBar: ThrottleBarView
    private lateinit var viewStick: ViewJoystickView

    private lateinit var btnHeadlight: ImageButton

    private lateinit var steeringWheel: SteeringWheelView
    private lateinit var ringSteering: android.widget.ImageView

    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView
    private lateinit var etIp: EditText

    private var settingsOpen = false

    /** Headlights are a toggle in the UI but a single press in the game. */
    private var headlightOn = false

    /**
     * When on, steering comes from the on-screen wheel and the roll axis of
     * the gyro is ignored. Pitch still drives gas and reverse either way.
     */
    @Volatile private var wheelMode = false

    private lateinit var switchMode: Switch

    private val uiHandler = Handler(Looper.getMainLooper())

    // =========================================================
    // ACTIVITY
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        rollOffset = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_ROLL_OFFSET, java.lang.Double.doubleToRawLongBits(0.0))
        )

        pitchOffset = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_PITCH_OFFSET, java.lang.Double.doubleToRawLongBits(0.0))
        )

        body = findViewById(R.id.body)
        xboxPanel = findViewById(R.id.xboxPanel)
        drivingPanel = findViewById(R.id.drivingPanel)

        settingsOverlay = findViewById(R.id.settingsOverlay)
        btnMenu = findViewById(R.id.btnMenu)

        tvStatus = findViewById(R.id.tvStatus)
        tvDebug = findViewById(R.id.tvDebug)
        etIp = findViewById(R.id.etIp)

        etIp.setText(prefs.getString(KEY_IP, ""))

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnCalibrate = findViewById<Button>(R.id.btnCalibrate)
        val btnCloseSettings = findViewById<ImageButton>(R.id.btnCloseSettings)
        switchMode = findViewById(R.id.switchMode)

        // Driving screen (included from activity_driving.xml)
        steeringArc = findViewById(R.id.steeringArc)
        throttleBar = findViewById(R.id.throttleBar)
        viewStick = findViewById(R.id.viewStick)

        btnHeadlight = findViewById(R.id.btnHeadlight)

        steeringWheel = findViewById(R.id.steeringWheel)
        ringSteering = findViewById(R.id.ringSteering)

        steeringWheel.onSteeringChanged = { value ->
            if (wheelMode) {
                steering = value
                steeringArc.steering = value
            }
        }

        val labelSteering = findViewById<TextView>(R.id.labelSteering)

        val toggleWheel = View.OnClickListener { applyWheelMode(!wheelMode) }

        ringSteering.setOnClickListener(toggleWheel)
        labelSteering.setOnClickListener(toggleWheel)

        val btnHandbrake = findViewById<ImageButton>(R.id.btnHandbrake)
        val btnHorn = findViewById<ImageButton>(R.id.btnHorn)
        val btnDrivingSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnXboxToggle = findViewById<ImageButton>(R.id.btnXboxToggle)

        // -----------------------------------------------------
        // Settings overlay
        // -----------------------------------------------------

        btnMenu.setOnClickListener { toggleSettings(!settingsOpen) }
        btnCloseSettings.setOnClickListener { toggleSettings(false) }

        // Tapping the dimmed backdrop closes too; the card itself is
        // clickable so taps inside it do not fall through to here.
        settingsOverlay.setOnClickListener { toggleSettings(false) }

        // -----------------------------------------------------
        // Sticks / triggers
        // -----------------------------------------------------

        leftStick = findViewById(R.id.leftStick)
        rightStick = findViewById(R.id.rightStick)

        leftStick.onMoveListener = { x, y -> lx = x; ly = y }
        rightStick.onMoveListener = { x, y -> rx = x; ry = y }

        leftTrigger = findViewById(R.id.leftTrigger)
        rightTrigger = findViewById(R.id.rightTrigger)

        leftTrigger.label = "LT"
        rightTrigger.label = "RT"

        leftTrigger.onValueChanged = { value -> lt = value }
        rightTrigger.onValueChanged = { value -> rt = value }

        // -----------------------------------------------------
        // Buttons
        // -----------------------------------------------------

        bindXboxButton(findViewById(R.id.btnDpadUp), "dpad_up")
        bindXboxButton(findViewById(R.id.btnDpadDown), "dpad_down")
        bindXboxButton(findViewById(R.id.btnDpadLeft), "dpad_left")
        bindXboxButton(findViewById(R.id.btnDpadRight), "dpad_right")

        bindXboxButton(findViewById(R.id.btnA), "a")
        bindXboxButton(findViewById(R.id.btnB), "b")
        bindXboxButton(findViewById(R.id.btnX), "x")
        bindXboxButton(findViewById(R.id.btnY), "y")

        bindXboxButton(findViewById(R.id.btnLB), "lb")
        bindXboxButton(findViewById(R.id.btnRB), "rb")

        bindXboxButton(findViewById(R.id.btnStart), "start")
        bindXboxButton(findViewById(R.id.btnBack), "back")

        bindXboxButton(findViewById(R.id.btnLS), "ls")
        bindXboxButton(findViewById(R.id.btnRS), "rs")

        // -----------------------------------------------------
        // Sensor
        // -----------------------------------------------------

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        gravitySensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // -----------------------------------------------------
        // Connect
        // -----------------------------------------------------

        btnConnect.setOnClickListener {

            val ip = etIp.text.toString().trim()

            hideKeyboard()

            thread {
                try {
                    val address = InetAddress.getByName(ip)
                    val newSocket = DatagramSocket()

                    pcAddress = address
                    socket?.close()
                    socket = newSocket

                    prefs.edit().putString(KEY_IP, ip).apply()

                    runOnUiThread {
                        tvStatus.text = "Connected to $ip"
                        tvStatus.setTextColor(0xFF66FF66.toInt())
                    }

                    if (!running) {
                        running = true
                        startSendLoop()
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        tvStatus.text = "Failed: ${e.message}"
                        tvStatus.setTextColor(0xFFFF6666.toInt())
                    }
                }
            }
        }

        // -----------------------------------------------------
        // Calibration (persisted, survives mode switch + restart)
        // -----------------------------------------------------

        btnCalibrate.setOnClickListener {

            rollOffset = lastRoll
            pitchOffset = lastPitch

            prefs.edit()
                .putLong(KEY_ROLL_OFFSET, java.lang.Double.doubleToRawLongBits(rollOffset))
                .putLong(KEY_PITCH_OFFSET, java.lang.Double.doubleToRawLongBits(pitchOffset))
                .putBoolean(KEY_CALIBRATED, true)
                .apply()

            Toast.makeText(this, "Centre saved", Toast.LENGTH_SHORT).show()
        }

        // -----------------------------------------------------
        // Driving controls
        // -----------------------------------------------------

        // Hold-style controls: LED lights while held.
        bindHoldButton(btnHandbrake, "handbrake",
            R.drawable.ic_handbrake, R.drawable.ic_handbrake_on)

        bindHoldButton(btnHorn, "horn",
            R.drawable.ic_horn, R.drawable.ic_horn_on)

        // Headlights: the game toggles on a press, so send one short pulse
        // and track the on/off state here for the icon.
        btnHeadlight.setOnClickListener {
            headlightOn = !headlightOn

            btnHeadlight.setImageResource(
                if (headlightOn) R.drawable.ic_headlight_on
                else R.drawable.ic_headlight
            )

            pulseButton("headlight")
        }

        applyWheelMode(prefs.getBoolean(KEY_WHEEL_MODE, false), persist = false)

        viewStick.onMoveListener = { x, y ->
            rx = x
            ry = y
        }

        btnDrivingSettings.setOnClickListener { toggleSettings(true) }

        btnXboxToggle.setOnClickListener {
            switchMode.isChecked = true
        }

        // -----------------------------------------------------
        // Mode
        // -----------------------------------------------------

        val savedMode = prefs.getBoolean(KEY_XBOX_MODE, false)

        switchMode.isChecked = savedMode
        applyMode(savedMode)

        switchMode.setOnCheckedChangeListener { _, isChecked ->
            applyMode(isChecked)
            prefs.edit().putBoolean(KEY_XBOX_MODE, isChecked).apply()
        }

        startDebugTicker()

        // Open settings on first launch so the IP field is reachable.
        if (prefs.getString(KEY_IP, "").isNullOrBlank()) {
            toggleSettings(true)
        }
    }

    // =========================================================
    // SETTINGS OVERLAY
    // =========================================================

    private fun toggleSettings(open: Boolean) {

        settingsOpen = open

        settingsOverlay.visibility = if (open) View.VISIBLE else View.GONE

        btnMenu.setImageResource(
            if (open) R.drawable.ic_close else R.drawable.ic_menu_lines
        )

        // Blur the controller behind the card. RenderEffect is API 31+;
        // on older devices the dark scrim alone does the job.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            body.setRenderEffect(
                if (open) {
                    RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
        }

        if (open) {
            // Nothing should stay latched while the pad is covered.
            resetXboxInputs()
            if (::viewStick.isInitialized) viewStick.reset()
            if (::steeringWheel.isInitialized) steeringWheel.reset()
        } else {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etIp.windowToken, 0)
        etIp.clearFocus()
    }

    override fun onBackPressed() {
        if (settingsOpen) {
            toggleSettings(false)
        } else {
            super.onBackPressed()
        }
    }

    // =========================================================
    // MODE
    // =========================================================

    private fun applyMode(xbox: Boolean) {

        isXboxMode = xbox

        xboxPanel.visibility = if (xbox) View.VISIBLE else View.GONE
        drivingPanel.visibility = if (xbox) View.GONE else View.VISIBLE

        if (xbox) {
            steering = 0.0
            gas = 0.0
            reverse = 0.0
        } else {
            resetXboxInputs()
        }

        // rollOffset / pitchOffset are deliberately untouched.
    }

    /**
     * Momentary press for controls the game treats as a toggle. Held long
     * enough to survive a few dropped packets at 60 Hz, then released.
     */
    private fun applyWheelMode(enabled: Boolean, persist: Boolean = true) {

        wheelMode = enabled

        steeringWheel.visibility = if (enabled) View.VISIBLE else View.GONE

        ringSteering.setImageResource(
            if (enabled) R.drawable.ic_toggle_ring_on
            else R.drawable.ic_toggle_ring
        )

        // Neither source should leave a value latched behind.
        steeringWheel.reset()

        steering = 0.0
        steeringArc.steering = 0.0

        if (persist) {
            prefs.edit().putBoolean(KEY_WHEEL_MODE, enabled).apply()
        }
    }

    private fun pulseButton(name: String) {
        pressedButtons.add(name)
        uiHandler.postDelayed({ pressedButtons.remove(name) }, 90)
    }

    /**
     * Press-and-hold control that swaps to its lit drawable while down.
     */
    private fun bindHoldButton(
        view: ImageButton,
        name: String,
        idleRes: Int,
        activeRes: Int
    ) {
        view.isSoundEffectsEnabled = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedButtons.add(name)
                    view.setImageResource(activeRes)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    pressedButtons.remove(name)
                    view.setImageResource(idleRes)
                }
            }
            true
        }
    }

    // =========================================================
    // LIFECYCLE
    // =========================================================

    override fun onResume() {
        super.onResume()

        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // =========================================================
    // SENSOR
    // =========================================================

    override fun onSensorChanged(event: SensorEvent) {

        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        val angle = Math.toDegrees(Math.atan2(gx.toDouble(), gy.toDouble()))

        lastRoll = angle
        lastPitch = gz.toDouble()

        if (isXboxMode) return

        // Roll drives steering only when the wheel is not up.
        if (!wheelMode) {
            var adjustedAngle = angle - rollOffset

            if (adjustedAngle > 180) adjustedAngle -= 360
            if (adjustedAngle < -180) adjustedAngle += 360

            steering = max(-1.0, min(1.0, -adjustedAngle / 45.0))
        }

        val tiltNorm = max(-1.0, min(1.0, (gz - pitchOffset) / 3.3))

        when {
            tiltNorm > 0.08 -> { gas = tiltNorm; reverse = 0.0 }
            tiltNorm < -0.08 -> { reverse = -tiltNorm; gas = 0.0 }
            else -> { gas = 0.0; reverse = 0.0 }
        }

        // Sensor callbacks arrive on the main thread, so the indicators can
        // be updated straight from here.
        if (!wheelMode) steeringArc.steering = steering

        throttleBar.gas = gas
        throttleBar.reverse = reverse
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =========================================================
    // BUTTONS
    // =========================================================

    private fun bindXboxButton(view: Button, name: String) {

        view.isSoundEffectsEnabled = false

        view.setOnTouchListener { v, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    pressedButtons.add(name)
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    pressedButtons.remove(name)
                    v.isPressed = false
                }
            }

            true
        }
    }

    private fun resetXboxInputs() {

        lx = 0.0; ly = 0.0
        rx = 0.0; ry = 0.0
        lt = 0.0; rt = 0.0

        pressedButtons.clear()

        if (::leftStick.isInitialized) leftStick.reset()
        if (::rightStick.isInitialized) rightStick.reset()
        if (::leftTrigger.isInitialized) leftTrigger.reset()
        if (::rightTrigger.isInitialized) rightTrigger.reset()
    }

    // =========================================================
    // DEBUG
    // =========================================================

    private val debugTicker = object : Runnable {
        override fun run() {

            tvDebug.text = if (isXboxMode) {

                val buttons = synchronized(pressedButtons) {
                    pressedButtons.joinToString(",")
                }

                "L %.2f,%.2f  R %.2f,%.2f  LT %.2f RT %.2f  [%s]"
                    .format(lx, ly, rx, ry, lt, rt, buttons)

            } else {

                val buttons = synchronized(pressedButtons) {
                    pressedButtons.joinToString(",")
                }

                "steer %.2f  gas %.2f  rev %.2f  look %.2f,%.2f  [%s]"
                    .format(steering, gas, reverse, rx, ry, buttons)
            }

            uiHandler.postDelayed(this, 100)
        }
    }

    private fun startDebugTicker() {
        uiHandler.postDelayed(debugTicker, 200)
    }

    // =========================================================
    // SEND LOOP
    // =========================================================

    private fun startSendLoop() {

        thread {

            while (running) {

                try {
                    val target = pcAddress
                    val sock = socket

                    if (target != null && sock != null) {

                        val json = JSONObject()

                        if (isXboxMode) {

                            json.put("mode", 1)
                            json.put("lx", lx)
                            json.put("ly", ly)
                            json.put("rx", rx)
                            json.put("ry", ry)
                            json.put("lt", lt)
                            json.put("rt", rt)

                            val buttonsArray = JSONArray()

                            synchronized(pressedButtons) {
                                for (button in pressedButtons) {
                                    buttonsArray.put(button)
                                }
                            }

                            json.put("buttons", buttonsArray)

                        } else {

                            json.put("mode", 0)
                            json.put("steering", steering)
                            json.put("gas", gas)
                            json.put("reverse", reverse)

                            // Look-around stick
                            json.put("rx", rx)
                            json.put("ry", ry)

                            // Driving buttons now ride the same named array
                            // that Xbox mode uses, so adding a control is a
                            // one-line change on each side.
                            val drivingButtons = JSONArray()

                            synchronized(pressedButtons) {
                                for (button in pressedButtons) {
                                    drivingButtons.put(button)
                                }
                            }

                            json.put("buttons", drivingButtons)
                        }

                        val data = json.toString().toByteArray()

                        sock.send(DatagramPacket(data, data.size, target, port))
                    }

                } catch (e: IOException) {
                    // Transient network error.
                }

                Thread.sleep(16)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        running = false
        uiHandler.removeCallbacksAndMessages(null)
        socket?.close()
    }
}