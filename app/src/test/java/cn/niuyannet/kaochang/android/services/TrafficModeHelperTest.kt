package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficModeHelperTest {

    @Test
    fun fillMissingTasteReservations_shouldBackfillBlankFrontPans() {
        val frontPans = (1..21).map { position ->
            KaoPan().apply {
                setPositionSn(position)
                if (position <= 6) {
                    setHasSausage(true)
                    setTaste(Taste("B"))
                } else if (position <= 12) {
                    setHasSausage(true)
                    setTaste(Taste("A"))
                } else if (position <= 15) {
                    setTaste(Taste("A"))
                } else {
                    setTaste(null)
                }
            }
        }

        val boxes = listOf(
            KaoPanBox().apply {
                setPositionSn(1)
                setNum(32)
                setTasteCode("A")
            },
            KaoPanBox().apply {
                setPositionSn(2)
                setNum(64)
                setTasteCode("B")
            }
        )

        TrafficModeHelper.fillMissingTasteReservations(frontPans, boxes)

        val blankReservedPans = frontPans.filter { !it.isHasSausage && !it.taste?.tasteCode.isNullOrBlank() }
        assertEquals(9, blankReservedPans.size)
        assertTrue(blankReservedPans.all { it.taste?.tasteCode == "A" || it.taste?.tasteCode == "B" })
    }
}
