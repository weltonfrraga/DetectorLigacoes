package com.calldetector.core

/** Pacote "quadro + resultado do detector" entregue à interface/diagnóstico. */
class FrameInfo(
    val features: FrameFeatures,
    val out: DetectorOutput,
    /** Há quanto tempo (ms) o microfone entrega silêncio digital absoluto (zeros). */
    val zeroRunMs: Long
)
