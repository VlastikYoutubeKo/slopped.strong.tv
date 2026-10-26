package cz.plain.tvstream

import android.content.Intent

/** Akce sdílené obrazovkami: přehrání, menu kanálu, oblíbené. */
object Actions {
    fun playItemOf(li: LiveItem) = PlayItem(
        key = li.key, src = li.src, title = li.ch.name, subtitle = li.src.name, logo = li.ch.logoUrl,
        kind = li.kind, live = true, liveItem = li, resolve = { Library.backend(li.src).streamUrl(li.ch) },
    )

    /** Spustí přehrávání kanálu. [queue] = seznam, po kterém se přepíná ▲▼ / CH± (stejný druh: televize nebo rádio). */
    fun playLive(act: MainActivity, item: LiveItem, queue: List<LiveItem>) {
        Playback.queue = queue
        Playback.queueIndex = queue.indexOfFirst { it.key == item.key }
        Playback.play(playItemOf(item))
        act.startActivity(Intent(act, PlayerActivity::class.java))
    }

    /** Menu po dlouhém stisku OK na kanálu. */
    fun channelMenu(act: MainActivity, item: LiveItem, changed: () -> Unit) {
        val fav = item.favorite
        val isRadio = item.kind == ChannelKind.RADIO
        act.showMenu(item.ch.name, "${item.src.name}${if (item.ch.number > 0) " · č. ${item.ch.number}" else ""}", listOf(
            MenuEntry("Přehrát", R.drawable.ic_play) { /* spouští volající přes klik */ playLive(act, item, listOf(item)) },
            MenuEntry(if (fav) "Odebrat z oblíbených" else "Přidat do oblíbených", if (fav) R.drawable.ic_star_outline else R.drawable.ic_star) {
                val now = UserData.toggleFavorite(item.key); act.toast(if (now) "Přidáno do oblíbených" else "Odebráno z oblíbených"); changed()
            },
            MenuEntry(if (isRadio) "Je to televize" else "Je to rádio", R.drawable.ic_swap, detail = if (UserData.radioOverride(item.key) != null) "ručně" else "automaticky") {
                UserData.setRadioOverride(item.key, !isRadio); act.toast(if (isRadio) "Přesunuto mezi televizi" else "Přesunuto do rádia"); Library.listeners.forEach { it() }; changed()
            },
        ) + (if (UserData.radioOverride(item.key) != null) listOf(MenuEntry("Vrátit automatické zařazení", R.drawable.ic_refresh) {
            UserData.setRadioOverride(item.key, null); act.toast("Automatické zařazení obnoveno"); Library.listeners.forEach { it() }; changed()
        }) else emptyList()))
    }
}
