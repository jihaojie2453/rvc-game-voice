package com.voicestudio.phone

/** Device hints choose the first attempt; successfully loading/running the model is the real check. */
internal data class ComputePolicy(val tryNpu: Boolean, val tryGpu: Boolean) {
    /**
     * The decoder is what decides whether realtime keeps up: on this Snapdragon it is ~520 ms per
     * 270 ms block on the CPU and ~54 ms on the HTP. The integer (w8a16) model is the best case, but
     * an fp16 model on the NPU still beats the CPU by an order of magnitude, so a Qualcomm device
     * always starts on the NPU instead of falling back to the CPU just because no int8 file exists.
     * BackendFallback still drops to the CPU when QNN cannot load the graph.
     */
    fun decoder(hasIntegerModel: Boolean, hasGpuModel: Boolean): String = when {
        tryNpu && hasIntegerModel -> "npu_int"
        tryGpu && hasGpuModel -> "gpu"
        tryNpu -> "npu16"
        else -> "cpu"
    }

    companion object {
        fun from(socManufacturer: String, hardware: String, board: String, vulkan: Boolean): ComputePolicy {
            val vendor = socManufacturer.lowercase(java.util.Locale.ROOT)
            val ids = listOf(hardware, board).map { it.lowercase(java.util.Locale.ROOT) }
            val qualcomm = vendor == "qti" || vendor.contains("qualcomm") || ids.any {
                it.contains("qcom") || it.contains("qualcomm") || it.matches(Regex("(?:msm|sdm|sm)\\d+.*"))
            }
            return ComputePolicy(qualcomm, vulkan)
        }
    }
}
