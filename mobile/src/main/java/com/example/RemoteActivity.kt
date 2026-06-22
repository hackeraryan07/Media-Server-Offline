package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.ServerManager
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class RemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tvIp = intent.getStringExtra("tv_ip") ?: return finish()

        setContent {
            MyApplicationTheme {
                RemoteScreen(tvIp, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(tvIp: String, onBack: () -> Unit) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentVideoId by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var needsResumeChoice by remember { mutableStateOf(false) }
    var resumePosition by remember { mutableStateOf(0L) }
    
    val client = remember { OkHttpClient() }
    val videoList = remember { ServerManager.localVideoServer?.getVideosList() ?: emptyList() }

    LaunchedEffect(tvIp) {
        while (true) {
            try {
                val request = Request.Builder().url("http://$tvIp:9000/state").build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (body != null) {
                                val json = JSONObject(body)
                                isPlaying = json.optBoolean("isPlaying", false)
                                val t = json.optString("title", "")
                                currentTitle = if (t.isNotBlank()) t else null
                                val vId = json.optString("videoId", "")
                                currentVideoId = if (vId.isNotBlank()) vId else null
                                duration = json.optLong("duration", 0L)
                                needsResumeChoice = json.optBoolean("needsResumeChoice", false)
                                resumePosition = json.optLong("resumePosition", 0L)
                                if (!isSeeking) {
                                    position = json.optLong("position", 0L)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteActivity", "Error polling state", e)
            }
            delay(1000)
        }
    }

    fun sendCommand(action: String, extraId: String? = null) {
        Thread {
            try {
                var url = "http://$tvIp:9000/command?action=$action"
                if (extraId != null) url += "&id=$extraId"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("RemoteActivity", "Command failed: $action", e)
            }
        }.start()
    }

    if (needsResumeChoice) {
        AlertDialog(
            onDismissRequest = { /* Do nothing, force choice */ },
            title = { Text("Resume Playback?") },
            text = { 
                val mins = (resumePosition / 1000) / 60
                val secs = (resumePosition / 1000) % 60
                val timeStr = String.format("%d:%02d", mins, secs)
                Text("Would you like to resume \"${currentTitle ?: "this video"}\" from $timeStr?")
            },
            confirmButton = {
                TextButton(onClick = { 
                    Thread {
                        val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=continue").build()
                        client.newCall(request).execute().close()
                    }.start()
                    needsResumeChoice = false
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    Thread {
                        val request = Request.Builder().url("http://$tvIp:9000/command?action=resume_choice&choice=start_over").build()
                        client.newCall(request).execute().close()
                    }.start()
                    needsResumeChoice = false
                }) {
                    Text("Start Over")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Remote Controls", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF7F2FA)
                )
            )
        },
        containerColor = Color(0xFFF7F2FA)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            
            // Now Playing Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "NOW PLAYING ON TV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21005D),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentTitle ?: "No Media Playing",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21005D),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Slider for Seek
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration.toFloat()) else 0f,
                        onValueChange = { percent ->
                            isSeeking = true
                            position = (percent * duration).toLong()
                        },
                        onValueChangeFinished = {
                            isSeeking = false
                            sendCommand("seek&position=$position")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6750A4),
                            activeTrackColor = Color(0xFF6750A4)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Player Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { sendCommand("prev") },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color(0xFF21005D))
                        }

                        IconButton(
                            onClick = { sendCommand("seek&position=${maxOf(0, position - 5000)}") },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            Icon(Icons.Default.FastRewind, contentDescription = "Rewind 5s", tint = Color(0xFF21005D))
                        }

                        IconButton(
                            onClick = { sendCommand(if (isPlaying) "pause" else "play") },
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF6750A4), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = { sendCommand("seek&position=${minOf(duration, position + 10000)}") },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "Forward 10s", tint = Color(0xFF21005D))
                        }

                        IconButton(
                            onClick = { sendCommand("next") },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color(0xFF21005D))
                        }
                    }
                }
            }

            Text(
                text = "Cast New Video",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF49454F),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Video List
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videoList) { video ->
                    val isCurrent = video.id == currentVideoId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sendCommand("play_video", video.id) },
                        colors = CardDefaults.cardColors(containerColor = if (isCurrent) Color(0xFFD3E3FD) else Color.White),
                        border = if (isCurrent) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PlayArrow, 
                                contentDescription = "Play", 
                                tint = if (isCurrent) Color(0xFF001D35) else Color(0xFF6750A4)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = video.title,
                                fontSize = 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color(0xFF001D35) else Color(0xFF1C1B1F)
                            )
                        }
                    }
                }
            }
        }
    }
}
