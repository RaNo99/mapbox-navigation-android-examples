package com.mapbox.navigation.examples.aaos.stt

import java.util.Locale

class Language(
    val locale: Locale,
) {
    constructor(languageTag: String) : this(Locale.forLanguageTag(languageTag))

    val languageTag: String = locale.toLanguageTag()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Language) return false

        return this.locale.language == other.locale.language
    }

    override fun hashCode(): Int {
        return locale.language.hashCode()
    }

    override fun toString(): String {
        return "Language(locale=$locale)"
    }
}

fun deviceLanguage(): Language {
    return Language(Locale.getDefault())
}
