package dev.androidagent.app.widgetprobe

import android.database.DataSetObserver
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.TextView
import java.util.IdentityHashMap

/** Only public View / Adapter APIs. A frame or loading placeholder is never a message row. */
internal class ProbeRows(private val changed: () -> Unit) {
    internal data class Row(val heading: String, val preview: String, val date: String)
    internal data class Sample(
        val collections: Int, val adapterCount: Int, val requested: Int,
        val rows: List<Row>, val capped: Boolean,
    )
    private class Collection(val adapter: Adapter, val observer: DataSetObserver) {
        val views = mutableMapOf<Int, View>()
    }
    private val collections = IdentityHashMap<AdapterView<*>, Collection>()

    fun sample(root: View): Sample {
        val lists = descendants(root).filterIsInstance<AdapterView<*>>().toList()
        collections.keys.toList().filter { it !in lists || it.adapter !== collections[it]?.adapter }
            .forEach { list -> collections.remove(list)?.let { it.adapter.unregisterDataSetObserver(it.observer) } }
        var count = 0
        var requested = 0
        val rows = mutableListOf<Row>()
        lists.forEach { list ->
            val adapter = list.adapter ?: return@forEach
            val collection = collections[list] ?: run {
                val observer = object : DataSetObserver() {
                    override fun onChanged() { collections[list]?.views?.clear(); changed() }
                    override fun onInvalidated() { onChanged() }
                }
                adapter.registerDataSetObserver(observer)
                Collection(adapter, observer).also { collections[list] = it }
            }
            val size = adapter.count.coerceAtLeast(0)
            count += size
            // Retain asynchronous loading views; retrying getView every scan discards their results.
            for (position in 0 until minOf(size, MAX_ROWS)) {
                if (position !in collection.views && requested < 8) {
                    collection.views[position] = adapter.getView(position, null, list)
                    requested++
                }
            }
            collection.views.filterKeys { it < size }.values.forEach { view ->
                readRow(view)?.let(rows::add)
            }
        }
        return Sample(lists.size, count, requested, rows.take(MAX_ROWS), count > MAX_ROWS)
    }

    fun close() {
        collections.values.forEach { it.adapter.unregisterDataSetObserver(it.observer) }
        collections.clear()
    }

    companion object {
        const val MAX_ROWS = 100
        private fun descendants(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
        }

        fun readRow(root: View): Row? {
            val fields = mutableMapOf<String, String>()
            descendants(root).filterIsInstance<TextView>().forEach { view ->
                if (view.visibility != View.VISIBLE || view.id == View.NO_ID) return@forEach
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                val owner = runCatching { view.resources.getResourcePackageName(view.id) }.getOrNull()
                if (owner == "com.whatsapp" && name in setOf("heading", "content", "date")) {
                    fields[name!!] = view.text?.toString().orEmpty()
                }
            }
            // The exact inspected provider uses these three TextViews. Other layouts stay unknown.
            if (!fields.keys.containsAll(listOf("heading", "content", "date"))) return null
            if (fields["heading"].isNullOrBlank() || fields["content"].isNullOrBlank()) return null
            return Row(fields.getValue("heading"), fields.getValue("content"), fields.getValue("date"))
        }
    }
}
