package com.example.mixtape

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.ManageableTagAdapter
import com.example.mixtape.adapters.MediaAdapter
import com.example.mixtape.adapters.FilterTagChipAdapter
import com.example.mixtape.model.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

class PlaylistActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var manageableTagAdapter: ManageableTagAdapter
    private lateinit var filterTagChipAdapter: FilterTagChipAdapter
    private lateinit var playlistTitle: MaterialTextView
    private lateinit var itemCount: MaterialTextView

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

        initViews()
        setupRecyclerViews()
        setupSidebar()
        loadPlaylistData()
        updateDisplay()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        playlistTitle = findViewById(R.id.playlistTitle)
        itemCount = findViewById(R.id.itemCount)

        // Header buttons
        findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<MaterialButton>(R.id.btnEditPlaylist).setOnClickListener {
            openEditPlaylistDialog()
        }

        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            logout()
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
        filterTagChipAdapter = FilterTagChipAdapter(availableTags) { tag ->
            // When a tag chip is clicked, add it to the filter
            val filterTagsInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.filterTags)
            val currentText = filterTagsInput.text?.toString()?.trim() ?: ""

            if (currentText.isEmpty()) {
                filterTagsInput.setText(tag)
            } else if (!currentText.contains(tag)) {
                filterTagsInput.setText("$currentText, $tag")
            }
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

    private fun setupSortSpinners() {
        val sortBySpinner = findViewById<Spinner>(R.id.spinnerSortBy)
        val orderSpinner = findViewById<Spinner>(R.id.spinnerOrder)

        // Sort by options
        val sortByOptions = arrayOf("Entry Order", "Title", "Artist", "Album")
        val sortByAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortByOptions)
        sortByAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
        val orderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, orderOptions)
        orderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
        val titleFilter = findViewById<TextInputEditText>(R.id.filterTitle)
        val artistFilter = findViewById<TextInputEditText>(R.id.filterArtist)
        val albumFilter = findViewById<TextInputEditText>(R.id.filterAlbum)
        val tagsFilter = findViewById<TextInputEditText>(R.id.filterTags)
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
        tagsFilter.addTextChangedListener(textWatcher)
        minDurationFilter.addTextChangedListener(textWatcher)
        maxDurationFilter.addTextChangedListener(textWatcher)

        findViewById<MaterialButton>(R.id.btnClearFilters).setOnClickListener {
            clearFilters()
        }
    }

    private fun setupTagManagement() {
        val addTagInput = findViewById<TextInputEditText>(R.id.addTagInput)

        // Add tag when end icon is clicked (you'd need to handle this in the TextInputLayout)
        addTagInput.setOnEditorActionListener { _, _, _ ->
            val tagName = addTagInput.text?.toString()?.trim()
            if (!tagName.isNullOrEmpty()) {
                addNewTag(tagName)
                addTagInput.text?.clear()
            }
            true
        }
    }

    private fun updateFilters() {
        val titleFilter = findViewById<TextInputEditText>(R.id.filterTitle)
        val artistFilter = findViewById<TextInputEditText>(R.id.filterArtist)
        val albumFilter = findViewById<TextInputEditText>(R.id.filterAlbum)
        val tagsFilter = findViewById<TextInputEditText>(R.id.filterTags)
        val minDurationFilter = findViewById<EditText>(R.id.filterMinDuration)
        val maxDurationFilter = findViewById<EditText>(R.id.filterMaxDuration)

        currentFilter = FilterOptions(
            titleFilter = titleFilter.text?.toString()?.trim() ?: "",
            artistFilter = artistFilter.text?.toString()?.trim() ?: "",
            albumFilter = albumFilter.text?.toString()?.trim() ?: "",
            tagFilter = tagsFilter.text?.toString()?.trim() ?: "",
            minDuration = minDurationFilter.text?.toString()?.toIntOrNull()?.times(60) ?: 0,
            maxDuration = maxDurationFilter.text?.toString()?.toIntOrNull()?.times(60) ?: Int.MAX_VALUE
        )

        applySortAndFilter()
    }

    private fun clearFilters() {
        findViewById<TextInputEditText>(R.id.filterTitle).text?.clear()
        findViewById<TextInputEditText>(R.id.filterArtist).text?.clear()
        findViewById<TextInputEditText>(R.id.filterAlbum).text?.clear()
        findViewById<TextInputEditText>(R.id.filterTags).text?.clear()
        findViewById<EditText>(R.id.filterMinDuration).text?.clear()
        findViewById<EditText>(R.id.filterMaxDuration).text?.clear()

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
            SortBy.ENTRY_ORDER -> items.sortedBy { it.id }
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

    private fun addNewTag(tag: String) {
        if (!availableTags.contains(tag)) {
            availableTags.add(tag)
            availableTags.sort()
            manageableTagAdapter.addTag(tag)
            filterTagChipAdapter.notifyDataSetChanged()
            Toast.makeText(this, "Tag '$tag' added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Tag already exists", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteTag(tag: String) {
        // Remove tag from all media items
        allMediaItems.forEach { item ->
            item.tags.removeAll { it == tag }
        }

        // Remove from available tags
        availableTags.remove(tag)
        manageableTagAdapter.removeTag(tag)
        filterTagChipAdapter.notifyDataSetChanged()

        // Update display to reflect tag removal from media items
        mediaAdapter.updateItems(filteredMediaItems)
        Toast.makeText(this, "Tag '$tag' deleted", Toast.LENGTH_SHORT).show()
    }

    private fun loadPlaylistData() {
        // Get playlist data from intent or load from database
        val playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "My Favorites"
        currentPlaylist = Playlist(playlistName, 0, 0) // Will be updated
        playlistTitle.text = playlistName

        // Load fake data for demo
        loadFakeData()
    }

    private fun loadFakeData() {
        // Fake songs
        allMediaItems.add(MediaItem.SongItem(Song(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 355, mutableListOf("classic", "rock"))))
        allMediaItems.add(MediaItem.SongItem(Song(2, "Thriller", "Michael Jackson", "Thriller", 357, mutableListOf("pop", "iconic"))))
        allMediaItems.add(MediaItem.SongItem(Song(3, "Hotel California", "Eagles", "Hotel California", 390, mutableListOf("rock", "classic"))))
        allMediaItems.add(MediaItem.SongItem(Song(4, "Imagine", "John Lennon", "Imagine", 183, mutableListOf("peace", "classic"))))

        // Fake videos
        allMediaItems.add(MediaItem.VideoItem(Video(5, "Take On Me", "a-ha", "Hunting High and Low", 220, mutableListOf("synth-pop"))))
        allMediaItems.add(MediaItem.VideoItem(Video(6, "Sweet Child O Mine", "Guns N' Roses", "Appetite for Destruction", 356, mutableListOf("rock", "metal"))))

        // Collect all unique tags from media items
        availableTags.clear()
        val allTags = mutableSetOf<String>()
        allMediaItems.forEach { item ->
            allTags.addAll(item.tags)
        }
        availableTags.addAll(allTags.sorted())

        // Update both adapters
        manageableTagAdapter.updateTags(availableTags)
        filterTagChipAdapter.notifyDataSetChanged()

        filteredMediaItems.addAll(allMediaItems)
        applySortAndFilter()
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

    private fun logout() {
        Toast.makeText(this, "Logout functionality will be added later", Toast.LENGTH_SHORT).show()
        // TODO: Implement logout when authentication is added
    }

    private fun openEditPlaylistDialog() {
        val dialog = EditPlaylistDialog.newInstance(
            currentPlaylist?.name ?: "Playlist",
            allMediaItems
        )
        dialog.show(supportFragmentManager, "EditPlaylist")
    }
}