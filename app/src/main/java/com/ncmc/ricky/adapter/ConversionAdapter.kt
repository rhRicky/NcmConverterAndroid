package com.ncmc.ricky.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.ncmc.ricky.R
import com.ncmc.ricky.model.ConversionItem

/**
 * 转换队列适配器：逐文件展示状态与单文件进度
 */
class ConversionAdapter : RecyclerView.Adapter<ConversionAdapter.Holder>() {

    private val items = mutableListOf<ConversionItem>()

    fun submit(newItems: List<ConversionItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversion, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.file.name

        when (item.status) {
            ConversionItem.Status.PENDING -> {
                holder.pb.visibility = View.GONE
                holder.ivStatus.setImageResource(R.drawable.ic_music)
                tint(holder, R.color.textSecondary)
                holder.tvDetail.text = holder.itemView.context.getString(R.string.status_waiting)
            }
            ConversionItem.Status.CONVERTING -> {
                holder.pb.visibility = View.VISIBLE
                holder.ivStatus.setImageResource(R.drawable.ic_convert)
                tint(holder, R.color.colorPrimary)
                val percent = if (item.size > 0) (item.progress * 100 / item.size) else 0
                holder.tvDetail.text =
                    holder.itemView.context.getString(R.string.status_converting, percent)
            }
            ConversionItem.Status.DONE -> {
                holder.pb.visibility = View.GONE
                holder.ivStatus.setImageResource(R.drawable.ic_check)
                tint(holder, R.color.success)
                holder.tvDetail.text = holder.itemView.context.getString(R.string.status_done)
            }
            ConversionItem.Status.FAILED -> {
                holder.pb.visibility = View.GONE
                holder.ivStatus.setImageResource(R.drawable.ic_error)
                tint(holder, R.color.error)
                holder.tvDetail.text = item.message ?: holder.itemView.context.getString(R.string.status_failed)
            }
            ConversionItem.Status.SKIPPED -> {
                holder.pb.visibility = View.GONE
                holder.ivStatus.setImageResource(R.drawable.ic_error)
                tint(holder, R.color.textSecondary)
                holder.tvDetail.text = item.message ?: holder.itemView.context.getString(R.string.status_skipped)
            }
        }
    }

    private fun tint(holder: Holder, colorRes: Int) {
        val color = ContextCompat.getColor(holder.itemView.context, colorRes)
        ImageViewCompat.setImageTintList(holder.ivStatus, ColorStateList.valueOf(color))
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvDetail: TextView = itemView.findViewById(R.id.tvDetail)
        val pb: ProgressBar = itemView.findViewById(R.id.pbPending)
    }
}
