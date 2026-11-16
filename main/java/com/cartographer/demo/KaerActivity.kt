package com.cartographer.demo

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

open class KaerActivity : AppCompatActivity(), SensorEventListener {

    private companion object {
        // Texture generation includes probability conversion and gzip work in
        // native code.  Do not let UI refresh steal CPU/lock time from SLAM.
        private const val SUBMAP_FETCH_INTERVAL_MS = 750L
        private const val FULL_SUBMAP_FETCH_INTERVAL_MS = 30_000L
        private const val DEBUG_TEXT_UPDATE_INTERVAL_MS = 1_000L
        private const val RELOCALIZATION_STATUS_INTERVAL_MS = 1_000L
        private const val RELOCALIZATION_TIMEOUT_MS = 90_000L
        private const val RELOCALIZATION_TIMEOUT_NODE_COUNT = 30
        private const val LIVE_SCAN_STALE_TIMEOUT_NS = 1_000_000_000L
        private const val DEVICE_TO_ROBOT_HEADING_OFFSET_RAD = 0f
        private const val ONLINE_MAGNETIC_RESULT_INTERVAL_MS = 500L
        private const val RSSI_SAMPLE_INTERVAL_MS = 1_000L

        // 新雷达（8003 SLAM）USB VID/PID
        private const val SLAM_VID = LidarUsbDrivers.SLAM_VID
        private const val SLAM_PID = LidarUsbDrivers.SLAM_PID
    }

    private enum class MappingMode {
        NORMAL_MAPPING,
        CONTINUE_MAPPING
    }

    private enum class RelocalizationPhase {
        NOT_REQUIRED,
        WAITING,
        RELOCALIZED,
        TIMEOUT
    }

    private lateinit var tvPose: TextView
    private lateinit var tvDebugData: TextView // ✅ 新加：显示真实数据
    private lateinit var tvMapMeasurement: TextView
    private lateinit var mapView: LidarMapView
    private lateinit var floorPlanView: ZoomableImageView
    private lateinit var btnStartMapping: Button
    private lateinit var btnFinishMapping: Button
    private lateinit var btnContinueMapping: Button
    private lateinit var btnCancelContinueMapping: Button
    private lateinit var btnSaveMap: Button
    private lateinit var btnSaveFloorPlan: Button
    private lateinit var btnMapNorth: ImageButton
    private lateinit var checkPointCloud: CheckBox
    private lateinit var checkFloorPlanOverlay: CheckBox
    private lateinit var checkHeatMapOverlay: CheckBox
    private lateinit var checkTrajectoryOverlay: CheckBox
    private lateinit var checkMarkersOverlay: CheckBox
    private lateinit var btnAddTestPoint: Button
    private var markerSerial = 0
    private val carto = CartographerNative()
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private var uiUpdatesStopped = false
    private val displayWorker = LatestValueWorker(SlamDisplaySnapshot(), "SlamDisplayReader") {
        Log.e("CartographerJNI", "读取显示快照失败", it)
    }
    private val magneticFitWorker = LatestValueWorker(
        OnlineMagneticNorthCalibrator.Result(), "MagneticFit"
    ) { Log.e("MagneticHeading", "后台地磁拟合失败", it) }
    @Volatile private var mappingUiGeneration = 0L
    private var lastMagneticSampleRequestMs = 0L
    private var lastPoseTextUpdateMs = 0L
    private val floorPlanNative = FloorPlanNative()
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val magneticHeadingProvider by lazy { MagneticHeadingProvider(sensorManager) }
    private val onlineMagneticNorthCalibrator = OnlineMagneticNorthCalibrator()
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private lateinit var lidarReader: LidarScanParserRouter
    private lateinit var switchAaProtocol: Switch
    private lateinit var switchLidarOnly: Switch
    @Volatile private var activeSlamInputMode: SlamInputMode? = null
    private val selectedSlamInputMode: SlamInputMode
        get() = if (lidarReader.protocol == LidarScanParserRouter.Protocol.KAER_PROTOCOL &&
            switchLidarOnly.isChecked) SlamInputMode.LIDAR_ONLY else SlamInputMode.LIDAR_IMU
    private val PREFS_RADAR = "radar_protocol"
    private val KEY_RADAR_PROTOCOL = "protocol"

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    @Volatile private var usbSerialPort: UsbSerialPort? = null
    @Volatile private var usbInputQueue: TimedUsbInputQueue? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val ACTION_USB_PERMISSION = "com.cartographer.demo.USB_PERMISSION"

    @Volatile private var lidarConnectionStatus = "未连接"
    private var usbPermissionDevice: UsbDevice? = null

    private var isSensorRunning = false
    @Volatile private var isMappingRunning = false
    private var isMappingFinished = false
    @Volatile private var isFinishingMapping = false
    private var mappingMode = MappingMode.NORMAL_MAPPING
    @Volatile private var relocalizationPhase = RelocalizationPhase.NOT_REQUIRED
    @Volatile private var relocalizationStatus = RelocalizationStatus()
    private var relocalizationStartedMs = 0L

    private var usesBoardImu = false
    private val radarStartupHandler = Handler(Looper.getMainLooper())
    @Volatile private var radarStartup: Kld42StartupController? = null
    private var imuSourceDescription = "未启动"
    private var imuCount = 0
    private var lidarCount = 0L
    private val acc = FloatArray(3)
    private val gyro = FloatArray(3)
    private var lastImuTimeNs: Long = 0
    private var lastImuSendNs = 0L
    private var lastAccelerationSampleNs = 0L
    private var hasAccelerationSample = false
    private var hasGyroscopeSample = false
    private val IMU_MIN_INTERVAL_NS = 10 * 1000_000L
    private val IMU_MAX_PAIR_AGE_NS = 25 * 1000_000L

    /**
     * 应用沙盒外部目录：/sdcard/Android/data/<packageName>/files。
     * 无需存储权限即可读写，文件管理器可直接查看。
     */
    private val externalAppFilesDir: File
        get() = getExternalFilesDir(null) ?: filesDir


