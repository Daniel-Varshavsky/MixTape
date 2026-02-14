package com.example.mixtape

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.*
import com.example.mixtape.model.*
import com.example.mixtape.utilities.FirebaseRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var repository: FirebaseRepository
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var manageableTagAdapter: ManageableTagAdapter
    private lateinit var filterTagChipAdapter: FilterTagChipAdapter

    private lateinit var playlistTitle: MaterialTextView
    private lateinit var itemCount: MaterialTextView
    private lateinit var userName: MaterialTextView

    // Current playlist data
    private var currentPlaylist: Playlist? = null
    private val allMediaItems = mutableListOf<MediaItem>()
    private val filteredMediaItems = mutableListOf<MediaItem>()
    private val availableTags = mutableListOf<String>()

    // Current sort and filter settings
    private var currentSortBy = SortBy.ENTRY_ORDER
    private var currentSortOrder = SortOrder.ASCENDING
    private var currentFilter = FilterOptions()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        // Initialize Firebase Auth and Repository
        auth = FirebaseAuth.getInstance()
        repository = FirebaseRepository()

        // Check if user is logged in
        if (auth.currentUser == null) {
            redirectToLogin()
            return
        }

        initViews()
        setupRecyclerViews()
        setupSidebar()
        setupLogout()
        updateUserDisplay()
        loadPlaylistData()
    }

    // -------------------------
    // INIT
    // -------------------------

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        playlistTitle = findViewById(R.id.playlistTitle)
        itemCount = findViewById(R.id.itemCount)
        userName = findViewById(R.id.userName)

        // Header buttons
        findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<MaterialButton>(R.id.btnCloseMenu).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<MaterialButton>(R.id.btnBackToPlaylists).setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btnEditPlaylist).setOnClickListener {
            openEditPlaylistDialog()
        }

        findViewById<MaterialButton>(R.id.btnPlayAll).setOnClickListener { playAll() }
        findViewById<MaterialButton>(R.id.btnShuffle).setOnClickListener { shufflePlay() }
        findViewById<MaterialButton>(R.id.btnRepeat).setOnClickListener { toggleRepeat() }
    }

    private fun setupRecyclerViews() {
        // Main media RecyclerView
        val mediaRecycler = findViewById<RecyclerView>(R.id.mediaRecycler)
        mediaRecycler.layoutManager = LinearLayoutManager(this)
        mediaAdapter = MediaAdapter(filteredMediaItems) { mediaItem ->
            playMediaItem(mediaItem)
        }
        mediaRecycler.adapter = mediaAdapter

        // Tags management RecyclerView
        val tagsRecycler = findViewById<RecyclerView>(R.id.tagsRecycler)
        tagsRecycler.layoutManager = LinearLayoutManager(this)
        manageableTagAdapter = ManageableTagAdapter { tag ->
            deleteTag(tag)
        }
        tagsRecycler.adapter = manageableTagAdapter

        // Filter tag chips RecyclerView - using GridLayoutManager from Version 1
        val filterRecycler = findViewById<RecyclerView>(R.id.filterTagsChipsRecycler)
        filterRecycler.layoutManager = GridLayoutManager(this, 3)
        filterTagChipAdapter = FilterTagChipAdapter { _, _ ->
            updateTagFilters()
        }
        filterRecycler.adapter = filterTagChipAdapter
    }

    private fun setupSidebar() {
        setupSortSpinners()
        setupCollapsibleSections()
        setupFilterInputs()
        setupTagManagement()
    }

    private fun setupLogout() {
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            logout()
        }
    }

    private fun updateUserDisplay() {
        val user = auth.currentUser
        userName.text = user?.displayName
            ?: user?.email?.substringBefore("@")
                    ?: "User"
    }

    private fun logout() {
        auth.signOut()
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
        redirectToLogin()
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // -------------------------
    // PLAYLIST + TAG LOADING
    // -------------------------

    private fun loadPlaylistData() {
        val playlistId = intent.getStringExtra("PLAYLIST_ID")
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
                        songs.forEach { allMediaItems.add(MediaItem.SongItem(it)) }
                        videos.forEach { allMediaItems.add(MediaItem.VideoItem(it)) }

                        filteredMediaItems.clear()
                        filteredMediaItems.addAll(allMediaItems)

                        loadGlobalTags()
                        applySortAndFilter()

                        if (allMediaItems.isEmpty()) {
                            Toast.makeText(
                                this@PlaylistActivity,
                                "This playlist is empty. Add some content!",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Log.d("PlaylistActivity", "Playlist loaded: ${allMediaItems.size} items")
                    }
                    .onFailure { exception ->
                        Toast.makeText(
                            this@PlaylistActivity,
                            "Error loading playlist: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
                    .onFailure { exception ->
                        // Silently handle - tags are not critical for basic functionality
                        Log.w("PlaylistActivity", "Failed to load global tags: ${exception.message}")
                    }
            } catch (e: Exception) {
                Log.w("PlaylistActivity", "Exception loading global tags: ${e.message}")
            }
        }
    }

    // -------------------------
    // FILTERING & SORTING
    // -------------------------

    private fun updateTagFilters() {
        val selectedTags = filterTagChipAdapter.getSelectedTags()

        currentFilter = currentFilter.copy(
            tagFilter = selectedTags.joinToString(", ")
        )

        applySortAndFilter()
    }

    private fun applySortAndFilter() {
        var items = allMediaItems.toList()

        // Apply filters
        if (!currentFilter.isEmpty()) {
            items = items.filter { item ->
                val matchesTitle = currentFilter.titleFilter.isEmpty() ||
                        item.title.contains(currentFilter.titleFilter, true)

                val matchesArtist = currentFilter.artistFilter.isEmpty() ||
                        item.artist.contains(currentFilter.artistFilter, true)

                val matchesAlbum = currentFilter.albumFilter.isEmpty() ||
                        item.album.contains(currentFilter.albumFilter, true)

                val matchesTags = currentFilter.tagFilter.isEmpty() ||
                        item.tags.any {
                            currentFilter.tagFilter.contains(it, true)
                        }

                val matchesDuration =
                    item.durationSeconds in currentFilter.minDuration..currentFilter.maxDuration

                matchesTitle && matchesArtist &&
                        matchesAlbum && matchesTags && matchesDuration
            }
        }

        // Apply sorting
        items = when (currentSortBy) {
            SortBy.ENTRY_ORDER -> {
                // Sort by createdAt timestamp for proper entry order
                items.sortedBy { item ->
                    when (item) {
                        is MediaItem.SongItem -> item.song.createdAt?.toDate()?.time ?: 0L
                        is MediaItem.VideoItem -> item.video.createdAt?.toDate()?.time ?: 0L
                    }
                }
            }
            SortBy.TITLE -> items.sortedBy { it.title }
            SortBy.ARTIST -> items.sortedBy { it.artist }
            SortBy.ALBUM -> items.sortedBy { it.album }
        }

        if (currentSortOrder == SortOrder.DESCENDING) {
            items = items.reversed()
        }

        filteredMediaItems.clear()
        filteredMediaItems.addAll(items)

        mediaAdapter.updateItems(filteredMediaItems)
        updateDisplay()
    }

    private fun updateDisplay() {
        itemCount.text = "${filteredMediaItems.size} items"
    }

    // -------------------------
    // REMAINING SIDEBAR METHODS
    // -------------------------

    private fun setupSortSpinners() {
        val sortBySpinner = findViewById<Spinner>(R.id.spinnerSortBy)
        val orderSpinner = findViewById<Spinner>(R.id.spinnerOrder)

        // Sort by options
        val sortByOptions = arrayOf("Entry Order", "Title", "Artist", "Album")
        val sortByAdapter = ArrayAdapter(this, R.layout.spinner_item_white, sortByOptions)
        sortByAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
        sortBySpinner.adapter = sortByAdapter

        sortBySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    currentSortBy = SortBy.values()[position]
                    applySortAndFilter()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // Order options
        val orderOptions = arrayOf("Ascending", "Descending")
        val orderAdapter = ArrayAdapter(this, R.layout.spinner_item_white, orderOptions)
        orderAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
        orderSpinner.adapter = orderAdapter

        orderSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    currentSortOrder = SortOrder.values()[position]
                    applySortAndFilter()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupCollapsibleSections() {
        val filtersHeader = findViewById<View>(R.id.filtersHeader)
        val filtersContent = findViewById<View>(R.id.filtersContent)
        val filtersArrow = findViewById<View>(R.id.filtersArrow)

        val tagsHeader = findViewById<View>(R.id.tagsHeader)
        val tagsContent = findViewById<View>(R.id.tagsContent)
        val tagsArrow = findViewById<View>(R.id.tagsArrow)

        filtersHeader.setOnClickListener {
            toggleSection(filtersContent, filtersArrow)
        }

        tagsHeader.setOnClickListener {
            toggleSection(tagsContent, tagsArrow)
        }
    }

    private fun toggleSection(content: View, arrow: View) {
        if (content.visibility == View.GONE) {
            content.visibility = View.VISIBLE
            arrow.rotation = 90f
        } else {
            content.visibility = View.GONE
            arrow.rotation = 0f
        }
    }

    private fun setupFilterInputs() {
        val filters = listOf(
            R.id.filterTitle,
            R.id.filterArtist,
            R.id.filterAlbum,
            R.id.filterMinDuration,
            R.id.filterMaxDuration
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateFilters()
            }
        }

        filters.forEach {
            findViewById<EditText>(it).addTextChangedListener(watcher)
        }

        findViewById<MaterialButton>(R.id.btnClearFilters)
            .setOnClickListener { clearFilters() }
    }

    private fun updateFilters() {
        currentFilter = FilterOptions(
            titleFilter = findViewById<EditText>(R.id.filterTitle).text?.toString()?.trim() ?: "",
            artistFilter = findViewById<EditText>(R.id.filterArtist).text?.toString()?.trim() ?: "",
            albumFilter = findViewById<EditText>(R.id.filterAlbum).text?.toString()?.trim() ?: "",
            tagFilter = filterTagChipAdapter.getSelectedTags().joinToString(", "),
            minDuration = findViewById<EditText>(R.id.filterMinDuration)
                .text?.toString()?.toIntOrNull()?.times(60) ?: 0,
            maxDuration = findViewById<EditText>(R.id.filterMaxDuration)
                .text?.toString()?.toIntOrNull()?.times(60) ?: Int.MAX_VALUE
        )

        applySortAndFilter()
    }

    private fun clearFilters() {
        findViewById<EditText>(R.id.filterTitle).text?.clear()
        findViewById<EditText>(R.id.filterArtist).text?.clear()
        findViewById<EditText>(R.id.filterAlbum).text?.clear()
        findViewById<EditText>(R.id.filterMinDuration).text?.clear()
        findViewById<EditText>(R.id.filterMaxDuration).text?.clear()

        filterTagChipAdapter.clearSelection()

        currentFilter = FilterOptions()
        applySortAndFilter()
    }

    private fun setupTagManagement() {
        val addTagInput = findViewById<EditText>(R.id.addTagInput)
        val btnAddTag = findViewById<MaterialButton>(R.id.btnAddTag)

        btnAddTag.setOnClickListener {
            val tagName = addTagInput.text?.toString()?.trim()
            if (!tagName.isNullOrEmpty()) {
                addNewTag(tagName)
                addTagInput.text?.clear()
            } else {
                Toast.makeText(this, "Please enter a tag name", Toast.LENGTH_SHORT).show()
            }
        }

        addTagInput.setOnEditorActionListener { _, _, _ ->
            val tagName = addTagInput.text?.toString()?.trim()
            if (!tagName.isNullOrEmpty()) {
                addNewTag(tagName)
                addTagInput.text?.clear()
            }
            true
        }
    }

    private fun addNewTag(tag: String) {
        // Check if tag already exists locally to avoid duplicate requests
        if (availableTags.contains(tag)) {
            Toast.makeText(this, "Tag '$tag' already exists", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                repository.addGlobalTag(tag)
                    .onSuccess {
                        // Add to local list
                        availableTags.add(tag)
                        availableTags.sort()

                        // Update all adapters that use global tags
                        manageableTagAdapter.updateTags(availableTags)
                        filterTagChipAdapter.updateTags(availableTags)

                        Toast.makeText(this@PlaylistActivity, "Tag '$tag' added", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { exception ->
                        Toast.makeText(
                            this@PlaylistActivity,
                            "Error adding tag: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteTag(tag: String) {
        lifecycleScope.launch {
            try {
                repository.removeGlobalTag(tag)
                    .onSuccess {
                        // Remove from local lists
                        availableTags.remove(tag)
                        manageableTagAdapter.removeTag(tag)
                        filterTagChipAdapter.updateTags(availableTags)

                        // Remove from displayed media items (this is just local UI update)
                        allMediaItems.forEach { item ->
                            item.tags.removeAll { it == tag }
                        }
                        mediaAdapter.updateItems(filteredMediaItems)

                        Toast.makeText(this@PlaylistActivity, "Tag '$tag' deleted", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { exception ->
                        Toast.makeText(
                            this@PlaylistActivity,
                            "Error deleting tag: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------
    // PLAYER ACTIONS
    // -------------------------

    private fun playMediaItem(item: MediaItem) {
        Toast.makeText(this, "Opening player for: ${item.title}", Toast.LENGTH_SHORT).show()
        // TODO: Open separate MusicPlayerActivity or VideoPlayerActivity based on media type
    }

    private fun playAll() {
        if (filteredMediaItems.isNotEmpty()) {
            Toast.makeText(this, "Opening player for all ${filteredMediaItems.size} items", Toast.LENGTH_SHORT).show()
            // TODO: Open player with playlist
        } else {
            Toast.makeText(this, "No items to play", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shufflePlay() {
        Toast.makeText(this, "Shuffle mode toggled", Toast.LENGTH_SHORT).show()
        // TODO: Toggle shuffle state and save preference
    }

    private fun toggleRepeat() {
        Toast.makeText(this, "Repeat mode toggled", Toast.LENGTH_SHORT).show()
        // TODO: Toggle repeat state and save preference
    }

    private fun openEditPlaylistDialog() {
        val dialog = EditPlaylistDialog.newInstance(
            playlistId = currentPlaylist?.id ?: "",
            playlistName = currentPlaylist?.name ?: "Playlist",
            mediaItems = allMediaItems,
            availableTags = availableTags,
            onPlaylistUpdated = {
                // Refresh the playlist data when dialog closes
                val playlistId = currentPlaylist?.id
                if (playlistId != null) {
                    Log.d("PlaylistActivity", "Refreshing playlist data after dialog closed")
                    loadPlaylistFromFirebase(playlistId)
                }
            }
        )
        dialog.show(supportFragmentManager, "EditPlaylist")
    }

    private fun refreshGlobalTags() {
        lifecycleScope.launch {
            try {
                repository.getGlobalTags()
                    .onSuccess { globalTags ->
                        // Update available tags
                        availableTags.clear()
                        availableTags.addAll(globalTags.sorted())

                        // Update all adapters with new tags
                        manageableTagAdapter.updateTags(availableTags)
                        filterTagChipAdapter.updateTags(availableTags)

                        Log.d("PlaylistActivity", "Global tags refreshed: ${availableTags.size} tags")
                    }
                    .onFailure { exception ->
                        Log.e("PlaylistActivity", "Error refreshing global tags: ${exception.message}")
                    }
            } catch (e: Exception) {
                Log.e("PlaylistActivity", "Exception refreshing global tags: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh global tags when activity resumes in case they were modified elsewhere
        refreshGlobalTags()
    }
}