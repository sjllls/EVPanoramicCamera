package com.mg4.camera.playback

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mg4.camera.R
import java.io.File

/**
 * 回放列表适配器（文件夹 / 视频文件通用）。
 * 支持多选模式：勾选若干文件/文件夹后由界面统一删除。
 */
class MediaAdapter(private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<MediaAdapter.VH>() {

    private val items = ArrayList<Pair<String, File?>>()

    var selectionMode = false
        private set
    val selected = LinkedHashSet<File>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(list: List<Pair<String, File?>>) {
        items.clear()
        items.addAll(list)
        // 清掉已不在列表里的选中项
        selected.retainAll(items.mapNotNull { it.second }.toSet())
        notifyDataSetChanged()
    }

    /** 取当前列表第 index 项 */
    fun itemAt(index: Int): Pair<String, File?>? = items.getOrNull(index)

    fun setSelectionMode(on: Boolean) {
        selectionMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun toggle(file: File) {
        if (!selected.add(file)) selected.remove(file)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun selectAll() {
        items.forEach { it.second?.let { f -> selected.add(f) } }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.text)
        val check: CheckBox = v.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (label, file) = items[position]
        holder.text.text = label
        holder.check.visibility =
            if (selectionMode && file != null) View.VISIBLE else View.GONE
        holder.check.isChecked = file != null && file in selected
        holder.itemView.setOnClickListener {
            if (selectionMode && file != null) toggle(file) else onClick(position)
        }
    }

    override fun getItemCount(): Int = items.size
}
