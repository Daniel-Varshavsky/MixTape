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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.*
import com.example.mixtape.model.*
import com.example.mixtape.service.MusicPlayerService
import com.example.mixtape.utilities.FirebaseRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@UnstableApi
class PlaylistActivity : AppCompatActivity(), MusicPlayerService.PlayerListener {

    // ── Firebase / data ───────────────────────────────────────────────────────
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

    // ── Playback control buttons ──────────────────────────────────────────────
    private lateinit var btnPlayAll: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnRepeat: MaterialButton

    // ── MusicPlayerService binding ────────────────────────────────────────────
    private var musicService: MusicPlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicPlayerService.ServiceBinder).getService()
            isBound = true
            musicService?.setListener(this@PlaylistActivity)
            // Sync the repeat button to whatever the service already has
            syncRepeatButton(musicService?.isRepeat() ?: false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

        // Bind to MusicPlayerService if it is already running (user came from
        // MusicPlayerActivity). BIND_AUTO_CREATE means Android will start the
        // service if it isn't running yet — that's fine here because the service
        // is cheap until initPlaylist() is called.
        bindService(
            Intent(this, MusicPlayerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        refreshGlobalTags()
        if (isBound) syncRepeatButton(musicService?.isRepeat() ?: false)
    }

    override fun onDestroy() {
        musicService?.setListener(null)
        if (isBound) { unbindService(serviceConnection); isBound = false }
        super.onDestroy()
    }

    // ── PlayerListener callbacks ──────────────────────────────────────────────

    /** Not needed in the playlist screen. */
    override fun onSongChanged(position: Int, songName: String) {}

    /** Not needed in the playlist screen. */
    override fun onPlaybackStateChanged(isPlaying: Boolean) {}

    /**
     * Called by MusicPlayerService whenever repeat is toggled from ANY screen
     * (the player activity, the notification, or this screen).
     */
    override fun onRepeatChanged(isRepeat: Boolean) {
        syncRepeatButton(isRepeat)
    }

    // ── Init helpers ──────────────────────────────────────────────────────────

    private fun initViews() {
        drawerLayout  = findViewById(R.id.drawerLayout)
        playlistTitle = findViewById(R.id.playlistTitle)
        itemCount     = findViewById(R.id.itemCount)
        userName      = findViewById(R.id.userName)
        btnPlayAll    = findViewById(R.id.btnPlayAll)
        btnShuffle    = findViewById(R.id.btnShuffle)
        btnRepeat     = findViewById(R.id.btnRepeat)

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
            if (isBound) {
                // Delegate to the service — visual update arrives via onRepeatChanged()
                musicService?.toggleRepeat()
            } else {
                Toast.makeText(this, "Start playing a song first to use repeat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerViews() {
        val mediaRecycler = findViewById<RecyclerView>(R.id.mediaRecycler)
        mediaRecycler.layoutManager = LinearLayoutManager(this)
        mediaAdapter = MediaAdapter(filteredMediaItems) { playMediaItem(it) }
        mediaRecycler.adapter = mediaAdapter

        val tagsRecycler = findViewById<RecyclerView>(R.id.tagsRecycler)
        tagsRecycler.layoutManager = LinearLayoutManager(this)
        manageableTagAdapter = ManageableTagAdapter { deleteTag(it) }
        tagsRecycler.adapter = manageableTagAdapter

        val filterRecycler = findViewById<RecyclerView>(R.id.filterTagsChipsRecycler)
        filterRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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

    private fun updateUserDisplay() {
        val user = auth.currentUser
        userName.text = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
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

    // ── Playlist + tag loading ────────────────────────────────────────────────

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
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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

    // ── Filtering & sorting ───────────────────────────────────────────────────

    private fun updateTagFilters() {
        currentFilter = currentFilter.copy(tagFilter = filterTagChipAdapter.getSelectedTags().joinToString(", "))
        applySortAndFilter()
    }

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

    // ── Sidebar ───────────────────────────────────────────────────────────────

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
            .setMessage("Are you sure you want to delete the tag '$tag'?\n\nThis will permanently remove it from your global tags and from all songs and videos that currently have this tag. This action cannot be undone.")
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

                        val msg = when {
                            failures == 0 && toUpdate.isNotEmpty() -> "Tag '$tag' deleted and removed from ${toUpdate.size} items"
                            failures == 0                           -> "Tag '$tag' deleted"
                            else                                    -> "Tag '$tag' deleted, but failed to update $failures items"
                        }
                        Toast.makeText(this@PlaylistActivity, msg, Toast.LENGTH_SHORT).show()
                        Log.d("PlaylistActivity", "Tag deletion complete: ${toUpdate.size - failures} successful, $failures failed")
                    }
                    .onFailure {
                        Toast.makeText(this@PlaylistActivity, "Error deleting tag: ${it.message}", Toast.LENGTH_SHORT).show()
                        Log.e("PlaylistActivity", "Error deleting global tag: ${it.message}", it)
                    }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("PlaylistActivity", "Exception in performTagDeletion: ${e.message}", e)
            }
        }
    }

    // ── Player actions ────────────────────────────────────────────────────────

    private fun playMediaItem(item: MediaItem) {
        // TODO: Launch MusicPlayerActivity or VideoPlayerActivity based on media type
        Toast.makeText(this, "Opening player for: ${item.title}", Toast.LENGTH_SHORT).show()
    }

    private fun playAll() {
        if (filteredMediaItems.isNotEmpty()) {
            Toast.makeText(this, "Opening player for all ${filteredMediaItems.size} items", Toast.LENGTH_SHORT).show()
            // TODO: Build song list and launch MusicPlayerActivity with the full filtered playlist
        } else {
            Toast.makeText(this, "No items to play", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shufflePlay() {
        Toast.makeText(this, "Shuffle mode toggled", Toast.LENGTH_SHORT).show()
        // TODO: Toggle shuffle state in MusicPlayerService once shuffle is implemented there
    }

    // ── Repeat visual sync ────────────────────────────────────────────────────

    /**
     * Tints btnRepeat red when repeat is on, white when off.
     * This is purely visual — it never calls toggleRepeat() itself.
     *
     * If you'd rather swap drawables instead of tinting, replace the two
     * setIconResource() calls with:
     *   btnRepeat.setIconResource(if (isRepeat) R.drawable.ic_repeat_active else R.drawable.ic_repeat)
     * and remove the iconTint lines.
     */
    private fun syncRepeatButton(isRepeat: Boolean) {
        val tintColor = if (isRepeat) R.color.red else R.color.white
        btnRepeat.iconTint = resources.getColorStateList(tintColor, theme)
    }

    // ── Edit playlist dialog ──────────────────────────────────────────────────

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
            mediaItems       = mediaItemCopies,
            availableTags    = availableTags,
            onPlaylistUpdated = {
                Log.d("PlaylistActivity", "Refreshing playlist data after dialog closed")
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
                        Log.d("PlaylistActivity", "Global tags refreshed: ${availableTags.size} tags")
                    }
                    .onFailure { Log.e("PlaylistActivity", "Error refreshing global tags: ${it.message}") }
            } catch (e: Exception) {
                Log.e("PlaylistActivity", "Exception refreshing global tags: ${e.message}")
            }
        }
    }
}