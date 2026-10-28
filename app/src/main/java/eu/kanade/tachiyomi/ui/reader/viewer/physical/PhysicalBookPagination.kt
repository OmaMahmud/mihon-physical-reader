package eu.kanade.tachiyomi.ui.reader.viewer.physical

/** Logical sheet positions, independent of the mirrored LTR/RTL presentation. */
internal object PhysicalBookPagination {
    fun afterForward(index: Int, lastIndex: Int): Int = minOf(lastIndex, if (index == -1) 1 else index + 2)

    fun beforeClosedEnd(lastIndex: Int): Int {
        val lastRightPage = if (lastIndex % 2 == 0) lastIndex else lastIndex - 1
        return lastRightPage - 1
    }

    fun closesEnd(index: Int, lastIndex: Int): Boolean = afterForward(index, lastIndex) == lastIndex
}
