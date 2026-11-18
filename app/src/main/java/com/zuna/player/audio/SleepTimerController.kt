package com.zuna.player.audio

import com.zuna.player.service.AudioPlayerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SleepTimerController {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun startSleepTimer(minutes: Long, onComplete: () -> Unit) {
        cancelSleepTimer()
        if (minutes <= 0) return

        val totalSec = minutes * 60L
        _remainingSeconds.value = totalSec
        _isActive.value = true

        timerJob = scope.launch {
            var currentSec = totalSec
            while (currentSec > 0 && _isActive.value) {
                delay(1000L)
                currentSec--
                _remainingSeconds.value = currentSec
            }
            if (_isActive.value) {
                _isActive.value = false
                _remainingSeconds.value = 0L
                AudioPlayerService.pause()
                onComplete()
            }
        }
    }

    fun cancelSleepTimer() {
        timerJob?.cancel()
        timerJob = null
        _isActive.value = false
        _remainingSeconds.value = 0L
    }

    fun formatRemainingTime(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
}
