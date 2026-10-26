package cz.plain.tvstream

import java.text.Normalizer

/**
 * Řazení a filtrování kanálů. Čistá logika bez Androidu (testovaná): řazení je deterministické a česky citlivé
 * („ch“ až za „h“, diakritika se ignoruje), a stabilní – shodné položky zůstanou v pořadí poskytovatele.
 */
interface Sortable {
    val name: String
    val number: Int
    /** Pořadí od poskytovatele (zdroj + pozice v jeho seznamu). */
    val index: Int
    val favorite: Boolean
    val lastPlayed: Long
    val plays: Int
}

enum class SortMode(val id: String, val label: String) {
    PROVIDER("provider", "Pořadí poskytovatele"),
    NAME_ASC("name_asc", "Název A–Z"),
    NAME_DESC("name_desc", "Název Z–A"),
    NUMBER_ASC("num_asc", "Číslo vzestupně"),
    NUMBER_DESC("num_desc", "Číslo sestupně"),
    RECENT("recent", "Naposledy sledované"),
    MOST("most", "Nejsledovanější"),
    FAV_FIRST("fav_first", "Oblíbené první");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: PROVIDER }
}

object Sorting {
    /** Klíč pro řazení: malá písmena bez diakritiky; „ch“ se řadí za „h“ jako v češtině. */
    fun fold(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(d.length + 2)
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c == 'c' && i + 1 < d.length && d[i + 1] == 'h' -> { sb.append("h~"); i++ }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }

    fun <T : Sortable> sort(items: List<T>, mode: SortMode, keyOf: (T) -> String = { fold(it.name) }): List<T> {
        val byProvider = Comparator<T> { a, b -> a.index.compareTo(b.index) }
        val cmp: Comparator<T> = when (mode) {
            SortMode.PROVIDER -> byProvider
            SortMode.NAME_ASC -> Comparator<T> { a, b -> keyOf(a).compareTo(keyOf(b)) }.then(byProvider)
            SortMode.NAME_DESC -> Comparator<T> { a, b -> keyOf(b).compareTo(keyOf(a)) }.then(byProvider)
            // Kanály bez čísla (0) padají na konec v obou směrech.
            SortMode.NUMBER_ASC -> Comparator<T> { a, b -> numKey(a.number).compareTo(numKey(b.number)) }.then(byProvider)
            SortMode.NUMBER_DESC -> Comparator<T> { a, b -> if (a.number == 0 || b.number == 0) numKey(a.number).compareTo(numKey(b.number)) else b.number.compareTo(a.number) }.then(byProvider)
            SortMode.RECENT -> Comparator<T> { a, b -> b.lastPlayed.compareTo(a.lastPlayed) }.then(byProvider)
            SortMode.MOST -> Comparator<T> { a, b -> b.plays.compareTo(a.plays) }.then(byProvider)
            SortMode.FAV_FIRST -> Comparator<T> { a, b -> b.favorite.compareTo(a.favorite) }.then(byProvider)
        }
        return items.sortedWith(cmp)
    }

    private fun numKey(n: Int) = if (n <= 0) Int.MAX_VALUE else n
}

/** Filtry průvodce. Prázdná množina = bez omezení. */
data class Filters(
    val quality: Set<String> = emptySet(),
    val language: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val epgOnly: Boolean = false,
) {
    val active: Boolean get() = quality.isNotEmpty() || language.isNotEmpty() || sources.isNotEmpty() || epgOnly
}
