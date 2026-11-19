package com.adekarya.ipynbrunner.data.model

enum class Backend(val label: String) {
    KAGGLE("Kaggle"),
    COLAB("Colab"),
    ;

    companion object {
        fun fromName(value: String?): Backend =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: COLAB
    }
}

data class KaggleRunOptions(
    val title: String,
    val slug: String,
    val useGpu: Boolean = false,
    val enableInternet: Boolean = true,
    val isPrivate: Boolean = true,
    val timeoutMinutes: Int = 180,
)

data class ColabSpecKey(
    val variant: String = "VARIANT_CPU",
    val accelerator: String = "NONE",
    val shape: String = "SHAPE_STANDARD",
) {
    val label: String
        get() {
            val accel = when (accelerator.uppercase()) {
                "NONE" -> "CPU"
                else -> accelerator.uppercase()
            }
            val memory = if (shape == "SHAPE_HIGHMEM") " · High-RAM" else ""
            return "$accel$memory"
        }

    fun asQueryMap(): Map<String, String> = mapOf(
        "variant" to variant,
        "accelerator" to accelerator,
        "shape" to shape,
    )
}

data class ColabRunOptions(
    val spec: ColabSpecKey,
    val keepAliveCell: Boolean = true,
    val keepAliveAfterRun: Boolean = true,
)

data class ColabRuntimeSpec(
    val key: ColabSpecKey,
    val eligible: Boolean,
)

data class ColabRuntimeInfo(
    val name: String,
    val spec: ColabSpecKey?,
    val connectionUrl: String,
    val proxyToken: String,
    val tokenExpireTime: String? = null,
    val endpoint: String? = null,
)
