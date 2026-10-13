package cloud.music.catalog

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.View
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
import androidx.core.content.ContextCompat
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
import top.wangchenyan.common.ui.activity.BaseActivity
import cloud.music.R
import cloud.music.main.MainActivity
import cloud.music.consts.RoutePath
import cloud.music.service.PlayServiceModule.playerController
import cloud.music.service.PlayMode
import me.wcy.router.CRouter
import kotlinx.coroutines.flow.collectLatest
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
                addView(controls)
            })
            input.setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event?.action == KeyEvent.ACTION_UP)) {
                    submitSearch(); true
                } else false
            }
            input.doAfterTextChanged {
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
        val scroll = ScrollView(requireContext()).apply { isFillViewport = true; clipToPadding = false }
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
        heading("Explore a mood")
        listOf("Indie", "Electronic", "Hip hop", "Ambient").forEach { term ->
            results?.addView(action(term) {
                searchInput?.setText(term); submitSearch()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(10) })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        openedPlaylist = null
        results?.removeAllViews()
        val heroCover = artwork("", "Featured artwork", 100).apply { alpha = 0.9f }
        val hero = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff173b65.toInt(), 0xff346e8b.toInt())).apply {
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
                    background = ripple(0xffe9f5fd.toInt(), 22)
                    setTextColor(0xff173b65.toInt())
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(16) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(heroCover, LinearLayout.LayoutParams(dp(100), dp(100)))
        }
        results?.addView(hero)
        val recent = CloudLibrary.history().take(3)
        if (recent.isNotEmpty()) { heading("Jump back in"); recent.forEach(::trackRow) }
        val feedSlot = sectionSlot("Made for discovery")
        val trendingSlot = sectionSlot("Trending now")
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
                            feed.items.forEach { section ->
                                heading(section.title)
                                playlistRail(section.playlists.items)
                                section.tracks.items.take(8).forEach(::trackRow)
                                section.artists.items.forEach { artist -> artistRow(artist) }
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(feedSlot, "Made for discovery", e) }
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
                            trending.items.take(20).forEach(::trackRow)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sectionError(trendingSlot, "Trending", e) }
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
            renderPage(metadata.artistTracks(artist.id, next), { artistTracks(artist, it) }, ::trackRow)
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
        val status = when {
            !item.allowsPlayback -> " · ${getString(R.string.cloud_unavailable)}"
            else -> ""
        }
        row(item.title, item.artist + status, item.artwork) {
            play(item)
        }.also { line ->
            line.addView(icon(R.drawable.ic_more, "More options for ${item.title}") { trackMenu(item) },
                LinearLayout.LayoutParams(dp(44), dp(56)))
            line.setOnLongClickListener { trackMenu(item); true }
        }
    }

    private fun play(item: CloudTrack) {
        if (!item.allowsPlayback) {
            toast("Track unavailable")
            return
        }
        CloudLibrary.remember(item)
        requireActivity().application.playerController().addAndPlay(item.toCloudMediaItem())
        CRouter.with(requireActivity()).url(RoutePath.PLAYING).start()
    }

    private fun playAllButton(tracks: List<CloudTrack>) {
        if (tracks.isEmpty()) return
        results?.addView(action("Play all", true) {
                val playable = tracks.filter { it.allowsPlayback }
                if (playable.isEmpty()) { toast("No playable tracks available"); return@action }
                playable.forEach(CloudLibrary::remember)
                val media = playable.map { it.toCloudMediaItem() }
                requireActivity().application.playerController().replaceAll(media, media.first())
                CRouter.with(requireActivity()).url(RoutePath.PLAYING).start()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(12) })
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
                    if (item.allowsPlayback) {
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
        loading?.cancel()
        results?.removeAllViews()
        if (openedPlaylist != null) {
            val playlist = CloudLibrary.playlists().find { it.id == openedPlaylist }
            if (playlist != null) {
                heading(playlist.name)
                val tracks = CloudLibrary.playlistTracks(playlist.id)
                playAllButton(tracks); tracks.forEach(::trackRow)
                results?.addView(action("Back to library") { openedPlaylist = null; library() })
                return
            }
            openedPlaylist = null
        }
        row("On this device", "Browse your local music", "") { CRouter.with(requireActivity()).url(RoutePath.LOCAL_SONG).start() }
        results?.addView(action("+ Create playlist") { editName("New playlist", "") { CloudLibrary.create(it) } })
        heading("Liked tracks")
        val likes = CloudLibrary.likes(); playAllButton(likes); likes.forEach(::trackRow)
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
        val rail = LinearLayout(requireContext()).apply { setPadding(0, 0, dp(4), dp(8)) }
        playlists.forEach { item ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(color(R.color.card_bg), 16)
                setPadding(dp(8), dp(8), dp(8), dp(12))
                setOnClickListener { openPlaylist(item) }
            }
            card.addView(artwork(item.artwork, item.title, 144), LinearLayout.LayoutParams(dp(144), dp(144)))
            card.addView(label(item.title, 15f, true).apply { maxLines = 2; setPadding(dp(4), dp(10), dp(4), 0) })
            card.addView(label(listOf(if (item.isAlbum) "Album" else "Playlist", item.creator).filter(String::isNotBlank).joinToString(" · "), 12f, secondary = true).apply {
                maxLines = 1; setPadding(dp(4), dp(4), dp(4), 0)
            })
            rail.addView(card, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        }
        results?.addView(HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(rail)
        })
    }

    private fun openPlaylist(item: CloudPlaylist) {
        openedPlaylist = null
        results?.removeAllViews()
        results?.addView(action("Back to browsing") { returnToBrowse() })
        val hero = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = surface(color(R.color.card_bg), 22)
            addView(artwork(item.artwork, item.title, 208), LinearLayout.LayoutParams(dp(208), dp(208)))
            addView(label(item.title, 25f, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8))
            })
            addView(label(item.creator, 15f, secondary = true).apply { gravity = Gravity.CENTER })
            addView(label(if (item.isAlbum) "Album" else "Playlist", 12f, secondary = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            })
        }
        results?.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val tracksSlot = sectionSlot("Songs")
        request(clear = false) {
            val tracks = metadata.playlistTracks(item)
            inSection(tracksSlot) {
                tracksSlot.removeAllViews()
                val total = tracks.sumOf { it.durationMs } / 60000
                results?.addView(label("${tracks.size} songs · $total min", 13f, secondary = true).apply {
                    setPadding(0, dp(18), 0, dp(14))
                })
                if (tracks.isEmpty()) heading("No songs available")
                playAllButton(tracks)
                if (tracks.isNotEmpty()) {
                    val actions = LinearLayout(requireContext())
                    actions.addView(action("Shuffle") { playCollection(tracks, true) },
                        LinearLayout.LayoutParams(0, dp(46), 1f))
                    actions.addView(action("Save to library") {
                        CloudLibrary.saveCollection(item.title, tracks); toast("Saved to your library")
                    }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
                    results?.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })
                }
                tracks.forEach(::trackRow)
            }
        }
    }

    private fun playCollection(tracks: List<CloudTrack>, shuffle: Boolean) {
        val playable = tracks.filter { it.allowsPlayback }
        if (playable.isEmpty()) { toast("No playable songs available"); return }
        playable.forEach(CloudLibrary::remember)
        val media = playable.map { it.toCloudMediaItem() }
        val controller = requireActivity().application.playerController()
        controller.setPlayMode(if (shuffle) PlayMode.Shuffle else PlayMode.Loop)
        controller.replaceAll(media, if (shuffle) media.random() else media.first())
        CRouter.with(requireActivity()).url(RoutePath.PLAYING).start()
    }

    private fun returnToBrowse() {
        if (libraryOnly) library() else if (searchOnly && query.isNotBlank()) search()
        else if (searchOnly) searchWelcome() else home()
    }

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

    private fun color(resource: Int) = ContextCompat.getColor(requireContext(), resource)
    private fun surface(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
    }
    private fun ripple(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(color(R.color.common_divider)), surface(fill, radius), surface(color(android.R.color.white), radius)
    )

    private fun request(clear: Boolean = true, work: suspend () -> Unit) {
        loading?.cancel()
        if (clear) { results?.removeAllViews(); nextButton = null }
        val target = results ?: return
        val indicator = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(20), dp(12), dp(20))
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(label("Finding your music…", 14f, secondary = true).apply { setPadding(dp(12), 0, 0, 0) })
        }
        target.addView(indicator)
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
        debounce?.cancel()
        debounce = null
        searchInput = null
        query = ""
        chips.clear()
        loading?.cancel()
        loading = null
        content = null
        results = null
        nextButton = null
        super.onDestroyView()
    }
}
