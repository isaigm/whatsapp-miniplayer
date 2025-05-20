package com.example.kat
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import java.io.File
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

class AudioService : Service() {
    lateinit var exoPlayer: ExoPlayer // Hazlo público o con un getter si MainActivity necesita accederlo directamente, aunque es mejor vía métodos del servicio.
    private val binder = LocalBinder()
    private var currentFile: File? = null

    companion object {
        const val CHANNEL_ID = "AudioServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()
        // Puedes añadir un listener aquí también si el servicio necesita reaccionar a eventos del player
        // ej. para stopSelf() cuando la reproducción termina y no hay más en cola.
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Opcional: parar el servicio si la reproducción termina y no hay más acciones pendientes
                    // stopSelf(); // Considera si esto es deseable
                    // O simplemente actualizar la notificación
                    updateNotification(currentFile?.name ?: "Audio", false)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                currentFile?.let {
                    updateNotification(it.name, isPlaying)
                }
            }
        })
        createNotificationChannel()
        Log.d("AudioServiceLifecycle", "AudioService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AudioService", "onStartCommand: Comando recibido. Intent action: ${intent?.action}")

        // Aquí es donde decides si el servicio necesita pasar a primer plano.
        // Por ejemplo, si el intent es para reproducir audio:
        // if (intent?.action == "PLAY_ACTION" || /* otra condición para reproducir */) {

        // --- INICIO DE LA SECCIÓN CRÍTICA ---
        val notificationText = "Procesando audio..." // O un texto más descriptivo si ya sabes qué audio es
        // Si ya tienes información del audio desde el intent, úsala.

        val notification = createNotification(notificationText, exoPlayer.isPlaying)

        try {
            Log.d("AudioService", "Llamando a startForeground...")
            startForeground(NOTIFICATION_ID, notification) // ¡ESTA ES LA LLAMADA CLAVE!
            Log.d("AudioService", "startForeground llamado exitosamente.")
        } catch (e: Exception) {
            Log.e("AudioService", "Error al llamar a startForeground: ${e.message}", e)
            // Considera detener el servicio si no puede pasar a primer plano y es un requisito
            // stopSelf()
        }
        // --- FIN DE LA SECCIÓN CRÍTICA ---

        // } // Fin del if (intent?.action == "PLAY_ACTION")

        // Tu lógica para manejar el intent, iniciar la reproducción, etc.
        // Ejemplo:
        // val audioFile = intent?.getSerializableExtra("AUDIO_FILE_EXTRA") as? File
        // audioFile?.let { playAudio(it) }

        // Política de reinicio del servicio
        return START_STICKY
    }

    fun playAudio(file: File) {
        currentFile = file
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true // Inicia la reproducción
        Log.d("AudioService", "Reproduciendo: ${file.name}")
        startForeground(NOTIFICATION_ID, createNotification(file.name, true))
    }

    fun togglePlayback() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
                // Si estaba parado o finalizado, y hay un currentFile, lo preparamos y reproducimos
                currentFile?.let {
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(it))) // Re-set por si acaso
                    exoPlayer.prepare()
                    exoPlayer.seekToDefaultPosition() // O a la última posición conocida si la guardas
                }
            }
            exoPlayer.play()
        }
        currentFile?.let { updateNotification(it.name, exoPlayer.isPlaying) }
    }

    fun stopPlayback() {
        exoPlayer.stop()
        // No llames a stopForeground aquí directamente si quieres que el servicio siga
        // disponible para reproducir otra cosa. MainActivity se encargará de stopService.
        // Pero sí actualiza la notificación
        currentFile?.let { updateNotification(it.name, false) }
        Log.d("AudioService", "Reproducción detenida por el servicio.")
    }

    fun restorePlaybackState(file: File, position: Long, playWhenReady: Boolean) {
        currentFile = file
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.prepare()
        exoPlayer.seekTo(position)
        exoPlayer.playWhenReady = playWhenReady
        Log.d("AudioService", "Restaurando estado: ${file.name}, pos: $position, play: $playWhenReady")
        startForeground(NOTIFICATION_ID, createNotification(file.name, playWhenReady))
    }


    override fun onBind(intent: Intent): IBinder {
        Log.d("AudioServiceLifecycle", "AudioService onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("AudioServiceLifecycle", "AudioService onUnbind")
        // Si quieres que el servicio se detenga cuando todos los clientes se desvinculan Y no fue iniciado con startService:
        // return super.onUnbind(intent) y luego stopSelf() si es apropiado.
        // Pero como usamos startForegroundService, el servicio continuará hasta que se llame a stopService() o stopSelf().
        return true // Permite re-bind
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release() // ¡MUY IMPORTANTE! Libera los recursos del player.
        stopForeground(STOP_FOREGROUND_REMOVE) // API 24+
        Log.d("AudioServiceLifecycle", "AudioService onDestroy: Player released, service stopped.")
    }

    // --- Gestión de Notificaciones ---
    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Audio Service Channel",
            NotificationManager.IMPORTANCE_LOW // IMPORTANCE_LOW o DEFAULT para que no haga sonido
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(contentText: String, isPlaying: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntentFlags =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // Acciones para la notificación (Play/Pause)
        // Necesitarás BroadcastReceivers o intents al servicio para manejar estas acciones
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        // val playPauseIntent = ... (intent para la acción de play/pause)
        // val playPausePendingIntent = PendingIntent.getService(this, 1, playPauseIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reproduciendo Audio")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Reemplaza con tu ícono
            .setContentIntent(pendingIntent)
            // .addAction(playPauseIcon, if(isPlaying) "Pausar" else "Reproducir", playPausePendingIntent) // Ejemplo de acción
            .setOngoing(isPlaying) // Hace que la notificación no se pueda descartar mientras está reproduciendo
            .build()
    }
    private fun updateNotification(contentText: String, isPlaying: Boolean) {
        val notification = createNotification(contentText, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Si no está reproduciendo, y no queremos que la notificación sea persistente, la podemos quitar
        // o dejarla como "no ongoing".
        if (!isPlaying) {
            // Si quieres que la notificación desaparezca al pausar y no es foreground obligatorio:
            // stopForeground(false) // false para mantener la notificación pero permitir que se descarte
            // O si quieres quitarla completamente:
            // stopForeground(STOP_FOREGROUND_REMOVE) // Y si ya no se va a usar, quizá stopSelf()
            // Por ahora, la mantenemos pero no "ongoing"
        } else {
            // Si está reproduciendo, asegurarse que sea foreground
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}