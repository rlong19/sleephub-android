package com.rlong.sleephub.ring.proto

/** Built-in CRP command probe catalog. DANGEROUS commands are never listed — only their (group,cmd) pairs are blocked. */
enum class ProbeClass { SAFE_READ, SAFE_TOGGLE }

data class ProbeCmd(
    val id: String,
    val group: Int,
    val cmd: Int,
    val payload: ByteArray,
    val cls: ProbeClass,
    val title: String,
    val summary: String,
    val decode: String = "hex",
    val stopPayload: ByteArray? = null,
    val rawStream: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    fun frame(): ByteArray = Frame.build(group, cmd, payload)
    fun stopFrame(): ByteArray? = stopPayload?.let { Frame.build(group, cmd, it) }
}

object ProbeCatalog {
    /** (group,cmd) pairs that must never be sent from the probe UI, including the custom box. */
    val BLOCKED_PAIRS: Set<Pair<Int, Int>> = setOf(
        1 to 0,
        1 to 1,
        1 to 2,
        1 to 3,
        1 to 4,
        1 to 5,
        1 to 6,
        1 to 7,
        1 to 8,
        1 to 12,
        1 to 13,
        1 to 16,
        1 to 17,
        1 to 20,
        1 to 22,
        1 to 23,
        1 to 34,
        1 to 36,
        1 to 37,
        1 to 38,
        1 to 39,
        1 to 40,
        1 to 41,
        3 to 0,
        3 to 1,
        3 to 9,
        3 to 12,
        3 to 14,
        3 to 31,
        4 to 0,
        4 to 1,
        4 to 4,
        4 to 5,
        5 to 1,
        5 to 3,
        5 to 6,
        5 to 8,
        5 to 12,
        6 to 2,
        6 to 12,
        6 to 14,
        7 to 3,
        7 to 11,
        9 to 0,
        9 to 1,
        10 to 4,
        10 to 5,
        10 to 10,
    )

    fun isBlocked(group: Int, cmd: Int) = (group to cmd) in BLOCKED_PAIRS

