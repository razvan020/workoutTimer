package com.example.workouttimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

class TimerViewModel : ViewModel() {
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var kickPlayer: android.media.MediaPlayer? = null // Added for the 30s warning
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // State
    var timeRemainingMs by mutableLongStateOf(0L)
    var isRunning by mutableStateOf(false)
    var isPrepPhase by mutableStateOf(false)
    var statusText by mutableStateOf("Tap numbers to edit")
    var totalDurationMs by mutableLongStateOf(60000L) // Default 1 min

    private var timerJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun initAudio(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .build()

        // 1. Setup Main Countdown
        mediaPlayer = android.media.MediaPlayer.create(context, R.raw.countdownfin)
        mediaPlayer?.setAudioAttributes(audioAttributes)
        mediaPlayer?.setVolume(1.0f, 1.0f)
        mediaPlayer?.setOnCompletionListener {
            focusRequest?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        }

        // 2. Setup 30-Second Kick Warning
        kickPlayer = android.media.MediaPlayer.create(context, R.raw.kickfin)
        kickPlayer?.setAudioAttributes(audioAttributes)
        kickPlayer?.setVolume(1.0f, 1.0f)
        kickPlayer?.setOnCompletionListener {
            // Restore Spotify volume instantly after the kick thud
            focusRequest?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playSound() {
        focusRequest?.let { audioManager?.requestAudioFocus(it) }
        mediaPlayer?.seekTo(0)
        mediaPlayer?.start()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playKickSound() {
        focusRequest?.let { audioManager?.requestAudioFocus(it) }
        kickPlayer?.seekTo(0)
        kickPlayer?.start()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startTimer(minutes: Int, seconds: Int) {
        totalDurationMs = ((minutes * 60) + seconds) * 1000L
        if (totalDurationMs <= 0) return

        isRunning = true
        isPrepPhase = true
        statusText = "GET READY..."

        val audioLengthMs = 4000L // Triggers at exactly 4 seconds remaining

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            // --- PREP PHASE ---
            val prepMs = 5000L
            val prepEnd = System.currentTimeMillis() + prepMs
            var prepAudioPlayed = false

            while (System.currentTimeMillis() < prepEnd) {
                timeRemainingMs = prepEnd - System.currentTimeMillis()

                if (timeRemainingMs <= audioLengthMs && !prepAudioPlayed) {
                    playSound()
                    prepAudioPlayed = true
                }
                delay(50)
            }

            // --- MAIN PHASE ---
            isPrepPhase = false
            statusText = "GO!"

            val mainEnd = System.currentTimeMillis() + totalDurationMs
            var mainAudioPlayed = false
            var hasPlayedWarning = false // Tracks if the 30s kick has fired

            while (System.currentTimeMillis() < mainEnd) {
                timeRemainingMs = mainEnd - System.currentTimeMillis()
                val secsLeft = (timeRemainingMs / 1000).toInt()

                // Trigger 30-second warning kick
                if (secsLeft == 30 && !hasPlayedWarning) {
                    playKickSound()
                    hasPlayedWarning = true
                }

                // Trigger the final countdown
                if (timeRemainingMs <= audioLengthMs && !mainAudioPlayed) {
                    playSound()
                    mainAudioPlayed = true
                }
                delay(50)
            }

            // Finish
            statusText = "TIME'S UP!"
            stopTimer(finished = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stopTimer(finished: Boolean = false) {
        timerJob?.cancel()
        isRunning = false
        isPrepPhase = false
        if (!finished) {
            statusText = "Tap numbers to edit"
            timeRemainingMs = totalDurationMs
        } else {
            timeRemainingMs = 0L
        }
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    override fun onCleared() {
        super.onCleared()
        // Release both media players to save memory!
        mediaPlayer?.release()
        mediaPlayer = null
        kickPlayer?.release()
        kickPlayer = null
    }
}

// --- 2. The User Interface (Jetpack Compose) ---
class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            WorkoutTimerApp()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WorkoutTimerApp(viewModel: TimerViewModel = viewModel()) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current // Allows us to hide the keyboard
    LaunchedEffect(Unit) { viewModel.initAudio(context) }

    // Colors
    val primaryColor = Color(0xFF0F172A)
    val dangerColor = Color(0xFFEF4444)
    val trackColor = Color(0x140F172A)

    val bgColor by animateColorAsState(
        targetValue = when {
            viewModel.timeRemainingMs == 0L && !viewModel.isRunning && viewModel.statusText == "TIME'S UP!" -> Color(0xFFFEE2E2)
            viewModel.isRunning -> Color(0xFF95F1B4)
            else -> Color(0xFFF1F5F9)
        },
        animationSpec = tween(1000),
        label = "bg_color_animation"
    )

    // Upgrade to TextFieldValue so we can control text highlighting
    var inputMins by remember { mutableStateOf(TextFieldValue("01")) }
    var inputSecs by remember { mutableStateOf(TextFieldValue("00")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            // ADD THIS: Tapping anywhere on the background hides the keyboard
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("WORKOUT", letterSpacing = 4.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF475569))

        Spacer(modifier = Modifier.height(32.dp))

        // Timer Ring
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(340.dp)) {
            val progress = if (viewModel.isRunning) {
                if (viewModel.isPrepPhase) (viewModel.timeRemainingMs / 5000f)
                else (viewModel.timeRemainingMs / viewModel.totalDurationMs.toFloat())
            } else 1f

            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(100, easing = LinearEasing),
                label = "ring_animation"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color = primaryColor, startAngle = -90f, sweepAngle = animatedProgress * 360f, useCenter = false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }

            // Time Display
            val scope = rememberCoroutineScope() // <--- Allows us to launch the delay

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val displayMins = if (viewModel.isRunning) {
                    TextFieldValue((viewModel.timeRemainingMs / 60000).toString().padStart(2, '0'))
                } else inputMins

                val displaySecs = if (viewModel.isRunning) {
                    TextFieldValue(((viewModel.timeRemainingMs % 60000) / 1000).toString().padStart(2, '0'))
                } else inputSecs

                androidx.compose.foundation.text.BasicTextField(
                    value = displayMins,
                    onValueChange = { newValue ->
                        if (newValue.text.length <= 2) {
                            inputMins = newValue.copy(text = newValue.text.filter { it.isDigit() })
                        }
                    },
                    enabled = !viewModel.isRunning,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    modifier = Modifier
                        .width(100.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                // THE FIX: Wait 50ms for the touch event to finish before highlighting
                                scope.launch {
                                    delay(50)
                                    inputMins = inputMins.copy(selection = TextRange(0, inputMins.text.length))
                                }
                            } else {
                                val padded = if (inputMins.text.isEmpty()) "00" else inputMins.text.padStart(2, '0')
                                inputMins = inputMins.copy(text = padded)
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done // Changes the enter key to a "Done" checkmark
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus() // Hides keyboard when "Done" is pressed
                        }
                    )
                )

                Text(
                    text = ":",
                    fontSize = 60.sp,
                    color = Color(0xFF808C9B),
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp, end = 4.dp)
                )

                androidx.compose.foundation.text.BasicTextField(
                    value = displaySecs,
                    onValueChange = { newValue ->
                        if (newValue.text.length <= 2) {
                            inputSecs = newValue.copy(text = newValue.text.filter { it.isDigit() })
                        }
                    },
                    enabled = !viewModel.isRunning,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    modifier = Modifier
                        .width(100.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                // THE FIX: Wait 50ms for the touch event to finish before highlighting
                                scope.launch {
                                    delay(50)
                                    inputSecs = inputSecs.copy(selection = TextRange(0, inputSecs.text.length))
                                }
                            } else {
                                val padded = if (inputSecs.text.isEmpty()) "00" else inputSecs.text.padStart(2, '0')
                                inputSecs = inputSecs.copy(text = padded)
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done // Changes the enter key to a "Done" checkmark
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus() // Hides keyboard when "Done" is pressed
                        }
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(viewModel.statusText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
        Spacer(modifier = Modifier.height(32.dp))

        // Start/Stop Button
        Button(
            onClick = {
                focusManager.clearFocus() // Drop cursor & hide keyboard on click

                if (viewModel.isRunning) {
                    viewModel.stopTimer()
                } else {
                    viewModel.startTimer(inputMins.text.toIntOrNull() ?: 0, inputSecs.text.toIntOrNull() ?: 0)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isRunning) dangerColor else primaryColor),
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(60.dp).width(160.dp)
        ) {
            Text(if (viewModel.isRunning) "STOP" else "START", fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}