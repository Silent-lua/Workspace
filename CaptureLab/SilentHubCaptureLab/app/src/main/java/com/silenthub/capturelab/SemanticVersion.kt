package com.silenthub.capturelab

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = major.toString() + "." + minor + "." + patch

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(value: String): SemanticVersion {
            val match = pattern.matchEntire(value)
                ?: throw IllegalArgumentException("Invalid semantic version: " + value)
            return SemanticVersion(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }
    }
}