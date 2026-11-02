package com.iceman.ice.car

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.iceman.ice.R
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Item
import com.iceman.ice.data.Section
import com.iceman.ice.player.Playback
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Android Auto. Ice registers as a navigation-style car app, the only kind Android Auto gives a
 * drawing surface on the car screen; the video is drawn there. Browsing uses Android Auto's lists.
 * (Sideloaded apps only appear with Android Auto's developer setting "Unknown sources" on.)
 */
class IceCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = IceCarSession()
}

class IceCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { Playback.carConnected.value = true }
            override fun onDestroy(owner: LifecycleOwner) {
                Playback.carConnected.value = false
                Playback.detachCarSurface()
                // Safer to pause when the car disconnects.
                Playback.pause()
            }
        })
        return VideoScreen(carContext)
    }
}

private fun icon(ctx: CarContext, res: Int) = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

/** The car screen's main view: the video, with the playback buttons. */
class VideoScreen(ctx: CarContext) : Screen(ctx) {
    private var opened = false

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                val s = container.surface ?: return
                Playback.attachCarSurface(s, container.width, container.height)
            }
            override fun onSurfaceDestroyed(container: SurfaceContainer) { Playback.detachCarSurface() }
            override fun onVisibleAreaChanged(visibleArea: Rect) {}
            override fun onStableAreaChanged(stableArea: Rect) {}
        })
        lifecycleScope.launch {
            // Refresh the buttons when playback starts/stops or pauses.
            combine(Playback.active, Playback.playing, Playback.nextItem) { a, p, n -> Triple(a, p, n != null) }
                .distinctUntilChanged()
                .collect { (active, _, _) ->
                    invalidate()
                    // Nothing playing: go straight to Browse.
                    if (!active && screenManager.top === this@VideoScreen) {
                        if (!opened) { opened = true; screenManager.push(BrowseScreen(carContext)) }
                    }
                    if (active) opened = false
                }
        }
    }

    override fun onGetTemplate(): Template {
        val active = Playback.active.value
        val playing = Playback.playing.value
        val browse = Action.Builder().setTitle("Browse").setOnClickListener {
            // Leaving the video to browse pauses it (like the down arrow on the phone).
            if (Playback.active.value && Playback.playing.value) Playback.pause()
            screenManager.push(BrowseScreen(carContext))
        }.build()
        val strip = ActionStrip.Builder().addAction(browse)
        if (active) {
            strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_back10))
                .setOnClickListener { Playback.skip(-10) }.build())
            strip.addAction(Action.Builder().setIcon(icon(carContext, if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play))
                .setOnClickListener { Playback.togglePause() }.build())
            strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_fwd10))
                .setOnClickListener { Playback.skip(10) }.build())
        }
        val b = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (active) {
            val map = ActionStrip.Builder()
            if (Playback.nextItem.value != null) map.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_next))
                .setOnClickListener { Playback.next() }.build())
            map.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_car_close))
                .setOnClickListener { Playback.stop() }.build())
            b.setMapActionStrip(map.build())
        }
        return b.build()
    }
}

// ---------------------------------------------------------------------------
// Browsing (Android Auto lists)
// ---------------------------------------------------------------------------

