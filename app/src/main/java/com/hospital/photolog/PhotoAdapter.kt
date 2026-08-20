package com.hospital.photolog

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hospital.photolog.databinding.ItemThumbBinding
import java.io.File

class PhotoAdapter(
    private val items: ArrayList<PhotoItem>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    class VH(val binding: ItemThumbBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.img.setImageBitmap(decodeSampled(item.file.absolutePath, 200))
        holder.binding.btnRemove.setOnClickListener { onRemove(position) }
    }

    override fun getItemCount(): Int = items.size

    private fun decodeSampled(path: String, max: Int): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val scale = (opts.outWidth.coerceAtLeast(opts.outHeight) / max).coerceAtLeast(1)
        val o2 = BitmapFactory.Options().apply { inSampleSize = scale }
        return BitmapFactory.decodeFile(path, o2)
    }
}
