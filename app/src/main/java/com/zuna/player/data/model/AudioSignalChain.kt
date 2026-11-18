package com.zuna.player.data.model

data class SignalStage(
    val stageName: String,
    val description: String,
    val iconName: String,
    val details: Map<String, String>,
    val isActive: Boolean = true,
    val isHiRes: Boolean = false
)

data class AudioSignalChain(
    val sourceStage: SignalStage,
    val decoderStage: SignalStage,
    val processingStage: SignalStage,
    val equalizerStage: SignalStage,
    val driverStage: SignalStage,
    val outputDeviceStage: SignalStage
) {
    val allStages: List<SignalStage>
        get() = listOf(
            sourceStage,
            decoderStage,
            processingStage,
            equalizerStage,
            driverStage,
            outputDeviceStage
        )
}
