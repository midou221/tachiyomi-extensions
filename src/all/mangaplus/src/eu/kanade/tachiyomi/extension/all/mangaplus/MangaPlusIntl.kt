package eu.kanade.tachiyomi.extension.all.mangaplus

class MangaPlusIntl(lang: Language) {

    val imageQuality: String = when (lang) {
        Language.PORTUGUESE_BR -> "Qualidade da imagem"
        else -> "Image quality"
    }

    val imageQualityLow: String = when (lang) {
        Language.PORTUGUESE_BR -> "Baixa"
        else -> "Low"
    }

    val imageQualityMedium: String = when (lang) {
        Language.PORTUGUESE_BR -> "Média"
        else -> "Medium"
    }

    val imageQualityHigh: String = when (lang) {
        Language.PORTUGUESE_BR -> "Alta"
        else -> "High"
    }

    val splitDoublePages: String = when (lang) {
        Language.PORTUGUESE_BR -> "Dividir as páginas duplas"
        else -> "Split double pages"
    }

    val splitDoublePagesSummary: String = when (lang) {
        Language.PORTUGUESE_BR -> "Somente poucos títulos suportam a desativação desta configuração."
        else -> "Only a few titles supports disabling this setting."
    }
}
