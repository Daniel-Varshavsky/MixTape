package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R

class ManageableTagAdapter(
    private var tags: MutableList<String>,
    private val onDeleteTag: (String) -> Unit
) : RecyclerView.Adapter<ManageableTagAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tagName: TextView = view.findViewById(R.id.tagName)
        val deleteBtn: View = view.findViewById(R.id.btnDeleteTag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manageable_tag, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.tagName.text = tag

        holder.deleteBtn.setOnClickListener {
            onDeleteTag(tag)
        }
    }

    override fun getItemCount() = tags.size

    fun updateTags(newTags: List<String>) {
        tags.clear()
        tags.addAll(newTags)
        notifyDataSetChanged()
    }

    fun removeTag(tag: String) {
        val position = tags.indexOf(tag)
        if (position != -1) {
            tags.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun addTag(tag: String) {
        if (!tags.contains(tag)) {
            tags.add(tag)
            notifyItemInserted(tags.size - 1)
        }
    }
}
