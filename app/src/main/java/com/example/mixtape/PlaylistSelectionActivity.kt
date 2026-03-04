package com.example.mixtape

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.PlaylistAdapter
import com.example.mixtape.model.Playlist
import com.example.mixtape.utilities.FirebaseRepository
import com.example.mixtape.ui.AddPlaylistDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * PlaylistSelectionActivity is the landing screen after a successful login.
 * It displays a list of the user's personal and shared playlists, allowing for 
 * creation of new ones or navigation into existing ones.
 */
class PlaylistSelectionActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var repository: FirebaseRepository
    private lateinit var subtitleText: MaterialTextView
    private lateinit var btnLogout: MaterialButton
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var recyclerView: RecyclerView

    private var playlists = mutableListOf<Playlist>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_selection)

        auth = FirebaseAuth.getInstance()
        repository = FirebaseRepository()

        // Security check: Redirect to login if the session has expired
        if (auth.currentUser == null) {
            redirectToLogin()
            return
        }

        initViews()
        setupLogout()
        updateUserDisplay()
        setupRecyclerView()
        loadPlaylists()

        findViewById<Button>(R.id.btnAddPlaylist).setOnClickListener {
            showAddPlaylistDialog()
        }
    }

    private fun initViews() {
        subtitleText = findViewById(R.id.subtitleText)
        btnLogout = findViewById(R.id.btnLogout)
        recyclerView = findViewById(R.id.playlistRecycler)
    }

    private fun setupLogout() {
        btnLogout.setOnClickListener {
            logout()
        }
    }

    /**
     * Fetches and displays the user's name. It first uses the local Firebase user
     * for immediate feedback, then updates with the Firestore profile for accuracy.
     */
    private fun updateUserDisplay() {
        val user = auth.currentUser
        val fallbackName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        subtitleText.text = "Welcome back, $fallbackName!"

        lifecycleScope.launch {
            try {
                repository.getUserProfile().onSuccess { profile ->
                    if (profile.displayName.isNotEmpty()) {
                        subtitleText.text = "Welcome back, ${profile.displayName}!"
                    }
                }
            } catch (e: Exception) {}
        }
    }

    /**
     * Configures the RecyclerView with a custom adapter that handles clicks, 
     * renames, and deletions of playlists.
     */
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter(
            playlists = playlists,
            lifecycleScope = lifecycleScope,
            fragmentManager = supportFragmentManager,
            onPlaylistClick = { playlist ->
                val intent = Intent(this, PlaylistActivity::class.java)
                intent.putExtra("PLAYLIST_ID", playlist.id)
                intent.putExtra("PLAYLIST_NAME", playlist.name)
                startActivity(intent)
            },
            onPlaylistDeleted = { /* Handled internally by adapter */ },
            onPlaylistRenamed = { _, _ -> /* Handled internally by adapter */ }
        )
        recyclerView.adapter = playlistAdapter
    }

    /**
     * Displays the AddPlaylistDialog and refreshes the list upon successful creation.
     */
    private fun showAddPlaylistDialog() {
        val dialog = AddPlaylistDialog.newInstance { _ ->
            loadPlaylists()
        }
        dialog.show(supportFragmentManager, "AddPlaylist")
    }

    /**
     * Loads the user's playlists from Firestore, sorted by creation date (newest first).
     */
    private fun loadPlaylists() {
        lifecycleScope.launch {
            try {
                val result = repository.getUserPlaylists()
                result.onSuccess { userPlaylists ->
                    playlists.clear()
                    playlists.addAll(userPlaylists.sortedByDescending { it.createdAt })
                    playlistAdapter.notifyDataSetChanged()

                    if (userPlaylists.isEmpty()) {
                        Toast.makeText(this@PlaylistSelectionActivity,
                            "No playlists yet. Create your first one!", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { exception ->
                    Toast.makeText(this@PlaylistSelectionActivity,
                        "Error loading playlists: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistSelectionActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to the selection screen to ensure consistency
        loadPlaylists()
        updateUserDisplay()
    }
}
