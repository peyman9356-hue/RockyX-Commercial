package com.rockyx.livinggem

enum class TrainingUiState { IDLE, READY, TRAINING, SUCCESS, REST }

enum class GemEvent { SUCCESS_PULSE, BLINK }

data class VisualProfile(
    val orbitDegPerSec: Float,
    val lightSweepHz: Float,
    val lightIntensity: Float,
    val glow: Float
)

object VisualProfiles {
    fun forState(state: TrainingUiState): VisualProfile = when (state) {
        TrainingUiState.IDLE -> VisualProfile(4.0f, 0.05f, 0.42f, 0.16f)
        TrainingUiState.READY -> VisualProfile(7.5f, 0.08f, 0.70f, 0.32f)
        TrainingUiState.TRAINING -> VisualProfile(12.0f, 0.14f, 1.00f, 0.58f)
        TrainingUiState.SUCCESS -> VisualProfile(9.0f, 0.11f, 1.00f, 0.82f)
        TrainingUiState.REST -> VisualProfile(2.5f, 0.035f, 0.28f, 0.08f)
    }
}