    // ✅ 测试用：保存最新雷达数据
    private var latestLidarPoints = 0
    private var latestLidarDist = 0f
    private var latestLidarAngle = 0f
    private var lastRenderedScanNs = 0L
    private var lastDebugTextUpdateMs = 0L
    private var lastSubmapFetchMs = 0L
    @Volatile private var lastFetchedSubmapNodeCount = -1L
    @Volatile private var lastFullSubmapFetchMs = 0L
    private var lastRelocalizationStatusFetchMs = 0L
    private var latestSubmapRefreshIntervalMs = 0L
    private var lastObservedRangeFrames = 0L
    private var lastObservedLocalSlamResults = 0L
    private var lastObservedInsertedNodes = 0L
    private var lastRangeFrameChangeMs = 0L
    private var lastLocalSlamChangeMs = 0L
    private var lastInsertedNodeChangeMs = 0L
    private var latestRangeFrameIntervalMs = 0L
    private var latestLocalSlamIntervalMs = 0L
    private var latestInsertedNodeIntervalMs = 0L
    @Volatile private var isSubmapFetchRunning = false
    @Volatile private var isRelocalizationStatusFetchRunning = false
    @Volatile private var latestSubmapCount = 0
    private var currentFloorPlanResult: File? = null
    private var currentFloorPlanDimensions: FloorPlanDimensions? = null
    private var currentFloorPlanCoveragePercent: Float? = null
    private var currentFloorPlanWarning: String? = null
    private var floorPlanLayers: FloorPlanLayers? = null
    private var floorPlanDisplayBitmap: Bitmap? = null
    private var pendingLegacyFloorPlanSave: File? = null
    private var pendingLegacyMapExport: File? = null
    private val measurementTextures = LinkedHashMap<String, SubmapTexture>()
    private var continueBaseSubmaps: List<SubmapTexture> = emptyList()
    private var finalizedSubmaps: List<SubmapTexture> = emptyList()
    private var currentMapMeasurement: MapMeasurement? = null
    private var measurementGeneration = 0L
    private var isMeasurementCalculationRunning = false
    private var measurementCalculationDirty = false
    @Volatile private var pendingNorthAlignment: MapNorthAlignment? = null
    @Volatile private var loadedMapNorthAlignment: MapNorthAlignment? = null
    private var savedMapNorthAlignment: MapNorthAlignment? = null
    private var continueSourceMapFile: File? = null
    private var savedMapFile: File? = null
    @Volatile private var finishedMapPose: Pose2D? = null
    @Volatile private var latestOnlineMagneticResult = OnlineMagneticNorthCalibrator.Result()
    private var lastOnlineMagneticResultMs = 0L
    private val rssiSamples = ArrayList<RssiSample>()
    private var lastRssiSampleMs = 0L

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { !it }) {
            Toast.makeText(
                this,
                "未授予 Wi-Fi 权限，热力图层将不可用",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val source = pendingLegacyFloorPlanSave
        val mapSource = pendingLegacyMapExport
        pendingLegacyFloorPlanSave = null
        pendingLegacyMapExport = null
        if (granted) {
            if (mapSource?.exists() == true) {
                Thread {
                    try {
                        val location = writeMapToDownloads(mapSource)
                        runOnUiThread { show("地图已保存：$location") }
                    } catch (e: Exception) {
                        runOnUiThread { show("地图已保存在应用内，但导出失败：${e.message}") }
                    }
                }.start()
            }
            if (source?.exists() == true) saveFloorPlanFile(source)
        } else {
            show("未获得存储权限，无法保存到 Downloads/CartographerMaps")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPose = findViewById(R.id.tv_pose)
        tvDebugData = findViewById(R.id.tv_debug_data) // ✅ 绑定显示控件
        tvMapMeasurement = findViewById(R.id.tv_map_measurement)
        mapView = findViewById(R.id.map_view)
        floorPlanView = findViewById(R.id.iv_floor_plan)
        btnStartMapping = findViewById(R.id.btn_start_mapping)
        btnFinishMapping = findViewById(R.id.btn_finish_mapping)
        btnContinueMapping = findViewById(R.id.btn_continue_mapping)
        btnCancelContinueMapping = findViewById(R.id.btn_cancel_continue_mapping)
        btnSaveMap = findViewById(R.id.btn_save_map)
        btnSaveFloorPlan = findViewById(R.id.btn_save_floor_plan)
        btnMapNorth = findViewById(R.id.btn_map_north)
        checkPointCloud = findViewById(R.id.check_point_cloud)
        checkFloorPlanOverlay = findViewById(R.id.check_floor_plan_overlay)
        checkHeatMapOverlay = findViewById(R.id.check_heat_map_overlay)
        checkTrajectoryOverlay = findViewById(R.id.check_trajectory_overlay)
        checkMarkersOverlay = findViewById(R.id.check_markers_overlay)
        btnAddTestPoint = findViewById(R.id.btn_add_test_point)

        lidarReader = LidarScanParserRouter(carto)
        // 雷达协议切换开关：默认旧雷达（裸 AA55），打开 Switch 使用新雷达（A 类协议）
        switchAaProtocol = findViewById(R.id.switch_aa_protocol)
        val radarProtocol = getSharedPreferences(PREFS_RADAR, Context.MODE_PRIVATE)
            .getString(KEY_RADAR_PROTOCOL, "legacy")
        lidarReader.protocol = if (radarProtocol == "aa_protocol")
            LidarScanParserRouter.Protocol.KAER_PROTOCOL
        else
            LidarScanParserRouter.Protocol.LEGACY_AA55
        switchAaProtocol.isChecked = radarProtocol == "aa_protocol"
        switchLidarOnly = findViewById(R.id.switch_lidar_only)
        switchLidarOnly.isChecked = getSharedPreferences(PREFS_RADAR, Context.MODE_PRIVATE)
            .getBoolean("new_radar_lidar_only", true)
        switchLidarOnly.visibility = if (switchAaProtocol.isChecked) View.VISIBLE else View.GONE
        switchLidarOnly.setOnCheckedChangeListener { _, checked ->
            if (isMappingRunning || isFinishingMapping) {
                val expected = activeSlamInputMode == SlamInputMode.LIDAR_ONLY
                if (checked != expected) {
                    switchLidarOnly.isChecked = expected
                    show("请先结束建图，再切换建图模式")
                }
                return@setOnCheckedChangeListener
            }
            getSharedPreferences(PREFS_RADAR, Context.MODE_PRIVATE).edit()
                .putBoolean("new_radar_lidar_only", checked).apply()
        }
        switchAaProtocol.setOnCheckedChangeListener { _, checked ->
            val protocol = if (checked)
                LidarScanParserRouter.Protocol.KAER_PROTOCOL
            else
                LidarScanParserRouter.Protocol.LEGACY_AA55
            if (protocol == lidarReader.protocol) return@setOnCheckedChangeListener
            if (isMappingRunning || isFinishingMapping) {
                switchAaProtocol.isChecked = !checked
                show("请先结束建图，再切换雷达协议")
                return@setOnCheckedChangeListener
            }
            closeLidar()
            lidarReader.protocol = protocol
            switchLidarOnly.visibility = if (checked) View.VISIBLE else View.GONE
            getSharedPreferences(PREFS_RADAR, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RADAR_PROTOCOL, if (checked) "aa_protocol" else "legacy")
                .apply()
            if (isSensorRunning) startLidar()
        }

        findViewById<Button>(R.id.btn_start_sensor).setOnClickListener {
            if (!isSensorRunning) {
                startSensors()
                isSensorRunning = true
            }
            // USB connection can fail or be denied while phone sensors keep running.
            // Allow another tap to retry instead of permanently ignoring this button.
            val port = usbSerialPort
            if (port == null) startLidar()
            else if (!isMappingRunning && selectedSlamInputMode.usesImu &&
                radarStartup?.stage == Kld42StartupController.Stage.FAILED) {
                startNewRadarStartup(port)
            } else show(if (usesBoardImu && selectedSlamInputMode.usesImu)
                radarStartup?.statusText ?: lidarConnectionStatus else lidarConnectionStatus)
        }

        btnStartMapping.setOnClickListener {
            if (!isSensorRunning) {
                Toast.makeText(this, "先开传感器！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isMappingRunning || isFinishingMapping) {
                Toast.makeText(this, "当前正在建图", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ensureMappingInputsReady()) return@setOnClickListener
            if (isMappingFinished) {
                carto.reset()
            }
            requestWifiRssiPermissionsIfNeeded()
            mappingMode = MappingMode.NORMAL_MAPPING
            prepareMappingUi(clearMap = true)
            startCartographer(mapToLoad = null)
            Toast.makeText(this, "正在初始化建图，收到传感器数据后开始生成地图", Toast.LENGTH_LONG).show()
        }

        btnContinueMapping.setOnClickListener {
            startContinueMapping()
        }

        btnFinishMapping.setOnClickListener {
            finishMapping()
        }
        btnCancelContinueMapping.setOnClickListener {
            cancelContinueMapping()
        }

        btnSaveMap.setOnClickListener {
            saveCurrentMap()
        }
        btnSaveFloorPlan.setOnClickListener {
            saveCurrentFloorPlan()
        }
        btnMapNorth.setOnClickListener {
            alignMapToMagneticNorth()
        }
        btnAddTestPoint.setOnClickListener {
            addTestPointMarker()
        }
        checkPointCloud.setOnCheckedChangeListener { _, _ ->
            refreshFloorPlanLayerPreview()
        }
        checkFloorPlanOverlay.setOnCheckedChangeListener { _, _ ->
            refreshFloorPlanLayerPreview()
        }
        checkHeatMapOverlay.setOnCheckedChangeListener { _, _ ->
            refreshFloorPlanLayerPreview()
        }
        checkTrajectoryOverlay.setOnCheckedChangeListener { _, _ ->
            refreshFloorPlanLayerPreview()
        }
        checkMarkersOverlay.setOnCheckedChangeListener { _, _ ->
            refreshFloorPlanLayerPreview()
        }

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
        val usbAttachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbAttachReceiver, usbAttachFilter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
        startUIUpdate()

        mapView.setMapStyle(true)
    }

    private fun addTestPointMarker() {
        if (!isMappingRunning && !isMappingFinished) {
            show("请先开始建图")
            return
        }
        if (isFinishingMapping) {
            show("正在完成优化，请稍候")
            return
        }
        val pose = mapView.currentPose()
        markerSerial++
        mapView.addMarker(pose.x.toFloat(), pose.y.toFloat(), "测试点$markerSerial")
        show("已标注测试点$markerSerial（%.2f, %.2f）".format(pose.x, pose.y))
    }

    /** 将当前标注点保存为与地图同名的 markers.json（随 pbstream 一起持久化）。 */
    private fun saveMarkersToFile(mapFile: File): Boolean {
        val markers = mapView.exportMarkers()
        if (markers.isEmpty()) return true
        val markersFile = File(
            mapFile.parentFile,
            mapFile.name.removeSuffix(".pbstream") + ".markers.json"
        )
        return try {
            val array = JSONArray()
            markers.forEach { marker ->
                array.put(JSONObject().apply {
                    put("x", marker.worldX)
                    put("y", marker.worldY)
                    put("label", marker.label)
                    put("type", marker.type.name)
                })
            }
            markersFile.writeText(JSONObject().put("markers", array).toString())
            true
        } catch (e: Exception) {
            Log.e("CartographerJNI", "保存标注点失败", e)
            false
        }
    }

    /** 从与地图同名的 markers.json 恢复标注点（加载旧地图/补采时调用）。 */
    private fun restoreMarkers(mapFile: File) {
        val markersFile = File(
            mapFile.parentFile,
            mapFile.name.removeSuffix(".pbstream") + ".markers.json"
        )
        if (!markersFile.exists()) return
        try {
            val array = JSONObject(markersFile.readText()).optJSONArray("markers") ?: return
            val restored = ArrayList<LidarMapView.MapMarker>(array.length())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val type = runCatching {
                    LidarMapView.MarkerType.valueOf(item.optString("type", "DOT"))
                }.getOrDefault(LidarMapView.MarkerType.DOT)
                restored.add(
                    LidarMapView.MapMarker(
                        worldX = item.optDouble("x").toFloat(),
                        worldY = item.optDouble("y").toFloat(),
                        label = item.optString("label", ""),
                        type = type
                    )
                )
            }
            markerSerial = restored.size
            mapView.importMarkers(restored)
            Log.i("CartographerJNI", "已恢复 ${restored.size} 个标注点")
        } catch (e: Exception) {
            Log.e("CartographerJNI", "恢复标注点失败", e)
        }
    }

    private fun startContinueMapping() {
        if (!isSensorRunning) {
            show("先开传感器！")
            return
        }
        if (isMappingRunning || isFinishingMapping) {
            show(if (isFinishingMapping) "正在完成优化，请稍候" else "当前正在建图")
            return
        }

        if (!ensureMappingInputsReady()) return

        // 新地图优先在应用沙盒目录查找，同时兼容升级前保存在 data 私有目录的旧地图
        val latestMap = listOf(
            File(externalAppFilesDir, "maps"),
            File(filesDir, "maps")
        ).mapNotNull { dir ->
            dir.listFiles { file ->
                file.isFile && file.extension.equals("pbstream", ignoreCase = true)
            }?.maxByOrNull { it.lastModified() }
        }.maxByOrNull { it.lastModified() }
        if (latestMap == null) {
            show("没有找到可补采的旧地图")
            return
        }

        continueSourceMapFile = latestMap
        requestWifiRssiPermissionsIfNeeded()

        // A loaded state must go into a fresh MapBuilder. The loaded trajectories
        // are frozen; startCartographer creates a separate active trajectory later.
        setDataReceivePaused(true)
        carto.reset()
        mappingMode = MappingMode.CONTINUE_MAPPING
        relocalizationPhase = RelocalizationPhase.WAITING
        relocalizationStatus = RelocalizationStatus()
        prepareMappingUi(clearMap = false)
        startCartographer(latestMap)
        show("正在加载旧地图…")
    }

    private fun prepareMappingUi(clearMap: Boolean) {
        displayWorker.invalidate()
        synchronized(onlineMagneticNorthCalibrator) {
            mappingUiGeneration++
            magneticFitWorker.invalidate()
            onlineMagneticNorthCalibrator.reset()
            lastOnlineMagneticResultMs = 0L
        }
        lastMagneticSampleRequestMs = 0L
        lastPoseTextUpdateMs = 0L
        if (clearMap) {
            mapView.clearMap()
            mapView.clearMarkers()
            markerSerial = 0
            clearMapMeasurement()
            continueBaseSubmaps = emptyList()
            continueSourceMapFile = null
            rssiSamples.clear()
            lastRssiSampleMs = 0L
        } else if (mappingMode == MappingMode.CONTINUE_MAPPING) {
            mapView.beginContinueMapping()
        }
        finalizedSubmaps = emptyList()
        clearFloorPlanLayers()
        mapView.resetToRobotFollowing()
        btnMapNorth.visibility = View.GONE
        btnMapNorth.alpha = 1f
        btnMapNorth.contentDescription = "磁力计：点击后将地图按磁北方向居中"
        latestOnlineMagneticResult = OnlineMagneticNorthCalibrator.Result()
        savedMapNorthAlignment = null
        savedMapFile = null
        pendingNorthAlignment = null
        loadedMapNorthAlignment = null
        finishedMapPose = null
        currentFloorPlanResult = null
        currentFloorPlanDimensions = null
        currentFloorPlanCoveragePercent = null
        currentFloorPlanWarning = null
        pendingLegacyFloorPlanSave = null
        pendingLegacyMapExport = null
        lastRenderedScanNs = 0L
        lastSubmapFetchMs = 0L
        lastFetchedSubmapNodeCount = -1L
        lastFullSubmapFetchMs = 0L
        lastRelocalizationStatusFetchMs = 0L
        isRelocalizationStatusFetchRunning = false
        latestSubmapRefreshIntervalMs = 0L
        lastObservedRangeFrames = 0L
        lastObservedLocalSlamResults = 0L
        lastObservedInsertedNodes = 0L
        lastRangeFrameChangeMs = 0L
        lastLocalSlamChangeMs = 0L
        lastInsertedNodeChangeMs = 0L
        latestRangeFrameIntervalMs = 0L
        latestLocalSlamIntervalMs = 0L
        latestInsertedNodeIntervalMs = 0L
        latestSubmapCount = 0
        if (mappingMode == MappingMode.NORMAL_MAPPING) {
            relocalizationPhase = RelocalizationPhase.NOT_REQUIRED
            relocalizationStatus = RelocalizationStatus()
        }
        isMappingFinished = false
        isMappingRunning = true
        btnFinishMapping.isEnabled = false
        btnContinueMapping.isEnabled = false
        btnSaveMap.isEnabled = false
        btnSaveFloorPlan.isEnabled = false
        btnCancelContinueMapping.visibility = if (mappingMode == MappingMode.CONTINUE_MAPPING) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun startCartographer(mapToLoad: File?) {
        val inputMode = selectedSlamInputMode
        activeSlamInputMode = inputMode
        setDataReceivePaused(true)

        Thread {
            try {
                val localConfigDir = File(externalAppFilesDir, "config")
                prepareCartographerConfig(localConfigDir)

                val configurationFile = inputMode.configurationFile(continuing = mapToLoad != null)
                val ok = carto.initialize(
                    localConfigDir.absolutePath,
                    configurationFile,
                    useImuData = inputMode.usesImu
                )
                if (!ok) {
                    handleMappingStartFailure("初始化失败")
                    return@Thread
                }

                if (mapToLoad != null) {
                    val loaded = carto.loadMap(mapToLoad.absolutePath, loadFrozenState = true)
                    if (!loaded) {
                        handleMappingStartFailure("旧地图加载失败")
                        return@Thread
                    }
                    val oldSubmaps = carto.getSubmapTextures()
                    // At this point no active trajectory exists, so this returns
                    // the paths restored from the frozen pbstream state.
                    val oldTrajectory = carto.getTrajectoryNodePoses()
                    loadedMapNorthAlignment = MapMetadataStore.load(mapToLoad)
                    pendingNorthAlignment = loadedMapNorthAlignment
                    restoreMarkers(mapToLoad)
                    runOnUiThread {
                        continueBaseSubmaps = oldSubmaps
                        latestSubmapCount = oldSubmaps.size
                        mapView.setSubmapTextures(oldSubmaps)
                        mapView.setBaseTrajectory(oldTrajectory)
                        // Map measurement/floor-plan geometry is not needed for
                        // relocalization and can be expensive on a large frozen
                        // map. Recompute it once matching succeeds.
                    }
                }

                val traj = carto.startTrajectory()
                if (traj < 0) {
                    handleMappingStartFailure("轨迹启动失败")
                    return@Thread
                }

                setDataReceivePaused(false)
                if (mapToLoad != null) {
                    relocalizationStartedMs = SystemClock.elapsedRealtime()
                }
                runOnUiThread {
                    btnFinishMapping.isEnabled = mapToLoad == null
                    show(if (mapToLoad == null) {
                        "${inputMode.displayName}已启动，正在等待首帧地图"
                    } else {
                        "旧地图已加载，正在重定位；请在已建区域缓慢移动和转动"
                    })
                }

            } catch (e: Exception) {
                e.printStackTrace()
                handleMappingStartFailure("错误：${e.message}")
            }
        }.start()
    }

    private fun handleMappingStartFailure(message: String) {
        setDataReceivePaused(true)
        isMappingRunning = false
        activeSlamInputMode = null
        carto.reset()
        runOnUiThread {
            btnStartMapping.isEnabled = true
            btnContinueMapping.isEnabled = true
            btnCancelContinueMapping.visibility = View.GONE
            show(message)
        }
    }

    private fun prepareCartographerConfig(outputDir: File) {
        if (!outputDir.exists()) outputDir.mkdirs()
        val plainAssets = assets.list("config").orEmpty().filter { it.endsWith(".lua") }
        if (plainAssets.isNotEmpty()) {
            plainAssets.forEach { name ->
                assets.open("config/$name").use { input ->
                    FileOutputStream(File(outputDir, name), false).use { output -> input.copyTo(output) }
                }
            }
            return
        }

        // Binary SDK deliveries carry obfuscated configuration resources rather
        // than plaintext Lua. Decrypt only into the app-private files directory.
        val key = byteArrayOf(0x43, 0x61, 0x72, 0x74, 0x6f, 0x53, 0x44, 0x4b)
        val protectedAssets = assets.list("carto_cfg").orEmpty().filter { it.endsWith(".bin") }
        require(protectedAssets.isNotEmpty()) { "Cartographer configuration is missing" }
        protectedAssets.forEach { name ->
            val bytes = assets.open("carto_cfg/$name").use { it.readBytes() }
            for (index in bytes.indices) {
                bytes[index] = (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
            File(outputDir, name.removeSuffix(".bin")).writeBytes(bytes)
        }
    }

    @Volatile private var pauseDataReceive = false

    private fun setDataReceivePaused(paused: Boolean) {
        pauseDataReceive = paused
        if (!paused) return
        usbInputQueue?.invalidate()
        lidarReader.clearLatestScanAndPendingData()
        lastRenderedScanNs = 0L
        runOnUiThread { mapView.clearLiveScan() }
    }

    private fun startSensors() {
        // IMU 来源兼容：设备自带加速度计+陀螺仪则用设备传感器；
        // 设备不带 IMU 时启用雷达自带 IMU（A 类协议 09 4A 数据）。
        val a = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val g = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (a != null && g != null) {
            usesBoardImu = false
            imuSourceDescription = "手机 IMU（${g.name}）"
            Log.i("SlamImu", "来源=$imuSourceDescription acc=${a.name} gyro=${g.vendor}; 使用 config/android_2d.lua 外参")
            sensorManager.registerListener(this, a, SensorManager.SENSOR_DELAY_FASTEST)
            sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_FASTEST)
        } else {
            usesBoardImu = true
            imuSourceDescription = "转接板 IMU"
            lidarReader.aaProtocol.imuListener =
                object : Kld42LidarScanParser.ImuListener {
                    override fun onImuData(
                        timestampNs: Long,
                        acceleration: FloatArray,
                        gyroscope: FloatArray
                    ) {
                        // 串口线程回调，与 addLidarData 同线程，无需额外加锁
                        carto.addImuData(timestampNs, acceleration, gyroscope)
                        imuCount++
                    }
                }
            Log.i("KaerActivity", "设备无加速度计/陀螺仪，改用雷达自带 IMU")
        }
        lidarReader.enableLidar()
        if (!magneticHeadingProvider.start()) {
            show("当前设备没有可用磁力计，建图不受影响，但无法进行地图南北对齐")
        } else {
            val magneticState = magneticHeadingProvider.getState()
            Log.i(
                "MagneticHeading",
                "磁力计已注册：${magneticState.sensorName}, " +
                    "vendor=${magneticState.sensorVendor}"
            )
        }
        // 纯 LiDAR 测试模式：不注册手机加速度计和陀螺仪。
        // hasAccelerationSample = false
        // hasGyroscopeSample = false
        // imuCount = 0
        // lidarParser.enableLidar()
    }

    private fun startLidar() {
        // 诊断：打印当前所有已连接 USB 设备
        usbManager.deviceList.forEach { (name, device) ->
            Log.i(
                "KaerActivity",
                "USB 设备: $name vid=0x%04X pid=0x%04X class=0x%02X subClass=0x%02X".format(
                    device.vendorId, device.productId, device.deviceClass, device.deviceSubclass
                )
            )
        }
        if (lidarReader.protocol == LidarScanParserRouter.Protocol.KAER_PROTOCOL) {
            startLidarNewProtocol()
        } else {
            startLidarLegacy()
        }
    }

    /** 新雷达：按固定 VID/PID（0x2015:0x0008）精确匹配设备，参考 temp 工程 IS_8003_SLAM 逻辑。 */
    private fun startLidarNewProtocol() {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == SLAM_VID && it.productId == SLAM_PID
        }
        if (device == null) {
            val devices = usbManager.deviceList.values
            val ids = devices.joinToString { "%04X:%04X".format(it.vendorId, it.productId) }
            lidarConnectionStatus = when {
                devices.isEmpty() -> "手机未检测到 USB 设备，请检查 OTG、数据线及供电"
                devices.any { it.vendorId == 0x1A86 && it.productId == 0x7523 } ->
                    "检测到旧雷达接口，请关闭“新雷达协议”后重新采集"
                else -> "未找到新雷达 2015:0008；当前 USB：$ids"
            }
            Log.e("KaerActivity", lidarConnectionStatus)
            show(lidarConnectionStatus)
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
        } else {
            openUsbDevice(device)
        }
    }

    /** 旧雷达沿用默认串口驱动，排除使用 A 类协议的新雷达。 */
    private fun startLidarLegacy() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .filterNot { it.device.vendorId == SLAM_VID && it.device.productId == SLAM_PID }
        Log.i("KaerActivity", "默认驱动探测结果: ${drivers.size} 个")
        if (drivers.isEmpty()) {
            Log.e("KaerActivity", "未识别到雷达 USB 驱动，请检查 VID/PID 与芯片类型")
            lidarConnectionStatus = if (usbManager.deviceList.values.any {
                it.vendorId == SLAM_VID && it.productId == SLAM_PID
            }) "检测到新雷达，请开启“新雷达协议”后重新采集"
            else "未识别到旧雷达 USB 设备，请检查连接及 USB 驱动"
            show(lidarConnectionStatus)
            return
        }
        val d = drivers[0]
        if (!usbManager.hasPermission(d.device)) {
            requestUsbPermission(d.device)
        } else {
            openUsbDevice(d.device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbPermissionDevice?.deviceName == device.deviceName) return
        val f = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, f)
        usbPermissionDevice = device
        lidarConnectionStatus = "等待 USB 授权：%04X:%04X".format(device.vendorId, device.productId)
        try {
            usbManager.requestPermission(device, pi)
        } catch (e: Exception) {
            usbPermissionDevice = null
            lidarConnectionStatus = "USB 授权请求失败：${e.message}"
            Log.e("KaerActivity", lidarConnectionStatus, e)
            show(lidarConnectionStatus)
        }
    }

    /** Probe the exact device that was granted permission, including KLD42's custom CDC ID. */
    private fun openUsbDevice(device: UsbDevice) {
        if (!isSensorRunning || usbSerialPort != null) return
        val isNewDevice = device.vendorId == SLAM_VID && device.productId == SLAM_PID
        val useNewProtocol = lidarReader.protocol == LidarScanParserRouter.Protocol.KAER_PROTOCOL
        if (isNewDevice != useNewProtocol) {
            startLidar()
            return
        }
        try {
            val driver = LidarUsbDrivers.createProber().probeDevice(device)
            val port = driver?.ports?.firstOrNull()
            if (port == null) {
                lidarConnectionStatus = "雷达驱动识别失败：%04X:%04X".format(device.vendorId, device.productId)
                Log.e("KaerActivity", lidarConnectionStatus)
                show(lidarConnectionStatus)
                return
            }
            Log.i("KaerActivity", "打开 USB：${device.deviceName}，驱动=${driver.javaClass.simpleName}")
            openPort(port)
        } catch (e: Exception) {
            lidarConnectionStatus = "雷达驱动初始化失败：${e.message}"
            Log.e("KaerActivity", lidarConnectionStatus, e)
            show(lidarConnectionStatus)
        }
    }

    private fun closeLidar(sendStop: Boolean = true) {
        radarStartupHandler.removeCallbacksAndMessages(null)
        radarStartup = null
        lidarReader.aaProtocol.controlReplyListener = null
        usbIoManager?.stop()
        usbIoManager = null
        usbInputQueue?.close()
        usbInputQueue = null
        val port = usbSerialPort
        usbSerialPort = null
        if (port != null) {
            if (sendStop) runCatching {
                if (lidarReader.protocol == LidarScanParserRouter.Protocol.KAER_PROTOCOL) {
                    lidarReader.aaProtocol.controlSensorPower(false)
                    lidarReader.aaProtocol.controlSlamPower(false)
                } else {
                    sendLidarCommand(port, 0xF5, 0x0A)
                }
            }.onFailure { Log.w("KaerActivity", "停止雷达失败", it) }
            runCatching { port.close() }
        }
        lidarReader.aaProtocol.commandSink = null
        lidarReader.aaProtocol.resetAuth()
        lidarReader.legacy.clearLatestScanAndPendingData()
        lidarReader.aaProtocol.clearLatestScanAndPendingData()
        latestLidarPoints = 0
        latestLidarDist = 0f
        latestLidarAngle = 0f
        lidarConnectionStatus = "未连接"
    }

    private fun ensureMappingInputsReady(): Boolean {
        if (!selectedSlamInputMode.usesImu) {
            val scan = lidarReader.getLatestScan()
            val age = scan?.let { SystemClock.elapsedRealtimeNanos() - it.timestampNs }
            if (usbSerialPort != null && age != null && age in -50_000_000L..2_000_000_000L) return true
            show("请先开始采集，收到有效点云后再启动纯雷达建图")
            return false
        }
        if (!usesBoardImu) return true
        val age = SystemClock.elapsedRealtimeNanos() - lidarReader.aaProtocol.latestImuTimestampNs
        if (usbSerialPort != null && radarStartup?.stage == Kld42StartupController.Stage.READY &&
            lidarReader.aaProtocol.imuPacketCount > 0 && age in 0..2_000_000_000L) return true
        show("尚未收到可用板载 IMU，暂不能启动建图：${radarStartup?.statusText ?: "请先开始采集"}")
        return false
    }

    private fun updateNewRadarStartup(
        port: UsbSerialPort,
        event: (Kld42StartupController) -> List<Kld42StartupController.Command>
    ) {
        val startup = radarStartup ?: return
        if (usbSerialPort !== port) return
        val previousStage = startup.stage
        try {
            for (command in event(startup)) {
                when (command) {
                    Kld42StartupController.Command.START_LIDAR -> lidarReader.aaProtocol.controlSlamPower(true)
                    Kld42StartupController.Command.ENABLE_IMU -> lidarReader.aaProtocol.controlSensorPower(true)
                }
            }
            if (startup.stage != previousStage) {
                Log.i("RadarStartup", "${startup.statusText}; ${startup.lastReply}")
                if (usesBoardImu && selectedSlamInputMode.usesImu && startup.stage in listOf(
                        Kld42StartupController.Stage.READY, Kld42StartupController.Stage.FAILED)) {
                    show(startup.statusText)
                }
            }
        } catch (e: Exception) {
            closeLidar(sendStop = false)
            lidarConnectionStatus = "雷达控制命令发送失败：${e.message}"
            Log.e("RadarStartup", lidarConnectionStatus, e)
            show(lidarConnectionStatus)
        }
    }

    private fun startNewRadarStartup(port: UsbSerialPort) {
        radarStartupHandler.removeCallbacksAndMessages(null)
        val startup = Kld42StartupController()
        radarStartup = startup
        lidarReader.aaProtocol.controlReplyListener = { operation, status ->
            radarStartupHandler.post {
                if (radarStartup === startup) updateNewRadarStartup(port) {
                    it.onControlReply(operation, status, SystemClock.elapsedRealtime())
                }
            }
        }
        updateNewRadarStartup(port) { it.start(SystemClock.elapsedRealtime()) }
        radarStartupHandler.postDelayed(object : Runnable {
            override fun run() {
                if (radarStartup !== startup || usbSerialPort !== port) return
                updateNewRadarStartup(port) { it.onTimer(SystemClock.elapsedRealtime()) }
                if (startup.stage == Kld42StartupController.Stage.WAITING_LIDAR ||
                    startup.stage == Kld42StartupController.Stage.WAITING_IMU) {
                    radarStartupHandler.postDelayed(this, 250L)
                }
            }
        }, 250L)
    }

    private fun reportUsbInputFailure(port: UsbSerialPort, error: Exception) {
        Log.e("LidarInput", "雷达接收或处理失败", error)
        runOnUiThread {
            if (usbSerialPort === port) {
                closeLidar(sendStop = false)
                lidarConnectionStatus = "雷达数据处理已停止：${error.message}；请结束建图后重新采集"
                show(lidarConnectionStatus)
            }
        }
    }

    private fun openPort(port: UsbSerialPort) {
        try {
            val connection = usbManager.openDevice(port.device)
                ?: throw IOException("无法打开 USB，请重新授权并检查连接")
            usbSerialPort = port
            try {
                port.open(connection)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
            port.setParameters(230400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = false
            lidarReader.clearLatestScanAndPendingData()
            lidarConnectionStatus = "串口已打开，等待雷达点云"
            var receivedScan = false
            val inputQueue = TimedUsbInputQueue(
                clock = { SystemClock.elapsedRealtimeNanos() },
                consume = consume@ { data, receivedAtNs ->
                    if (usbSerialPort !== port || pauseDataReceive) return@consume
                    if (!receivedScan) lidarConnectionStatus = "已收到串口数据，等待有效点云"
                    val previousImuPackets = lidarReader.aaProtocol.imuPacketCount
                    val previousLidarPackets = lidarReader.aa55PacketCount
                    lidarReader.onDataReceived(data, data.size, receivedAtNs)
                    val sawImu = lidarReader.aaProtocol.imuPacketCount > previousImuPackets
                    val sawLidar = lidarReader.aa55PacketCount > previousLidarPackets
                    val startup = radarStartup
                    if (startup != null && (sawImu || sawLidar)) {
                        radarStartupHandler.post {
                            if (radarStartup === startup) updateNewRadarStartup(port) {
                                if (sawImu) it.onImuData()
                                if (sawLidar) it.onLidarData(SystemClock.elapsedRealtime()) else emptyList()
                            }
                        }
                    }

                    // ✅ 实时读取雷达数据（测试用）
                    val lastPoints = lidarReader.lastValidPointCount
                    if (!receivedScan && lidarReader.getLatestScan() != null) {
                        receivedScan = true
                        lidarConnectionStatus = "雷达已连接，已收到点云"
                        show(lidarConnectionStatus)
                    }
                    val lastDist = lidarReader.lastShowDistance
                    val lastAngle = lidarReader.lastShowAngle
                    runOnUiThread {
                        latestLidarPoints = lastPoints
                        latestLidarDist = lastDist
                        latestLidarAngle = lastAngle
                    }
                },
                onFailure = { error -> reportUsbInputFailure(port, error) }
            )
            usbInputQueue = inputQueue
            usbIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val receivedAtNs = SystemClock.elapsedRealtimeNanos()
                    carto.recordUsbData(receivedAtNs, data)
                    val epoch = inputQueue.epoch
                    if (usbSerialPort !== port) return
                    lidarCount += data.size
                    if (pauseDataReceive) return
                    inputQueue.submit(data, receivedAtNs, epoch)
                }
                override fun onRunError(e: Exception) {
                    reportUsbInputFailure(port, e)
                }
            }).apply { start() }
            // Start reading before issuing commands so short control replies are not missed.
            Thread.sleep(200)
            if (lidarReader.protocol == LidarScanParserRouter.Protocol.KAER_PROTOCOL) {
                lidarReader.aaProtocol.commandSink = { bytes ->
                    port.write(bytes, 1000)
                    Log.i("KaerActivity", "发送 A 类命令：" +
                        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                }
                startNewRadarStartup(port)
            } else {
                sendLidarCommand(port, 0xF0, 0x0F)
            }
            show("雷达串口已打开，正在等待点云")
        } catch (e: Exception) {
            closeLidar(sendStop = false)
            lidarConnectionStatus = "雷达连接或启动失败：${e.message}"
            Log.e("KaerActivity", lidarConnectionStatus, e)
            show(lidarConnectionStatus)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!isSensorRunning) return

        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acc[0] = e.values[0]
                acc[1] = e.values[1]
                acc[2] = e.values[2]
                lastAccelerationSampleNs = e.timestamp
                hasAccelerationSample = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = e.values[0]
                gyro[1] = e.values[1]
                gyro[2] = e.values[2]
                hasGyroscopeSample = true
            }
            else -> return
        }

        // SensorEvent.timestamp is the hardware sample time on the same
        // CLOCK_BOOTTIME time base as elapsedRealtimeNanos().
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE ||
            !hasAccelerationSample ||
            !hasGyroscopeSample ||
            e.timestamp - lastAccelerationSampleNs !in
                0L..IMU_MAX_PAIR_AGE_NS ||
            e.timestamp - lastImuSendNs < IMU_MIN_INTERVAL_NS) {
            return
        }
        lastImuSendNs = e.timestamp
        lastImuTimeNs = e.timestamp
        carto.addImuData(e.timestamp, acc, gyro)
        imuCount++
    }



    private fun startUIUpdate() {
        val h = uiUpdateHandler
        h.post(object : Runnable {
            override fun run() {
                if (uiUpdatesStopped) return
                // UI only reads immutable cached values. Both pose reads and
                // magnetic fitting can block; neither belongs on the main loop.
                if (isMappingRunning && !isFinishingMapping) {
                    displayWorker.request {
                        if (isFinishingMapping) SlamDisplaySnapshot() else carto.readDisplaySnapshot()
                    }
                }
                val display = displayWorker.latest
                val pose = if (!isFinishingMapping) display.pose else null
                val status = display.status
                val magneticState = magneticHeadingProvider.getState()
                val nowMs = SystemClock.elapsedRealtime()
                updateSlamTiming(status, nowMs)
                val pose2d = pose?.to2D()
                if (isMappingRunning && !isFinishingMapping &&
                    mappingMode == MappingMode.NORMAL_MAPPING && pose2d != null) {
                    latestOnlineMagneticResult = magneticFitWorker.latest
                    if (nowMs - lastMagneticSampleRequestMs >= 100L) {
                        val generation = mappingUiGeneration
                        if (magneticFitWorker.request {
                            synchronized(onlineMagneticNorthCalibrator) {
                                if (generation != mappingUiGeneration || isFinishingMapping ||
                                    !isMappingRunning) {
                                    magneticFitWorker.latest
                                } else {
                                    onlineMagneticNorthCalibrator.addSample(magneticState, pose2d)
                                    if (nowMs - lastOnlineMagneticResultMs >=
                                        ONLINE_MAGNETIC_RESULT_INTERVAL_MS) {
                                        lastOnlineMagneticResultMs = nowMs
                                        onlineMagneticNorthCalibrator.getResult()
                                    } else magneticFitWorker.latest
                                }
                            }
                        }) lastMagneticSampleRequestMs = nowMs
                    }
                }
                maybeSampleWifiRssi(pose2d, nowMs)
                val latestScan = lidarReader.getLatestScan()
                val scanAgeNs = latestScan?.let {
                    SystemClock.elapsedRealtimeNanos() - it.timestampNs
                }
                val hasFreshScan = latestScan != null && scanAgeNs != null &&
                    scanAgeNs in 0..LIVE_SCAN_STALE_TIMEOUT_NS
                if (!pauseDataReceive && hasFreshScan &&
                    latestScan!!.timestampNs != lastRenderedScanNs) {
                    mapView.setLiveScan(latestScan, pose2d)
                    lastRenderedScanNs = latestScan.timestampNs
                } else if ((pauseDataReceive || !hasFreshScan) && lastRenderedScanNs != 0L) {
                    mapView.clearLiveScan()
                    lastRenderedScanNs = 0L
                }
                maybeFetchSubmaps(status.insertedNodes)
                if (nowMs - lastPoseTextUpdateMs >= 250L) {
                    lastPoseTextUpdateMs = nowMs
                    val tip = when {
                        isFinishingMapping -> "正在完成优化"
                        isMappingRunning && status.rangeFrames == 0L -> "等待雷达扫描数据"
                        isMappingRunning && activeSlamInputMode?.usesImu != false && status.imuSamples == 0L -> "等待 IMU 数据，尚未生成地图"
                        isMappingRunning && status.localSlamResults == 0L -> "传感器数据已进入，等待 SLAM 首帧"
                        isMappingRunning && relocalizationPhase == RelocalizationPhase.WAITING -> "正在旧地图中重定位"
                        isMappingRunning && relocalizationPhase == RelocalizationPhase.TIMEOUT -> "重定位尚未成功，请回到已建区域"
                        isMappingRunning && relocalizationPhase == RelocalizationPhase.RELOCALIZED -> "已对齐，补采建图中"
                        isMappingRunning && mappingMode == MappingMode.NORMAL_MAPPING -> {
                            val calibration = latestOnlineMagneticResult
                            if (calibration.isReady) {
                                "建图中｜地磁校准已就绪"
                            } else {
                                "建图中｜地磁方向覆盖 ${String.format(Locale.US, "%.0f°", calibration.headingCoverageDegrees)}"
                            }
                        }
                        isMappingRunning -> "建图中"
                        isMappingFinished -> "建图已结束"
                        isSensorRunning -> "传感器采集中；$lidarConnectionStatus"
                        else -> "待机"
                    }
                    tvPose.text = if (pose2d != null) {
                        "$tip\nIMU:$imuCount 雷达:$lidarCount\nX:%.2f Y:%.2f θ:%.1f°".format(pose2d.x, pose2d.y, Math.toDegrees(pose2d.theta))
                    } else {
                        "$tip\nIMU:$imuCount 雷达:$lidarCount"
                    }
                }

                // ===============================
                // ✅ 实时显示真实数据到屏幕！
                // ===============================
                if (nowMs - lastDebugTextUpdateMs >= DEBUG_TEXT_UPDATE_INTERVAL_MS) {
                    lastDebugTextUpdateMs = nowMs
                    val debugText = """
                    📱 实时传感器数据
                    ─────────────────────
                    建图输入：${(activeSlamInputMode ?: selectedSlamInputMode).displayName}
                    IMU 采集来源：$imuSourceDescription
                    手机加速度：${acc.joinToString(" / ") { "%.3f".format(Locale.US, it) }} m/s²
                    手机角速度：${gyro.joinToString(" / ") { "%.3f".format(Locale.US, it) }} rad/s
                    板载启动：${radarStartup?.statusText ?: "未启动"}
                    控制应答：${radarStartup?.lastReply ?: "无"}
                    板载 IMU 包：${lidarReader.aaProtocol.imuPacketCount}
                    板载加速度：${lidarReader.aaProtocol.latestAcceleration.joinToString(" / ") { "%.3f".format(Locale.US, it) }} m/s²
                    板载输入角速度：${lidarReader.aaProtocol.latestAngularVelocity.joinToString(" / ") { "%.3f".format(Locale.US, it) }} rad/s
                    板载原始角速度：${lidarReader.aaProtocol.latestRawGyroscope.joinToString(" / ") { "%.3f".format(Locale.US, it) }}（现有固件原始值）

                    磁力计：
                    设备：${magneticState.sensorName}
                    厂商：${magneticState.sensorVendor}
                    支持/注册：${if (magneticState.isSupported) "是" else "否"}/${if (magneticState.isRegistered) "是" else "否"}
                    回调：${magneticState.callbackCount}
                    X/Y/Z：${String.format(Locale.US, "%.1f / %.1f / %.1f μT", magneticState.magneticXMicroTesla, magneticState.magneticYMicroTesla, magneticState.magneticZMicroTesla)}
                    磁场强度：${String.format(Locale.US, "%.1f μT", magneticState.fieldStrengthMicroTesla)}，波动：${String.format(Locale.US, "%.1f μT", magneticState.fieldStrengthStdDevMicroTesla)}
                    系统精度：${formatMagneticAccuracy(magneticState.accuracy)}
                    航向样本：${magneticState.sampleCount}，时长：${String.format(Locale.US, "%.1f s", magneticState.samplingDurationMillis / 1000.0)}，波动：${formatMagneticDispersion(magneticState.dispersionDegrees)}
                    在线校准：${latestOnlineMagneticResult.failureReason}
                    拟合模型：${if (latestOnlineMagneticResult.usesThreeDimensionalBias) "三维磁偏移" else "二维回退"}
                    方向覆盖：${String.format(Locale.US, "%.0f°", latestOnlineMagneticResult.headingCoverageDegrees)}，有效样本：${latestOnlineMagneticResult.inlierCount}/${latestOnlineMagneticResult.sampleCount}
                    拟合残差：${if (latestOnlineMagneticResult.residualMicroTesla.isFinite()) String.format(Locale.US, "%.1f μT", latestOnlineMagneticResult.residualMicroTesla) else "--"}
                    
                    雷达：
                    连接：$lidarConnectionStatus
                    有效点数：$latestLidarPoints
                    距离：${String.format("%.2f", latestLidarDist)} m
                    角度：${String.format("%.1f", latestLidarAngle)} °
                    协议：${lidarReader.activeProtocol}
                    接收队列：${usbInputQueue?.queuedReads ?: 0} 批
                    处理延迟：${(usbInputQueue?.latestDelayNs ?: 0L) / 1_000_000} ms
                    最大积压：${(usbInputQueue?.maxDelayNs ?: 0L) / 1_000_000} ms
                    批量IMU样本：${lidarReader.aaProtocol.batchedImuSampleCount}
                    时间压缩批次：${lidarReader.aaProtocol.compressedImuBatchCount}
                    A类有效帧：${lidarReader.aaProtocol.protocolFrameCount}
                    A类校验错误：${lidarReader.aaProtocol.protocolChecksumErrorCount}
                    A类丢弃字节：${lidarReader.aaProtocol.protocolDiscardedByteCount}
                    AA55包：${lidarReader.aa55PacketCount}
                    异常包：${lidarReader.invalidAa55PacketCount}
                    残缺转圈：${lidarReader.droppedIncompleteScanCount}
                    丢弃字节：${lidarReader.droppedByteCount}
                    离群点：${lidarReader.rejectedOutlierPointCount}

                    Cartographer：
                    雷达帧：${status.rangeFrames}
                    融合IMU：${if (activeSlamInputMode == SlamInputMode.LIDAR_ONLY) "未使用（纯雷达模式）" else "${status.imuSamples} 帧"}
                    LocalSLAM：${status.localSlamResults}
                    插入节点：${status.insertedNodes}
                    Submap：$latestSubmapCount
                    位姿：${if (status.hasPose) "有" else "无"}
                    ${formatRelocalizationDebug()}

                    实际间隔：
                    雷达帧：${formatInterval(latestRangeFrameIntervalMs)}
                    LocalSLAM：${formatInterval(latestLocalSlamIntervalMs)}
                    插入节点：${formatInterval(latestInsertedNodeIntervalMs)}
                    UI刷图：${formatInterval(latestSubmapRefreshIntervalMs)}
                    显示快照读取：${display.readDurationMs}ms（后台）
                    显示快照年龄：${if (display.completedMs > 0L) "${nowMs - display.completedMs}ms" else "--"}
                    """.trimIndent()
                    tvDebugData.text = debugText
                }

                h.postDelayed(this, 50)
            }
        })
    }

    private fun updateSlamTiming(status: SlamStatus, nowMs: Long) {
        if (status.rangeFrames != lastObservedRangeFrames) {
            if (lastRangeFrameChangeMs > 0L) {
                latestRangeFrameIntervalMs = nowMs - lastRangeFrameChangeMs
            }
            lastRangeFrameChangeMs = nowMs
            lastObservedRangeFrames = status.rangeFrames
        }
        if (status.localSlamResults != lastObservedLocalSlamResults) {
            if (lastLocalSlamChangeMs > 0L) {
                latestLocalSlamIntervalMs = nowMs - lastLocalSlamChangeMs
            }
            lastLocalSlamChangeMs = nowMs
            lastObservedLocalSlamResults = status.localSlamResults
        }
        if (status.insertedNodes != lastObservedInsertedNodes) {
            if (lastInsertedNodeChangeMs > 0L) {
                latestInsertedNodeIntervalMs = nowMs - lastInsertedNodeChangeMs
            }
            lastInsertedNodeChangeMs = nowMs
            lastObservedInsertedNodes = status.insertedNodes
        }
    }

    private fun requestWifiRssiPermissionsIfNeeded() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@KaerActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@KaerActivity,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (missing.isNotEmpty()) wifiPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasWifiRssiPermissions(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        return hasLocation && hasNearby
    }

    @Suppress("DEPRECATION")
    private fun maybeSampleWifiRssi(pose: Pose2D?, nowMs: Long) {
        if (!isMappingRunning || pose == null ||
            nowMs - lastRssiSampleMs < RSSI_SAMPLE_INTERVAL_MS ||
            !hasWifiRssiPermissions()) return

        val rssi = try {
            wifiManager.connectionInfo?.rssi ?: return
        } catch (_: SecurityException) {
            return
        }
        if (rssi !in -126..-1) return
        rssiSamples += RssiSample(
            worldX = pose.x.toFloat(),
            worldY = pose.y.toFloat(),
            rssiDbm = rssi.toFloat(),
            timestampMillis = System.currentTimeMillis()
        )
        lastRssiSampleMs = nowMs
    }

    private fun formatInterval(intervalMs: Long): String {
        return if (intervalMs <= 0L) "--" else "${intervalMs}ms"
    }

    private fun formatRelocalizationDebug(): String {
        if (mappingMode != MappingMode.CONTINUE_MAPPING || !isMappingRunning) return ""
        val stateText = when (relocalizationPhase) {
            RelocalizationPhase.WAITING -> "等待匹配"
            RelocalizationPhase.RELOCALIZED -> "已成功"
            RelocalizationPhase.TIMEOUT -> "尚未成功"
            RelocalizationPhase.NOT_REQUIRED -> "--"
        }
        return """
            重定位：$stateText
            新轨迹节点：${relocalizationStatus.activeNodeCount}
            跨轨迹约束：${relocalizationStatus.interTrajectoryConstraintCount}
            已匹配新节点：${relocalizationStatus.matchedActiveNodeCount}
            已匹配旧Submap：${relocalizationStatus.matchedOldSubmapCount}
        """.trimIndent()
    }

    private fun maybeFetchSubmaps(insertedNodeCount: Long) {
        if (!isMappingRunning || isFinishingMapping) return
        if (mappingMode == MappingMode.CONTINUE_MAPPING &&
            relocalizationPhase != RelocalizationPhase.RELOCALIZED) {
            maybeUpdateRelocalizationStatus()
            return
        }
        if (isSubmapFetchRunning) return
        val nowMs = SystemClock.elapsedRealtime()
        val fullRefreshDue = lastFullSubmapFetchMs > 0L &&
            nowMs - lastFullSubmapFetchMs >= FULL_SUBMAP_FETCH_INTERVAL_MS
        if (insertedNodeCount <= lastFetchedSubmapNodeCount && !fullRefreshDue) return
        if (nowMs - lastSubmapFetchMs < SUBMAP_FETCH_INTERVAL_MS) return
        val previousFetchMs = lastSubmapFetchMs
        lastSubmapFetchMs = nowMs
        val fetchAllSubmaps = lastFullSubmapFetchMs == 0L || fullRefreshDue
        isSubmapFetchRunning = true

        Thread {
            try {
                // Most refreshes only decode the active submaps. Decoding and
                // converting every historical submap while holding Cartographer's
                // data mutex made sensor insertion progressively slower. A low
                // frequency full refresh still applies pose-graph corrections to
                // cached historical submaps. Final optimization/export always
                // requests the complete set independently of this display path.
                val textures = if (fetchAllSubmaps) {
                    carto.getSubmapTextures()
                } else {
                    // Cartographer inserts into two overlapping active submaps.
                    // Fetching only the newest one leaves half of the live map
                    // stale until the next full refresh.
                    carto.getActiveSubmapTextures()
                }
                if (textures.isNotEmpty()) {
                    lastFetchedSubmapNodeCount = maxOf(
                        lastFetchedSubmapNodeCount,
                        insertedNodeCount
                    )
                    if (fetchAllSubmaps) lastFullSubmapFetchMs = nowMs
                }
                if (previousFetchMs > 0L) {
                    latestSubmapRefreshIntervalMs = nowMs - previousFetchMs
                }
                if (textures.isNotEmpty()) {
                    latestSubmapCount = if (fetchAllSubmaps) {
                        textures.size
                    } else {
                        maxOf(
                            latestSubmapCount,
                            textures.maxOf { it.submapIndex + 1 }
                        )
                    }
                }
                if (textures.isNotEmpty()) {
                    runOnUiThread {
                        mapView.setSubmapTextures(textures)
                        // MapMeasurementCalculator scans all cached texture
                        // pixels. Re-running it on every 500 ms live texture
                        // update can consume more CPU than the renderer itself.
                        if (fetchAllSubmaps &&
                            (mappingMode == MappingMode.NORMAL_MAPPING ||
                                relocalizationPhase == RelocalizationPhase.RELOCALIZED)) {
                            mergeMeasurementTextures(textures)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CartographerJNI", "获取Submap失败", e)
            } finally {
                isSubmapFetchRunning = false
            }
        }.start()
    }

    private fun maybeUpdateRelocalizationStatus() {
        if (mappingMode != MappingMode.CONTINUE_MAPPING || !isMappingRunning ||
            isFinishingMapping || isRelocalizationStatusFetchRunning ||
            relocalizationPhase == RelocalizationPhase.RELOCALIZED) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastRelocalizationStatusFetchMs <
            RELOCALIZATION_STATUS_INTERVAL_MS) {
            return
        }
        lastRelocalizationStatusFetchMs = nowMs
        isRelocalizationStatusFetchRunning = true
        Thread {
            try {
                val newStatus = carto.getRelocalizationStatus()
                relocalizationStatus = newStatus
                val previousPhase = relocalizationPhase
                relocalizationPhase = when {
                    newStatus.isRelocalized -> RelocalizationPhase.RELOCALIZED
                    SystemClock.elapsedRealtime() - relocalizationStartedMs >=
                        RELOCALIZATION_TIMEOUT_MS ||
                        newStatus.activeNodeCount >= RELOCALIZATION_TIMEOUT_NODE_COUNT ->
                        RelocalizationPhase.TIMEOUT
                    else -> RelocalizationPhase.WAITING
                }
                if (previousPhase != RelocalizationPhase.RELOCALIZED &&
                    relocalizationPhase == RelocalizationPhase.RELOCALIZED) {
                    // Only after matching succeeds do the expensive full texture
                    // extraction and fused-map rendering. During global search the
                    // static loaded map remains visible and CPU stays with SLAM.
                    val alignedSubmaps = carto.getSubmapTextures()
                    latestSubmapCount = alignedSubmaps.size
                    val alignedFetchMs = SystemClock.elapsedRealtime()
                    lastSubmapFetchMs = alignedFetchMs
                    lastFullSubmapFetchMs = alignedFetchMs
                    runOnUiThread {
                        mapView.setSubmapTextures(alignedSubmaps)
                        replaceMeasurementTextures(alignedSubmaps)
                        btnFinishMapping.isEnabled = true
                        show("✅ 已与旧地图建立稳定约束，可以开始正式补采")
                    }
                }
            } catch (e: Exception) {
                Log.e("CartographerJNI", "查询重定位状态失败", e)
            } finally {
                isRelocalizationStatusFetchRunning = false
            }
        }.start()
    }

    private fun finishMapping() {
        if (!isMappingRunning || isFinishingMapping) {
            Toast.makeText(this, "当前没有正在运行的建图轨迹", Toast.LENGTH_SHORT).show()
            return
        }
        if (mappingMode == MappingMode.CONTINUE_MAPPING &&
            relocalizationPhase != RelocalizationPhase.RELOCALIZED) {
            show("补采轨迹尚未与旧地图对齐，请继续在已建区域缓慢移动；不需要本次数据可点取消补采")
            return
        }

        val onlineResultBeforeFinish = if (mappingMode == MappingMode.NORMAL_MAPPING) {
            onlineMagneticNorthCalibrator.getResult().also {
                latestOnlineMagneticResult = it
            }
        } else null
        if (mappingMode == MappingMode.NORMAL_MAPPING &&
            onlineResultBeforeFinish?.isReady != true) {
            val result = onlineResultBeforeFinish ?: OnlineMagneticNorthCalibrator.Result()
            val nextAction = when {
                result.isSaturated -> "磁力计数据已超量程，请改变设备姿态后再走一小段"
                result.headingCoverageDegrees < 90f -> "请再经过一个明显转弯"
                result.sampleCount < 30 -> "请再走动一小段以补充有效样本"
                else -> "请保持当前建图流程再走动一小段"
            }
            show(
                "暂时无法完成南北对齐：${result.failureReason}。方向覆盖 ${
                    String.format(Locale.US, "%.0f°", result.headingCoverageDegrees)
                }，$nextAction"
            )
            return
        }

        isFinishingMapping = true
        setDataReceivePaused(true)
        btnFinishMapping.isEnabled = false
        btnSaveMap.isEnabled = false
        show("正在结束建图并完成优化…")

        Thread {
            try {
                carto.finishTrajectory()
                val finalPose = carto.getLastFinishedPose()?.to2D()
                finishedMapPose = finalPose
                // 新建地图用走行过程中在线估计的磁北，并用最终优化轨迹复算一次；
                // 补采地图沿用加载地图时恢复的北向。
                if (mappingMode == MappingMode.CONTINUE_MAPPING) {
                    pendingNorthAlignment = loadedMapNorthAlignment
                } else {
                    val optimizedNodes = carto.getTrajectoryNodePoseSamples()
                    val optimizedResult = onlineMagneticNorthCalibrator.getResult(optimizedNodes)
                    val finalResult = if (optimizedResult.isReady) {
                        optimizedResult
                    } else {
                        onlineResultBeforeFinish!!
                    }
                    latestOnlineMagneticResult = finalResult
                    val headingState = magneticHeadingProvider.getState()
                    pendingNorthAlignment = MapNorthAlignment(
                        magneticNorthYawRadians = normalizeRadians(
                            finalResult.magneticNorthYawRadians.toDouble() +
                                DEVICE_TO_ROBOT_HEADING_OFFSET_RAD
                        ).toFloat(),
                        headingAccuracy = headingState.accuracy,
                        sampleTimestampNs = headingState.timestampNs
                    )
                }
                val optimizedSubmaps = carto.getSubmapTextures()
                val optimizedTrajectory = carto.getTrajectoryNodePoses()
                finalizedSubmaps = optimizedSubmaps
                isMappingRunning = false
                isMappingFinished = true
                runOnUiThread {
                    latestSubmapCount = optimizedSubmaps.size
                    mapView.setSubmapTextures(optimizedSubmaps)
                    replaceMeasurementTextures(optimizedSubmaps)
                    mapView.setOptimizedTrajectory(optimizedTrajectory)
                    val hasNorthAlignment = pendingNorthAlignment != null
                    btnSaveMap.isEnabled = hasNorthAlignment
                    btnContinueMapping.isEnabled = hasNorthAlignment
                    btnCancelContinueMapping.visibility = View.GONE
                    btnMapNorth.visibility = View.GONE
                    if (hasNorthAlignment) {
                        show(if (mappingMode == MappingMode.CONTINUE_MAPPING) {
                            "补采与优化已完成，已沿用原地图北向，可以保存扩展地图"
                        } else if (latestOnlineMagneticResult.isHighInterference) {
                            "建图与优化已完成，已自动完成南北对齐；当前环境磁干扰较强"
                        } else {
                            "建图与优化已完成，已根据行走过程自动完成南北对齐"
                        })
                    } else {
                        show("地图缺少可靠的磁北方向，无法生成南北对齐户型图")
                    }
                }
            } catch (e: Exception) {
                Log.e("CartographerJNI", "结束建图失败", e)
                setDataReceivePaused(false)
                runOnUiThread {
                    btnFinishMapping.isEnabled = true
                    show("结束建图失败：${e.message}")
                }
            } finally {
                isFinishingMapping = false
            }
        }.start()
    }

    private fun saveCurrentMap() {
        if (!isMappingFinished) {
            Toast.makeText(this, "请先结束建图", Toast.LENGTH_SHORT).show()
            return
        }
        if (mappingMode == MappingMode.CONTINUE_MAPPING &&
            relocalizationPhase != RelocalizationPhase.RELOCALIZED) {
            Toast.makeText(this, "补采轨迹未与旧地图对齐，禁止保存扩展地图", Toast.LENGTH_LONG).show()
            return
        }

        btnSaveMap.isEnabled = false
        show("正在保存地图并生成户型图，请稍候…")
        Thread {
            val mapDir = File(externalAppFilesDir, "maps")
            if (!mapDir.exists()) mapDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val prefix = if (mappingMode == MappingMode.CONTINUE_MAPPING) {
                "cartographer_extended"
            } else {
                "cartographer"
            }
            val output = File(mapDir, "${prefix}_$timestamp.pbstream")
            val ok = try {
                carto.saveMap(output.absolutePath)
            } catch (e: Exception) {
                Log.e("CartographerJNI", "保存地图失败", e)
                false
            }
            val alignmentForSave = pendingNorthAlignment
            val metadataSaved = ok && alignmentForSave != null &&
                MapMetadataStore.save(output, alignmentForSave)
            if (ok) {
                saveMarkersToFile(output)
            }
            var publicMapLocation: String? = null
            var publicMapExportError: String? = null
            if (ok) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED) {
                    pendingLegacyMapExport = output
                    runOnUiThread {
                        legacyStoragePermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    }
                } else {
                    try {
                        publicMapLocation = writeMapToDownloads(output)
                    } catch (e: Exception) {
                        Log.e("CartographerJNI", "导出地图到 Downloads 失败", e)
                        publicMapExportError = e.message ?: "未知错误"
                    }
                }
                runOnUiThread {
                    savedMapFile = output
                    savedMapNorthAlignment = alignmentForSave
                    btnMapNorth.visibility = View.GONE
                }
            }

            // finishMapping already extracted the complete optimized texture set.
            // Reusing it avoids holding two full JNI pixel copies in memory.
            val finalizedTextures = finalizedSubmaps

            val floorPlanOutput = try {
                exportFloorPlanMaps(timestamp, finalizedTextures)
            } catch (e: Exception) {
                Log.e("CartographerJNI", "导出户型图输入地图失败", e)
                null
            }

            val floorPlanResult = try {
                floorPlanOutput?.let { generateFloorPlan(timestamp, it) }
            } catch (e: Exception) {
                Log.e("CartographerJNI", "生成户型图失败", e)
                null
            }

            val floorPlanRotationDegrees = alignmentForSave?.let {
                Math.toDegrees(it.magneticNorthYawRadians.toDouble()).toFloat()
            } ?: 0f
            val alignedFloorPlanResult = floorPlanResult?.let {
                rotateGeneratedFloorPlanToNorth(it, floorPlanRotationDegrees)
            }
            val alignedFallback = if (alignedFloorPlanResult == null) {
                floorPlanOutput?.visual?.let {
                    rotateFloorPlanFileToNorth(it, floorPlanRotationDegrees, "north_right")
                }
            } else null

            runOnUiThread {
                btnSaveMap.isEnabled = true
                // Start the on-screen final fusion only after export/generation
                // has released its large temporary grids and bitmaps. Running
                // both fusions concurrently can exhaust memory on supplement maps.
                if (finalizedTextures.isNotEmpty()) {
                    mapView.setFinalizedSubmapTextures(finalizedTextures)
                }
                val floorPlanText = updateCurrentFloorPlan(
                    floorPlanOutput,
                    alignedFloorPlanResult,
                    alignedFallback,
                    floorPlanRotationDegrees
                )
                val mapText = if (ok) {
                    when {
                        publicMapLocation != null -> "地图已保存：$publicMapLocation"
                        publicMapExportError != null ->
                            "地图已保存在应用内：${output.absolutePath}\n" +
                                "导出到 Downloads/CartographerMaps 失败：$publicMapExportError"
                        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
                            "地图已保存在应用内，授权后将导出到 Downloads/CartographerMaps"
                        else -> "地图已保存：${output.absolutePath}"
                    }
                } else {
                    "地图保存失败"
                }
                val northText = if (!ok) {
                    ""
                } else when {
                    alignmentForSave == null -> "\n磁北方向：点击地图上的南北按钮进行校准"
                    metadataSaved -> "\n磁北方向信息：已保存"
                    else -> "\n地图已保存，但磁北方向元数据保存失败"
                }
                show("$mapText\n$floorPlanText$northText")
            }
        }.start()
    }

    private fun cancelContinueMapping() {
        if (!isMappingRunning || mappingMode != MappingMode.CONTINUE_MAPPING || isFinishingMapping) {
            show("当前没有可取消的补采任务")
            return
        }
        isFinishingMapping = true
        setDataReceivePaused(true)
        btnFinishMapping.isEnabled = false
        btnCancelContinueMapping.isEnabled = false
        Thread {
            try {
                carto.finishTrajectory()
                finishedMapPose = carto.getLastFinishedPose()?.to2D()
            } catch (e: Exception) {
                Log.w("CartographerJNI", "取消补采时结束轨迹失败", e)
            } finally {
                carto.reset()
                isMappingRunning = false
                isMappingFinished = false
                isFinishingMapping = false
                relocalizationPhase = RelocalizationPhase.NOT_REQUIRED
                runOnUiThread {
                    mapView.clearMap()
                    mapView.setSubmapTextures(continueBaseSubmaps)
                    replaceMeasurementTextures(continueBaseSubmaps)
                    savedMapFile = continueSourceMapFile
                    savedMapNorthAlignment = loadedMapNorthAlignment
                    btnMapNorth.visibility = View.GONE
                    btnContinueMapping.isEnabled = true
                    btnCancelContinueMapping.isEnabled = true
                    btnCancelContinueMapping.visibility = View.GONE
                    show("已取消本次补采，未保存未对齐轨迹")
                }
            }
        }.start()
    }

    private fun alignMapToMagneticNorth() {
        if (isMappingRunning || isFinishingMapping) {
            show("建图过程中正在自动完成地磁校准")
            return
        }
        if (savedMapFile == null && isMappingFinished) {
            val alignment = pendingNorthAlignment
            if (alignment == null) {
                show("该地图没有可靠的磁北方向")
            } else {
                showNorthOverview(alignment)
            }
            return
        }
        val mapFile = savedMapFile
        val alignment = savedMapNorthAlignment
        if (mapFile == null || !mapFile.exists()) {
            show("请先完成并保存地图")
            return
        }
        if (alignment == null) {
            show("该地图没有南北方向信息")
            return
        }
        showNorthOverview(alignment)
    }

    private fun showNorthOverview(alignment: MapNorthAlignment) {
        val measurement = currentMapMeasurement
        if (measurement == null) {
            show("地图尺寸仍在计算，暂时无法自动居中")
            return
        }
        if (mapView.showMagneticNorthOverview(
                measurement,
                alignment.magneticNorthYawRadians
            )) {
            show("地图已按磁北方向居中：左侧为南，右侧为北")
        } else {
            show("地图范围不足，无法自动居中")
        }
    }

    private fun formatMagneticAccuracy(accuracy: Int): String = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高(3)"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中(2)"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低(1)"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "未标定(0)"
        else -> "未知($accuracy)"
    }

    private fun formatMagneticDispersion(dispersionDegrees: Float): String {
        return if (dispersionDegrees.isFinite()) {
            String.format(Locale.US, "%.1f°", dispersionDegrees)
        } else {
            "--"
        }
    }

    private fun normalizeRadians(value: Double): Double {
        val fullTurn = Math.PI * 2.0
        var normalized = value % fullTurn
        if (normalized < 0.0) normalized += fullTurn
        return normalized
    }

    private fun clearMapMeasurement() {
        measurementGeneration++
        measurementTextures.clear()
        currentMapMeasurement = null
        measurementCalculationDirty = isMeasurementCalculationRunning
        mapView.setMapMeasurement(null)
        tvMapMeasurement.text = "地图尺寸：--"
    }

    private fun replaceMeasurementTextures(textures: List<SubmapTexture>) {
        measurementTextures.clear()
        for (texture in textures) measurementTextures[texture.key] = texture
        measurementGeneration++
        requestMapMeasurementCalculation()
    }

    private fun mergeMeasurementTextures(textures: List<SubmapTexture>) {
        var changed = false
        for (texture in textures) {
            val previous = measurementTextures[texture.key]
            if (previous == null || previous.version != texture.version ||
                !previous.hasSameMeasurementPose(texture)) {
                measurementTextures[texture.key] = texture
                changed = true
            }
        }
        if (!changed) return
        measurementGeneration++
        requestMapMeasurementCalculation()
    }

    private fun requestMapMeasurementCalculation() {
        if (isMeasurementCalculationRunning) {
            measurementCalculationDirty = true
            return
        }
        val snapshot = measurementTextures.values.toList()
        if (snapshot.isEmpty()) {
            currentMapMeasurement = null
            mapView.setMapMeasurement(null)
            tvMapMeasurement.text = "地图尺寸：--"
            return
        }

        val generation = measurementGeneration
        isMeasurementCalculationRunning = true
        measurementCalculationDirty = false
        if (currentMapMeasurement == null) tvMapMeasurement.text = "正在计算地图尺寸…"
        Thread {
            val measurement = try {
                MapMeasurementCalculator.calculate(snapshot)
            } catch (e: Exception) {
                Log.e("MapMeasurement", "计算地图尺寸失败", e)
                null
            }
            runOnUiThread {
                isMeasurementCalculationRunning = false
                if (generation == measurementGeneration) {
                    currentMapMeasurement = measurement
                    mapView.setMapMeasurement(measurement)
                    updateMeasurementDisplay()
                    val alignment = if (isMappingFinished) {
                        pendingNorthAlignment ?: savedMapNorthAlignment
                    } else null
                    if (measurement != null && alignment != null) {
                        mapView.showMagneticNorthOverview(
                            measurement,
                            alignment.magneticNorthYawRadians
                        )
                    }
                }
                if (measurementCalculationDirty || generation != measurementGeneration) {
                    measurementCalculationDirty = false
                    requestMapMeasurementCalculation()
                }
            }
        }.start()
    }

    private fun formatMapMeasurement(measurement: MapMeasurement?): String {
        if (measurement == null) return "地图尺寸：--"
        if (measurement.hasSharedFloorPlanGeometry()) {
            return String.format(
                Locale.US,
                "地图/户型图统一边界：长 %.2f m　宽 %.2f m\n闭合轮廓面积：%.2f m²",
                measurement.lengthMeters,
                measurement.widthMeters,
                measurement.boundingAreaSquareMeters
            )
        }
        return String.format(
            Locale.US,
            "地图全局范围：%.2f m × %.2f m\n长：%.2f m　宽：%.2f m\n矩形范围面积：%.2f m²",
            measurement.xSizeMeters,
            measurement.ySizeMeters,
            measurement.lengthMeters,
            measurement.widthMeters,
            measurement.boundingAreaSquareMeters
        )
    }

    private fun SubmapTexture.hasSameMeasurementPose(other: SubmapTexture): Boolean {
        return kotlin.math.abs(originX - other.originX) < 1e-4f &&
            kotlin.math.abs(originY - other.originY) < 1e-4f &&
            kotlin.math.abs(theta - other.theta) < 1e-4f
    }

    private fun generateFloorPlan(
        timestamp: Long,
        export: FloorPlanExportResult
    ): FloorPlanGenerationResult? {
        val output = File(export.sessionDir, "floorplan_result_$timestamp.png")
        val workDir = File(export.sessionDir, "work")
        val nativeGeneration = floorPlanNative.generate(
            input = export.input,
            visualInput = export.visual,
            semanticInput = export.semantic,
            output = output,
            workDir = workDir,
            metersPerPixel = export.geometry.resolutionMetersPerPixel,
            trajectoryPixels = export.trajectoryPixels
        )
        if (nativeGeneration == null) {
            return generateFallbackFloorPlan(output, export)
        }
        if (!output.exists()) return null
        val rssiSnapshot = rssiSamples.toList()
        val semanticBitmap = BitmapFactory.decodeFile(export.semantic.absolutePath)
        val heatMapCoverage = semanticBitmap?.let { semantic ->
            try {
                HeatMapCoverageCalculator.calculate(
                    samples = rssiSnapshot,
                    geometry = export.geometry,
                    outlinePixels = nativeGeneration.outlineVerticesPixels,
                    semanticMap = semantic
                )
            } finally {
                semantic.recycle()
            }
        }
        val generation = nativeGeneration.copy(
            heatMapCoveragePercent = heatMapCoverage?.coveragePercent
        )
        var annotation = FloorPlanImageAnnotator.annotateFile(
            file = output,
            generation = generation,
            metersPerPixel = export.geometry.resolutionMetersPerPixel
        )
        if (!annotation.annotated) {
            Log.w("FloorPlan", "户型图尺寸标注失败：${annotation.failureReason}")
            annotation = FloorPlanImageAnnotator.annotateFallbackFile(
                file = output,
                metersPerPixel = export.geometry.resolutionMetersPerPixel,
                heatMapCoveragePercent = generation.heatMapCoveragePercent
            )
        }
        val length = annotation.lengthMeters.takeIf { it.isFinite() && it > 0f }
            ?: (generation.dimensionLongSizePixels *
                export.geometry.resolutionMetersPerPixel)
        val width = annotation.widthMeters.takeIf { it.isFinite() && it > 0f }
            ?: (generation.dimensionShortSizePixels *
                export.geometry.resolutionMetersPerPixel)
        val footprintArea = generation.footprintAreaPixelsSquared *
            export.geometry.resolutionMetersPerPixel *
            export.geometry.resolutionMetersPerPixel
        val dimensions = FloorPlanDimensions(
            lengthMeters = length,
            widthMeters = width,
            areaSquareMeters = footprintArea,
            source = FloorPlanDimensionSource.FITTED_OUTLINE
        ).takeIf { it.isValid() }
        val sharedMapMeasurement = MapMeasurementCalculator.fromFloorPlan(
            generation = generation,
            geometry = export.geometry
        )
        val clippedOccupancy = File(workDir, "occupancy_clipped.png")
            .takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (clippedOccupancy != null) {
            runOnUiThread {
                mapView.setFinalizedOccupancyBitmap(clippedOccupancy)
            }
        }

        val displayFloorPlanOverlayFile = File(workDir, "floorplan_display_overlay.png")
        File(workDir, "floorplan_overlay.png")
            .takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
            ?.let { sourceOverlay ->
                val labeled = FloorPlanDimensionOverlayRenderer.render(
                    floorPlanOverlay = sourceOverlay,
                    generation = generation,
                    metersPerPixel = export.geometry.resolutionMetersPerPixel
                )
                sourceOverlay.recycle()
                if (labeled != null) {
                    try {
                        FileOutputStream(displayFloorPlanOverlayFile).use { stream ->
                            if (!labeled.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                throw IOException("透明户型图层写入失败")
                            }
                        }
                    } finally {
                        labeled.recycle()
                    }
                }
            }

        val heatMapOverlayFile = File(workDir, "heatmap_overlay.png")
        try {
            HeatMapRenderer.render(
                samples = rssiSnapshot,
                geometry = export.geometry,
                outlinePixels = generation.outlineVerticesPixels,
                supportMask = heatMapCoverage?.supportMask
            )?.let { heatMap ->
                try {
                    FileOutputStream(heatMapOverlayFile).use { stream ->
                        if (!heatMap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw IOException("透明热力图层写入失败")
                        }
                    }
                } finally {
                    heatMap.recycle()
                }
            } ?: Log.i("HeatMap", "有效 RSSI 轨迹不足，跳过热力图层")
        } finally {
            heatMapCoverage?.supportMask?.recycle()
        }

        val trajectoryOverlayFile = File(workDir, "trajectory_overlay.png")
        TrajectoryOverlayRenderer.render(
            width = export.geometry.widthPx,
            height = export.geometry.heightPx,
            points = export.trajectoryPixels
        )?.let { trajectoryOverlay ->
            try {
                FileOutputStream(trajectoryOverlayFile).use { stream ->
                    if (!trajectoryOverlay.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("运动轨迹图层写入失败")
                    }
                }
            } finally {
                trajectoryOverlay.recycle()
            }
        }

        val markersOverlayFile = File(workDir, "markers_overlay.png")
        MarkersOverlayRenderer.render(
            width = export.geometry.widthPx,
            height = export.geometry.heightPx,
            markers = mapView.exportMarkers(),
            geometry = export.geometry,
            dotIcon = mapView.markerIconFor(LidarMapView.MarkerType.DOT),
            routerIcon = mapView.markerIconFor(LidarMapView.MarkerType.ROUTER)
        )?.let { markersOverlay ->
            try {
                FileOutputStream(markersOverlayFile).use { stream ->
                    if (!markersOverlay.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("标注点图层写入失败")
                    }
                }
            } finally {
                markersOverlay.recycle()
            }
        }
        return FloorPlanGenerationResult(
            file = output,
            annotation = annotation,
            dimensions = dimensions,
            heatMapCoveragePercent = generation.heatMapCoveragePercent,
            outlineClosed = generation.outlineClosed,
            outlineVerticesPixels = generation.outlineVerticesPixels,
            sharedMapMeasurement = sharedMapMeasurement,
            structuralMap = File(workDir, "best_structural_map.png")
                .takeIf { it.exists() },
            floorPlanOverlay = displayFloorPlanOverlayFile.takeIf { it.exists() },
            heatMapOverlay = heatMapOverlayFile.takeIf { it.exists() },
            trajectoryOverlay = trajectoryOverlayFile.takeIf { it.exists() },
            markersOverlay = markersOverlayFile.takeIf { it.exists() }
        )
    }

    private fun generateFallbackFloorPlan(
        output: File,
        export: FloorPlanExportResult
    ): FloorPlanGenerationResult? {
        return try {
            export.visual.copyTo(output, overwrite = true)
            val annotation = FloorPlanImageAnnotator.annotateFallbackFile(
                file = output,
                metersPerPixel = export.geometry.resolutionMetersPerPixel
            )
            val fallbackDimensions = if (
                annotation.lengthMeters.isFinite() && annotation.lengthMeters > 0f &&
                annotation.widthMeters.isFinite() && annotation.widthMeters > 0f &&
                annotation.boundingAreaSquareMeters.isFinite() &&
                annotation.boundingAreaSquareMeters > 0f
            ) {
                FloorPlanDimensions(
                    lengthMeters = annotation.lengthMeters,
                    widthMeters = annotation.widthMeters,
                    areaSquareMeters = annotation.boundingAreaSquareMeters,
                    source = FloorPlanDimensionSource.MAP_PREVIEW
                )
            } else {
                export.previewDimensions
            }
            FloorPlanGenerationResult(
                file = output,
                annotation = annotation,
                dimensions = fallbackDimensions,
                outlineClosed = annotation.annotated,
                sharedMapMeasurement = null,
                structuralMap = null
            )
        } catch (e: Exception) {
            Log.e("FloorPlan", "生成户型图最终尺寸兜底失败", e)
            null
        }
    }

    private fun rotateGeneratedFloorPlanToNorth(
        result: FloorPlanGenerationResult,
        rotationDegrees: Float
    ): FloorPlanGenerationResult {
        val rotated = rotateFloorPlanFileToNorth(
            result.file,
            rotationDegrees,
            "north_right"
        ) ?: return result
        return result.copy(file = rotated)
    }

    private fun rotateFloorPlanFileToNorth(
        source: File,
        rotationDegrees: Float,
        suffix: String
    ): File? {
        if (!source.exists()) return null
        if (!rotationDegrees.isFinite() || kotlin.math.abs(rotationDegrees) < 0.01f) {
            return source
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return null
            val rotated = FloorPlanBitmapRotator.rotate(
                bitmap,
                rotationDegrees,
                android.graphics.Color.WHITE
            )
            val output = File(
                source.parentFile,
                "${source.nameWithoutExtension}_${suffix}.${source.extension.ifEmpty { "png" }}"
            )
            try {
                FileOutputStream(output).use { stream ->
                    if (!rotated.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("北向户型图写入失败")
                    }
                }
            } finally {
                if (rotated !== bitmap && !rotated.isRecycled) rotated.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            output
        } catch (error: Exception) {
            Log.e("FloorPlan", "旋转北向户型图失败", error)
            null
        }
    }

    private fun updateCurrentFloorPlan(
        export: FloorPlanExportResult?,
        generated: FloorPlanGenerationResult?,
        alignedFallback: File? = null,
        rotationDegrees: Float = 0f
    ): String {
        val selected = generated?.file?.takeIf { it.exists() }
            // The visual fallback is the complete occupancy-grid presentation:
            // gray unknown, white explored floor and black occupied boundary.
            // Algorithm input remains a separate wall-only structural raster.
            ?: alignedFallback?.takeIf { it.exists() }
            ?: export?.visual?.takeIf { it.exists() }
        if (selected == null) {
            currentFloorPlanResult = null
            currentFloorPlanDimensions = null
            currentFloorPlanCoveragePercent = null
            currentFloorPlanWarning = null
            btnSaveFloorPlan.isEnabled = false
            clearFloorPlanLayers()
            updateMeasurementDisplay()
            return "户型图 PNG 导出失败"
        }

        val layerExport = export ?: run {
            clearFloorPlanLayers()
            currentFloorPlanResult = null
            currentFloorPlanCoveragePercent = null
            btnSaveFloorPlan.isEnabled = false
            return "点云图层数据缺失"
        }
        val pointCloud = BitmapFactory.decodeFile(layerExport.visual.absolutePath)
        if (pointCloud == null) {
            clearFloorPlanLayers()
            currentFloorPlanResult = null
            currentFloorPlanCoveragePercent = null
            btnSaveFloorPlan.isEnabled = false
            return "点云图层解码失败"
        }

        currentFloorPlanResult = selected
        currentFloorPlanDimensions = generated?.dimensions ?: layerExport.previewDimensions
        currentFloorPlanCoveragePercent = generated?.heatMapCoveragePercent
        generated?.sharedMapMeasurement?.let { sharedMeasurement ->
            // Invalidate any older asynchronous raster-bounds calculation and
            // drive the map overlay from the same fitted outline used by the
            // floor-plan PNG.
            measurementGeneration++
            currentMapMeasurement = sharedMeasurement
            mapView.setMapMeasurement(sharedMeasurement)
        }
        currentFloorPlanWarning = when {
            generated == null -> "绿色拟合户型图生成失败，已回退到完整占据栅格图"
            !generated.annotation.annotated -> "户型图已生成，但尺寸标注失败：" +
                (generated.annotation.failureReason ?: "未知原因")
            !generated.outlineClosed -> "拟合轮廓未完全闭合，尺寸按绿色拟合边界外接范围计算"
            generated.heatMapCoveragePercent == null ->
                "没有足够的有效 RSSI 轨迹，无法评估热力图覆盖率"
            generated.heatMapCoveragePercent < 60f ->
                "热力图覆盖不足，建议继续行走采集"
            generated.heatMapCoveragePercent < 80f ->
                "热力图存在部分盲区，建议补充采集"
            else -> null
        }
        btnSaveFloorPlan.isEnabled = true
        setFloorPlanLayers(
            FloorPlanLayers(
                width = layerExport.geometry.widthPx,
                height = layerExport.geometry.heightPx,
                pointCloud = pointCloud,
                pointCloudOutline = generated?.outlineVerticesPixels,
                heatMap = generated?.heatMapOverlay?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                },
                trajectory = generated?.trajectoryOverlay?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                },
                floorPlan = generated?.floorPlanOverlay?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                },
                rotationDegrees = rotationDegrees,
                markers = generated?.markersOverlay?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                }
            )
        )
        updateMeasurementDisplay()

        val resultDescription = when {
            generated?.annotation?.annotated == true -> "原效果户型图及四边尺寸标注已生成"
            generated != null -> "原效果户型图已生成，可使用原按钮保存"
            else -> "绿色拟合结果不可用，完整占据栅格图仍可使用原按钮保存"
        }
        val dimensionsText = formatFloorPlanDimensions(currentFloorPlanDimensions)
        val coverageText = currentFloorPlanCoveragePercent
            ?.let { "\n${formatHeatMapCoverage(it)}" }.orEmpty()
        val warningText = currentFloorPlanWarning?.let { "\n$it" }.orEmpty()
        return "$resultDescription\n$dimensionsText$coverageText$warningText"
    }

    private fun setFloorPlanLayers(layers: FloorPlanLayers) {
        clearFloorPlanLayers()
        floorPlanLayers = layers
        checkPointCloud.isEnabled = layers.pointCloud != null
        checkFloorPlanOverlay.isEnabled = layers.floorPlan != null
        checkHeatMapOverlay.isEnabled = layers.heatMap != null
        checkTrajectoryOverlay.isEnabled = layers.trajectory != null
        checkMarkersOverlay.isEnabled = layers.markers != null
        checkHeatMapOverlay.text = if (layers.heatMap != null) {
            "热力图"
        } else {
            "热力图（无数据）"
        }
        refreshFloorPlanLayerPreview()
    }

    private fun refreshFloorPlanLayerPreview() {
        val layers = floorPlanLayers ?: return
        val output = FloorPlanLayerComposer.compose(
            layers,
            FloorPlanLayerVisibility(
                pointCloud = checkPointCloud.isChecked,
                heatMap = checkHeatMapOverlay.isChecked,
                trajectory = checkTrajectoryOverlay.isChecked,
                floorPlan = checkFloorPlanOverlay.isChecked,
                markers = checkMarkersOverlay.isChecked
            )
        ) ?: return
        val previous = floorPlanDisplayBitmap
        floorPlanDisplayBitmap = output
        floorPlanView.setImageBitmap(output)
        if (previous !== output && previous?.isRecycled == false) previous.recycle()
    }

    private fun clearFloorPlanLayers() {
        floorPlanView.setImageBitmap(null)
        floorPlanDisplayBitmap?.takeIf { !it.isRecycled }?.recycle()
        floorPlanDisplayBitmap = null
        floorPlanLayers?.let { layers ->
            listOfNotNull(
                layers.pointCloud,
                layers.heatMap,
                layers.trajectory,
                layers.floorPlan,
                layers.markers
            ).distinct().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        floorPlanLayers = null
        if (::checkHeatMapOverlay.isInitialized) {
            checkHeatMapOverlay.text = "热力图"
        }
    }

    private fun formatFloorPlanDimensions(dimensions: FloorPlanDimensions?): String {
        if (dimensions == null || !dimensions.isValid()) return "户型图尺寸/面积：--"
        val label = if (dimensions.source == FloorPlanDimensionSource.FITTED_OUTLINE) {
            "户型图绿色拟合边界"
        } else {
            "户型图预览范围"
        }
        val areaLabel = if (dimensions.source == FloorPlanDimensionSource.FITTED_OUTLINE) {
            "闭合轮廓面积"
        } else {
            "范围面积"
        }
        return String.format(
            Locale.US,
            "%s：长 %.2f m × 宽 %.2f m，%s %.2f m²",
            label,
            dimensions.lengthMeters,
            dimensions.widthMeters,
            areaLabel,
            dimensions.areaSquareMeters
        )
    }

    private fun formatHeatMapCoverage(percent: Float): String =
        String.format(Locale.US, "热力图有效覆盖率：%.1f%%", percent)

    private fun updateMeasurementDisplay() {
        val mapText = formatMapMeasurement(currentMapMeasurement)
        val floorPlanText = currentFloorPlanDimensions?.let { "\n${formatFloorPlanDimensions(it)}" }
            .orEmpty()
        val coverageText = currentFloorPlanCoveragePercent
            ?.let { "\n${formatHeatMapCoverage(it)}" }.orEmpty()
        tvMapMeasurement.text = mapText + floorPlanText + coverageText
    }

    private fun saveCurrentFloorPlan() {
        val source = currentFloorPlanResult
        if (source == null || !source.exists()) {
            Toast.makeText(this, "还没有可保存的户型图", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED) {
            pendingLegacyFloorPlanSave = source
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveFloorPlanFile(source)
    }

    private fun saveFloorPlanFile(source: File) {
        if (!source.exists()) {
            show("还没有可保存的户型图")
            return
        }
        val dimensions = currentFloorPlanDimensions
        val coveragePercent = currentFloorPlanCoveragePercent
        val warning = currentFloorPlanWarning
        btnSaveFloorPlan.isEnabled = false
        Thread {
            try {
                val location = writeFloorPlanToDownloads(source)
                runOnUiThread {
                    btnSaveFloorPlan.isEnabled = currentFloorPlanResult?.exists() == true
                    updateMeasurementDisplay()
                    val dimensionsText = formatFloorPlanDimensions(dimensions)
                    val coverageText = coveragePercent
                        ?.let { "\n${formatHeatMapCoverage(it)}" }.orEmpty()
                    val warningText = warning?.let { "\n注意：$it" }.orEmpty()
                    show("户型图已保存：$location\n$dimensionsText$coverageText$warningText")
                }
            } catch (e: Exception) {
                Log.e("CartographerJNI", "保存户型图失败", e)
                runOnUiThread {
                    btnSaveFloorPlan.isEnabled = currentFloorPlanResult?.exists() == true
                    show("保存户型图失败：${e.message ?: "未知错误"}")
                }
            }
        }.start()
    }

    private fun writeFloorPlanToDownloads(source: File): String {
        val displayName = "floorplan_saved_${System.currentTimeMillis()}.png"
        return writeFileToDownloads(source, displayName, "image/png", "户型图")
    }

    private fun writeMapToDownloads(source: File): String =
        writeFileToDownloads(
            source = source,
            displayName = source.name,
            mimeType = "application/octet-stream",
            description = "地图"
        )

    private fun writeFileToDownloads(
        source: File,
        displayName: String,
        mimeType: String,
        description: String
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/CartographerMaps"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在 Downloads 中创建$description")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("无法打开$description 保存位置")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "${Environment.DIRECTORY_DOWNLOADS}/CartographerMaps/$displayName"
        }

        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CartographerMaps"
        )
        if (!publicDir.exists() && !publicDir.mkdirs()) {
            throw IOException("无法创建 Downloads/CartographerMaps")
        }
        val output = File(publicDir, displayName)
        source.copyTo(output, overwrite = false)
        MediaScannerConnection.scanFile(
            this,
            arrayOf(output.absolutePath),
            arrayOf(mimeType),
            null
        )
        return output.absolutePath
    }

    private fun exportFloorPlanMaps(
        timestamp: Long,
        textures: List<SubmapTexture>
    ): FloorPlanExportResult? {
        if (textures.isEmpty()) return null
        // Render the finalized submaps once. The on-screen final map and all
        // floor-plan inputs now use the same confidence fusion, dimensions and
        // world-to-pixel transform instead of independently compositing raw
        // submaps with four different alpha decisions.
        val fusedRender = FusedMapRenderer.render(textures, finalized = true)
            ?: return null
        val renders = try {
            val input = FloorPlanMapExporter.renderFusedWithGeometry(
                fusedRender,
                FloorPlanMapExporter.Style.ALGORITHM_INPUT
            ) ?: return null
            val visual = FloorPlanMapExporter.renderFusedWithGeometry(
                fusedRender,
                FloorPlanMapExporter.Style.VISUAL_MAP
            ) ?: run {
                input.bitmap.recycle()
                return null
            }
            val semantic = FloorPlanMapExporter.renderFusedWithGeometry(
                fusedRender,
                FloorPlanMapExporter.Style.SEMANTIC
            ) ?: run {
                input.bitmap.recycle()
                visual.bitmap.recycle()
                return null
            }
            val preview = FloorPlanMapExporter.renderFusedWithGeometry(
                fusedRender,
                FloorPlanMapExporter.Style.PREVIEW
            ) ?: run {
                input.bitmap.recycle()
                visual.bitmap.recycle()
                semantic.bitmap.recycle()
                return null
            }
            arrayOf(input, visual, semantic, preview)
        } finally {
            fusedRender.bitmap.recycle()
        }
        val inputRender = renders[0]
        val visualRender = renders[1]
        val semanticRender = renders[2]
        val previewBitmap = renders[3].bitmap
        val sessionDir = File(externalAppFilesDir, "floorplans/$timestamp")
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            inputRender.bitmap.recycle()
            visualRender.bitmap.recycle()
            semanticRender.bitmap.recycle()
            previewBitmap.recycle()
            throw IOException("无法创建户型图内部目录")
        }
        val inputOutput = File(sessionDir, "floorplan_input_$timestamp.png")
        val visualOutput = File(sessionDir, "floorplan_visual_$timestamp.png")
        val semanticOutput = File(sessionDir, "floorplan_semantic_$timestamp.png")
        val previewOutput = File(sessionDir, "floorplan_preview_$timestamp.png")
        try {
            FileOutputStream(inputOutput).use { stream ->
                if (!inputRender.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("户型图算法输入写入失败")
                }
            }
            FileOutputStream(visualOutput).use { stream ->
                if (!visualRender.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("户型图视觉底图写入失败")
                }
            }
            FileOutputStream(semanticOutput).use { stream ->
                if (!semanticRender.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("户型图语义图写入失败")
                }
            }
            FileOutputStream(previewOutput).use { stream ->
                if (!previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("户型图预览写入失败")
                }
            }
        } finally {
            inputRender.bitmap.recycle()
            visualRender.bitmap.recycle()
            semanticRender.bitmap.recycle()
            previewBitmap.recycle()
        }
        val trajectoryPixels = carto.getTrajectoryNodePoses().mapNotNull { point ->
            val pixelX = inputRender.geometry.worldToPixelX(point.x)
            val pixelY = inputRender.geometry.worldToPixelY(point.y)
            if (pixelX.isFinite() && pixelY.isFinite() &&
                pixelX >= 0f && pixelY >= 0f &&
                pixelX < inputRender.geometry.widthPx &&
                pixelY < inputRender.geometry.heightPx) {
                FloorPlanPixelPoint(pixelX, pixelY)
            } else {
                null
            }
        }
        val previewDimensions = try {
            MapMeasurementCalculator.calculate(textures)?.let {
                FloorPlanDimensions(
                    lengthMeters = it.lengthMeters,
                    widthMeters = it.widthMeters,
                    areaSquareMeters = it.boundingAreaSquareMeters,
                    source = FloorPlanDimensionSource.MAP_PREVIEW
                )
            }
        } catch (e: Exception) {
            Log.w("FloorPlan", "计算户型图预览范围失败", e)
            null
        }
        return FloorPlanExportResult(
            input = inputOutput,
            visual = visualOutput,
            semantic = semanticOutput,
            preview = previewOutput,
            geometry = inputRender.geometry,
            sessionDir = sessionDir,
            trajectoryPixels = trajectoryPixels,
            previewDimensions = previewDimensions,
            textures = textures
        )
    }

    private data class FloorPlanExportResult(
        val input: File,
        val visual: File,
        val semantic: File,
        val preview: File,
        val geometry: FloorPlanMapExporter.ExportGeometry,
        val sessionDir: File,
        val trajectoryPixels: List<FloorPlanPixelPoint>,
        val previewDimensions: FloorPlanDimensions?,
        val textures: List<SubmapTexture>
    )

    private data class FloorPlanGenerationResult(
        val file: File,
        val annotation: FloorPlanImageAnnotator.Result,
        val dimensions: FloorPlanDimensions?,
        val heatMapCoveragePercent: Float? = null,
        val outlineClosed: Boolean,
        val outlineVerticesPixels: List<FloorPlanPixelPoint>? = null,
        val sharedMapMeasurement: MapMeasurement?,
        val structuralMap: File?,
        val floorPlanOverlay: File? = null,
        val heatMapOverlay: File? = null,
        val trajectoryOverlay: File? = null,
        val markersOverlay: File? = null
    )

    private enum class FloorPlanDimensionSource {
        FITTED_OUTLINE,
        MAP_PREVIEW
    }

    private data class FloorPlanDimensions(
        val lengthMeters: Float,
        val widthMeters: Float,
        val areaSquareMeters: Float,
        val source: FloorPlanDimensionSource
    ) {
        fun isValid(): Boolean = lengthMeters.isFinite() && widthMeters.isFinite() &&
            areaSquareMeters.isFinite() && lengthMeters > 0f && widthMeters > 0f &&
            areaSquareMeters > 0f
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_USB_PERMISSION) return
            val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (usbPermissionDevice?.deviceName != dev.deviceName) return
            usbPermissionDevice = null
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                Log.i("KaerActivity", "USB 授权成功：%04X:%04X".format(dev.vendorId, dev.productId))
                openUsbDevice(dev)
            } else {
                lidarConnectionStatus = "USB 授权被拒绝，请再次点击开始采集并允许访问"
                show(lidarConnectionStatus)
            }
        }
    }

    /** 设备插入/拔出监听：新雷达插入时自动弹 USB 授权框（参考 temp 工程 ATTACHED 逻辑）。 */
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    Log.i(
                        "KaerActivity",
                        "USB 设备插入: vid=0x%04X pid=0x%04X".format(device.vendorId, device.productId)
                    )
                    val isNewDevice = device.vendorId == SLAM_VID && device.productId == SLAM_PID
                    val isLegacyDevice = device.vendorId == 0x1A86 && device.productId == 0x7523
                    if (isSensorRunning && usbSerialPort == null && (isNewDevice || isLegacyDevice)) {
                        startLidar()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (usbPermissionDevice?.deviceName == device.deviceName) usbPermissionDevice = null
                    if (usbSerialPort?.device?.deviceName == device.deviceName) {
                        closeLidar(sendStop = false)
                        lidarConnectionStatus = "雷达已断开，请重新连接后点击开始采集"
                        show(lidarConnectionStatus)
                    }
                }
            }
        }
    }

    private fun sendLidarCommand(port: UsbSerialPort, cmd1: Int, cmd2: Int) {
        val command = byteArrayOf(0xAA.toByte(), 0x55.toByte(), cmd1.toByte(), cmd2.toByte())
        port.write(command, 100)
        Log.i("LidarFinal", "发送雷达命令：AA 55 %02X %02X".format(cmd1, cmd2))
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onDestroy() {
        uiUpdatesStopped = true
        uiUpdateHandler.removeCallbacksAndMessages(null)
        displayWorker.close()
        magneticFitWorker.close()
        clearFloorPlanLayers()
        super.onDestroy()
        magneticHeadingProvider.stop()
        sensorManager.unregisterListener(this)
        closeLidar()
        usbPermissionDevice = null
        unregisterReceiver(usbReceiver)
        unregisterReceiver(usbAttachReceiver)
        carto.reset()
    }

    private fun show(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
