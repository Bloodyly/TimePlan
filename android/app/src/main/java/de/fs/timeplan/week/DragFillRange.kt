package de.fs.timeplan.week

object DragFillRange {
    fun coveredIndices(cellCenters: List<Float>, originIndex: Int, currentX: Float): List<Int> {
        val result = mutableListOf<Int>()
        for (i in originIndex + 1 until cellCenters.size) {
            if (currentX >= cellCenters[i]) {
                result.add(i)
            } else {
                break
            }
        }
        return result
    }
}
