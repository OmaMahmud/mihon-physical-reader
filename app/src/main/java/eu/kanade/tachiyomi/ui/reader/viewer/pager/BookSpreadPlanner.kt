package eu.kanade.tachiyomi.ui.reader.viewer.pager

/**
 * Layout planning for a physical-book reader. This class deliberately does not
 * depend on Android views, so page pairing can be tested independently of rendering.
 *
 * A wide source image occupies both facing slots and is never cut in half.
 * The front cover occupies a single right-hand slot in left-to-right books,
 * or a single left-hand slot in right-to-left books.
 */
internal object BookSpreadPlanner {
    data class SourcePage(val index: Int, val width: Int, val height: Int) {
        val isWide: Boolean get() = width > height * 1.15f
    }

    data class Spread(val left: SourcePage?, val right: SourcePage?, val wide: SourcePage? = null) {
        init {
            require(wide == null || (left == null && right == null))
        }
    }

    fun plan(pages: List<SourcePage>, rightToLeft: Boolean = false, coverIsSingle: Boolean = true): List<Spread> {
        if (pages.isEmpty()) return emptyList()
        val spreads = mutableListOf<Spread>()
        var cursor = 0
        if (coverIsSingle && !pages[0].isWide) {
            spreads += if (rightToLeft) Spread(pages[0], null) else Spread(null, pages[0])
            cursor++
        }
        while (cursor < pages.size) {
            val first = pages[cursor]
            if (first.isWide) {
                spreads += Spread(null, null, first)
                cursor++
                continue
            }
            val second = pages.getOrNull(cursor + 1)?.takeUnless { it.isWide }
            spreads += if (rightToLeft) Spread(second, first) else Spread(first, second)
            cursor += if (second == null) 1 else 2
        }
        return spreads
    }
}
