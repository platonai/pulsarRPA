package ai.platon.pulsar.common.metrology

object FileSizeUnits {

    fun convert(bytes: Int, unit: String) = convert(bytes.toLong(), unit)

    fun convert(bytes: Long, unit: String): Double {
        return when (unit[0]) {
            'B' -> bytes.toDouble()
            'K' -> bytes / 1024.0
            'M' -> bytes / 1024.0 / 1024.0
            'G' -> bytes / 1024.0 / 1024.0 / 1024.0
            'T' -> bytes / 1024.0 / 1024.0 / 1024.0 / 1024.0
            else -> 0.0
        }
    }
}
