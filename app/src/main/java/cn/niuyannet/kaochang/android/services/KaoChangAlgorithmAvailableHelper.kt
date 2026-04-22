package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal data class AvailableHeatBatch(
    val name: String,
    val remainHeatTimeMs: Long
)

internal fun findNextOpeningSellBatch(
    isLowOrTransition: Boolean,
    sellArea: List<KaoPan>,
    now: Long
): AvailableHeatBatch? {
    val openingBatches = if (isLowOrTransition) {
        listOf("1~9开盘批次" to sellArea)
    } else {
        listOf(
            "1~9开盘批次" to sellArea.filter { it.positionSn in 1..9 },
            "10~21开盘批次" to sellArea.filter { it.positionSn in 10..21 }
        )
    }
    return openingBatches
        .mapNotNull { (name, pans) ->
            calcAvailableBatchRemainHeatTime(pans, now)?.let { remain ->
                AvailableHeatBatch(name = name, remainHeatTimeMs = remain)
            }
        }
        .minByOrNull { it.remainHeatTimeMs }
}

private fun calcAvailableBatchRemainHeatTime(
    pans: List<KaoPan>,
    now: Long
): Long? {
    return pans
        .asSequence()
        .filter { it.isHasSausage && it.startTime > 0L && it.holdingTime <= 0L }
        .mapNotNull { pan ->
            val remain = pan.bakingTime - (now - pan.startTime)
            remain.takeIf { it > 0L }
        }
        .maxOrNull()
}
