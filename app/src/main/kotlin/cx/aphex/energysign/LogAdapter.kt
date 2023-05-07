package cx.aphex.energysign

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class LogAdapter(private val context: Context) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logLines: ArrayList<String> = ArrayList(MAX_LOG_LINES)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder =
        LogViewHolder(
            LayoutInflater.from(context)
                .inflate(R.layout.item_log, parent, false)
        )

    override fun getItemCount(): Int = logLines.size

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logLines[position])
    }

    fun clear() {
        logLines.clear()
        notifyDataSetChanged()
    }

    fun add(logLine: String) {
        while (itemCount > MAX_LOG_LINES) {
            remove(0)
        }
        logLines.add(logLine)
        notifyItemInserted(logLines.lastIndex)
    }

    private fun remove(idx: Int) {
        logLines.removeAt(idx)
        notifyItemRemoved(idx)
    }

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        fun bind(logLine: String) {
            itemView.findViewById<TextView>(R.id.log_line).text = logLine
        }
    }

    companion object {
        private const val MAX_LOG_LINES: Int = 999
    }
}
