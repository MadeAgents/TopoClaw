package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FullscreenImagePagerAdapter(
    private val context: Context,
    private val imageUris: List<Uri>,
    private val decodeBitmap: (Uri) -> Bitmap?,
    private val onSingleTap: () -> Unit,
    private val onLongPress: (Uri) -> Unit
) : RecyclerView.Adapter<FullscreenImagePagerAdapter.ImagePageViewHolder>() {

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImagePageViewHolder {
        val zoomableImageView = ZoomableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }
        val root = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            addView(zoomableImageView)
        }
        return ImagePageViewHolder(root, zoomableImageView)
    }

    override fun onBindViewHolder(holder: ImagePageViewHolder, position: Int) {
        holder.bind(imageUris[position])
    }

    override fun onViewRecycled(holder: ImagePageViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = imageUris.size

    fun release() {
        adapterScope.cancel()
    }

    inner class ImagePageViewHolder(
        itemView: FrameLayout,
        private val imageView: ZoomableImageView
    ) : RecyclerView.ViewHolder(itemView) {
        private var loadJob: Job? = null

        fun bind(uri: Uri) {
            loadJob?.cancel()
            imageView.setImageDrawable(null)
            imageView.interactionListener = object : ZoomableImageView.InteractionListener {
                override fun onSingleTap() {
                    this@FullscreenImagePagerAdapter.onSingleTap.invoke()
                }

                override fun onLongPress() {
                    this@FullscreenImagePagerAdapter.onLongPress.invoke(uri)
                }
            }
            loadJob = adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                if (!isActive) return@launch
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setBackgroundColor(0xFF202020.toInt())
                }
            }
        }

        fun unbind() {
            loadJob?.cancel()
            imageView.interactionListener = null
            imageView.setImageDrawable(null)
        }
    }
}
