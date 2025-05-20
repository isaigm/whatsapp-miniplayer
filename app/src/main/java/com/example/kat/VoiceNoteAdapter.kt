package com.example.kat

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceNoteAdapter(
    private var notes: List<File>,
    private val onItemClick: (File, Int) -> Unit // Callback para clics
) : RecyclerView.Adapter<VoiceNoteAdapter.VoiceNoteViewHolder>() {

    var currentlyPlaying: Int = -1
        set(value) {
            val previousPlaying = field
            field = value
            if (previousPlaying != -1) {
                notifyItemChanged(previousPlaying) // Actualiza el ítem que dejó de reproducirse
            }
            if (value != -1) {
                notifyItemChanged(value) // Actualiza el ítem que ahora se reproduce
            }
            // Si solo quieres refrescar todo (menos eficiente pero más simple para empezar):
            // notifyDataSetChanged()
        }

    private val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    /**
     * Se llama cuando RecyclerView necesita un nuevo [ViewHolder] del tipo dado para representar
     * un ítem.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceNoteViewHolder {
        val context = parent.context // Obtener el contexto del ViewGroup padre
        val inflater = LayoutInflater.from(context)
        // Infla tu layout de ítem personalizado.
        // Si usas el layout simple_list_item_1:
        val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
        // Si usas un layout personalizado (ej: R.layout.list_item_voice_note):
        // val view = inflater.inflate(R.layout.list_item_voice_note, parent, false)
        return VoiceNoteViewHolder(view)
    }

    /**
     * Se llama por RecyclerView para mostrar los datos en la posición especificada.
     * Este método debería actualizar el contenido del [ViewHolder.itemView] para reflejar
     * el ítem en la posición dada.
     */
    override fun onBindViewHolder(holder: VoiceNoteViewHolder, position: Int) {
        val file = notes[position]
        holder.bind(file, position == currentlyPlaying, formatter)

        holder.itemView.setOnClickListener {
            onItemClick(file, position)
        }
    }

    /**
     * Retorna el número total de ítems en el conjunto de datos que tiene el adaptador.
     */
    override fun getItemCount(): Int = notes.size

    fun updateData(newNotes: List<File>) {
        notes = newNotes
        notifyDataSetChanged() // Para una actualización completa.
        // Considera usar DiffUtil para actualizaciones más eficientes en el futuro.
    }

    /**
     * ViewHolder describe una vista de ítem y metadatos sobre su lugar dentro del RecyclerView.
     */
    inner class VoiceNoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Si usas android.R.layout.simple_list_item_1, el ID es android.R.id.text1
        private val textView: TextView = itemView.findViewById(android.R.id.text1)
        // Si usas un layout personalizado como list_item_voice_note.xml con ID textViewNoteInfo:
        // private val textView: TextView = itemView.findViewById(R.id.textViewNoteInfo)

        fun bind(file: File, isPlaying: Boolean, formatter: SimpleDateFormat) {
            textView.text = "${file.name}\n${formatter.format(Date(file.lastModified()))}"

            textView.setTextColor(
                if (isPlaying) {
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                } else {
                    // Asegúrate de que este color sea visible en tu tema.
                    // android.R.color.background_dark puede ser muy oscuro.
                    // Considera usar un color de texto primario o secundario de tu tema.
                    // Por ejemplo, android.R.attr.textColorPrimary
                    // O un color definido en tus colors.xml
                    ContextCompat.getColor(itemView.context, android.R.color.black) // Ejemplo, ajústalo
                }
            )
        }
    }
}