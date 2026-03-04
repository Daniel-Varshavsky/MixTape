package com.example.mixtape

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.*
import com.example.mixtape.model.*
import com.example.mixtape.service.UnifiedPlayerService
import com.example.mixtape.ui.EditPlaylistDialog
import com.example.mixtape.utilities.FirebaseRepository
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.io.File

/**
 * PlaylistActivity displays the contents of a specific playlist.
 * It provides advanced features such as:
 * - Dynamic filtering and sorting of media items (audio and video).
 * - Global and playlist-specific tag management.
 * - Integration with UnifiedPlayerService for persistent background playback.
 * - A sidebar for detailed organization and account settings.
 */
@UnstableApi
class PlaylistActivity : AppCompatActivity(), UnifiedPlayerService.PlayerListener {

    // --- Firebase & Data State ---
    private lateinit var auth: FirebaseAuth
    private lateinit var repository: FirebaseRepository
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var manageableTagAdapter: ManageableTagAdapter
    private lateinit var filterTagChipAdapter: FilterTagChipAdapter

    private lateinit var playlistTitle: MaterialTextView
    private lateinit var itemCount: MaterialTextView
    private lateinit var userName: MaterialTextView

    private var currentPlaylist: Playlist? = null
    private val allMediaItems      = mutableListOf<MediaItem>()
    private val filteredMediaItems = mutableListOf<MediaItem>()
    private val availableTags      = mutableListOf<String>()

    private var currentSortBy    = SortBy.ENTRY_ORDER
    private var currentSortOrder = SortOrder.ASCENDING
    private var currentFilter    = FilterOptions()

    // --- Playback Controls ---
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnAutoplay: MaterialButton

    // --- Service Binding ---
    private var musicService: UnifiedPlayerService? = null
    private var isBound = false

    /**
     * Manages the connection to the UnifiedPlayerService.
     * Synchronizes local UI state with the service once a connection is established.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as UnifiedPlayerService.ServiceBinder).getService()
            isBound = true
            musicService?.addListener(this@PlaylistActivity)
            
            // Sync UI state with the service's current configuration
            syncRepeatButton(musicService?.isRepeat() ?: false)
            syncAutoplayButton(musicService?.isAutoplay() ?: true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        auth       = FirebaseAuth.getInstance()
        repository = FirebaseRepository()

        if (auth.currentUser == null) { redirectToLogin(); return }

        initViews()
        setupRecyclerViews()
        setupSidebar()
        setupLogout()
        updateUserDisplay()
        loadPlaylistData()
        setupBackPressHandler()

        // Bind to the playback service to maintain session state across activities
        bindService(
            Intent(this, UnifiedPlayerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        refreshGlobalTags()
        if (isBound) {
            syncRepeatButton(musicService?.isRepeat() ?: false)
            syncAutoplayButton(musicService?.isAutoplay() ?: true)
        }
        updateUserDisplay() 
    }

    override fun onDestroy() {
        musicService?.removeListener(this)
        if (isBound) { unbindService(serviceConnection); isBound = false }
        super.onDestroy()
    }

    // --- PlayerListener implementation ---

    override fun onSongChanged(position: Int, songName: String) {}
    override fun onPlaybackStateChanged(isPlaying: Boolean) {}

    override fun onRepeatChanged(isRepeat: Boolean) {
        syncRepeatButton(isRepeat)
    }

    override fun onAutoplayChanged(isAutoplay: Boolean) {
        syncAutoplayButton(isAutoplay)
    }

    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        Log.d("PlaylistActivity", "Activity switch requested but handled by the player")
    }

    override fun onVideoPositionUpdate(position: Int, duration: Int) {}

    // --- Initialization Helpers ---

    private fun initViews() {
        drawerLayout  = findViewById(R.id.drawerLayout)
        playlistTitle = findViewById(R.id.playlistTitle)
        itemCount     = findViewById(R.id.itemCount)
        userName      = findViewById(R.id.userName)
        btnPlayAll    = findViewById(R.id.btnPlayAll)
        btnShuffle    = findViewById(R.id.btnShuffle)
        btnRepeat     = findViewById(R.id.btnRepeat)
        btnAutoplay    = findViewById(R.id.btnAutoplay)

        findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<MaterialButton>(R.id.btnCloseMenu).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<MaterialButton>(R.id.btnBackToPlaylists).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnEditPlaylist).setOnClickListener { openEditPlaylistDialog() }

        btnPlayAll.setOnClickListener { playAll() }
        btnShuffle.setOnClickListener { shufflePlay() }

        btnRepeat.setOnClickListener {
            if (isBound) musicService?.toggleRepeat()
            else Toast.makeText(this, "Start playing to use repeat", Toast.LENGTH_SHORT).show()
        }

        btnAutoplay.setOnClickListener {
            if (isBound) musicService?.toggleAutoplay()
            else Toast.makeText(this, "Start playing to use autoplay", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Sets up the main media list and the various tag clouds using FlexboxLayoutManager
     * for a responsive, wrapping UI.
     */
    private fun setupRecyclerViews() {
        val mediaRecycler = findViewById<RecyclerView>(R.id.mediaRecycler)
        mediaRecycler.layoutManager = LinearLayoutManager(this)
        mediaAdapter = MediaAdapter(filteredMediaItems) { playMediaItem(it) }
        mediaRecycler.adapter = mediaAdapter

        val tagsRecycler = findViewById<RecyclerView>(R.id.tagsRecycler)
        tagsRecycler.layoutManager = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
        manageableTagAdapter = ManageableTagAdapter { deleteTag(it) }
        tagsRecycler.adapter = manageableTagAdapter

        val filterRecycler = findViewById<RecyclerView>(R.id.filterTagsChipsRecycler)
        filterRecycler.layoutManager = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
        filterTagChipAdapter = FilterTagChipAdapter { _, _ -> updateTagFilters() }
        filterRecycler.adapter = filterTagChipAdapter
    }

