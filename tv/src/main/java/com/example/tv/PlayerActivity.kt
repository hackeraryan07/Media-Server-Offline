package com.example.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.DefaultTimeBar
import org.json.JSONObject
import android.widget.Toast

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var overlay: View
    private lateinit var titleText: TextView
    private lateinit var btnPlayPause: android.widget.ImageButton
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var loadingSpinner: android.widget.ProgressBar

    private var lastFocusedTopBarView: View? = null
    private var lastFocusedMiddleRightView: View? = null
    private var lastFocusedControlsPillView: View? = null
    private var lastFocusedUpperView: View? = null
    private var ignoreFocusMemory = false
    
    private val hideHandler = Handler(Looper.getMainLooper())
    private var videoUrlString: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    
    private var playlist: List<TvVideo>? = null
    private var currentIndex: Int = 0
    private var currentVideo: TvVideo? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    if (duration > 0 && currentPos >= 0) {
                        currentVideo?.let { video ->
                            sendProgressUpdate(video.id, currentPos, duration)
                        }
                    }
                }
                
                // Update timebar
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0) {
                    timeBar.setPosition(pos)
                    timeBar.setDuration(dur)
                    txtCurrentTime.text = formatTime(pos)
                    txtTotalTime.text = formatTime(dur)
                }
            }
            progressHandler.postDelayed(this, 1000)
        }
    }

    private val hideRunnable = Runnable {
        overlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_player)
    
            playerView = findViewById(R.id.internalVideoView)
            overlay = findViewById(R.id.playerControlsOverlay)
            titleText = findViewById(R.id.playerTitleText)
            titleText.isSelected = true
            btnPlayPause = findViewById(R.id.playerPlayPauseBtn)
            timeBar = findViewById(R.id.playerSeekBar)
            txtCurrentTime = findViewById(R.id.playerCurrentTime)
            txtTotalTime = findViewById(R.id.playerTotalTime)
            loadingSpinner = findViewById(R.id.playerLoadingSpinner)
    
            timeBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                    exoPlayer?.pause()
                    scheduleMetadataHide()
                }
                override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                    txtCurrentTime.text = formatTime(position)
                    scheduleMetadataHide()
                }
                override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                    exoPlayer?.seekTo(position)
                    exoPlayer?.play()
                    scheduleMetadataHide()
                }
            })
    
            findViewById<View>(R.id.btnNext).setOnClickListener {
                exoPlayer?.seekToNextMediaItem()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnPrevious).setOnClickListener {
                exoPlayer?.seekToPreviousMediaItem()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnForward10).setOnClickListener {
                exoPlayer?.let { it.seekTo(it.currentPosition + 10000) }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnReplay10).setOnClickListener {
                exoPlayer?.let { it.seekTo(Math.max(0, it.currentPosition - 10000)) }
                scheduleMetadataHide()
            }
            btnPlayPause.setOnClickListener {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.playerBackBtn).setOnClickListener { finish() }
    
            currentVideo = intent.getSerializableExtra("video") as? TvVideo
            @Suppress("UNCHECKED_CAST")
            playlist = intent.getSerializableExtra("playlist") as? ArrayList<TvVideo>
            currentIndex = intent.getIntExtra("currentIndex", 0)
    
            if (currentVideo == null) {
                Toast.makeText(this, "No video provided", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
    
            initializePlayer()
            setupFocusMemory()
        } catch (e: Exception) {
            val errString = "Player Crash! Cause: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}"
            android.util.Log.e("PlayerActivity", errString)
            Toast.makeText(this, errString.take(150), Toast.LENGTH_LONG).show()
            Toast.makeText(this, errString.take(150), Toast.LENGTH_LONG).show() // show twice to last longer
            finish()
        }

    }
    
    private fun getVideoById(id: String): TvVideo? {
        return playlist?.find { it.id == id } ?: if (currentVideo?.id == id) currentVideo else null
    }

    private fun initializePlayer() {
        videoUrlString = currentVideo?.url
        titleText.text = currentVideo?.title
        
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        
        val items = mutableListOf<MediaItem>()
        if (playlist != null && playlist!!.isNotEmpty()) {
            playlist!!.forEach { video ->
                items.add(MediaItem.Builder()
                    .setUri(Uri.parse(video.url))
                    .setMediaId(video.id)
                    .build())
            }
            exoPlayer?.setMediaItems(items, currentIndex, 0L)
        } else {
            currentVideo?.let {
                exoPlayer?.setMediaItem(MediaItem.Builder()
                    .setUri(Uri.parse(it.url))
                    .setMediaId(it.id)
                    .build())
            }
        }
        
        exoPlayer?.prepare()
        
        TvRemoteServer.playerController = object : TvRemoteServer.PlayerController {
            override fun play() { Handler(Looper.getMainLooper()).post { exoPlayer?.play() } }
            override fun pause() { Handler(Looper.getMainLooper()).post { exoPlayer?.pause() } }
            override fun next() { Handler(Looper.getMainLooper()).post { if (exoPlayer?.hasNextMediaItem() == true) exoPlayer?.seekToNextMediaItem() } }
            override fun prev() { Handler(Looper.getMainLooper()).post { if (exoPlayer?.hasPreviousMediaItem() == true) exoPlayer?.seekToPreviousMediaItem() } }
            override fun playVideo(id: String) {
                Handler(Looper.getMainLooper()).post {
                    var index = playlist?.indexOfFirst { it.id == id } ?: -1
                    if (index == -1) {
                        playlist = java.util.ArrayList(TvDataStore.playlist)
                        val items = mutableListOf<MediaItem>()
                        playlist!!.forEach { video ->
                            items.add(MediaItem.Builder()
                                .setUri(Uri.parse(video.url))
                                .setMediaId(video.id)
                                .build())
                        }
                        index = playlist?.indexOfFirst { it.id == id } ?: -1
                        if (index != -1) {
                            exoPlayer?.setMediaItems(items, index, 0L)
                            exoPlayer?.prepare()
                            return@post
                        }
                    }
                    if (index != -1) {
                        exoPlayer?.seekToDefaultPosition(index)
                        exoPlayer?.prepare()
                    }
                }
            }
            override fun seekTo(positionMs: Long) {
                Handler(Looper.getMainLooper()).post { exoPlayer?.seekTo(positionMs) }
            }
            override fun getState(): JSONObject {
                val state = JSONObject()
                state.put("videoId", currentVideo?.id ?: "")
                state.put("title", currentVideo?.title ?: "")
                var playing = false
                var position = 0L
                var duration = 0L
                var needsResume = false
                var resumePos = 0L
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    playing = exoPlayer?.isPlaying ?: false
                    position = exoPlayer?.currentPosition ?: 0L
                    duration = exoPlayer?.duration ?: 0L
                    needsResume = isWaitingForResume
                    resumePos = currentVideo?.watchedPosition ?: 0L
                    latch.countDown()
                }
                try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
                state.put("isPlaying", playing)
                state.put("position", position)
                state.put("duration", duration)
                state.put("needsResumeChoice", needsResume)
                state.put("resumePosition", resumePos)
                return state
            }
            override fun handleResumeChoice(choice: String) {
                Handler(Looper.getMainLooper()).post {
                    this@PlayerActivity.handleResumeChoice(choice, currentVideo?.watchedPosition ?: 0L)
                }
            }
        }
        
        val watchedPos = currentVideo!!.watchedPosition
        val totDur = currentVideo!!.totalDuration
        val isCompleted = totDur > 0L && watchedPos >= totDur - 5000L
        if (watchedPos > 1000 && !isCompleted) {
            exoPlayer?.playWhenReady = false
            showResumeDialog(currentVideo!!)
        } else {
            if (isCompleted) {
                exoPlayer?.seekTo(0L)
            }
            exoPlayer?.playWhenReady = true
        }

        showMetadataTemp()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                mediaItem?.mediaId?.let { id ->
                    getVideoById(id)?.let { video ->
                        currentVideo = video
                        titleText.text = video.title
                        videoUrlString = video.url
                        showMetadataTemp()
                        
                        if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) {
                            
                            exoPlayer?.playWhenReady = false
                            isWaitingForResume = false
                            if (resumeDialog?.isShowing == true) {
                                resumeDialog?.dismiss()
                            }
                            
                            val watched = video.watchedPosition
                            val total = video.totalDuration
                            val completed = total > 0L && watched >= total - 5000L
                            if (watched > 1000 && !completed) {
                                showResumeDialog(video)
                            } else {
                                if (completed) {
                                    exoPlayer?.seekTo(0L)
                                }
                                exoPlayer?.playWhenReady = true
                            }
                        }
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    loadingSpinner.visibility = View.VISIBLE
                } else {
                    loadingSpinner.visibility = View.GONE
                }
                
                if (playbackState == Player.STATE_ENDED) {
                    val finalDuration = exoPlayer?.duration?.takeIf { it > 0 } ?: currentVideo?.totalDuration ?: 0L
                    currentVideo?.let { video ->
                        sendProgressUpdate(video.id, finalDuration, finalDuration)
                    }
                    if (exoPlayer?.hasNextMediaItem() == false) {
                        finish()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_flat)
                    scheduleMetadataHide()
                } else {
                    btnPlayPause.setImageResource(R.drawable.ic_play_flat)
                    hideHandler.removeCallbacks(hideRunnable)
                    showMetadataTemp()
                }
            }
        })

        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, 1000)
    }

    private var isWaitingForResume = false
    private var resumeDialog: android.app.AlertDialog? = null

    fun handleResumeChoice(choice: String, position: Long) {
        if (!isWaitingForResume) return
        isWaitingForResume = false
        if (resumeDialog?.isShowing == true) {
            resumeDialog?.dismiss()
        }
        if (choice == "continue") {
            exoPlayer?.seekTo(position)
        } else {
            exoPlayer?.seekTo(0)
        }
        exoPlayer?.playWhenReady = true
        exoPlayer?.play()
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%d:%02d", mins, secs)
    }

    private fun showResumeDialog(video: TvVideo) {
        if (resumeDialog?.isShowing == true) {
            resumeDialog?.dismiss()
        }
        isWaitingForResume = true
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Resume Playback?")
        builder.setMessage("Would you like to resume \"${video.title}\" from ${formatTime(video.watchedPosition)}?")
        builder.setPositiveButton("Continue") { dialog, _ ->
            handleResumeChoice("continue", video.watchedPosition)
            dialog.dismiss()
        }
        builder.setNegativeButton("Start Over") { dialog, _ ->
            handleResumeChoice("start_over", 0L)
            dialog.dismiss()
        }
        builder.setCancelable(false)
        resumeDialog = builder.create()
        resumeDialog?.show()
    }

    private fun sendProgressUpdate(videoId: String, position: Long, duration: Long) {
        val videoUrl = videoUrlString
        if (videoUrl.isNullOrEmpty() || !videoUrl.startsWith("http")) return
        
        val uri = Uri.parse(videoUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "127.0.0.1"
        val port = uri.port
        val baseUrl = if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        
        val updateUrl = "$baseUrl/update_progress?id=$videoId&position=$position&duration=$duration"
        
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(updateUrl)
            .build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
    }

    private fun saveFinalProgress() {
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_ENDED) return
            val currentPos = player.currentPosition
            val duration = player.duration
            if (duration > 0 && currentPos >= 0 && currentPos < duration) {
                currentVideo?.let { video ->
                    sendProgressUpdate(video.id, currentPos, duration)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (overlay.visibility != View.VISIBLE) {
                val focusOnSeekBar = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                showMetadataTemp(focusOnSeekBar)
                return true
            }

            val currentFocusView = currentFocus
            val topBar = findViewById<android.view.ViewGroup>(R.id.topBar)
            val middleRightBar = findViewById<android.view.ViewGroup>(R.id.middleRightBar)
            val controlsPillContainer = btnPlayPause.parent as? android.view.ViewGroup

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (currentFocusView == timeBar) {
                    val target = lastFocusedControlsPillView ?: btnPlayPause
                    if (target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                } else if (currentFocusView != null && currentFocusView.parent == middleRightBar) {
                    timeBar.requestFocus()
                    scheduleMetadataHide()
                    return true
                } else if (currentFocusView != null && currentFocusView.parent == topBar) {
                    val target = lastFocusedMiddleRightView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                    } else {
                        timeBar.requestFocus()
                    }
                    scheduleMetadataHide()
                    return true
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (currentFocusView != null && currentFocusView.parent == controlsPillContainer) {
                    timeBar.requestFocus()
                    scheduleMetadataHide()
                    return true
                } else if (currentFocusView == timeBar) {
                    val target = lastFocusedUpperView ?: lastFocusedMiddleRightView ?: lastFocusedTopBarView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                } else if (currentFocusView != null && currentFocusView.parent == middleRightBar) {
                    val target = lastFocusedTopBarView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                }
            } else if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) && currentFocusView == timeBar) {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                scheduleMetadataHide()
                return true
            }
            scheduleMetadataHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupFocusMemory() {
        val topBar = findViewById<android.view.ViewGroup>(R.id.topBar)
        val middleRightBar = findViewById<android.view.ViewGroup>(R.id.middleRightBar)
        val controlsPillContainer = btnPlayPause.parent as? android.view.ViewGroup

        // Default initial focus values
        lastFocusedTopBarView = findViewById(R.id.btnCast)
        lastFocusedMiddleRightView = findViewById(R.id.btnAudioTrack)
        lastFocusedControlsPillView = btnPlayPause
        lastFocusedUpperView = lastFocusedMiddleRightView

        // Top Bar focus tracking
        topBar?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedTopBarView = v
                        lastFocusedUpperView = v
                    }
                }
            }
        }

        // Middle Right Bar focus tracking
        middleRightBar?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedMiddleRightView = v
                        lastFocusedUpperView = v
                    }
                }
            }
        }

        // Controls Pill focus tracking
        controlsPillContainer?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedControlsPillView = v
                    }
                }
            }
        }
    }

    private fun showMetadataTemp(focusOnSeekBar: Boolean = false) {
        val wasHidden = overlay.visibility != View.VISIBLE
        if (wasHidden) {
            ignoreFocusMemory = true
            overlay.visibility = View.VISIBLE
            if (focusOnSeekBar) {
                timeBar.requestFocus()
            } else {
                (lastFocusedControlsPillView ?: btnPlayPause).requestFocus()
            }
            overlay.post {
                ignoreFocusMemory = false
            }
        } else {
            overlay.visibility = View.VISIBLE
        }
        scheduleMetadataHide()
    }

    private fun scheduleMetadataHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 4000)
    }

    override fun onDestroy() {
        super.onDestroy()
        TvRemoteServer.playerController = null
        hideHandler.removeCallbacks(hideRunnable)
        progressHandler.removeCallbacks(progressRunnable)
        saveFinalProgress()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        saveFinalProgress()
    }

    override fun onResume() {
        super.onResume()
        currentVideo?.let { video ->
            if (video.watchedPosition <= 1000 || (exoPlayer?.currentPosition ?: 0L) > 0L) {
                exoPlayer?.play()
            }
        }
    }
}

