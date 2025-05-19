package com.example.kat
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import java.io.File
import java.util.Locale
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {
    private lateinit var btnSortByDate: Button

    private lateinit var listView: ListView
    private val voiceNotes = mutableListOf<File>()
    private var currentlyPlayingPosition = -1
    private lateinit var adapter: VoiceNoteAdapter
    private lateinit var miniPlayer: LinearLayout
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnLoop: ImageView
    private lateinit var txtCurrentAudio: TextView
    private lateinit var btnClose: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private var isNewestFirst = true
    private var currentlyPlayingFile: File? = null
    private var audioService: AudioService? = null
    private var isBound = false
    private var playerListener: Player.Listener? = null
    private var pendingRestoreFilePath: String? = null
    private var pendingRestoreProgress: Int = 0
    private var pendingRestoreWasPlaying: Boolean = false
    private var pendingRestorePosition: Int = NO_POSITION
    private var pendingMiniPlayerVisibility: Int = View.GONE
    private var noteVoiceLooped: Boolean = false

    enum class ORDER(val value: Int) {
        ASC(1), DESC(2);

        companion object {
            fun valueOf(value: Int) = entries.find { it.value == value }
        }
    }

    private var currOrder = ORDER.DESC

    private var hasPendingStateToRestore: Boolean = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            playerListener?.let { audioService?.exoPlayer?.removeListener(it) }
            playerListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        audioService?.exoPlayer?.let { player ->
                            val duration = player.duration
                            if (duration > 0) {
                                seekBar.max = duration.toInt()
                                txtTotalTime.text = formatTime(duration.toInt())
                            }
                            if (player.isPlaying) {
                                startProgressUpdates()
                            }
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        // Opcional: Manejar el final de la reproducción aquí si es necesario
                        // Por ejemplo, pasar a la siguiente pista o actualizar la UI
                        Log.d("info", "stooped")
                        if (noteVoiceLooped)
                        {
                            startPlayback(currentlyPlayingPosition)
                        }
                        else
                        {
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            seekBar.progress = seekBar.max
                        }
                        updateSeekBar.removeCallbacks(updateRunnable)

                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    audioService?.exoPlayer?.let { player ->
                        if (isPlaying) {
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                            val duration = player.duration
                            if (duration > 0 && seekBar.max != duration.toInt()) {
                                seekBar.max = duration.toInt()
                                txtTotalTime.text = formatTime(duration.toInt())
                            }
                            startProgressUpdates()
                        } else {

                            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                            updateSeekBar.removeCallbacks(updateRunnable)
                        }
                    }
                }
            }
            audioService?.exoPlayer?.addListener(playerListener!!)
            Log.d("ServiceDebug", "AudioService connected and listener added.")

            if (hasPendingStateToRestore) {
                tryToApplyPendingRestoreState()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            playerListener?.let { audioService?.exoPlayer?.removeListener(it) }
            audioService = null
            isBound = false
            Log.w("ServiceDebug", "Servicio desconectado inesperadamente")
        }
    }

    private var updateSeekBar: Handler = Handler(Looper.getMainLooper())

    companion object {
        private const val KEY_CURRENT_FILE_PATH = "current_file_path"
        private const val KEY_CURRENT_PROGRESS = "current_progress"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_MINI_PLAYER_VISIBILITY = "mini_player_visibility"
        private const val KEY_CURRENTLY_PLAYING_POSITION = "currently_playing_position"
        private const val KEY_CURRENT_ORDER = "asc_order"
        private const val NO_POSITION = -1
        private const val REQUEST_CODE_READ_STORAGE = 1
        private const val KEY_LOOP_NOTE = "loop_note"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listView)
        miniPlayer = findViewById(R.id.miniPlayer)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnLoop = findViewById(R.id.btnLoop)
        txtCurrentAudio = findViewById(R.id.txtCurrentAudio)
        btnClose = findViewById(R.id.btnClose)
        seekBar = findViewById(R.id.seekBar)
        txtCurrentTime = findViewById(R.id.txtCurrentTime)
        txtTotalTime = findViewById(R.id.txtTotalTime)
        btnSortByDate = findViewById(R.id.btnSortByDate)

        btnSortByDate.setOnClickListener {
            toggleSortOrder()
        }
        adapter = VoiceNoteAdapter(this, voiceNotes)
        listView.adapter = adapter

        // Iniciar y vincular el servicio
        Intent(this, AudioService::class.java).also { intent ->
            Log.d("ServiceDebug", "Intent para AudioService creado: $intent")
            try {
                startForegroundService(intent)
                Log.d("ServiceDebug", "Servicio iniciado/intentado iniciar como foreground.")
                isBound = bindService(
                    intent,
                    connection,
                    BIND_AUTO_CREATE
                ) //  BIND_IMPORTANT es más para casos muy específicos
                Log.d("ServiceDebug", "Resultado de bindService: $isBound")
            } catch (e: Exception) {
                Log.e("ServiceDebug", "Error al iniciar o vincular servicio", e)
            }
        }

        if (checkPermissions()) {
            loadVoiceNotes()
        } else {
            requestPermissions() // Esto podría mejorarse para pedir ambos permisos a la vez si es necesario
        }

        btnPlayPause.setOnClickListener {
            toggleMiniPlayerPlayback()
        }

        btnClose.setOnClickListener {
            stopPlaybackAndHideMiniPlayer() // Cambiado para que también oculte el mini reproductor
        }
        btnLoop.setOnClickListener {
            noteVoiceLooped = !noteVoiceLooped
            updateLoopButtonTint()

        }
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            togglePlayback(position)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioService?.exoPlayer?.seekTo(progress.toLong())
                    txtCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                updateSeekBar.removeCallbacks(updateRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (audioService?.exoPlayer?.isPlaying == true) { // Reanuda solo si estaba reproduciendo
                    startProgressUpdates()
                }
            }
        })
        updateLoopButtonTint()
        updateSortButtonText()
    }
    private fun updateLoopButtonTint() {
        if (noteVoiceLooped) {
            // Aplicar tinte para estado activo
            btnLoop.setColorFilter(ContextCompat.getColor(this, R.color.icon_tint_active), PorterDuff.Mode.SRC_IN)
            btnLoop.contentDescription = "Loop activado"
        } else {
            // Aplicar tinte para estado inactivo o quitar el tinte si el original es el deseado
            btnLoop.setColorFilter(ContextCompat.getColor(this, R.color.icon_tint_inactive), PorterDuff.Mode.SRC_IN)
            // O para quitar el tinte completamente y volver al color original del drawable:
            // btnLoop.clearColorFilter()
            btnLoop.contentDescription = "Loop desactivado"
        }
    }
    private fun startPlayback(position: Int) {
        Log.d("AudioDebug", "Intentando reproducir posición $position")
        if (!isBound || audioService == null) {
            Log.e(
                "AudioDebug",
                "Servicio no disponible. Estado: bound=$isBound, service=${audioService != null}"
            )
            if (!isBound) {
                bindAudioService()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                startPlayback(position)
            }, 500)
            return
        }

        val file = voiceNotes.getOrNull(position) ?: run {
            Log.e("AudioDebug", "Índice inválido: $position")
            return
        }

        Log.d("AudioDebug", "Reproduciendo: ${file.name}")
        audioService?.playAudio(file) // Delegar la reproducción al servicio
        currentlyPlayingPosition = position
        currentlyPlayingFile = file
        updatePlayerUI(position) // Actualiza la UI inmediatamente
    }

    private fun updatePlayerUI(position: Int) {
        if (position < 0 || position >= voiceNotes.size) {
            Log.w("UpdateUI", "Posición inválida: $position. Ocultando mini reproductor.")
            miniPlayer.visibility = View.GONE
            txtCurrentAudio.text = ""
            // Resetear otros elementos de la UI si es necesario
            return
        }

        miniPlayer.visibility = View.VISIBLE
        txtCurrentAudio.text = voiceNotes[position].name
        // El estado de play/pause y la seekbar se actualizarán a través del listener del player
        // Forzamos una actualización inicial por si el listener tarda o el estado ya es conocido
        audioService?.exoPlayer?.let { player ->

            btnPlayPause.setImageResource(
                if (player.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            val duration = player.duration
            if (duration > 0) {
                seekBar.max = duration.toInt()
                txtTotalTime.text = formatTime(duration.toInt())
                seekBar.progress = player.currentPosition.toInt()
                txtCurrentTime.text = formatTime(player.currentPosition.toInt())
            } else { // Si la duración no es conocida aún
                seekBar.max = 0
                seekBar.progress = 0
                txtTotalTime.text = formatTime(0)
                txtCurrentTime.text = formatTime(0)
            }
            if (player.isPlaying) {
                startProgressUpdates()
            }
        }
        adapter.currentlyPlaying = position
        adapter.notifyDataSetChanged()
    }


    private fun startProgressUpdates() {
        updateSeekBar.removeCallbacks(updateRunnable)
        updateSeekBar.post(updateRunnable)
        Log.d("ProgressUpdate", "Iniciando actualización de progreso")
    }

    private fun bindAudioService() {
        Intent(applicationContext, AudioService::class.java).also { intent ->
            try {
                applicationContext.startForegroundService(intent)
                val bindResult = applicationContext.bindService(
                    intent,
                    connection,
                    BIND_AUTO_CREATE
                )
                Log.d("ServiceDebug", "Resultado bind: $bindResult")
            } catch (e: Exception) {
                Log.e("ServiceDebug", "Error al vincular: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            audioService?.exoPlayer?.let { player ->
                if (player.isPlaying && player.playbackState == Player.STATE_READY) { // Asegura que esté listo
                    val currentPos = player.currentPosition.toInt()
                    val duration = player.duration.toInt()

                    runOnUiThread {
                        if (duration > 0) {
                            if (seekBar.max != duration) {
                                seekBar.max = duration
                                txtTotalTime.text = formatTime(duration)
                            }

                            seekBar.progress = currentPos.coerceIn(0, duration)
                            txtCurrentTime.text = formatTime(currentPos.coerceIn(0, duration))
                        } else {
                            seekBar.progress = 0
                            txtCurrentTime.text = formatTime(0)
                        }
                    }
                    if (player.isPlaying) { // Doble check
                        updateSeekBar.postDelayed(
                            this,
                            250
                        ) // Un poco más de tiempo puede ser mejor
                    }
                } else if (player.playbackState != Player.STATE_READY && miniPlayer.visibility == View.VISIBLE) {
                    // Si no está listo pero el mini reproductor está visible, podría ser un error o carga
                    // No hacer nada o mostrar un estado de carga
                }
            }
        }
    }

    private fun toggleMiniPlayerPlayback() {
        if (isBound && audioService != null) {
            audioService?.togglePlayback()
        } else {
            Log.w(
                "AudioDebug",
                "Cannot toggle playback: Service not bound or audioService is null."
            )
            if (!isBound) bindAudioService()
        }
    }

    override fun onDestroy() {
        Log.d("MainActivityLifecycle", "onDestroy called")
        updateSeekBar.removeCallbacks(updateRunnable)
        playerListener?.let {
            audioService?.exoPlayer?.removeListener(it)
        }

        // Detener el servicio para que libere recursos y se cierre
        Log.d("ServiceDebug", "Intentando detener AudioService desde MainActivity.onDestroy")
        val serviceIntent = Intent(this, AudioService::class.java)
        stopService(serviceIntent) // MUY IMPORTANTE para detener el servicio

        if (isBound) {
            try {
                Log.d("ServiceDebug", "Desvinculando AudioService")
                unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Log.w("ServiceDebug", "Service not registered or already unbound: ${e.message}")
            }
            isBound = false
        }
        audioService = null // Ayuda al GC
        super.onDestroy()
    }

    private fun toggleSortOrder() {
        isNewestFirst = !isNewestFirst
        currOrder = if (isNewestFirst) ORDER.DESC else ORDER.ASC
        updateSortButtonText()
        sortNotes()
    }

    private fun updateSortButtonText() {
        btnSortByDate.text = if (currOrder == ORDER.DESC) { // DESC es más recientes primero
            "Ordenar: Más recientes primero"
        } else {
            "Ordenar: Más antiguos primero"
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isBound && audioService != null && audioService?.exoPlayer != null && currentlyPlayingFile != null && currentlyPlayingPosition != NO_POSITION) {
            outState.putString(KEY_CURRENT_FILE_PATH, currentlyPlayingFile!!.absolutePath)
            outState.putInt(
                KEY_CURRENT_PROGRESS,
                audioService!!.exoPlayer.currentPosition.toInt()
            )
            outState.putBoolean(KEY_IS_PLAYING, audioService!!.exoPlayer.isPlaying)
            outState.putInt(KEY_CURRENTLY_PLAYING_POSITION, currentlyPlayingPosition)
        } else {
            outState.remove(KEY_CURRENT_FILE_PATH)
            outState.putInt(KEY_CURRENTLY_PLAYING_POSITION, NO_POSITION)
        }
        outState.putBoolean(KEY_LOOP_NOTE, noteVoiceLooped)
        outState.putInt(KEY_CURRENT_ORDER, currOrder.value)
        outState.putInt(KEY_MINI_PLAYER_VISIBILITY, miniPlayer.visibility)
        // outState.putBoolean(KEY_IS_NEWEST_FIRST, isNewestFirst) // Ya cubierto por currOrder
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currOrder = ORDER.valueOf(savedInstanceState.getInt(KEY_CURRENT_ORDER, ORDER.DESC.value))
            ?: ORDER.DESC
        isNewestFirst = (currOrder == ORDER.DESC)
        updateSortButtonText()

        noteVoiceLooped = savedInstanceState.getBoolean(KEY_LOOP_NOTE, false)
        updateLoopButtonTint()
        // Cargar notas PRIMERO, luego aplicar el orden, luego restaurar el estado de reproducción
        if (checkPermissions() && voiceNotes.isEmpty()) { // Solo carga si no se han cargado aun
            loadVoiceNotes() // Esto aplica el orden por defecto
        }
        sortNotes() // Aplica el orden restaurado

        pendingMiniPlayerVisibility =
            savedInstanceState.getInt(KEY_MINI_PLAYER_VISIBILITY, View.GONE)
        // La visibilidad real del miniplayer se determinará en tryToApplyPendingRestoreState

        val filePath = savedInstanceState.getString(KEY_CURRENT_FILE_PATH)
        val position = savedInstanceState.getInt(KEY_CURRENTLY_PLAYING_POSITION, NO_POSITION)

        if (filePath != null && position != NO_POSITION) {
            pendingRestoreFilePath = filePath
            pendingRestoreProgress = savedInstanceState.getInt(KEY_CURRENT_PROGRESS, 0)
            pendingRestoreWasPlaying = savedInstanceState.getBoolean(KEY_IS_PLAYING, false)
            pendingRestorePosition = position // Este es el índice original, se recalculará
            hasPendingStateToRestore = true
            Log.d(
                "RestoreState",
                "State read from bundle. Path: $filePath, Original Pos: $position, MiniPlayerVisible: ${pendingMiniPlayerVisibility == View.VISIBLE}"
            )
        } else {
            hasPendingStateToRestore = false
            Log.d(
                "RestoreState",
                "No playback state found in bundle. MiniPlayerVisible: ${pendingMiniPlayerVisibility == View.VISIBLE}"
            )
            updateUIForRestoredPlayback(
                NO_POSITION,
                0,
                false
            ) // Asegura que el miniplayer se oculte si no hay nada que restaurar
        }
        tryToApplyPendingRestoreState()
    }

    private fun tryToApplyPendingRestoreState() {
        if (!hasPendingStateToRestore) {
            // Si no hay nada que restaurar, asegurarse que el miniplayer esté oculto
            // a menos que pendingMiniPlayerVisibility fuera VISIBLE por alguna razón (y no debería serlo sin track)
            if (pendingMiniPlayerVisibility == View.VISIBLE && currentlyPlayingPosition == NO_POSITION) {
                miniPlayer.visibility = View.GONE
            } else {
                miniPlayer.visibility = pendingMiniPlayerVisibility
            }
            return
        }

        if (pendingRestoreFilePath != null) {
            if (isBound && audioService != null && audioService?.exoPlayer != null) {
                Log.d(
                    "RestoreState",
                    "Service ready, applying pending state for: $pendingRestoreFilePath"
                )

                if (voiceNotes.isEmpty() && checkPermissions()) {
                    Log.w(
                        "RestoreState",
                        "Voice notes empty, attempting to load them before restoring."
                    )
                    loadVoiceNotes() // Esto aplicará el orden por defecto, luego sortNotes (si es necesario) lo corregirá
                    sortNotes() // Re-aplicar el orden correcto
                }

                val fileToRestore = File(pendingRestoreFilePath!!)
                val actualCurrentPositionInList =
                    voiceNotes.indexOfFirst { it.absolutePath == pendingRestoreFilePath }

                if (actualCurrentPositionInList != NO_POSITION) {
                    currentlyPlayingFile = voiceNotes[actualCurrentPositionInList]
                    currentlyPlayingPosition = actualCurrentPositionInList

                    // Delegar la restauración al servicio
                    audioService?.restorePlaybackState(
                        fileToRestore,
                        pendingRestoreProgress.toLong(),
                        pendingRestoreWasPlaying
                    )

                    updateUIForRestoredPlayback(
                        currentlyPlayingPosition,
                        pendingRestoreProgress,
                        pendingRestoreWasPlaying
                    )
                    miniPlayer.visibility = View.VISIBLE // Asegurar que esté visible
                } else {
                    Log.w(
                        "RestoreState",
                        "File $pendingRestoreFilePath not found in current voiceNotes. Clearing playback state."
                    )
                    currentlyPlayingFile = null
                    currentlyPlayingPosition = NO_POSITION
                    updateUIForRestoredPlayback(NO_POSITION, 0, false)
                }
                hasPendingStateToRestore = false
                pendingRestoreFilePath = null
            } else {
                Log.d(
                    "RestoreState",
                    "Service not yet bound or player not ready. Will retry onServiceConnected. Path: $pendingRestoreFilePath"
                )
                // Mantener la visibilidad del miniplayer como estaba en el bundle, se corregirá después
                miniPlayer.visibility = pendingMiniPlayerVisibility
            }
        }
    }

    private fun updateUIForRestoredPlayback(position: Int, progress: Int, wasPlaying: Boolean) {
        adapter.currentlyPlaying = position // Siempre actualizar el adaptador

        if (position == NO_POSITION || position >= voiceNotes.size) {
            Log.w(
                "RestoreUI",
                "Invalid position ($position) or no item to restore. Hiding miniPlayer."
            )
            miniPlayer.visibility = View.GONE
            currentlyPlayingPosition = NO_POSITION
            currentlyPlayingFile = null
            txtCurrentAudio.text = ""
            seekBar.progress = 0
            seekBar.max = 0
            txtCurrentTime.text = formatTime(0)
            txtTotalTime.text = formatTime(0)
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            updateSeekBar.removeCallbacks(updateRunnable)
            adapter.notifyDataSetChanged()
            return
        }

        miniPlayer.visibility = View.VISIBLE
        txtCurrentAudio.text = voiceNotes[position].name

        audioService?.exoPlayer?.let { player ->
            // Actualizar UI basado en el estado del player, pero con los valores restaurados
            seekBar.progress = progress
            txtCurrentTime.text = formatTime(progress)

            val playerDuration = player.duration
            if (playerDuration > 0) {
                seekBar.max = playerDuration.toInt()
                txtTotalTime.text = formatTime(playerDuration.toInt())
            } else {
                // Si el player no está listo, y estamos restaurando, es posible que la duración no esté disponible.
                // Podríamos usar un valor temporal o esperar al listener.
                // Por ahora, lo ponemos a 0 si no está disponible.
                seekBar.max =
                    0 // O `progress` si queremos que la barra parezca llena hasta ese punto
                txtTotalTime.text = formatTime(0) // O formatTime(progress)
            }

            btnPlayPause.setImageResource(
                if (wasPlaying && player.playWhenReady) android.R.drawable.ic_media_pause // wasPlaying y el player está configurado para reproducir
                else android.R.drawable.ic_media_play
            )

            if (wasPlaying && player.playWhenReady) {
                if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
                    startProgressUpdates()
                }
            } else {
                updateSeekBar.removeCallbacks(updateRunnable)
            }
        } ?: run {
            // Si el player no está listo, mostramos UI basado en los datos de restauración
            seekBar.progress = progress
            txtCurrentTime.text = formatTime(progress)
            txtTotalTime.text = formatTime(0) // No conocemos la duración total aún
            btnPlayPause.setImageResource(
                if (wasPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
        adapter.notifyDataSetChanged()
        Log.d("RestoreUI", "UI updated for restored playback. Pos: $position, Playing: $wasPlaying")
    }

    private fun sortNotes() {
        val currentFilePath = currentlyPlayingFile?.absolutePath

        if (currOrder == ORDER.DESC) { // Más recientes primero
            voiceNotes.sortByDescending { it.lastModified() }
        } else { // Más antiguos primero (ASC)
            voiceNotes.sortBy { it.lastModified() }
        }

        adapter.updateData(voiceNotes) // Esto actualiza la lista interna del adaptador Y llama a notifyDataSetChanged

        currentFilePath?.let { path ->
            val newPosition = voiceNotes.indexOfFirst { it.absolutePath == path }
            if (newPosition != NO_POSITION) {
                currentlyPlayingPosition = newPosition
                adapter.currentlyPlaying = newPosition // Actualiza el resaltado en el adaptador
            } else {
                // El archivo que se estaba reproduciendo ya no está o la lista está vacía
                if (currentlyPlayingPosition != NO_POSITION) { // Si había algo reproduciéndose
                    //stopPlaybackAndHideMiniPlayer() // Opcional: detener si el archivo desaparece
                }
            }
        }
        // No es necesario llamar a adapter.notifyDataSetChanged() de nuevo si updateData ya lo hace
    }

    // Renombrado para claridad, usado por el botón X del mini reproductor
    private fun stopPlaybackAndHideMiniPlayer() {
        audioService?.stopPlayback() // Decirle al servicio que pare
        updateSeekBar.removeCallbacks(updateRunnable)
        miniPlayer.visibility = View.GONE
        currentlyPlayingPosition = NO_POSITION
        currentlyPlayingFile = null
        adapter.currentlyPlaying = NO_POSITION
        adapter.notifyDataSetChanged()
    }

    // Usado internamente cuando se cambia de una pista a otra
    private fun stopCurrentTrackBeforePlayingNew() {
        audioService?.stopPlayback() // Solo detiene la reproducción, no afecta la UI tanto
        updateSeekBar.removeCallbacks(updateRunnable)
        // No ocultar el mini reproductor aquí, se actualizará con la nueva pista
    }


    private fun togglePlayback(position: Int) {
        if (currentlyPlayingPosition == position) {
            toggleMiniPlayerPlayback()
        } else {
            stopCurrentTrackBeforePlayingNew() // Detiene el actual
            startPlayback(position)          // Inicia el nuevo
        }
        // `startPlayback` y `toggleMiniPlayerPlayback` (a través del listener) deberían manejar la actualización de `adapter.currentlyPlaying`
        // y `adapter.notifyDataSetChanged()` a través de `updatePlayerUI` o el listener del player.
    }

    private fun loadVoiceNotes() {
        val possiblePaths = arrayOf(
            File(
                Environment.getExternalStorageDirectory(),
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
            ),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Voice Notes"),
            File(
                Environment.getExternalStorageDirectory(),
                "Download/WhatsApp Voice Notes"
            ), // Otra posible ruta común
            File(Environment.getExternalStorageDirectory(), "Recordings/Voice Notes"), // Genérica
            File(filesDir, "MyVoiceNotes") // Carpeta interna de la app (si guardaras notas allí)
        )
        val foundNotes = mutableListOf<File>()

        for (path in possiblePaths) {
            if (path.exists() && path.isDirectory) {
                path.walkTopDown()
                    .filter {
                        it.isFile && (it.extension.equals(
                            "opus",
                            ignoreCase = true
                        ) || it.extension.equals("aac", ignoreCase = true) || it.extension.equals(
                            "m4a",
                            ignoreCase = true
                        ) || it.extension.equals("mp3", ignoreCase = true))
                    }
                    .forEach { foundNotes.add(it) }
            }
        }

        if (foundNotes.isEmpty()) {
            Toast.makeText(
                this,
                "No se encontró la carpeta de notas de voz o está vacía",
                Toast.LENGTH_LONG
            ).show()
            //return // No retornar, para que sortNotes pueda operar sobre una lista vacía si es necesario
        }

        Log.d("VoiceNotes", "Encontradas ${foundNotes.size} notas")
        voiceNotes.clear()
        voiceNotes.addAll(foundNotes)
        sortNotes() // Aplicar el orden actual
        // adapter.updateData ya es llamado dentro de sortNotes
    }

    private fun checkPermissions(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        // No necesitas chequear POST_NOTIFICATIONS aquí para la lógica de carga de notas
        return storagePermission
    }

    private val manageStoragePermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Check if permission was granted after returning from settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // Permission granted
                    Log.i("Permissions", "MANAGE_EXTERNAL_STORAGE Permission Granted")
                    // Proceed with your logic that requires this permission
                    requestDependentPermissions() // Or directly call the next permission request
                } else {
                    // Permission denied
                    Log.w("Permissions", "MANAGE_EXTERNAL_STORAGE Permission Denied")
                    // Handle permission denial (e.g., show a rationale, disable functionality)
                }
            }
        }

    // Launcher for other runtime permissions (READ_EXTERNAL_STORAGE, POST_NOTIFICATIONS)
    private val requestMultiplePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allPermissionsGranted = true
            permissions.entries.forEach {
                val permissionName = it.key
                val isGranted = it.value
                if (isGranted) {
                    Log.i("Permissions", "$permissionName Permission Granted")
                } else {
                    Log.w("Permissions", "$permissionName Permission Denied")
                    allPermissionsGranted = false
                    // Handle individual permission denial (e.g., show a rationale for that specific permission)
                }
            }

            if (allPermissionsGranted) {
                // All requested runtime permissions were granted
                Log.i("Permissions", "All runtime permissions granted.")
                // Proceed with your app's logic
            } else {
                // Not all permissions were granted.
                // You might want to show a general message or disable features.
                Log.w("Permissions", "Not all runtime permissions were granted.")
            }
        }


    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data =
                        String.format("package:%s", applicationContext.packageName).toUri()
                    manageStoragePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e("Permissions", "Error requesting MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", e)
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStoragePermissionLauncher.launch(intent)
                    } catch (ex: Exception) {
                        Log.e("Permissions", "Error requesting ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION", ex)
                        // Optionally, handle the case where neither intent can be launched
                    }
                }
                // The result is handled by manageStoragePermissionLauncher's callback.
                // Do not add to permissionsToRequest here, as it's a special permission.
                // If this permission is crucial, you might want to gate further permission requests
                // until this one is granted, or handle it in the callback.
                // For simplicity here, we'll assume other permissions can be requested regardless for now,
                // or you can call requestDependentPermissions() from the manageStoragePermissionLauncher callback.
                requestDependentPermissions() // Call to request other permissions
                return // Exit after launching the settings intent for storage manager
            }
        } else {
            // For versions older than R, request READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // Request POST_NOTIFICATIONS for Android 13 (TIRAMISU) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All necessary permissions are already granted (or not applicable)
            Log.i("Permissions", "All necessary permissions already granted or not applicable.")
            // Proceed with your app's logic
        }
    }

    /**
     * This function is called after the MANAGE_EXTERNAL_STORAGE flow (if initiated)
     * or directly if MANAGE_EXTERNAL_STORAGE is already granted or not applicable.
     */
    private fun requestDependentPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // This block is for SDK < R, if MANAGE_EXTERNAL_STORAGE wasn't handled.
        // Or, if you still want to request READ_EXTERNAL_STORAGE for SDK < R even if MANAGE_EXTERNAL_STORAGE was handled for SDK >= R.
        // The original logic might need slight adjustment here based on exact flow desired.
        // Assuming the original logic: if not R+, check for READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // This case means MANAGE_EXTERNAL_STORAGE was attempted but perhaps denied,
            // and there were no other permissions to request.
            // You might want to log or inform the user if MANAGE_EXTERNAL_STORAGE is critical.
            Log.w("Permissions", "MANAGE_EXTERNAL_STORAGE may still be required and was not granted.")
        } else {
            // All permissions are granted or not applicable at this stage.
            Log.i("Permissions", "All dependent permissions already granted or not applicable.")
        }
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_READ_STORAGE) { // Mismo código que usaste para startActivityForResult
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d(
                        "Permissions",
                        "MANAGE_ALL_FILES_ACCESS_PERMISSION concedido después de ir a Ajustes."
                    )
                    loadVoiceNotes()
                } else {
                    Log.w(
                        "Permissions",
                        "MANAGE_ALL_FILES_ACCESS_PERMISSION denegado después de ir a Ajustes."
                    )
                    Toast.makeText(
                        this,
                        "Permiso de gestión de archivos denegado",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            var readStorageGranted =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R // Asume concedido para R+ (se verifica en onResume/onActivityResult)
            var notificationsGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU // Asume concedido para pre-Tiramisu

            grantResults.forEachIndexed { index, grantResult ->
                when (permissions[index]) {
                    Manifest.permission.READ_EXTERNAL_STORAGE -> {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            Log.d("Permissions", "Permiso READ_EXTERNAL_STORAGE concedido.")
                            readStorageGranted = true
                        } else {
                            Log.w("Permissions", "Permiso READ_EXTERNAL_STORAGE denegado.")
                            Toast.makeText(
                                this,
                                "Permiso de almacenamiento denegado",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    Manifest.permission.POST_NOTIFICATIONS -> {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            Log.d("Permissions", "Permiso POST_NOTIFICATIONS concedido.")
                            notificationsGranted = true
                            // Aquí podrías reiniciar el servicio si es necesario para que la notificación funcione
                            // o simplemente confiar en que la próxima vez que se inicie (o si ya está iniciado) lo usará.
                        } else {
                            Log.w("Permissions", "Permiso POST_NOTIFICATIONS denegado.")
                            Toast.makeText(
                                this,
                                "Permiso de notificación denegado. Las notificaciones no se mostrarán.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            if (readStorageGranted) { // Solo carga notas si el permiso de almacenamiento está OK
                loadVoiceNotes()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivityLifecycle", "onResume called")
        // Re-verificar permisos, especialmente MANAGE_EXTERNAL_STORAGE
        if (checkPermissions()) {
            if (voiceNotes.isEmpty() || hasPendingStateToRestore) { // Carga si está vacía o si hay estado pendiente que podría necesitar la lista
                loadVoiceNotes() // Esto llamará a sortNotes
            }
            // Si hay estado pendiente, intenta aplicarlo de nuevo, ya que el servicio podría estar listo ahora
            // y las notas cargadas
            if (hasPendingStateToRestore) {
                tryToApplyPendingRestoreState()
            }
        } else {
            // Podrías mostrar un diálogo persistente o una UI que indique que los permisos son necesarios
            if (voiceNotes.isEmpty()) { // Limpia la lista si los permisos fueron revocados
                voiceNotes.clear()
                adapter.updateData(voiceNotes)
                miniPlayer.visibility = View.GONE
            }
        }

        // Reconectar al servicio si no está conectado y se espera que lo esté
        if (!isBound && audioService == null) {
            Log.d("ServiceDebug", "onResume: Intentando re-vincular al servicio.")
            bindAudioService() // Intenta vincular de nuevo
        } else if (isBound && audioService != null && currentlyPlayingPosition != NO_POSITION) {
            // Si está vinculado y hay algo "reproduciendo", actualiza la UI por si acaso
            // (ej. si el servicio actualizó su estado mientras la app estaba pausada)
            updatePlayerUI(currentlyPlayingPosition)
            audioService?.exoPlayer?.let {
                if (it.isPlaying) startProgressUpdates()
            }
        }
    }
}