    private fun setupSidebar() {
        setupSortSpinners()
        setupCollapsibleSections()
        setupFilterInputs()
        setupTagManagement()
    }

    private fun setupLogout() {
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener { logout() }
    }

    /**
     * Refreshes the display name from the Firestore user profile.
     */
    private fun updateUserDisplay() {
        val user = auth.currentUser
        val fallbackName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        userName.text = fallbackName

        lifecycleScope.launch {
            try {
                repository.getUserProfile().onSuccess { profile ->
                    if (profile.displayName.isNotEmpty()) {
                        userName.text = profile.displayName
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun logout() {
        auth.signOut()
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
        redirectToLogin()
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // --- Data Loading & Management ---

    private fun loadPlaylistData() {
        val playlistId   = intent.getStringExtra("PLAYLIST_ID")
        val playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "Playlist"
        playlistTitle.text = playlistName

        if (playlistId == null) {
            Toast.makeText(this, "No playlist data available", Toast.LENGTH_SHORT).show()
            updateDisplay()
            return
        }
        loadPlaylistFromFirebase(playlistId)
    }

    /**
     * Fetches playlist metadata and all associated media items from Firebase.
     * Triggers a UI update and re-applies any active filters once loaded.
     */
    private fun loadPlaylistFromFirebase(playlistId: String) {
        lifecycleScope.launch {
            try {
                repository.getPlaylistWithMedia(playlistId)
                    .onSuccess { (playlist, songs, videos) ->
                        currentPlaylist = playlist
                        playlistTitle.text = playlist.name

                        allMediaItems.clear()
                        songs.forEach  { allMediaItems.add(MediaItem.SongItem(it)) }
                        videos.forEach { allMediaItems.add(MediaItem.VideoItem(it)) }

                        filteredMediaItems.clear()
                        filteredMediaItems.addAll(allMediaItems)

                        loadGlobalTags()
                        applySortAndFilter()

                        if (allMediaItems.isEmpty())
                            Toast.makeText(this@PlaylistActivity, "This playlist is empty. Add some content!", Toast.LENGTH_LONG).show()

                        Log.d("PlaylistActivity", "Playlist loaded: ${allMediaItems.size} items")
                    }
                    .onFailure { Toast.makeText(this@PlaylistActivity, "Error loading playlist: ${it.message}", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGlobalTags() {
        lifecycleScope.launch {
            try {
                repository.getGlobalTags()
                    .onSuccess { tags ->
                        availableTags.clear()
                        availableTags.addAll(tags.sorted())
                        manageableTagAdapter.updateTags(availableTags)
                        filterTagChipAdapter.updateTags(availableTags)
                    }
                    .onFailure { Log.w("PlaylistActivity", "Failed to load global tags: ${it.message}") }
            } catch (e: Exception) {
                Log.w("PlaylistActivity", "Exception loading global tags: ${e.message}")
            }
        }
    }

    // --- Filtering & Sorting ---

    private fun updateTagFilters() {
        currentFilter = currentFilter.copy(tagFilter = filterTagChipAdapter.getSelectedTags().joinToString(", "))
        applySortAndFilter()
    }

    /**
     * Applies the current filter criteria and sort order to the media list.
     * Filters by title, artist, album, tags, and duration.
     */
    private fun applySortAndFilter() {
        var items = allMediaItems.toList()

        if (!currentFilter.isEmpty()) {
            items = items.filter { item ->
                val matchesTitle    = currentFilter.titleFilter.isEmpty()  || item.title.contains(currentFilter.titleFilter, true)
                val matchesArtist   = currentFilter.artistFilter.isEmpty() || item.artist.contains(currentFilter.artistFilter, true)
                val matchesAlbum    = currentFilter.albumFilter.isEmpty()  || item.album.contains(currentFilter.albumFilter, true)
                val matchesTags     = currentFilter.tagFilter.isEmpty()    || item.tags.any { currentFilter.tagFilter.contains(it, true) }
                val matchesDuration = item.durationSeconds in currentFilter.minDuration..currentFilter.maxDuration
                matchesTitle && matchesArtist && matchesAlbum && matchesTags && matchesDuration
            }
        }

        items = when (currentSortBy) {
            SortBy.ENTRY_ORDER -> items.sortedBy { item ->
                when (item) {
                    is MediaItem.SongItem  -> item.song.createdAt?.toDate()?.time  ?: 0L
                    is MediaItem.VideoItem -> item.video.createdAt?.toDate()?.time ?: 0L
                }
            }
            SortBy.TITLE  -> items.sortedBy { it.title }
            SortBy.ARTIST -> items.sortedBy { it.artist }
            SortBy.ALBUM  -> items.sortedBy { it.album }
        }

        if (currentSortOrder == SortOrder.DESCENDING) items = items.reversed()

        filteredMediaItems.clear()
        filteredMediaItems.addAll(items)
        mediaAdapter.updateItems(filteredMediaItems)
        updateDisplay()
    }

    private fun updateDisplay() { itemCount.text = "${filteredMediaItems.size} items" }

    // --- Sidebar Components ---

    private fun setupSortSpinners() {
        val sortBySpinner = findViewById<Spinner>(R.id.spinnerSortBy)
        val orderSpinner  = findViewById<Spinner>(R.id.spinnerOrder)

        ArrayAdapter(this, R.layout.spinner_item_white, arrayOf("Entry Order","Title","Artist","Album")).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
            sortBySpinner.adapter = it
        }
        sortBySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { currentSortBy = SortBy.values()[pos]; applySortAndFilter() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        ArrayAdapter(this, R.layout.spinner_item_white, arrayOf("Ascending","Descending")).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
            orderSpinner.adapter = it
        }
        orderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { currentSortOrder = SortOrder.values()[pos]; applySortAndFilter() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupCollapsibleSections() {
        val filtersHeader  = findViewById<View>(R.id.filtersHeader)
        val filtersContent = findViewById<View>(R.id.filtersContent)
        val filtersArrow   = findViewById<View>(R.id.filtersArrow)
        val tagsHeader     = findViewById<View>(R.id.tagsHeader)
        val tagsContent    = findViewById<View>(R.id.tagsContent)
        val tagsArrow      = findViewById<View>(R.id.tagsArrow)
        filtersHeader.setOnClickListener { toggleSection(filtersContent, filtersArrow) }
        tagsHeader.setOnClickListener    { toggleSection(tagsContent, tagsArrow) }
    }

    private fun toggleSection(content: View, arrow: View) {
        if (content.visibility == View.GONE) { content.visibility = View.VISIBLE; arrow.rotation = 90f }
        else                                  { content.visibility = View.GONE;    arrow.rotation = 0f }
    }

    private fun setupFilterInputs() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateFilters() }
        }
        listOf(R.id.filterTitle, R.id.filterArtist, R.id.filterAlbum, R.id.filterMinDuration, R.id.filterMaxDuration)
            .forEach { findViewById<EditText>(it).addTextChangedListener(watcher) }
        findViewById<MaterialButton>(R.id.btnClearFilters).setOnClickListener { clearFilters() }
    }

    private fun updateFilters() {
        currentFilter = FilterOptions(
            titleFilter  = findViewById<EditText>(R.id.filterTitle).text?.toString()?.trim()  ?: "",
            artistFilter = findViewById<EditText>(R.id.filterArtist).text?.toString()?.trim() ?: "",
            albumFilter  = findViewById<EditText>(R.id.filterAlbum).text?.toString()?.trim()  ?: "",
            tagFilter    = filterTagChipAdapter.getSelectedTags().joinToString(", "),
            minDuration  = findViewById<EditText>(R.id.filterMinDuration).text?.toString()?.toIntOrNull()?.times(60) ?: 0,
            maxDuration  = findViewById<EditText>(R.id.filterMaxDuration).text?.toString()?.toIntOrNull()?.times(60) ?: Int.MAX_VALUE
        )
        applySortAndFilter()
    }

    private fun clearFilters() {
        listOf(R.id.filterTitle, R.id.filterArtist, R.id.filterAlbum, R.id.filterMinDuration, R.id.filterMaxDuration)
            .forEach { findViewById<EditText>(it).text?.clear() }
        filterTagChipAdapter.clearSelection()
        currentFilter = FilterOptions()
        applySortAndFilter()
    }

    private fun setupTagManagement() {
        val addTagInput = findViewById<EditText>(R.id.addTagInput)
        val btnAddTag   = findViewById<MaterialButton>(R.id.btnAddTag)
        btnAddTag.setOnClickListener {
            val tag = addTagInput.text?.toString()?.trim()
            if (!tag.isNullOrEmpty()) { addNewTag(tag); addTagInput.text?.clear() }
            else Toast.makeText(this, "Please enter a tag name", Toast.LENGTH_SHORT).show()
        }
        addTagInput.setOnEditorActionListener { _, _, _ ->
            val tag = addTagInput.text?.toString()?.trim()
            if (!tag.isNullOrEmpty()) { addNewTag(tag); addTagInput.text?.clear() }
            true
        }
    }

    private fun addNewTag(tag: String) {
        if (availableTags.contains(tag)) { Toast.makeText(this, "Tag '$tag' already exists", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try {
                repository.addGlobalTag(tag)
                    .onSuccess {
                        availableTags.add(tag); availableTags.sort()
                        manageableTagAdapter.updateTags(availableTags)
                        filterTagChipAdapter.updateTags(availableTags)
                        Toast.makeText(this@PlaylistActivity, "Tag '$tag' added", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { Toast.makeText(this@PlaylistActivity, "Error adding tag: ${it.message}", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteTag(tag: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Tag")
            .setMessage("Are you sure you want to delete the tag '$tag'?\n\nThis will permanently remove it from your global tags and from all items. This action cannot be undone.")
            .setPositiveButton("Delete") { dialog, _ -> performTagDeletion(tag); dialog.dismiss() }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun performTagDeletion(tag: String) {
        lifecycleScope.launch {
            try {
                repository.removeGlobalTag(tag)
                    .onSuccess {
                        availableTags.remove(tag)
                        manageableTagAdapter.removeTag(tag)
                        filterTagChipAdapter.updateTags(availableTags)

                        val toUpdate = mutableListOf<Pair<String, Boolean>>()
                        allMediaItems.forEach { item ->
                            if (item.tags.contains(tag)) {
                                item.tags.remove(tag)
                                toUpdate.add(item.id to (item is MediaItem.VideoItem))
                            }
                        }

                        var failures = 0
                        for ((id, isVideo) in toUpdate) {
                            val newTags = allMediaItems.find { it.id == id }?.tags ?: continue
                            val result  = if (isVideo) repository.updateVideoTags(id, newTags) else repository.updateSongTags(id, newTags)
                            if (result.isFailure) { failures++; Log.e("PlaylistActivity", "Failed to update tags for $id: ${result.exceptionOrNull()?.message}") }
                        }

                        mediaAdapter.updateItems(filteredMediaItems)

                        val msg = if (failures == 0) "Tag '$tag' deleted successfully" else "Tag deleted, but failed to update some items"
                        Toast.makeText(this@PlaylistActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(this@PlaylistActivity, "Error deleting tag: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Player Integration ---

    private fun playMediaItem(item: MediaItem) {
        val clickedPosition = filteredMediaItems.indexOf(item)
        if (clickedPosition != -1) {
            launchUnifiedPlayer(clickedPosition)
        } else {
            Toast.makeText(this, "Media not found in current list", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAll() {
        if (filteredMediaItems.isEmpty()) {
            Toast.makeText(this, "No items to play", Toast.LENGTH_SHORT).show()
            return
        }
        launchUnifiedPlayer(0)
    }

    /**
     * Prepares and launches the UnifiedPlayerActivity.
     * Extracts absolute file paths and metadata for all currently filtered items
     * to ensure consistent playback order.
     */
    private fun launchUnifiedPlayer(startFromPosition: Int) {
        try {
            Log.d("PlaylistActivity", "Launching UnifiedPlayerActivity with ${filteredMediaItems.size} items")

            val allFiles = mutableListOf<File>()
            val allTitles = mutableListOf<String>()
            val allArtists = mutableListOf<String>()
            val mediaTypes = mutableListOf<String>()

            filteredMediaItems.forEach { mediaItem ->
                when (mediaItem) {
                    is MediaItem.SongItem -> {
                        convertSongToFile(mediaItem.song)?.let {
                            allFiles.add(it)
                            allTitles.add(mediaItem.song.title)
                            allArtists.add(mediaItem.song.artist)
                            mediaTypes.add("audio")
                        }
                    }
                    is MediaItem.VideoItem -> {
                        convertVideoToFile(mediaItem.video)?.let {
                            allFiles.add(it)
                            allTitles.add(mediaItem.video.title)
                            allArtists.add(mediaItem.video.artist)
                            mediaTypes.add("video")
                        }
                    }
                }
            }

            if (allFiles.isEmpty()) {
                Toast.makeText(this, "No playable media found", Toast.LENGTH_SHORT).show()
                return
            }

            val actualStartPosition = startFromPosition.coerceIn(0, allFiles.size - 1)

            val intent = Intent(this, UnifiedPlayerActivity::class.java).apply {
                val filePaths = allFiles.map { it.absolutePath }
                putExtra("songs", ArrayList(filePaths))
                putExtra("songTitles", ArrayList(allTitles))
                putExtra("songArtists", ArrayList(allArtists))
                putExtra("mediaTypes", ArrayList(mediaTypes))
                putExtra("pos", actualStartPosition)
                putExtra("songName", allTitles[actualStartPosition])
            }

            startActivity(intent)

        } catch (e: Exception) {
            Log.e("PlaylistActivity", "Error launching unified player: ${e.message}", e)
            Toast.makeText(this, "Error starting player", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shuffles the current filtered items and launches the player.
     * Uses the Fisher-Yates algorithm for uniform distribution.
     */
    private fun shufflePlay() {
        if (filteredMediaItems.isEmpty()) {
            Toast.makeText(this, "No items to shuffle", Toast.LENGTH_SHORT).show()
            return
        }
        val itemsToShuffle = filteredMediaItems.toMutableList()
        fisherYatesShuffle(itemsToShuffle)
        launchUnifiedPlayerWithCustomOrder(itemsToShuffle, 0)
    }

    private fun fisherYatesShuffle(items: MutableList<MediaItem>) {
        for (i in items.size - 1 downTo 1) {
            val j = (Math.random() * (i + 1)).toInt()
            val temp = items[i]
            items[i] = items[j]
            items[j] = temp
        }
    }

    private fun launchUnifiedPlayerWithCustomOrder(customOrderItems: List<MediaItem>, startFromPosition: Int) {
        try {
            val allFiles = mutableListOf<File>()
            val allTitles = mutableListOf<String>()
            val allArtists = mutableListOf<String>()
            val mediaTypes = mutableListOf<String>()

            customOrderItems.forEach { mediaItem ->
                when (mediaItem) {
                    is MediaItem.SongItem -> {
                        convertSongToFile(mediaItem.song)?.let {
                            allFiles.add(it)
                            allTitles.add(mediaItem.song.title)
                            allArtists.add(mediaItem.song.artist)
                            mediaTypes.add("audio")
                        }
                    }
                    is MediaItem.VideoItem -> {
                        convertVideoToFile(mediaItem.video)?.let {
                            allFiles.add(it)
                            allTitles.add(mediaItem.video.title)
                            allArtists.add(mediaItem.video.artist)
                            mediaTypes.add("video")
                        }
                    }
                }
            }

            if (allFiles.isEmpty()) return

            val intent = Intent(this, UnifiedPlayerActivity::class.java).apply {
                val filePaths = allFiles.map { it.absolutePath }
                putExtra("songs", ArrayList(filePaths))
                putExtra("songTitles", ArrayList(allTitles))
                putExtra("songArtists", ArrayList(allArtists))
                putExtra("mediaTypes", ArrayList(mediaTypes))
                putExtra("pos", startFromPosition)
                putExtra("songName", allTitles[startFromPosition])
            }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    /**
     * Creates a temporary placeholder file containing the download URL.
     * The service will read this URL to stream the content from Firebase.
     */
    private fun convertSongToFile(song: Song): File? {
        return try {
            val cacheDir = File(cacheDir, "songs").apply { if (!exists()) mkdirs() }
            val file = File(cacheDir, "${song.id}.mp3")
            if (!file.exists()) file.writeText(song.storageUrl)
            file
        } catch (e: Exception) { null }
    }

    private fun convertVideoToFile(video: Video): File? {
        return try {
            if (video.storageUrl.isBlank()) return null
            val cacheDir = File(cacheDir, "videos").apply { if (!exists()) mkdirs() }
            val file = File(cacheDir, "${video.id}.mp4")
            if (!file.exists()) file.writeText(video.storageUrl)
            file
        } catch (e: Exception) { null }
    }

    // --- Button Synchronization ---

    private fun syncRepeatButton(isRepeat: Boolean) {
        val tintColor = if (isRepeat) R.color.red else R.color.white
        btnRepeat.iconTint = resources.getColorStateList(tintColor, theme)
    }

    private fun syncAutoplayButton(isAutoplay: Boolean) {
        val tintColor = if (isAutoplay) R.color.red else R.color.white
        btnAutoplay.iconTint = resources.getColorStateList(tintColor, theme)
    }

    // --- Dialogs ---

    private fun openEditPlaylistDialog() {
        val mediaItemCopies = allMediaItems.map { item ->
            when (item) {
                is MediaItem.SongItem  -> MediaItem.SongItem(item.song.copy(tags = item.song.tags.toMutableList()))
                is MediaItem.VideoItem -> MediaItem.VideoItem(item.video.copy(tags = item.video.tags.toMutableList()))
            }
        }
        val dialog = EditPlaylistDialog.newInstance(
            playlistId       = currentPlaylist?.id ?: "",
            playlistName     = currentPlaylist?.name ?: "Playlist",
            originalOwnerId  = currentPlaylist?.originalOwnerId ?: "",
            mediaItems       = mediaItemCopies,
            availableTags    = availableTags,
            onPlaylistUpdated = {
                currentPlaylist?.id?.let { loadPlaylistFromFirebase(it) }
            }
        )
        dialog.show(supportFragmentManager, "EditPlaylist")
    }

    private fun refreshGlobalTags() {
        lifecycleScope.launch {
            try {
                repository.getGlobalTags()
                    .onSuccess { tags ->
                        availableTags.clear(); availableTags.addAll(tags.sorted())
                        manageableTagAdapter.updateTags(availableTags)
                        filterTagChipAdapter.updateTags(availableTags)
                    }
            } catch (e: Exception) {}
        }
    }
}
