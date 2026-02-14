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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.ManageableTagAdapter
import com.example.mixtape.adapters.MediaAdapter
import com.example.mixtape.adapters.FilterTagChipAdapter
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
    private var allMediaItems = mutableListOf<MediaItem>()
    private var filteredMediaItems = mutableListOf<MediaItem>()
    private var availableTags = mutableListOf<String>()

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
        updateDisplay()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        playlistTitle = findViewById(R.id.playlistTitle)
        itemCount = findViewById(R.id.itemCount)
        userName = findViewById(R.id.userName)

        // Header buttons
        findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<MaterialButton>(R.id.btnEditPlaylist).setOnClickListener {
            openEditPlaylistDialog()
        }

        findViewById<MaterialButton>(R.id.btnPlayAll).setOnClickListener {
            playAll()
        }

        findViewById<MaterialButton>(R.id.btnShuffle).setOnClickListener {
            shufflePlay()
        }

        findViewById<MaterialButton>(R.id.btnRepeat).setOnClickListener {
            toggleRepeat()
        }

        // Close menu button
        findViewById<MaterialButton>(R.id.btnCloseMenu).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun setupLogout() {
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            logout()
        }
    }

    private fun updateUserDisplay() {
        val user = auth.currentUser
        val displayName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        userName.text = displayName
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
        manageableTagAdapter = ManageableTagAdapter(availableTags) { tag ->
            deleteTag(tag)
        }
        tagsRecycler.adapter = manageableTagAdapter

        // Filter tag chips RecyclerView
        val filterTagsRecycler = findViewById<RecyclerView>(R.id.filterTagsChipsRecycler)
        filterTagsRecycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        filterTagChipAdapter = FilterTagChipAdapter(availableTags) { tag, isSelected ->
            updateTagFilters()
        }
        filterTagsRecycler.adapter = filterTagChipAdapter
    }

    private fun setupSidebar() {
        // Back to playlists
        findViewById<MaterialButton>(R.id.btnBackToPlaylists).setOnClickListener {
            finish()
        }

        // Setup sort spinners
        setupSortSpinners()

        // Setup collapsible sections
        setupCollapsibleSections()

        // Setup filter inputs
        setupFilterInputs()

        // Setup tag management
        setupTagManagement()
    }

    private fun loadPlaylistData() {
        val playlistId = intent.getStringExtra("PLAYLIST_ID")
        val playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "Unknown Playlist"

        playlistTitle.text = playlistName

        if (playlistId != null) {
            loadPlaylistFromFirebase(playlistId)
        } else {
            // Fallback: create empty playlist view
            Toast.makeText(this, "No playlist data available", Toast.LENGTH_SHORT).show()
            updateDisplay()
        }
    }

    private fun loadPlaylistFromFirebase(playlistId: String) {
        lifecycleScope.launch {
            try {
                val result = repository.getPlaylistWithMedia(playlistId)
                result.onSuccess { (playlist, songs, videos) ->
                    currentPlaylist = playlist
                    playlistTitle.text = playlist.name

                    // Convert Firebase models to MediaItems
                    allMediaItems.clear()

                    songs.forEach { song ->
                        allMediaItems.add(MediaItem.SongItem(song))
                    }

                    videos.forEach { video ->
                        allMediaItems.add(MediaItem.VideoItem(video))
                    }

                    // Load global tags
                    loadGlobalTags()

                    // Update UI
                    filteredMediaItems.clear()
                    filteredMediaItems.addAll(allMediaItems)
                    applySortAndFilter()

                    if (allMediaItems.isEmpty()) {
                        Toast.makeText(
                            this@PlaylistActivity,
                            "This playlist is empty. Add some content!",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    Log.d("PlaylistActivity", "Playlist refreshed: ${allMediaItems.size} items loaded")

                }.onFailure { exception ->
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
                val result = repository.getGlobalTags()
                result.onSuccess { globalTags ->
                    availableTags.clear()
                    availableTags.addAll(globalTags) // This is safer than reassigning

                    // Update adapters
                    manageableTagAdapter.updateTags(availableTags)
                    filterTagChipAdapter.notifyDataSetChanged()
                }.onFailure { exception ->
                    // Silently handle - tags are not critical for basic functionality
                    availableTags.clear()
                }
            } catch (e: Exception) {
                // Silently handle
            }
        }
    }

    private fun setupSortSpinners() {
        val sortBySpinner = findViewById<Spinner>(R.id.spinnerSortBy)
        val orderSpinner = findViewById<Spinner>(R.id.spinnerOrder)

        // Sort by options
        val sortByOptions = arrayOf("Entry Order", "Title", "Artist", "Album")
        val sortByAdapter = ArrayAdapter(this, R.layout.spinner_item_white, sortByOptions)
        sortByAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white)
        sortBySpinner.adapter = sortByAdapter

        sortBySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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

        orderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
        val titleFilter = findViewById<EditText>(R.id.filterTitle)
        val artistFilter = findViewById<EditText>(R.id.filterArtist)
        val albumFilter = findViewById<EditText>(R.id.filterAlbum)
        val minDurationFilter = findViewById<EditText>(R.id.filterMinDuration)
        val maxDurationFilter = findViewById<EditText>(R.id.filterMaxDuration)

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateFilters()
            }
        }

        titleFilter.addTextChangedListener(textWatcher)
        artistFilter.addTextChangedListener(textWatcher)
        albumFilter.addTextChangedListener(textWatcher)
        minDurationFilter.addTextChangedListener(textWatcher)
        maxDurationFilter.addTextChangedListener(textWatcher)

        findViewById<MaterialButton>(R.id.btnClearFilters).setOnClickListener {
            clearFilters()
        }
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
                val result = repository.addGlobalTag(tag)
                result.onSuccess {
                    // Add to local list
                    availableTags.add(tag)
                    availableTags.sort()

                    // Update all adapters that use global tags
                    manageableTagAdapter.updateTags(availableTags)
                    filterTagChipAdapter.notifyDataSetChanged()

                    Toast.makeText(this@PlaylistActivity, "Tag '$tag' added", Toast.LENGTH_SHORT).show()
                }.onFailure { exception ->
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
                val result = repository.removeGlobalTag(tag)
                result.onSuccess {
                    // Remove from local lists
                    availableTags.remove(tag)
                    manageableTagAdapter.removeTag(tag)
                    filterTagChipAdapter.notifyDataSetChanged()

                    // Remove from displayed media items (this is just local UI update)
                    allMediaItems.forEach { item ->
                        item.tags.removeAll { it == tag }
                    }
                    mediaAdapter.updateItems(filteredMediaItems)

                    Toast.makeText(this@PlaylistActivity, "Tag '$tag' deleted", Toast.LENGTH_SHORT).show()
                }.onFailure { exception ->
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

    // Keep all the existing filter, sort, and UI methods but remove the fake data loading
    private fun updateFilters() {
        val titleFilter = findViewById<EditText>(R.id.filterTitle)
        val artistFilter = findViewById<EditText>(R.id.filterArtist)
        val albumFilter = findViewById<EditText>(R.id.filterAlbum)
        val minDurationFilter = findViewById<EditText>(R.id.filterMinDuration)
        val maxDurationFilter = findViewById<EditText>(R.id.filterMaxDuration)

        val selectedTags = filterTagChipAdapter.getSelectedTags()

        currentFilter = FilterOptions(
            titleFilter = titleFilter.text?.toString()?.trim() ?: "",
            artistFilter = artistFilter.text?.toString()?.trim() ?: "",
            albumFilter = albumFilter.text?.toString()?.trim() ?: "",
            tagFilter = selectedTags.joinToString(", "),
            minDuration = minDurationFilter.text?.toString()?.toIntOrNull()?.times(60) ?: 0,
            maxDuration = maxDurationFilter.text?.toString()?.toIntOrNull()?.times(60) ?: Int.MAX_VALUE
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

    private fun applySortAndFilter() {
        var items = allMediaItems.toList()

        // Apply filters
        if (!currentFilter.isEmpty()) {
            items = items.filter { item ->
                val matchesTitle = currentFilter.titleFilter.isEmpty() ||
                        item.title.contains(currentFilter.titleFilter, ignoreCase = true)
                val matchesArtist = currentFilter.artistFilter.isEmpty() ||
                        item.artist.contains(currentFilter.artistFilter, ignoreCase = true)
                val matchesAlbum = currentFilter.albumFilter.isEmpty() ||
                        item.album.contains(currentFilter.albumFilter, ignoreCase = true)
                val matchesTags = currentFilter.tagFilter.isEmpty() ||
                        item.tags.any { it.contains(currentFilter.tagFilter, ignoreCase = true) }
                val matchesDuration = item.durationSeconds >= currentFilter.minDuration &&
                        item.durationSeconds <= currentFilter.maxDuration

                matchesTitle && matchesArtist && matchesAlbum && matchesTags && matchesDuration
            }
        }

        // Apply sorting
        items = when (currentSortBy) {
            SortBy.ENTRY_ORDER -> {
                // Sort by createdAt timestamp instead of string ID
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
        updateItemCount()
    }

    private fun updateDisplay() {
        updateItemCount()
    }

    private fun updateItemCount() {
        itemCount.text = "${filteredMediaItems.size} items"
    }

    private fun playMediaItem(mediaItem: MediaItem) {
        Toast.makeText(this, "Opening player for: ${mediaItem.title}", Toast.LENGTH_SHORT).show()
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

    private fun updateTagFilters() {
        val selectedTags = filterTagChipAdapter.getSelectedTags()
        currentFilter = currentFilter.copy(
            tagFilter = selectedTags.joinToString(", ")
        )
        applySortAndFilter()
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
                val result = repository.getGlobalTags()
                result.onSuccess { globalTags ->
                    // Update available tags
                    availableTags.clear()
                    availableTags.addAll(globalTags.sorted())

                    // Update all adapters with new tags
                    manageableTagAdapter.updateTags(availableTags)
                    filterTagChipAdapter.notifyDataSetChanged()

                    Log.d("PlaylistActivity", "Global tags refreshed: ${availableTags.size} tags")
                }.onFailure { exception ->
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