package cloud.music.catalog

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnLayout
import cloud.music.utils.ImageUtils.loadCover
import android.view.inputmethod.EditorInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import java.io.IOException
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import cloud.music.common.BaseMusicFragment
import cloud.music.common.configureBrowserInsets
import cloud.music.common.getCloudColor
import top.wangchenyan.common.ui.activity.BaseActivity
import cloud.music.R
import cloud.music.main.MainActivity
import cloud.music.consts.RoutePath
import cloud.music.service.PlayServiceModule.playerController
import cloud.music.service.PlayServiceModule
import cloud.music.service.PlayMode
import me.wcy.router.CRouter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import top.wangchenyan.common.ext.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.wangchenyan.common.ext.load
import top.wangchenyan.common.widget.TitleLayout

/** Client-side catalog browser with stable YouTube IDs. */
abstract class CloudBrowseFragment : BaseMusicFragment() {
    protected open val searchOnly = false
    protected open val libraryOnly = false
    private var openedPlaylist: String? = null
    private val metadata = YouTubeMusicMetadata.instance
    private var loading: Job? = null
    private var content: LinearLayout? = null
    private var results: LinearLayout? = null
    private var nextButton: TextView? = null
    private var debounce: Job? = null
    private var searchInput: EditText? = null
    private val chips = mutableListOf<TextView>()
    private var query = ""
    private var kind = 0
    private var discovery: CloudDestination? = null
    private var browserScroll: ScrollView? = null
    private var currentScreen: BrowserScreen? = null
    private val navigationHistory = mutableListOf<BrowserSnapshot>()
    private var restoringNavigation = false
    private var backCallback: OnBackPressedCallback? = null
    private var navigationRevision = 0L
    private var activeLoad: PendingLoad? = null
    private var displayedArtist: CloudArtist? = null
    private var playbackCollection: List<CloudTrack>? = null
    private var collectionObserver: Job? = null
    private val collectionRows = mutableListOf<CollectionRow>()

    private data class CollectionRow(val item: CloudTrack, val number: Int, val marker: TextView, val title: TextView)

    private sealed class BrowserScreen {
        object Home : BrowserScreen()
        object SearchWelcome : BrowserScreen()
        data class Search(val query: String, val kind: Int) : BrowserScreen()
        object Library : BrowserScreen()
        data class LocalPlaylist(val id: String) : BrowserScreen()
        data class Discovery(val destination: CloudDestination) : BrowserScreen()
        data class Artist(val artist: CloudArtist) : BrowserScreen()
        data class Playlist(val playlist: CloudPlaylist) : BrowserScreen()
    }

    private data class BrowserSnapshot(
        val screen: BrowserScreen, val children: List<View>, val scrollY: Int,
        val query: String, val kind: Int, val next: TextView?, val discovery: CloudDestination?,
        val artist: CloudArtist?, val rows: List<CollectionRow>, val libraryRevision: Int,
        val pending: PendingLoad?,
    )

    private data class PendingLoad(val clear: Boolean, val work: suspend () -> Unit, val indicator: View)

    override fun isLazy(): Boolean = false

    override fun getTitleLayout(): TitleLayout? = null

