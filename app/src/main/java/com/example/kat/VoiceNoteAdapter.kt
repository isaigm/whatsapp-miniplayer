package com.example.kat

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceNoteAdapter(
    private val context: Context,
    private var notes: List<File>
) : BaseAdapter() {  // Cambiamos a BaseAdapter para mayor control

    var currentlyPlaying: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()  // Notificar cambios cuando cambia la posición en reproducción
        }

    private val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun getCount(): Int = notes.size

    override fun getItem(position: Int): File = notes[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // 1. Reutilizar la vista si existe
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)

        // 2. Obtener el TextView usando su ID oficial
        val textView = view.findViewById<TextView>(android.R.id.text1)

        // 3. Configurar el contenido
        val file = notes[position]
        textView.text = "${file.name}\n${formatter.format(Date(file.lastModified()))}"

        // 4. Cambiar color si está reproduciéndose
        textView.setTextColor(
            if (position == currentlyPlaying) {
                ContextCompat.getColor(context, android.R.color.holo_blue_dark)
            } else {
                ContextCompat.getColor(context, android.R.color.background_dark)
            }
        )

        return view
    }

    fun updateData(newNotes: List<File>) {
        notes = newNotes
        notifyDataSetChanged()
    }
}