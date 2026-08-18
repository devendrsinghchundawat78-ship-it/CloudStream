package com.lagradost.cloudstream3.ui.download

import android.app.Activity
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP_DOWNLOAD
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Bottom-sheet source picker for downloads.
 *
 * Slips up from the bottom with a "searching sources…" state, then lists every
 * in-app downloadable source (name + quality + size when available). Tapping a
 * source starts the download for that exact link.
 *
 * Purely additive UI on top of the existing [com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager]
 * — the download engine, storage format and data system are untouched.
 */
class DownloadSourcePicker(
    private val activity: Activity,
    private val episode: ResultEpisode,
    private val onPick: (ExtractorLink, List<SubtitleData>) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dialog: BottomSheetDialog? = null
    private var loadJob: Job? = null

    private val rows = Collections.synchronizedList(mutableListOf<SourceRow>())
    private val subs = Collections.synchronizedSet(mutableSetOf<SubtitleData>())
    private var adapter: SourceAdapter? = null

    data class SourceRow(val link: ExtractorLink, @Volatile var size: Long? = null)

    fun show() {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.download_source_picker, null, false)
        val title = view.findViewById<TextView>(R.id.title_text)
        val list = view.findViewById<RecyclerView>(R.id.source_list)

        title.text = activity.getNameFull(episode.name, episode.episode, episode.season)

        list.layoutManager = LinearLayoutManager(activity)
        adapter = SourceAdapter()
        list.adapter = adapter

        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(view)
        dialog = sheet

        sheet.setOnDismissListener { cancel() }
        sheet.setOnCancelListener { cancel() }
        sheet.show()

        loadJob = scope.launch {
            val generator = RepoLinkGenerator(listOf(episode))
            try {
                generator.generateLinks(
                    clearCache = false,
                    sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                    callback = { (link, _) -> link?.let { onNewLink(it) } },
                    subtitleCallback = { sub -> subs.add(sub) },
                    offset = 0,
                    isCasting = false,
                )
            } catch (t: Throwable) {
                logError(t)
            }

            activity.runOnUiThread { onLinksLoaded() }
            fetchSizes()
        }
    }

    private fun cancel() {
        loadJob?.cancel()
        scope.cancel()
        dialog = null
    }

    private fun onNewLink(link: ExtractorLink) {
        synchronized(rows) {
            if (rows.any { it.link.url == link.url }) return
            rows.add(SourceRow(link))
        }
        activity.runOnUiThread {
            dialog?.findViewById<View>(R.id.loading_holder)?.isVisible = false
            dialog?.findViewById<RecyclerView>(R.id.source_list)?.isVisible = true
            adapter?.notifyDataSetChanged()
        }
    }

    private fun onLinksLoaded() {
        val d = dialog ?: return
        d.findViewById<View>(R.id.loading_holder).isVisible = false

        if (synchronized(rows) { rows.isEmpty() }) {
            showToast(R.string.no_links_found_toast, Toast.LENGTH_SHORT)
            d.dismiss()
            return
        }

        d.findViewById<RecyclerView>(R.id.source_list).isVisible = true
        adapter?.notifyDataSetChanged()
    }

    /** Fetch file sizes for video links (parallel, limited concurrency). */
    private suspend fun fetchSizes() {
        val videoLinks = synchronized(rows) {
            rows.filter { it.link.type == ExtractorLinkType.VIDEO }.toList()
        }
        if (videoLinks.isEmpty()) return

        val sem = Semaphore(4)
        videoLinks.map { row ->
            async {
                sem.withPermit {
                    val size = runCatching { row.link.getVideoSize(timeoutSeconds = 3L) }.getOrNull()
                    if (size != null) {
                        row.size = size
                        activity.runOnUiThread { adapter?.notifyDataSetChanged() }
                    }
                }
            }
        }.awaitAll()
    }

    private inner class SourceAdapter : RecyclerView.Adapter<SourceAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.source_name)
            val details: TextView = view.findViewById(R.id.source_details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.download_source_item, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = synchronized(rows) { rows.size }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = synchronized(rows) { rows.getOrNull(position) } ?: return

            holder.name.text = row.link.name.ifBlank { row.link.source }

            val quality = Qualities.getStringByInt(row.link.quality)
            val sizeText = row.size?.let { Formatter.formatShortFileSize(activity, it) }
            val details = buildString {
                if (quality.isNotBlank()) append(quality)
                if (sizeText != null) {
                    if (isNotEmpty()) append("  •  ")
                    append(sizeText)
                }
            }
            holder.details.isVisible = details.isNotBlank()
            holder.details.text = details

            holder.itemView.setOnClickListener {
                val link = row.link
                val subsList = synchronized(subs) { subs.toList() }
                dialog?.dismiss()
                onPick(link, subsList)
            }
        }
    }
}
