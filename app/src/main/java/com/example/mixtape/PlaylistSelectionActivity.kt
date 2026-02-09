package com.example.mixtape

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.PlaylistAdapter
import com.example.mixtape.model.Playlist

class PlaylistSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_selection)

        val recycler = findViewById<RecyclerView>(R.id.playlistRecycler)
        val addButton = findViewById<Button>(R.id.btnAddPlaylist)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PlaylistAdapter(fakePlaylists())

        addButton.setOnClickListener {
            AddPlaylistDialog().show(supportFragmentManager, "AddPlaylist")
        }
    }

    private fun fakePlaylists(): List<Playlist> {
        return listOf(
            Playlist("My Favorites", 4, 2),
            Playlist("Rock Classics", 4, 1),
            Playlist("Music Videos", 1, 4),
            Playlist("Chill Vibes", 5, 1)
        )
    }
}
