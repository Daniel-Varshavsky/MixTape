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
import com.example.mixtape.repository.FirebaseRepository
import com.example.mixtape.ui.AddPlaylistDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

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

        // Initialize Firebase Auth and Repository
        auth = FirebaseAuth.getInstance()
        repository = FirebaseRepository()

        // Check if user is logged in
        if (auth.currentUser == null) {
            redirectToLogin()
            return
        }

        initViews()
        setupLogout()
        updateUserDisplay()
        setupRecyclerView()
        loadPlaylists()

        val addButton = findViewById<Button>(R.id.btnAddPlaylist)
        addButton.setOnClickListener {
            AddPlaylistDialog().show(supportFragmentManager, "AddPlaylist")
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

    private fun updateUserDisplay() {
        val user = auth.currentUser
        val userName = user?.displayName ?: user?.email?.substringBefore("@") ?: "User"
        subtitleText.text = "Welcome back, $userName!"
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter(playlists) { playlist ->
            // Launch PlaylistActivity when a playlist is clicked
            val intent = Intent(this, PlaylistActivity::class.java)
            intent.putExtra("PLAYLIST_ID", playlist.id)
            intent.putExtra("PLAYLIST_NAME", playlist.name)
            startActivity(intent)
        }
        recyclerView.adapter = playlistAdapter
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            try {
                val result = repository.getUserPlaylists()
                result.onSuccess { userPlaylists ->
                    playlists.clear()
                    playlists.addAll(userPlaylists)
                    playlistAdapter.notifyDataSetChanged()

                    // Show message if no playlists
                    if (userPlaylists.isEmpty()) {
                        Toast.makeText(
                            this@PlaylistSelectionActivity,
                            "No playlists yet. Create your first one!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { exception ->
                    Toast.makeText(
                        this@PlaylistSelectionActivity,
                        "Error loading playlists: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistSelectionActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
        // Reload playlists when returning to this activity
        loadPlaylists()
    }
}