    override fun getRootView(): View {
        content?.let { return it }
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.common_background_color))
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        content = column
        val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(if (libraryOnly) "Your library" else if (searchOnly) "Search" else "Cloud", 28f, true),
            LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(icon(R.drawable.ic_menu_settings, "Settings") {
            (activity as? MainActivity)?.openDrawer()
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        column.addView(header)
        column.addView(label(when {
            libraryOnly -> "Your music, always within reach"
            searchOnly -> "Find your next favorite"
            else -> "A little discovery. A lot of music."
        }, 13f, secondary = true).apply { setPadding(0, 0, 0, dp(20)) })
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.cloud_search_hint)
            textSize = 15f
            setTextColor(color(R.color.common_text_h1_color))
            setHintTextColor(color(R.color.common_text_h2_color))
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, 0, 0)
        }
        searchInput = if (searchOnly) input else null
        if (searchOnly) {
            val searchBar = LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = surface(color(R.color.card_bg), 16)
            }
            searchBar.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
            searchBar.addView(icon(R.drawable.ic_menu_search, "Search") { submitSearch() },
                LinearLayout.LayoutParams(dp(52), dp(52)))
            column.addView(searchBar)
            val controls = LinearLayout(requireContext()).apply { setPadding(0, dp(14), 0, dp(14)) }
            chips.clear()
            listOf(R.string.cloud_all, R.string.cloud_tracks, R.string.cloud_artists, R.string.cloud_albums, R.string.cloud_playlists).forEachIndexed { index, resource ->
                val chip = action(getString(resource), index == kind) {
                    kind = index; updateChips(); submitSearch()
                }
                chips.add(chip)
                controls.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            column.addView(HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                addView(controls)
            })
            input.setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event?.action == KeyEvent.ACTION_UP)) {
                    submitSearch(); true
                } else false
            }
            input.doAfterTextChanged {
                if (restoringNavigation) return@doAfterTextChanged
                debounce?.cancel()
                val value = it?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) debounce = viewLifecycleOwner.lifecycleScope.launch {
                    delay(450)
                    if (value != query) { query = value; search() }
                } else {
                    query = ""
                    loading?.cancel()
                    results?.removeAllViews()
                    if (searchOnly) searchWelcome() else home()
                }
            }
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true; clipToPadding = false
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        }
        browserScroll = scroll
        results = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(results)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (searchOnly) searchWelcome()
        return column
    }

    private fun submitSearch() {
        debounce?.cancel()
        query = searchInput?.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) { if (searchOnly) searchWelcome() else home(); return }
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput?.windowToken, 0)
        CloudLibrary.rememberSearch(query)
        search()
    }

    private fun updateChips() = chips.forEachIndexed { index, chip ->
        chip.background = surface(color(if (index == kind) R.color.common_theme_color else R.color.card_bg), 20)
        chip.setTextColor(color(if (index == kind) android.R.color.white else R.color.common_text_h1_color))
    }

    private fun searchWelcome() {
        beginScreen(BrowserScreen.SearchWelcome, root = true)
        clearCollectionRows()
        displayedArtist = null
        discovery = null
        results?.removeAllViews()
        heading("What are you in the mood for?")
        results?.addView(label("Search songs, artists, albums and playlists from YouTube Music.", 14f, secondary = true).apply {
            setPadding(0, 0, 0, dp(24))
        })
        if (CloudLibrary.searchHistory().isNotEmpty()) {
            heading("Recent searches")
            CloudLibrary.searchHistory().take(5).forEach { term ->
                results?.addView(action(term) { searchInput?.setText(term); submitSearch() })
            }
        }
        heading("Browse something new")
        discoveryNavigation()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // MainActivity owns drawer, active-tab and root-tab Back handling. Standalone browsers
        // register a lifecycle-bound callback so offscreen pager fragments cannot intercept Back.
        if (activity !is MainActivity) {
            backCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() { navigateBackIfPossible() }
            }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        }
        refreshBackHandling()
        PlayServiceModule.isPlayerReady.observe(viewLifecycleOwner) { ready ->
            if (ready == true && collectionRows.isNotEmpty()) observeCollectionPlayback()
        }
        if (activity !is MainActivity) {
            (activity as? BaseActivity)?.configureBrowserInsets()
        }
        if (libraryOnly) {
            viewLifecycleOwner.lifecycleScope.launch {
                CloudLibrary.revision.collectLatest { library() }
            }
        } else if (!searchOnly) home()
    }

    private fun home() {
        beginScreen(BrowserScreen.Home, root = true)
        clearCollectionRows()
        displayedArtist = null
        discovery = null
        openedPlaylist = null
        results?.removeAllViews()
        val heroCover = artwork("", "Featured artwork", 100).apply { alpha = 0.9f }
        val hero = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color(R.color.cloud_hero_start), color(R.color.cloud_hero_end))).apply {
                cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(22), dp(24), dp(18), dp(24))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(12), 0)
                addView(label("DISCOVER SOMETHING NEW", 10f, true).apply { setTextColor(0xffbde3f4.toInt()); letterSpacing = 0.10f })
                addView(label("Find your\nnext favorite.", 25f, true).apply {
                    setTextColor(color(android.R.color.white)); setPadding(0, dp(10), 0, dp(8))
                })
                addView(label("Fresh sounds. Hidden gems.", 13f).apply { setTextColor(0xffe2edf6.toInt()) })
                addView(action("Find music") {
                    val main = activity as? MainActivity
                    if (main != null) main.openSearch()
                    else CRouter.with(requireActivity()).url(RoutePath.SEARCH).start()
                }.apply {
                    background = ripple(color(R.color.card_bg), 22)
                    setTextColor(color(R.color.common_text_h1_color))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(16) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(heroCover, LinearLayout.LayoutParams(dp(100), dp(100)))
        }
        results?.addView(hero)
        heading("Explore")
        discoveryNavigation()
        val recent = CloudLibrary.history().take(3)
        if (recent.isNotEmpty()) { heading("Jump back in"); recent.forEach(::trackRow) }
        val feedSlot = sectionSlot("Made for discovery")
        val releasesSlot = sectionSlot("New releases")
        val trendingSlot = sectionSlot("Trending now")
        val moodsSlot = sectionSlot("Moods & genres")
        val seed = recent.firstOrNull()
        val relatedSlot = seed?.let { sectionSlot("More like ${it.title}") }
        request(clear = false) {
            coroutineScope {
                launch {
                    try {
                        val feed = metadata.home()
                        inSection(feedSlot) {
                            feedSlot.removeAllViews()
                            if (feed.items.isEmpty()) heading("No selections right now")
                            feed.items.forEach { section -> renderSection(section, compact = true) }
                            feed.next?.let {
                                results?.addView(action("More to discover") {
                                    openDiscovery(CloudDestination("FEmusic_home", "Discover"))
                                })
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(feedSlot, "Made for discovery", e) }
                }
                launch {
                    try {
                        val releases = metadata.newReleases()
                        inSection(releasesSlot) {
                            releasesSlot.removeAllViews()
                            releases.items.take(2).forEach { renderSection(it, compact = true) }
                            if (releases.items.isEmpty()) heading("No new releases right now")
                            results?.addView(action("Browse new releases") { openDiscovery(NEW_RELEASES) })
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(releasesSlot, "New releases", e) }
                }
                launch {
                    try {
                        val trending = metadata.trending()
                        trending.items.firstOrNull()?.let { featured ->
                            if (featured.artwork.isNotBlank()) heroCover.load(featured.artwork, dp(12))
                        }
                        inSection(trendingSlot) {
                            trendingSlot.removeAllViews(); heading("Trending now")
                            if (trending.items.isEmpty()) results?.addView(label("No trending tracks right now.", 14f, secondary = true))
                            trending.items.take(8).forEach(::trackRow)
                            results?.addView(action("See the charts") { openDiscovery(CHARTS) })
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(trendingSlot, "Trending", e) }
                }
                launch {
                    try {
                        val moods = metadata.moodsAndGenres()
                        inSection(moodsSlot) {
                            moodsSlot.removeAllViews()
                            moods.items.forEach { renderSection(it, compact = true) }
                            results?.addView(action("All moods & genres") { openDiscovery(MOODS) })
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(moodsSlot, "Moods & genres", e) }
                }
                if (seed != null && relatedSlot != null) launch {
                    try {
                        val related = metadata.related(seed.id)
                        inSection(relatedSlot) {
                            relatedSlot.removeAllViews(); heading("More like ${seed.title}")
                            related.items.take(8).forEach(::trackRow)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(relatedSlot, "Recommendations", e) }
                }
            }
        }
    }

    private fun discoveryNavigation() {
        destinationGrid(listOf(NEW_RELEASES, CHARTS, MOODS))
    }

    private fun destinationGrid(destinations: List<CloudDestination>) {
        destinations.chunked(2).forEach { pair ->
            val line = LinearLayout(requireContext())
            pair.forEachIndexed { index, destination ->
                val tile = label(destination.title, 15f, true).apply {
                    setPadding(dp(18), dp(12), dp(14), dp(12))
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (destination.color != 0) GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(ColorUtils.blendARGB(color(R.color.card_bg), destination.color, 0.22f), color(R.color.card_bg)),
                    ).apply { cornerRadius = dp(16).toFloat() } else ripple(color(R.color.card_bg), 16)
                    setOnClickListener { openDiscovery(destination) }
                }
                line.addView(tile, LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                    if (index > 0) marginStart = dp(10)
                })
            }
            if (pair.size == 1) line.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(10) })
            results?.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply { bottomMargin = dp(10) })
        }
    }

    private fun renderSection(section: CloudSection, compact: Boolean = false) {
        heading(section.title)
        if (section.destinations.isNotEmpty()) destinationGrid(if (compact) section.destinations.take(6) else section.destinations)
        playlistRail(if (compact) section.playlists.items.take(10) else section.playlists.items)
        (if (compact) section.tracks.items.take(6) else section.tracks.items).forEach(::trackRow)
        (if (compact) section.artists.items.take(6) else section.artists.items).forEach(::artistRow)
        section.destination?.let { destination ->
            results?.addView(action("See all ${section.title.lowercase()}") { openDiscovery(destination) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(6) })
        }
    }

    private fun openDiscovery(destination: CloudDestination, next: String? = null) {
        if (next == null) beginScreen(BrowserScreen.Discovery(destination))
        displayedArtist = null
        openedPlaylist = null
        if (next == null) {
            clearCollectionRows()
            discovery = destination
            results?.removeAllViews()
            nextButton = null
            results?.addView(action("Back") { returnToBrowse() })
            heading(destination.title)
            results?.addView(label(when (destination.id) {
                "FEmusic_new_releases" -> "Fresh albums and songs, ready to explore."
                "FEmusic_charts" -> "Popular songs, artists and playlists from YouTube Music."
                "FEmusic_moods_and_genres" -> "Find a soundtrack for your day."
                else -> "Explore songs, albums and playlists."
            }, 14f, secondary = true))
        }
        request(clear = false) {
            val page = metadata.discover(destination, next)
            nextButton?.let { results?.removeView(it) }
            nextButton = null
            page.items.forEach { renderSection(it) }
            if (page.items.isEmpty()) heading("Nothing here right now. Try another category.")
            page.next?.let { token ->
                nextButton = action("Discover more") { openDiscovery(destination, token) }
                results?.addView(nextButton)
            }
        }
    }

    private fun sectionSlot(title: String): LinearLayout {
        val slot = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        results?.addView(slot)
        inSection(slot) {
            heading(title)
            results?.addView(label("Loading…", 14f, secondary = true).apply { setPadding(0, 0, 0, dp(16)) })
        }
        return slot
    }

    private fun inSection(slot: LinearLayout, render: () -> Unit) {
        val parent = results
        results = slot
        try { render() } finally { results = parent }
    }

    private fun sectionError(slot: LinearLayout, title: String, error: Exception) {
        slot.removeAllViews()
        inSection(slot) {
            heading(title)
            results?.addView(label(error.message?.take(180) ?: "Check your connection and try again.", 13f, secondary = true))
            results?.addView(action("Retry $title") { home() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        }
    }

    private fun search(next: String? = null) {
        if (query.isBlank()) return
        if (next == null) beginScreen(BrowserScreen.Search(query, kind), root = true)
        displayedArtist = null
        discovery = null
        openedPlaylist = null
        request(clear = next == null) {
            when (kind) {
                0 -> searchEverything()
                1 -> {
                    val page = metadata.searchTracks(query, next)
                    renderPage(page, render = ::trackRow)
                }
                2 -> {
                    val page = metadata.searchArtists(query, next)
                    renderPage(page) { artist -> row(artist.name, getString(R.string.cloud_artist), artist.artwork) {
                        artistTracks(artist)

                    } }
                }
                3 -> renderPage(metadata.searchAlbums(query, next), render = ::playlistRow)
                else -> renderPage(metadata.searchPlaylists(query, next), render = ::playlistRow)
            }
        }
    }

    private suspend fun <T> pageOrNull(load: suspend () -> CloudPage<T>): CloudPage<T>? = try {
        load()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) { null }

    private suspend fun searchEverything() = coroutineScope {
        val songs = async { pageOrNull { metadata.searchTracks(query) } }
        val artists = async { pageOrNull { metadata.searchArtists(query) } }
        val albums = async { pageOrNull { metadata.searchAlbums(query) } }
        val playlists = async { pageOrNull { metadata.searchPlaylists(query) } }
        val songPage = songs.await()
        val artistPage = artists.await()
        val albumPage = albums.await()
        val playlistPage = playlists.await()
        if (songPage == null && artistPage == null && albumPage == null && playlistPage == null) {
            throw IOException("YouTube Music could not be reached. Check your connection and try again.")
        }
        if (!songPage?.items.isNullOrEmpty()) {
            heading("Songs"); songPage!!.items.take(8).forEach(::trackRow)
            moreCategory("All songs", 1)
        }
        if (!artistPage?.items.isNullOrEmpty()) {
            heading("Artists"); artistPage!!.items.take(3).forEach(::artistRow)
            moreCategory("All artists", 2)
        }
        if (!albumPage?.items.isNullOrEmpty()) { heading("Albums"); playlistRail(albumPage!!.items); moreCategory("All albums", 3) }
        if (!playlistPage?.items.isNullOrEmpty()) { heading("Playlists"); playlistRail(playlistPage!!.items); moreCategory("All playlists", 4) }
        if ((results?.childCount ?: 0) <= 1) heading("No matches yet")
    }

    private fun moreCategory(title: String, category: Int) {
        results?.addView(action(title) { kind = category; updateChips(); search() })
    }

    private fun artistRow(artist: CloudArtist) = row(artist.name, "Artist", artist.artwork) { artistTracks(artist) }

    private fun artistTracks(artist: CloudArtist, next: String? = null) {
        if (next == null) beginScreen(BrowserScreen.Artist(artist))
        displayedArtist = artist
        openedPlaylist = null
        request(clear = next == null) {
            if (next == null) {
                results?.addView(action("Back to browsing") { returnToBrowse() })
                heading(artist.name)
                if (artist.description.isNotBlank()) results?.addView(label(artist.description, 14f, secondary = true))
                pageOrNull { metadata.artistAlbums(artist.id) }?.items?.takeIf { it.isNotEmpty() }?.let {
                    heading("Albums"); playlistRail(it)
                }
                heading("Songs")
            }
            val page = metadata.artistTracks(artist.id, next)
            withCollection(page.items) {
                renderPage(page, { artistTracks(artist, it) }, ::trackRow)
            }
        }
    }

    private fun <T> renderPage(page: CloudPage<T>, more: (String) -> Unit = { search(it) }, render: (T) -> Unit) {
        nextButton?.let { results?.removeView(it) }
        nextButton = null
        page.items.forEach(render)
        if (page.items.isEmpty() && (results?.childCount ?: 0) <= 1) {
            heading("No matches yet")
            results?.addView(label("Try another name, or browse songs, artists, albums and playlists.", 14f, secondary = true))
        }
        page.next?.let { next ->
            nextButton = action(getString(R.string.cloud_more)) { more(next) }
            results?.addView(nextButton)
        }
    }

    private fun playlistRow(item: CloudPlaylist) = row(item.title, listOf(if (item.isAlbum) "Album" else "Playlist", item.creator).filter(String::isNotBlank).joinToString(" · "), item.artwork) {
        openPlaylist(item)
    }

    private fun trackRow(item: CloudTrack) {
        val collection = playbackCollection
        val status = when {
            !item.allowsPlayback -> " · ${getString(R.string.cloud_unavailable)}"
            else -> ""
        }
        row(item.title, item.artist + status, item.artwork) {
            play(item, collection)
        }.also { line ->
            line.addView(icon(R.drawable.ic_more, "More options for ${item.title}") { trackMenu(item) },
                LinearLayout.LayoutParams(dp(44), dp(56)))
            line.setOnLongClickListener { trackMenu(item); true }
        }
    }

    private fun playbackReady(): Boolean {
        if (PlayServiceModule.isPlayerReady.value == true) return true
        toast("Playback is still starting. You can continue browsing.")
        return false
    }

    private fun play(item: CloudTrack, collection: List<CloudTrack>? = null) {
        if (!playbackReady()) return
        if (!item.allowsPlayback) {
            toast("Track unavailable")
            return
        }
        val tracks = collection?.filter { it.allowsPlayback }?.takeIf { it.any { track -> track.id == item.id } }
            ?: listOf(item)
        tracks.forEach(CloudLibrary::remember)
        val media = tracks.map { it.toCloudMediaItem() }
        val selected = media.first { it.mediaId == "3#${item.id}" }
        val controller = requireActivity().application.playerController()
        controller.setPlayMode(PlayMode.Sequential)
        controller.replaceAll(media, selected)
        CRouter.with(requireActivity()).url(RoutePath.PLAYING).start()
    }

    private fun playAllButton(tracks: List<CloudTrack>) {
        if (tracks.isEmpty()) return
        results?.addView(action("Play all", true) { playCollection(tracks, false) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(12) })
    }

    private fun trackMenu(item: CloudTrack) {
        CloudLibrary.remember(item)
        val choices = mutableListOf(if (CloudLibrary.liked(item.id)) "Unlike" else "Like", "Add to playlist", "Add to queue", "Track details")
        if (openedPlaylist != null) choices.add("Remove from playlist")
        AlertDialog.Builder(requireContext()).setTitle(item.title).setItems(choices.toTypedArray()) { _, which ->
            when (which) {
                0 -> CloudLibrary.toggleLike(item.id)
                1 -> addToPlaylist(item)
                2 -> {
                    if (item.allowsPlayback && playbackReady()) {
                        requireActivity().application.playerController().enqueue(item.toCloudMediaItem())
                        toast("Added to queue")
                    }
                }
                3 -> AlertDialog.Builder(requireContext()).setTitle(item.title)
                    .setMessage("${item.artist}\n${item.album}\n${item.genre}\n${item.durationMs / 60000}:${((item.durationMs / 1000) % 60).toString().padStart(2, '0')}")
                    .setPositiveButton(R.string.cloud_open_source) { _, _ -> openSource(item.url) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                4 -> { openedPlaylist?.let { CloudLibrary.remove(it, item.id) }; library() }
            }
        }.show()
    }

    private fun addToPlaylist(track: CloudTrack) {
        val playlists = CloudLibrary.playlists()
        val names = playlists.map { it.name } + "New playlist"
        AlertDialog.Builder(requireContext()).setTitle("Add to playlist").setItems(names.toTypedArray()) { _, which ->
            if (which < playlists.size) CloudLibrary.add(playlists[which].id, track)
            else editName("New playlist", "") { name -> CloudLibrary.add(CloudLibrary.create(name).id, track) }
        }.show()
    }

    private fun editName(title: String, name: String, save: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setText(name); isSingleLine = true }
        AlertDialog.Builder(requireContext()).setTitle(title).setView(input)
            .setPositiveButton(R.string.save) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(save) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun library() {
        if (!libraryOnly || results == null) return
        beginScreen(openedPlaylist?.let { BrowserScreen.LocalPlaylist(it) } ?: BrowserScreen.Library,
            root = openedPlaylist == null)
        loading?.cancel()
        clearCollectionRows()
        results?.removeAllViews()
        if (openedPlaylist != null) {
            val playlist = CloudLibrary.playlists().find { it.id == openedPlaylist }
            if (playlist != null) {
                val tracks = CloudLibrary.playlistTracks(playlist.id)
                results?.addView(action("Back to library") { returnToBrowse() })
                collectionHero(playlist.name, "Your library", "Playlist", tracks.firstOrNull()?.artwork.orEmpty(),
                    collectionSummary(tracks)) { localPlaylistMenu(playlist) }
                collectionActions(playlist.name, tracks, local = true)
                collectionTrackList(tracks)
                return
            }
            openedPlaylist = null
            library()
            return
        }
        row("On this device", "Browse your local music", "") { CRouter.with(requireActivity()).url(RoutePath.LOCAL_SONG).start() }
        results?.addView(action("+ Create playlist") { editName("New playlist", "") { CloudLibrary.create(it) } })
        heading("Liked tracks")
        val likes = CloudLibrary.likes(); playAllButton(likes); withCollection(likes) { likes.forEach(::trackRow) }
        if (likes.isEmpty()) results?.addView(label("Tap a track’s menu to save your favorites here.", 14f, secondary = true))
        heading("Your playlists")
        CloudLibrary.playlists().forEach { playlist ->
            row(playlist.name, "${playlist.tracks.size} tracks", "") {
                openedPlaylist = playlist.id; library()
            }.setOnLongClickListener {
                AlertDialog.Builder(requireContext()).setTitle(playlist.name).setItems(arrayOf("Rename", "Delete")) { _, which ->
                    if (which == 0) editName("Rename playlist", playlist.name) { CloudLibrary.rename(playlist.id, it) }
                    else AlertDialog.Builder(requireContext()).setMessage("Delete ${playlist.name}?")
                        .setPositiveButton(R.string.delete) { _, _ -> CloudLibrary.delete(playlist.id) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }.show(); true
            }
        }
        heading("Recently played")
        CloudLibrary.history().forEach(::trackRow)
    }

    private fun playlistRail(playlists: List<CloudPlaylist>) {
        if (playlists.isEmpty()) return
        val rail = RecyclerView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            isNestedScrollingEnabled = false
            clipToPadding = false
            setPadding(0, 0, dp(4), dp(8))
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount(): Int = playlists.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val card = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = ripple(color(R.color.card_bg), 16)
                        setPadding(dp(8), dp(8), dp(8), dp(12))
                        layoutParams = RecyclerView.LayoutParams(dp(160), RecyclerView.LayoutParams.MATCH_PARENT).apply {
                            marginEnd = dp(12)
                        }
                    }
                    card.addView(artwork("", "Album or playlist", 144), LinearLayout.LayoutParams(dp(144), dp(144)))
                    card.addView(label("", 15f, true).apply {
                        maxLines = 2; setPadding(dp(4), dp(8), dp(4), 0)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
                    card.addView(label("", 12f, secondary = true).apply {
                        maxLines = 1; setPadding(dp(4), 0, dp(4), 0)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)))
                    return object : RecyclerView.ViewHolder(card) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val item = playlists[position]
                    val card = holder.itemView as LinearLayout
                    val cover = card.getChildAt(0) as ImageView
                    cover.contentDescription = item.title
                    cover.loadCover(if (item.artwork.isBlank()) R.drawable.ic_playlist else item.artwork, dp(12))
                    (card.getChildAt(1) as TextView).text = item.title
                    (card.getChildAt(2) as TextView).text = listOf(if (item.isAlbum) "Album" else "Playlist", item.creator)
                        .filter(String::isNotBlank).joinToString(" · ")
                    card.setOnClickListener { openPlaylist(item) }
                }
            }
        }
        results?.addView(rail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(242)))
    }

    private fun openPlaylist(item: CloudPlaylist) {
        beginScreen(BrowserScreen.Playlist(item))
        val artistParent = displayedArtist
        openedPlaylist = null
        clearCollectionRows()
        results?.removeAllViews()
        results?.addView(action(if (artistParent != null) "Back to ${artistParent.name}" else "Back to browsing") {
            returnToBrowse()
        })
        val summary = collectionHero(item.title, item.creator, if (item.isAlbum) "Album" else "Playlist", item.artwork,
            if (item.trackCount > 0) "${item.trackCount} songs · Loading duration…" else "Loading songs…") {
            AlertDialog.Builder(requireContext()).setTitle(item.title).setItems(arrayOf("Open in YouTube Music")) { _, _ ->
                openSource(item.url)
            }.show()
        }
        val tracksSlot = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        results?.addView(tracksSlot)
        request(clear = false) {
            val tracks = metadata.playlistTracks(item)
            summary.text = collectionSummary(tracks)
            inSection(tracksSlot) {
                clearCollectionRows()
                tracksSlot.removeAllViews()
                collectionActions(item.title, tracks, local = false)
                collectionTrackList(tracks)
            }
        }
    }

    private fun collectionHero(title: String, creator: String, type: String, cover: String,
                               summary: String, menu: () -> Unit): TextView {
        val hero = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = surface(color(R.color.card_bg), 24)
        }
        val available = (content?.width?.takeIf { it > 0 } ?: dp(resources.configuration.screenWidthDp)) - dp(76)
        val size = minOf(dp(224), available).coerceAtLeast(dp(80))
        hero.addView(artwork(cover, title, 0), LinearLayout.LayoutParams(size, size))
        hero.addView(label(type.uppercase(), 11f, true, secondary = true).apply {
            letterSpacing = 0.12f; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8))
        })
        hero.addView(label(title, 27f, true).apply {
            gravity = Gravity.CENTER; maxLines = 3
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        hero.addView(label(creator, 14f, secondary = true).apply {
            gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(10), 0, 0)
        })
        val details = label(summary, 12f, secondary = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        }
        hero.addView(details)
        hero.addView(icon(R.drawable.ic_more, "Options for $title", menu), LinearLayout.LayoutParams(dp(44), dp(40)).apply {
            topMargin = dp(8)
        })
        results?.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(18) })
        return details
    }

    private fun collectionSummary(tracks: List<CloudTrack>): String {
        val seconds = tracks.sumOf { it.durationMs.coerceAtLeast(0) } / 1000
        val duration = when {
            seconds >= 3600 -> "${seconds / 3600} hr ${(seconds % 3600) / 60} min"
            seconds > 0 -> "${maxOf(1, seconds / 60)} min"
            else -> ""
        }
        return listOf("${tracks.size} ${if (tracks.size == 1) "song" else "songs"}", duration)
            .filter(String::isNotBlank).joinToString(" · ")
    }

    private fun collectionActions(title: String, tracks: List<CloudTrack>, local: Boolean) {
        if (tracks.isEmpty()) return
        val line = LinearLayout(requireContext())
        line.addView(action("▶  Play", true) { playCollection(tracks, false) }.apply { minimumHeight = dp(48) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(action("Shuffle") { playCollection(tracks, true) }.apply { minimumHeight = dp(48) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        results?.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val save = if (local) action("Add songs") {
            val main = activity as? MainActivity
            if (main != null) main.openSearch() else CRouter.with(requireActivity()).url(RoutePath.SEARCH).start()
        } else action("+  Save to library") {
            CloudLibrary.saveCollection(title, tracks)
            toast("Saved to your library")
        }
        if (!local) save.setOnClickListener {
            CloudLibrary.saveCollection(title, tracks)
            save.text = "Saved to library"
            save.isEnabled = false
            toast("Saved to your library")
        }
        save.minimumHeight = dp(44)
        results?.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
    }

    private fun localPlaylistMenu(playlist: SavedPlaylist) {
        AlertDialog.Builder(requireContext()).setTitle(playlist.name).setItems(arrayOf("Rename playlist", "Delete playlist")) { _, choice ->
            if (choice == 0) editName("Rename playlist", playlist.name) { CloudLibrary.rename(playlist.id, it) }
            else AlertDialog.Builder(requireContext()).setMessage("Delete ${playlist.name}?")
                .setPositiveButton(R.string.delete) { _, _ -> CloudLibrary.delete(playlist.id) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.show()
    }

    private fun collectionTrackList(tracks: List<CloudTrack>) {
        heading("Songs")
        if (tracks.isEmpty()) {
            results?.addView(label(if (openedPlaylist != null) "Your playlist is empty. Add songs from a track’s menu." else "No songs available right now.",
                14f, secondary = true).apply { setPadding(0, 0, 0, dp(18)) })
            if (openedPlaylist != null) results?.addView(action("Find songs") {
                val main = activity as? MainActivity
                if (main != null) main.openSearch() else CRouter.with(requireActivity()).url(RoutePath.SEARCH).start()
            })
            return
        }
        tracks.forEachIndexed { index, item -> collectionTrackRow(item, index + 1, tracks) }
        observeCollectionPlayback()
    }

    private fun observeCollectionPlayback() {
        if (PlayServiceModule.isPlayerReady.value != true) return
        val controller = requireActivity().application.playerController()
        collectionObserver?.cancel()
        collectionObserver = viewLifecycleOwner.lifecycleScope.launch {
            combine(controller.currentSong, controller.playState) { song, state -> song?.mediaId to state.isPlaying }
                .collectLatest { (id, playing) ->
                    collectionRows.forEach { row ->
                        val active = id == "3#${row.item.id}"
                        row.marker.text = if (active) if (playing) "▶" else "Ⅱ" else row.number.toString()
                        row.marker.contentDescription = if (active) if (playing) "Now playing" else "Paused" else "Track ${row.number}"
                        row.marker.setTextColor(color(if (active) R.color.common_theme_color else R.color.common_text_h2_color))
                        row.title.setTextColor(color(if (active) R.color.common_theme_color else R.color.common_text_h1_color))
                    }
                }
        }
    }

    private fun collectionTrackRow(item: CloudTrack, number: Int, tracks: List<CloudTrack>) {
        val line = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            minimumHeight = dp(68)
            background = ripple(color(android.R.color.transparent), 12)
            setOnClickListener { play(item, tracks) }
            setOnLongClickListener { trackMenu(item); true }
        }
        val marker = label(number.toString(), 12f, secondary = true).apply { gravity = Gravity.CENTER }
        line.addView(marker, LinearLayout.LayoutParams(dp(26), dp(44)))
        line.addView(artwork(item.artwork, item.title, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = label(item.title, 14f, true).apply { maxLines = 2 }
        line.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0)
            addView(title)
            addView(label(item.artist + if (!item.allowsPlayback) " · Unavailable" else "", 12f, secondary = true).apply {
                maxLines = 1; setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val duration = if (item.durationMs > 0) "${item.durationMs / 60000}:${((item.durationMs / 1000) % 60).toString().padStart(2, '0')}" else ""
        line.addView(label(duration, 11f, secondary = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(dp(42), dp(44)))
        line.addView(icon(R.drawable.ic_more, "Options for ${item.title}") { trackMenu(item) }, LinearLayout.LayoutParams(dp(36), dp(44)))
        results?.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        collectionRows.add(CollectionRow(item, number, marker, title))
    }

    private fun clearCollectionRows() {
        collectionObserver?.cancel()
        collectionObserver = null
        collectionRows.clear()
    }

    private fun playCollection(tracks: List<CloudTrack>, shuffle: Boolean) {
        if (!playbackReady()) return
        val playable = tracks.filter { it.allowsPlayback }
        if (playable.isEmpty()) { toast("No playable songs available"); return }
        playable.forEach(CloudLibrary::remember)
        val media = playable.map { it.toCloudMediaItem() }
        val controller = requireActivity().application.playerController()
        controller.setPlayMode(if (shuffle) PlayMode.Shuffle else PlayMode.Sequential)
        controller.replaceAll(media, if (shuffle) media.random() else media.first())
        CRouter.with(requireActivity()).url(RoutePath.PLAYING).start()
    }

    private fun withCollection(tracks: List<CloudTrack>, render: () -> Unit) {
        val previous = playbackCollection
        playbackCollection = tracks
        try { render() } finally { playbackCollection = previous }
    }

    private fun returnToBrowse() {
        if (navigateBackIfPossible()) return
        if (libraryOnly) { openedPlaylist = null; library() } else if (searchOnly && query.isNotBlank()) search()
        else if (searchOnly) searchWelcome() else home()
    }

    private fun beginScreen(screen: BrowserScreen, root: Boolean = false) {
        if (restoringNavigation) { currentScreen = screen; return }
        if (root) navigationHistory.clear()
        else if (currentScreen != null && currentScreen != screen) {
            val parent = currentScreen!!
            val pending = activeLoad?.takeIf { loading?.isActive == true }
            val children = results?.let { column -> (0 until column.childCount).mapNotNull { column.getChildAt(it) } }
                .orEmpty().filterNot { it === pending?.indicator }
            navigationHistory.add(BrowserSnapshot(
                parent, children, browserScroll?.scrollY ?: 0,
                (parent as? BrowserScreen.Search)?.query ?: query,
                (parent as? BrowserScreen.Search)?.kind ?: kind,
                nextButton, discovery, displayedArtist, collectionRows.toList(), CloudLibrary.revision.value,
                pending,
            ))
            // Detached views keep paginated rows and scroll positions exact without retaining an
            // unbounded stack of artwork. A normal category/artist/album path uses 2–4 snapshots.
            if (navigationHistory.size > 12) navigationHistory.removeAt(0)
        }
        if (root || currentScreen != screen) {
            loading?.cancel()
            debounce?.cancel()
            navigationRevision += 1
            browserScroll?.scrollTo(0, 0)
        }
        currentScreen = screen
        refreshBackHandling()
    }

    /** Called only for the selected tab by MainActivity, or by the standalone browser callback. */
    fun navigateBackIfPossible(): Boolean {
        val main = activity as? MainActivity
        if (main != null && !main.isCloudTabActive(this)) return false
        val snapshot = navigationHistory.lastOrNull() ?: run {
            // If a very deep trail exceeded the bounded snapshot cache, return to this tab's
            // root instead of allowing a detail page to close the app.
            if (isTabRoot()) return false
            if (libraryOnly) { openedPlaylist = null; library() }
            else if (searchOnly && query.isNotBlank()) search()
            else if (searchOnly) searchWelcome() else home()
            return true
        }
        navigationHistory.removeAt(navigationHistory.lastIndex)
        loading?.cancel()
        debounce?.cancel()
        clearCollectionRows()
        val revision = ++navigationRevision
        restoringNavigation = true
        try {
            currentScreen = snapshot.screen
            query = snapshot.query
            kind = snapshot.kind
            discovery = snapshot.discovery
            displayedArtist = snapshot.artist
            openedPlaylist = (snapshot.screen as? BrowserScreen.LocalPlaylist)?.id
            if (searchOnly && searchInput?.text?.toString() != query) searchInput?.setText(query)
            updateChips()
            val localChanged = (snapshot.screen is BrowserScreen.Library || snapshot.screen is BrowserScreen.LocalPlaylist) &&
                snapshot.libraryRevision != CloudLibrary.revision.value
            if (localChanged) {
                renderScreen(snapshot.screen)
            } else {
                results?.removeAllViews()
                snapshot.children.forEach { child ->
                    (child.parent as? ViewGroup)?.removeView(child)
                    results?.addView(child)
                }
                nextButton = snapshot.next
                collectionRows.addAll(snapshot.rows)
                if (collectionRows.isNotEmpty()) observeCollectionPlayback()
                // Resume the original continuation if navigation interrupted a Load More request.
                // Initial requests rebuild their own content; appended requests retain prior pages.
                snapshot.pending?.let { request(it.clear, it.work) }
            }
            loading?.takeIf { it.isActive }?.invokeOnCompletion { error ->
                if (error == null) browserScroll?.post {
                    if (revision == navigationRevision) browserScroll?.scrollTo(0, snapshot.scrollY)
                }
            }
            browserScroll?.doOnLayout { if (revision == navigationRevision) it.scrollTo(0, snapshot.scrollY) }
        } finally {
            restoringNavigation = false
            refreshBackHandling()
        }
        return true
    }

    private fun renderScreen(screen: BrowserScreen) = when (screen) {
        BrowserScreen.Home -> home()
        BrowserScreen.SearchWelcome -> searchWelcome()
        is BrowserScreen.Search -> { query = screen.query; kind = screen.kind; search() }
        BrowserScreen.Library -> { openedPlaylist = null; library() }
        is BrowserScreen.LocalPlaylist -> { openedPlaylist = screen.id; library() }
        is BrowserScreen.Discovery -> openDiscovery(screen.destination)
        is BrowserScreen.Artist -> artistTracks(screen.artist)
        is BrowserScreen.Playlist -> openPlaylist(screen.playlist)
    }

    private fun refreshBackHandling() {
        backCallback?.isEnabled = activity !is MainActivity && isVisible && isResumed &&
            (navigationHistory.isNotEmpty() || !isTabRoot())
    }

    private fun isTabRoot(): Boolean = currentScreen == null || currentScreen == BrowserScreen.Home ||
        currentScreen == BrowserScreen.SearchWelcome || currentScreen is BrowserScreen.Search ||
        currentScreen == BrowserScreen.Library

    override fun onResume() { super.onResume(); refreshBackHandling() }
    override fun onPause() { backCallback?.isEnabled = false; super.onPause() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); refreshBackHandling() }

    private fun row(title: String, subtitle: String, artworkUrl: String, click: () -> Unit): LinearLayout {
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            background = ripple(color(R.color.card_bg), 14)
            setOnClickListener { click() }
        }
        line.addView(artwork(artworkUrl, title, 56), LinearLayout.LayoutParams(dp(56), dp(56)))
        line.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(label(title, 15f, true).apply { maxLines = 2 })
            addView(label(subtitle, 12f, secondary = true).apply { maxLines = 1; setPadding(0, dp(5), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        results?.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        return line
    }

    private fun artwork(url: String, title: String, size: Int) = ImageView(requireContext()).apply {
        contentDescription = title
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = surface(color(R.color.common_divider), 12)
        clipToOutline = true
        setImageResource(R.drawable.ic_playlist)
        if (url.isNotBlank()) load(url, dp(12))
        minimumWidth = dp(size); minimumHeight = dp(size)
    }

    private fun label(value: String, size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size
        setTextColor(color(if (secondary) R.color.common_text_h2_color else R.color.common_text_h1_color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun action(value: String, primary: Boolean = false, click: () -> Unit) = label(value, 14f, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = ripple(color(if (primary) R.color.common_theme_color else R.color.card_bg), 24)
        if (primary) setTextColor(color(android.R.color.white))
        setOnClickListener { click() }
    }

    private fun icon(resource: Int, description: String, click: () -> Unit) = ImageView(requireContext()).apply {
        contentDescription = description
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color(R.color.common_text_h1_color))
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = ripple(color(android.R.color.transparent), 24)
        setOnClickListener { click() }
    }

    private fun heading(value: String) {
        results?.addView(label(value, 20f, true).apply { setPadding(0, dp(20), 0, dp(14)) })
    }

    private fun color(resource: Int) = requireContext().getCloudColor(resource)
    private fun surface(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
    }
    private fun ripple(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(color(R.color.common_divider)), surface(fill, radius), surface(color(android.R.color.white), radius)
    )

    private fun request(clear: Boolean = true, work: suspend () -> Unit) {
        loading?.cancel()
        if (clear) { clearCollectionRows(); results?.removeAllViews(); nextButton = null }
        val target = results ?: return
        val indicator = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(20), dp(12), dp(20))
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(label("Finding your music…", 14f, secondary = true).apply { setPadding(dp(12), 0, 0, 0) })
        }
        target.addView(indicator)
        val pending = PendingLoad(clear, work, indicator)
        activeLoad = pending
        loading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    background = surface(color(R.color.card_bg), 16)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    addView(label("Couldn’t load music", 18f, true))
                    addView(label(e.message?.take(240) ?: "Check your connection and try again.", 14f, secondary = true).apply {
                        setPadding(0, dp(8), 0, dp(16))
                    })
                    addView(action(getString(R.string.cloud_retry), true) { target.removeView(this); request(clear, work) })
                }
                target.addView(error)
            } finally {
                target.removeView(indicator)
                if (activeLoad === pending) activeLoad = null
            }
        }
    }

    private fun openSource(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" && uri.host in setOf("music.youtube.com", "www.youtube.com", "youtube.com")) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        clearCollectionRows()
        debounce?.cancel()
        debounce = null
        searchInput = null
        query = ""
        chips.clear()
        loading?.cancel()
        loading = null
        activeLoad = null
        content = null
        results = null
        nextButton = null
        discovery = null
        browserScroll = null
        navigationHistory.clear()
        currentScreen = null
        backCallback?.remove()
        backCallback = null
        navigationRevision += 1
        displayedArtist = null
        playbackCollection = null
        super.onDestroyView()
    }

    companion object {
        private val NEW_RELEASES = CloudDestination("FEmusic_new_releases", "New releases")
        private val CHARTS = CloudDestination("FEmusic_charts", "Charts")
        private val MOODS = CloudDestination("FEmusic_moods_and_genres", "Moods & genres")
    }
}
