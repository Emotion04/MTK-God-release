package magicau.mtkcontroller.data.cpu

import magicau.mtkcontroller.data.powerhal.PowerHal

enum class CpuProbeMode(val label: String, val loaded: Boolean = false) {
    OBSERVE("只读观察 30 秒"), RANGE("三资源范围 8 秒"),
    HARD_RANGE("四资源范围 8 秒"), POINT("上端点单值 8 秒"),
    RANGE_LOAD("三资源范围 + 计算负载", true),
    HARD_RANGE_LOAD("四资源范围 + 计算负载", true),
    POINT_LOAD("上端点单值 + 计算负载", true),
    CAMERA("相机只读对照 60 秒"), ROM("ROM 控制接口快照（只读）"),
    VIVO_LIMIT("Vivo CPU 限制接口验证（8 秒请求）"),
    VIVO_POLICY_9("Vivo 策略 9（2 秒）"), VIVO_POLICY_71("Vivo 策略 71（2 秒）"),
    VIVO_SOURCE_MAX("Vivo ROM MAX=4095 候选（5 秒）");

    fun pairs(index: Int, low: Long, high: Long): List<Pair<String, String>> {
        require(index in 0..255 && low > 0 && low <= high && high <= Int.MAX_VALUE)
        if (this == OBSERVE || this == CAMERA || this == ROM || this == VIVO_LIMIT || this == VIVO_POLICY_9 || this == VIVO_POLICY_71 || this == VIVO_SOURCE_MAX) return emptyList()
        val floor = if (this == POINT || this == POINT_LOAD) high else low
        return buildList {
            add(PowerHal.commandId(PowerHal.BASE_MIN, index) to floor.toString())
            add(PowerHal.commandId(PowerHal.BASE_MAX, index) to high.toString())
            if (this@CpuProbeMode != RANGE && this@CpuProbeMode != RANGE_LOAD)
                add(PowerHal.commandId(PowerHal.BASE_HARD_MIN, index) to floor.toString())
            add(PowerHal.commandId(PowerHal.BASE_HARD_MAX, index) to high.toString())
        }
    }
}
