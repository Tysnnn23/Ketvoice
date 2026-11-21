package org.tensorflow.lite

/**
 * LiteRT is not on the desktop. This shape only lets the shared `stems/Uvr.kt` compile as it is (its
 * `UvrSeparator` and `MixedFft` are pure Kotlin); the desktop runs the networks through ONNX Runtime
 * (`OnnxStemNet`, `OnnxUvrNet` in the desktop `StemEngine.kt`), so constructing one is an error.
 */
class Interpreter(@Suppress("UNUSED_PARAMETER") file: java.io.File, @Suppress("UNUSED_PARAMETER") options: Options) {
    init { throw UnsupportedOperationException("LiteRT is not available on the desktop") }

    class Options {
        fun setNumThreads(@Suppress("UNUSED_PARAMETER") n: Int) = this
        fun setUseXNNPACK(@Suppress("UNUSED_PARAMETER") on: Boolean) = this
    }

    class Tensor { fun shape(): IntArray = IntArray(0) }

    fun getInputTensor(@Suppress("UNUSED_PARAMETER") i: Int) = Tensor()
    fun getOutputTensor(@Suppress("UNUSED_PARAMETER") i: Int) = Tensor()
    fun run(@Suppress("UNUSED_PARAMETER") input: Any, @Suppress("UNUSED_PARAMETER") output: Any) {}
    fun close() {}
}