    val ALL: List<ProbeCmd> = listOf(
        ProbeCmd("01_09_startMeasureHeartRate", 1, 9, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureHeartRate", "One-shot HR (PPG); 00 stops", decode = "u8:bpm", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_0a_startMeasureHrv", 1, 10, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureHrv", "One-shot HRV; 00 stops", decode = "u8:ms", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_0b_startMeasureBloodOxygen", 1, 11, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureBloodOxygen", "One-shot SpO2 (red/IR PPG); 00 stops", decode = "u8:%", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_0e_startMeasureStress", 1, 14, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureStress", "One-shot stress; 00 stops", decode = "u8:stress", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_0f_sendRemoteControlState(a)", 1, 15, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "sendRemoteControlState(a)", "Enable gesture remote-control; 00 off", decode = "hex", stopPayload = byteArrayOf(0x00.toByte())),
        ProbeCmd("01_12_sendMessage", 1, 18, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "sendMessage", "Push notification buzz of type N", decode = "hex"),
        ProbeCmd("01_19_startMeasureBloodPressure", 1, 25, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureBloodPressure", "One-shot BP; 00 stops", decode = "bp", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_20_startMeasureTemp", 1, 32, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startMeasureTemp", "One-shot temp; 00 stops", decode = "temp", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("sensor")),
        ProbeCmd("01_21_enabelShutter", 1, 33, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "enabelShutter", "Camera shutter gesture; 00 off", decode = "hex", stopPayload = byteArrayOf(0x00.toByte())),
        ProbeCmd("01_23_sendRemoteControlState(b)", 1, 35, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "sendRemoteControlState(b)", "Remote-control enable part 2; 00 off", decode = "hex", stopPayload = byteArrayOf(0x00.toByte())),
        ProbeCmd("02_02_queryDailyGoals", 2, 2, byteArrayOf(), ProbeClass.SAFE_READ, "queryDailyGoals", "Daily goals", decode = "goals"),
        ProbeCmd("02_03_queryTrainingDayGoals", 2, 3, byteArrayOf(), ProbeClass.SAFE_READ, "queryTrainingDayGoals", "Training-day goals", decode = "goals"),
        ProbeCmd("02_04_queryActivityReminder", 2, 4, byteArrayOf(), ProbeClass.SAFE_READ, "queryActivityReminder", "Sedentary reminder", decode = "hex"),
        ProbeCmd("02_05_queryHeartRateAlert", 2, 5, byteArrayOf(), ProbeClass.SAFE_READ, "queryHeartRateAlert", "HR alert setting", decode = "hex"),
        ProbeCmd("02_06_queryTimingHeartRateState", 2, 6, byteArrayOf(), ProbeClass.SAFE_READ, "queryTimingHeartRateState", "Periodic HR interval", decode = "u8:min"),
        ProbeCmd("02_07_queryTimingHrvState", 2, 7, byteArrayOf(), ProbeClass.SAFE_READ, "queryTimingHrvState", "Periodic HRV interval", decode = "u8:min"),
        ProbeCmd("02_08_queryTimingBloodOxygenState", 2, 8, byteArrayOf(), ProbeClass.SAFE_READ, "queryTimingBloodOxygenState", "Periodic SpO2 interval", decode = "u8:min"),
        ProbeCmd("02_09_queryHistoryHeartRate", 2, 9, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistoryHeartRate", "History of one-shot HR", decode = "hex"),
        ProbeCmd("02_0a_queryHistoryHrv", 2, 10, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistoryHrv", "History of one-shot HRV", decode = "hex"),
        ProbeCmd("02_0b_queryHistoryBloodOxygen", 2, 11, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistoryBloodOxygen", "History of one-shot SpO2", decode = "hex"),
        ProbeCmd("02_0c_querySupportTraining", 2, 12, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportTraining", "Supported sport types", decode = "hex"),
        ProbeCmd("02_0d_queryHistorySteps", 2, 13, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "queryHistorySteps", "Steps for day N (0=today)", decode = "hex"),
        ProbeCmd("02_0e_queryHistorySleep", 2, 14, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "queryHistorySleep", "Sleep hypnogram: stage change points (0 awake/1 light/2 deep/3 REM)", decode = "sleep_hypno"),
        ProbeCmd("02_0f_queryHistoryTimingHeartRate", 2, 15, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTimingHeartRate", "5-min HR table day/page", decode = "hex"),
        ProbeCmd("02_10_queryHistoryTimingHrv", 2, 16, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTimingHrv", "5-min HRV table", decode = "hex"),
        ProbeCmd("02_11_queryHistoryTimingBloodOxygen", 2, 17, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTimingBloodOxygen", "SpO2 table", decode = "hex"),
        ProbeCmd("02_12_queryHistoryStepsDetails", 2, 18, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryStepsDetails", "Steps in 30-minute buckets", decode = "steps30"),
        ProbeCmd("02_13_queryActionDetails", 2, 19, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryActionDetails", "Per-minute sleep actigraphy/restlessness (page 0; SDK pages 0-7)", decode = "actigraphy"),
        ProbeCmd("02_14_queryHistoryTraining", 2, 20, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTraining", "History training list (sub=0)", decode = "hex"),
        ProbeCmd("02_15_queryTimingTempState", 2, 21, byteArrayOf(), ProbeClass.SAFE_READ, "queryTimingTempState", "Periodic temp state", decode = "hex"),
        ProbeCmd("02_16_queryHistoryTemp", 2, 22, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTemp", "Temp table day/page", decode = "hex"),
        ProbeCmd("02_17_queryTrainingState", 2, 23, byteArrayOf(), ProbeClass.SAFE_READ, "queryTrainingState", "Current training state", decode = "hex"),
        ProbeCmd("02_18_queryMeasureState", 2, 24, byteArrayOf(), ProbeClass.SAFE_READ, "queryMeasureState", "Is a measure running?", decode = "u8:flag"),
        ProbeCmd("02_19_queryHistoryStress", 2, 25, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistoryStress", "History of one-shot stress", decode = "hex"),
        ProbeCmd("02_1a_queryRemoteControlDetails(a)", 2, 26, byteArrayOf(), ProbeClass.SAFE_READ, "queryRemoteControlDetails(a)", "Remote-control details (primary)", decode = "hex"),
        ProbeCmd("02_1b_queryHistoryVoltage", 2, 27, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryVoltage", "Battery voltage history", decode = "hex"),
        ProbeCmd("02_1d_querySupportMessageList", 2, 29, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportMessageList", "Supported notification types", decode = "hex"),
        ProbeCmd("02_20_querySosState", 2, 32, byteArrayOf(), ProbeClass.SAFE_READ, "querySosState", "SOS enabled?", decode = "u8:flag"),
        ProbeCmd("02_22_queryReadBookMode", 2, 34, byteArrayOf(), ProbeClass.SAFE_READ, "queryReadBookMode", "Read-book mode", decode = "u8:mode"),
        ProbeCmd("02_25_querySupportBloodOxygenType", 2, 37, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportBloodOxygenType", "SpO2 capability", decode = "u8:type"),
        ProbeCmd("02_26_queryShutterState", 2, 38, byteArrayOf(), ProbeClass.SAFE_READ, "queryShutterState", "Shutter gesture state", decode = "u8:flag"),
        ProbeCmd("02_27_queryRFPower", 2, 39, byteArrayOf(), ProbeClass.SAFE_READ, "queryRFPower", "BLE TX power setting", decode = "u8:pwr"),
        ProbeCmd("02_28_queryRemoteControlDetails(b)", 2, 40, byteArrayOf(), ProbeClass.SAFE_READ, "queryRemoteControlDetails(b)", "Remote-control details (alt)", decode = "hex"),
        ProbeCmd("02_29_queryAutoLock", 2, 41, byteArrayOf(), ProbeClass.SAFE_READ, "queryAutoLock", "Auto-lock value", decode = "u8:v"),
        ProbeCmd("02_2a_queryBrightness", 2, 42, byteArrayOf(), ProbeClass.SAFE_READ, "queryBrightness", "Brightness", decode = "u8:v"),
        ProbeCmd("02_2b_querySupportDisplayFeature", 2, 43, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportDisplayFeature", "Supported display features", decode = "hex"),
        ProbeCmd("02_2c_queryDisplayFeature", 2, 44, byteArrayOf(), ProbeClass.SAFE_READ, "queryDisplayFeature", "Current display features", decode = "hex"),
        ProbeCmd("02_2d_queryTimingStressState", 2, 45, byteArrayOf(), ProbeClass.SAFE_READ, "queryTimingStressState", "Periodic stress interval", decode = "u8:min"),
        ProbeCmd("02_2f_queryHistoryTimingStress", 2, 47, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryHistoryTimingStress", "Stress table day/page", decode = "hex"),
        ProbeCmd("02_30_querySleepState", 2, 48, byteArrayOf(), ProbeClass.SAFE_READ, "querySleepState", "Is sleeping?", decode = "u8:state"),
        ProbeCmd("02_14_queryTraining", 2, 20, byteArrayOf(0x02.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryTraining", "Single training by id (sub=2)", decode = "hex"),
        ProbeCmd("03_03_queryFirmwareVersion", 3, 3, byteArrayOf(), ProbeClass.SAFE_READ, "queryFirmwareVersion", "Firmware version string", decode = "ascii"),
        ProbeCmd("03_04_queryFirmwareHash", 3, 4, byteArrayOf(), ProbeClass.SAFE_READ, "queryFirmwareHash", "Firmware hash/build id", decode = "ascii"),
        ProbeCmd("03_06_queryRealTimeBattery", 3, 6, byteArrayOf(), ProbeClass.SAFE_READ, "queryRealTimeBattery", "Battery % via protocol (alt to 2A19)", decode = "battery"),
        ProbeCmd("03_07_queryTestWearState", 3, 7, byteArrayOf(), ProbeClass.SAFE_READ, "queryTestWearState", "Wear-detection state (test path)", decode = "u8:flag", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("03_0d_queryRestartCount", 3, 13, byteArrayOf(), ProbeClass.SAFE_READ, "queryRestartCount", "MCU restart counter", decode = "u16:count", tags = listOf("unused-by-ui")),
        ProbeCmd("03_16_sendBindingReminder", 3, 22, byteArrayOf(), ProbeClass.SAFE_TOGGLE, "sendBindingReminder", "Vibrate as bind reminder", decode = "hex"),
        ProbeCmd("03_1b_querySendCmdCount", 3, 27, byteArrayOf(), ProbeClass.SAFE_READ, "querySendCmdCount", "TX/RX command counters", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("05_00_querySettingInfo", 5, 0, byteArrayOf(), ProbeClass.SAFE_READ, "querySettingInfo", "color/size/type", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("05_02_queryDeviceInfo", 5, 2, byteArrayOf(), ProbeClass.SAFE_READ, "queryDeviceInfo", "Device name + version", decode = "devinfo", tags = listOf("unused-by-ui")),
        ProbeCmd("05_07_queryActivationState", 5, 7, byteArrayOf(), ProbeClass.SAFE_READ, "queryActivationState", "Activation state", decode = "hex"),
        ProbeCmd("05_0b_querySupportAlterDeviceInfo", 5, 11, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportAlterDeviceInfo", "Can rewrite device info?", decode = "u8:flag", tags = listOf("unused-by-ui")),
        ProbeCmd("05_0d_queryDeviceSN", 5, 13, byteArrayOf(), ProbeClass.SAFE_READ, "queryDeviceSN", "Serial number", decode = "ascii", tags = listOf("unused-by-ui")),
        ProbeCmd("06_05_querySupportHardwareCheck", 6, 5, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportHardwareCheck", "Which HW self-tests exist", decode = "hex", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("06_06_queryHardwareCheckResult", 6, 6, byteArrayOf(), ProbeClass.SAFE_READ, "queryHardwareCheckResult", "Last HW self-test result", decode = "hex", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("06_08_queryTestNotWornData", 6, 8, byteArrayOf(0x02.toByte()), ProbeClass.SAFE_READ, "queryTestNotWornData", "Raw not-worn PPG samples (test)", decode = "hex", tags = listOf("self-test", "raw-sensor", "unused-by-ui")),
        ProbeCmd("06_0b_querySupportTestList", 6, 11, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_READ, "querySupportTestList", "List of supported factory tests", decode = "hex", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("06_0b_startTest", 6, 11, byteArrayOf(0x02.toByte()), ProbeClass.SAFE_TOGGLE, "startTest", "Run factory HW self-test suite", decode = "hex", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("06_0d_querySARCalibrationState", 6, 13, byteArrayOf(), ProbeClass.SAFE_READ, "querySARCalibrationState", "SAR calibration present?", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("07_00_querySupportGomore", 7, 0, byteArrayOf(), ProbeClass.SAFE_READ, "querySupportGomore", "Gomore SDK support", decode = "hex"),
        ProbeCmd("07_01_querySavedGomoreKey", 7, 1, byteArrayOf(), ProbeClass.SAFE_READ, "querySavedGomoreKey", "Gomore key present?", decode = "u8:flag"),
        ProbeCmd("07_02_queryGomoreEUID", 7, 2, byteArrayOf(), ProbeClass.SAFE_READ, "queryGomoreEUID", "Gomore EUID", decode = "hex"),
        ProbeCmd("07_04_queryHistorySleepList", 7, 4, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistorySleepList", "Sleep session start timestamps", decode = "sleep_list"),
        ProbeCmd("07_05_querySleepDetails", 7, 5, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "querySleepDetails", "Sleep session summary (start/end/score)", decode = "sleep_details"),
        ProbeCmd("07_06_querySleepInfo", 7, 6, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "querySleepInfo", "Sleep hypnogram by session id", decode = "sleep_hypno"),
        ProbeCmd("07_07_querySleepChronotype", 7, 7, byteArrayOf(), ProbeClass.SAFE_READ, "querySleepChronotype", "Chronotype", decode = "hex"),
        ProbeCmd("07_08_queryHistoryDailyTraining", 7, 8, byteArrayOf(), ProbeClass.SAFE_READ, "queryHistoryDailyTraining", "Daily-training timestamps", decode = "hex"),
        ProbeCmd("07_09_queryDailyTraining", 7, 9, byteArrayOf(0x00.toByte()), ProbeClass.SAFE_READ, "queryDailyTraining", "One daily-training record", decode = "hex"),
        ProbeCmd("07_0a_querySleepState(alt)", 7, 10, byteArrayOf(), ProbeClass.SAFE_READ, "querySleepState(alt)", "Sleep state (alt path)", decode = "u8:state"),
        ProbeCmd("07_0c_queryCurrentPhysicalStrength", 7, 12, byteArrayOf(), ProbeClass.SAFE_READ, "queryCurrentPhysicalStrength", "Current physical strength x0.1", decode = "u16:x0.1", tags = listOf("unused-by-ui")),
        ProbeCmd("07_0d_queryGomoreVersion", 7, 13, byteArrayOf(), ProbeClass.SAFE_READ, "queryGomoreVersion", "Gomore version", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("07_0e_queryFullDayPhysicalStrength", 7, 14, byteArrayOf(0x00.toByte(), 0x00.toByte()), ProbeClass.SAFE_READ, "queryFullDayPhysicalStrength", "Physical strength every 5 min (page 0 of 0-3)", decode = "phys5", tags = listOf("unused-by-ui")),
        ProbeCmd("07_ff_queryGomoreLog", 7, 255, byteArrayOf(), ProbeClass.SAFE_READ, "queryGomoreLog", "Gomore debug log stream", decode = "hex", tags = listOf("unused-by-ui", "raw-sensor")),
        ProbeCmd("07_ff_queryTestSleepData", 7, 255, byteArrayOf(), ProbeClass.SAFE_READ, "queryTestSleepData", "Raw sleep test dump (alias of 7/-1)", decode = "hex", tags = listOf("self-test", "unused-by-ui")),
        ProbeCmd("09_02_startFindDevice", 9, 2, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startFindDevice", "Find-my-ring vibrate; 00 stops", decode = "hex", stopPayload = byteArrayOf(0x00.toByte())),
        ProbeCmd("09_03_heartbeat", 9, 3, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "heartbeat", "Keepalive / heartbeat response", decode = "hex"),
        ProbeCmd("0a_00_queryRecordingState", 10, 0, byteArrayOf(), ProbeClass.SAFE_READ, "queryRecordingState", "Recording state", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_01_startRecording", 10, 1, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "startRecording", "Start PPG/audio recording; 00 stop, 02 pause", decode = "hex", stopPayload = byteArrayOf(0x00.toByte()), tags = listOf("raw-sensor", "unused-by-ui")),
        ProbeCmd("0a_01_pauseRecording", 10, 1, byteArrayOf(0x02.toByte()), ProbeClass.SAFE_TOGGLE, "pauseRecording", "Pause recording", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_02_queryRecordingStorageInfo", 10, 2, byteArrayOf(), ProbeClass.SAFE_READ, "queryRecordingStorageInfo", "Storage used/free", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_03_queryRecordingFileList", 10, 3, byteArrayOf(), ProbeClass.SAFE_READ, "queryRecordingFileList", "Recording file list", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_06_queryRecordingStreamState", 10, 6, byteArrayOf(), ProbeClass.SAFE_READ, "queryRecordingStreamState", "Live stream switch state", decode = "u8:flag", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_07_enableRecordingStream", 10, 7, byteArrayOf(0x01.toByte()), ProbeClass.SAFE_TOGGLE, "enableRecordingStream", "Enable LIVE recording stream on FDD6 (OPUS/MP3/WAV containers; not a documented XYZ accel stream); 00 off", decode = "hex", stopPayload = byteArrayOf(0x00.toByte()), rawStream = true, tags = listOf("raw-sensor", "unused-by-ui")),
        ProbeCmd("0a_09_queryRecordingConfig", 10, 9, byteArrayOf(), ProbeClass.SAFE_READ, "queryRecordingConfig", "Recording config", decode = "hex", tags = listOf("unused-by-ui")),
        ProbeCmd("0a_0b_queryStorageExist", 10, 11, byteArrayOf(), ProbeClass.SAFE_READ, "queryStorageExist", "Does ring have flash storage?", decode = "u8:flag", tags = listOf("unused-by-ui")),
    )

    val SAFE_READ: List<ProbeCmd> = ALL.filter { it.cls == ProbeClass.SAFE_READ }
    val SAFE_TOGGLE: List<ProbeCmd> = ALL.filter { it.cls == ProbeClass.SAFE_TOGGLE }

/** Best-effort decode of a reply frame for the probe UI. */
    fun decodeReply(sent: ProbeCmd?, frame: ByteArray): String {
        if (frame.size < 6 || frame[0] != Frame.H0 || frame[1] != Frame.H1) return "raw " + frame.hex()
        val g = frame[4].toInt() and 0xFF; val c = frame[5].toInt() and 0xFF
        val p = if (frame.size > 6) frame.copyOfRange(6, frame.size) else ByteArray(0)
        val kind = sent?.decode ?: guessKind(g, c)
        fun u8(i: Int = 0) = if (i < p.size) (p[i].toInt() and 0xFF) else -1
        return when (kind) {
            "u8:bpm" -> if (p.isNotEmpty()) { val v=u8(); if (v==0xFF) "HR failed" else "HR=$v bpm" } else "empty"
            "u8:ms" -> if (p.isNotEmpty()) { val v=u8(); if (v==0xFF) "HRV failed" else "HRV=$v ms" } else "empty"
            "u8:%" -> if (p.isNotEmpty()) { val v=u8(); if (v==0xFF) "SpO2 failed" else "SpO2=$v%" } else "empty"
            "u8:stress" -> if (p.isNotEmpty()) { val v=u8(); if (v==0xFF) "stress failed" else "stress=$v" } else "empty"
            "u8:flag","u8:min","u8:mode","u8:pwr","u8:v","u8:state","u8:type" -> if (p.isNotEmpty()) "$kind=${u8()}" else "empty"
            "temp" -> if (p.size>=2) { val raw=le16(p,0); if (raw==0xFFFF) "temp failed" else "temp=${raw/10.0} C" } else "empty"
            "bp" -> if (p.size>=2) "BP=${u8(0)}/${u8(1)} mmHg" else "empty"
            "battery" -> if (p.isNotEmpty()) BatteryReading.fromRaw(u8()).label else "empty"
            "ascii" -> p.toString(Charsets.UTF_8).trim { it < ' ' || it == '\u0000' }
            "devinfo" -> try {
                if (p.isEmpty()) "empty" else {
                    var o=0; val nLen=u8(o); o++
                    val name=String(p,o,nLen.coerceAtMost(p.size-o),Charsets.UTF_8); o+=nLen
                    val vLen=if (o<p.size) u8(o) else 0; o++
                    val ver=if (vLen>0 && o<p.size) String(p,o,vLen.coerceAtMost(p.size-o),Charsets.UTF_8) else ""
                    "name=$name ver=$ver"
                }
            } catch (_: Exception) { p.hex() }
            "u16:count","u16:x0.1" -> if (p.size>=2) { val v=le16(p,0); if (kind.endsWith("x0.1")) "value=${v/10.0}" else "count=$v" } else "empty"
            "actigraphy" -> decodeActigraphy(p)
            "sleep_hypno" -> decodeSleepHypno(p)
            "sleep_details" -> decodeSleepDetails(p)
            "sleep_list" -> decodeSleepList(p)
            "steps30" -> decodeSteps30(p)
            "phys5" -> decodePhys5(p)
            else -> if (p.isEmpty()) "ack g=$g/c=$c" else "g=$g/c=$c ${p.hex()}"
        }
    }

    private fun guessKind(g: Int, c: Int): String = when {
        g == 2 && c == 19 -> "actigraphy"
        g == 2 && c == 14 -> "sleep_hypno"
        g == 2 && c == 18 -> "steps30"
        g == 7 && c == 5 -> "sleep_details"
        g == 7 && c == 4 -> "sleep_list"
        g == 7 && c == 6 -> "sleep_hypno"
        g == 7 && c == 14 -> "phys5"
        else -> "hex"
    }

    /** 2/13 reply: [day, page, …u8]. value>128 → moved (mag=v-128), else still. 1-min samples. */
    fun decodeActigraphy(p: ByteArray): String {
        if (p.size < 2) return "empty"
        val day = p[0].toInt() and 0xFF; val page = p[1].toInt() and 0xFF
        val samples = p.copyOfRange(2, p.size).map { it.toInt() and 0xFF }
        val moved = samples.map { if (it > 128) it - 128 else 0 }
        val nMove = moved.count { it > 0 }
        val maxM = moved.maxOrNull() ?: 0
        val first = moved.withIndex().filter { it.value > 0 }.take(8)
            .joinToString { "m${page * 180 + it.index}=${it.value}" } // rough; true index needs all pages
        return "actigraphy day=$day page=$page n=${samples.size} moved=$nMove max=$maxM (1-min; mag=v-128 if v>128) $first"
    }

    /** 2/0E or 7/06: [hdr?,] then (stage, HH, MM)* ; stages 0 awake/1 light/2 deep/3 REM. */
    fun decodeSleepHypno(p: ByteArray): String {
        if (p.isEmpty()) return "empty"
        val stages = arrayOf("awake", "light", "deep", "REM")
        val start = if (p.size % 3 == 1) 1 else 0
        val segs = ArrayList<String>()
        var i = start
        while (i + 2 < p.size) {
            val st = p[i].toInt() and 0xFF
            val hh = p[i + 1].toInt() and 0xFF
            val mm = p[i + 2].toInt() and 0xFF
            val name = stages.getOrElse(st) { "s$st" }
            segs.add("%s@%02d:%02d".format(name, hh, mm))
            i += 3
        }
        return "hypno n=${segs.size} (1-min boundaries): " + segs.joinToString(" → ")
    }

    /** 7/05: id, type, start32, end32, sleepTime_f32, eff_f32, score_f32 */
    fun decodeSleepDetails(p: ByteArray): String {
        if (p.size < 22) return "short ${p.hex()}"
        val id = p[0].toInt() and 0xFF
        val type = when (p[1].toInt() and 0xFF) { 1 -> "LONG"; 2 -> "NAP"; else -> "t${p[1]}" }
        val start = le32(p, 2); val end = le32(p, 6)
        return "sleep id=$id type=$type start=$start end=$end (sec GMT+8-adjusted) +floats@10"
    }

    fun decodeSleepList(p: ByteArray): String {
        if (p.isEmpty() || p.size % 4 != 0) return "list ${p.hex()}"
        val n = p.size / 4
        val ts = (0 until minOf(n, 8)).map { le32(p, it * 4) }
        return "sessions=$n first=${ts.joinToString()}"
    }

    /** 2/12: [day, …u16LE steps] — 30-min buckets */
    fun decodeSteps30(p: ByteArray): String {
        if (p.isEmpty()) return "empty"
        val day = p[0].toInt() and 0xFF
        val buckets = ArrayList<Int>()
        var i = 1
        while (i + 1 < p.size) { buckets.add(le16(p, i)); i += 2 }
        val sum = buckets.sum()
        return "steps30 day=$day buckets=${buckets.size} sum=$sum (30-min) peek=${buckets.take(8)}"
    }

    /** 7/0E page: [day, page, …u16LE /10] — 5-min physical strength */
    fun decodePhys5(p: ByteArray): String {
        if (p.size < 2) return "empty"
        val day = p[0].toInt() and 0xFF; val page = p[1].toInt() and 0xFF
        val vals = ArrayList<Float>()
        var i = 2
        while (i + 1 < p.size) { vals.add(le16(p, i) / 10f); i += 2 }
        return "phys5 day=$day page=$page n=${vals.size} (5-min) peek=${vals.take(6)}"
    }
}