private fun listLimit(ctx: CarContext): Int = try {
    ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (_: Exception) { 6 }

private fun title(item: Item) = if (item.isEpisode) item.grandparentTitle ?: item.title else item.title

private fun subtitle(item: Item): String? =
    if (item.isEpisode) listOfNotNull(item.episodeCode.takeIf { it.isNotEmpty() }, item.rawTitle).joinToString(" · ")
    else item.year?.toString()

/** Base for list screens: loads in the background, poster images fill in as they arrive. */
abstract class ListScreen(ctx: CarContext, private val heading: String) : Screen(ctx) {
    protected var sections: List<Pair<String?, List<Item>>>? = null
    protected var folders: List<Pair<String, () -> Unit>> = emptyList()
    protected var folderHeader = "Libraries"
    private val images = HashMap<String, CarIcon>()
    private val loader = ImageLoader(ctx)

    abstract suspend fun load()

    init {
        lifecycleScope.launch {
            try { load() } catch (_: Exception) { sections = emptyList() }
            invalidate()
            loadImages()
        }
    }

    private suspend fun loadImages() {
        val items = sections?.flatMap { it.second }?.take(60) ?: return
        for (it in items) {
            val url = AppState.client.imageUrl(it.posterPath, 160, 240) ?: continue
            try {
                val d = loader.execute(ImageRequest.Builder(carContext).data(url).allowHardware(false).build()).drawable ?: continue
                val bmp: Bitmap = d.toBitmap(160, 240)
                images[it.key] = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
            } catch (_: Exception) {}
        }
        invalidate()
    }

    protected fun open(item: Item) {
        if (item.isPlayable) {
            Playback.play(item)
            screenManager.popToRoot()
        } else {
            screenManager.push(ChildrenScreen(carContext, item))
        }
    }

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder().setTitle(heading).setHeaderAction(Action.BACK)
        val secs = sections
        if (secs == null) return b.setLoading(true).build()
        val limit = listLimit(carContext)
        var used = 0
        val lists = mutableListOf<Pair<String, ItemList>>()
        if (folders.isNotEmpty() && used < limit) {
            val il = ItemList.Builder()
            for ((name, action) in folders.take(limit - used)) {
                il.addItem(Row.Builder().setTitle(name).setImage(icon(carContext, R.drawable.ic_car_folder))
                    .setBrowsable(true).setOnClickListener { action() }.build())
                used++
            }
            lists.add(folderHeader to il.build())
        }
        for ((header, items) in secs) {
            if (used >= limit || items.isEmpty()) continue
            val il = ItemList.Builder()
            for (it in items.take(limit - used)) {
                val r = Row.Builder().setTitle(title(it))
                subtitle(it)?.takeIf { s -> s.isNotEmpty() }?.let { s -> r.addText(s) }
                images[it.key]?.let { img -> r.setImage(img, Row.IMAGE_TYPE_LARGE) }
                if (!it.isPlayable) r.setBrowsable(true)
                r.setOnClickListener { open(it) }
                il.addItem(r.build())
                used++
            }
            lists.add((header ?: "") to il.build())
        }
        if (lists.isEmpty()) {
            b.setSingleList(ItemList.Builder().setNoItemsMessage(
                if (AppState.server.value == null) "Sign in on your phone first" else "Nothing here yet").build())
        } else if (lists.size == 1 && lists[0].first.isEmpty()) {
            b.setSingleList(lists[0].second)
        } else {
            for ((h, il) in lists) b.addSectionedList(SectionedItemList.create(il, h.ifEmpty { heading }))
        }
        return b.build()
    }
}

/** Continue Watching + libraries. */
class BrowseScreen(ctx: CarContext) : ListScreen(ctx, "Ice") {
    override suspend fun load() {
        val c = AppState.client
        val cw = try { c.continueWatching().filter { !AppState.isExcluded(it) } } catch (_: Exception) { emptyList() }
        val secs: List<Section> = try { AppState.loadSections(); AppState.visibleSections } catch (_: Exception) { emptyList() }
        folders = secs.map { s -> s.title to { screenManager.push(LibraryScreen(carContext, s)) } }
        sections = listOf("Continue Watching" to cw)
    }
}

/** A library: recently added, then A–Z. */
class LibraryScreen(ctx: CarContext, private val section: Section) : ListScreen(ctx, section.title) {
    override suspend fun load() {
        val c = AppState.client
        val recent = try { c.recentlyAdded(section.key, 20) } catch (_: Exception) { emptyList() }
        val all = try { c.library(section.key, "titleSort", false, 0, 200).first } catch (_: Exception) { emptyList() }
        sections = listOf("Recently Added" to recent, "A–Z" to all)
    }
}

/** A show's seasons or a season's episodes (shows also get "Play next episode" via the first row). */
class ChildrenScreen(ctx: CarContext, private val parent: Item) : ListScreen(ctx, parent.title) {
    override suspend fun load() {
        val kids = AppState.client.children(parent.key)
        if (parent.type == "show") {
            folderHeader = "Show"
            folders = listOf("▶  Play next episode" to { Playback.play(parent); screenManager.popToRoot() })
        }
        sections = listOf(null to kids)
    }
}
