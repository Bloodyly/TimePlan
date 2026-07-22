package de.fs.timeplan.grid

import android.view.View
import de.fs.timeplan.week.DragFillRange

class DragFillController(
    private val overlay: DragFillOverlay,
    private val onCommit: (workerId: String, originIndex: Int, targetIndices: List<Int>) -> Unit
) {
    private var armed = false
    private var workerId: String? = null
    private var originIndex = -1
    private var cellCenters: List<Float> = emptyList()
    private var rowCenterY = 0f

    fun arm(workerId: String, originIndex: Int, cellViews: List<View>) {
        armed = true
        this.workerId = workerId
        this.originIndex = originIndex
        cellCenters = cellViews.map { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            loc[0] + v.width / 2f
        }
        val loc = IntArray(2)
        cellViews[originIndex].getLocationOnScreen(loc)
        rowCenterY = loc[1] + cellViews[originIndex].height / 2f
        overlay.show(cellCenters[originIndex], rowCenterY)
    }

    fun progress(rawX: Float) {
        if (!armed) return
        val clampedX = maxOf(rawX, cellCenters[originIndex])
        overlay.update(cellCenters[originIndex], rowCenterY, clampedX)
    }

    fun release(rawX: Float) {
        if (!armed) return
        val clampedX = maxOf(rawX, cellCenters[originIndex])
        val targets = DragFillRange.coveredIndices(cellCenters, originIndex, clampedX)
        val id = workerId!!
        val origin = originIndex
        reset()
        if (targets.isNotEmpty()) onCommit(id, origin, targets)
    }

    fun cancel() = reset()

    private fun reset() {
        armed = false
        workerId = null
        originIndex = -1
        overlay.hide()
    }
}
