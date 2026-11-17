package com.batubot.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class ThongDiep(private val context: Context? = null) {
    val menhLaGiGiaiThich: String get() = context?.getString(R.string.td_menh_la_gi_giai_thich) ?: ""
    val vanLaGiGiaiThich: String get() = context?.getString(R.string.td_van_la_gi_giai_thich) ?: ""
    val caiMenhNtnSummary: String get() = context?.getString(R.string.td_cai_menh_ntn_summary) ?: ""
    val elementKimContent: String get() = context?.getString(R.string.td_element_kim_content) ?: ""
    val elementMocContent: String get() = context?.getString(R.string.td_element_moc_content) ?: ""
    val elementThuyContent: String get() = context?.getString(R.string.td_element_thuy_content) ?: ""
    val elementHoaContent: String get() = context?.getString(R.string.td_element_hoa_content) ?: ""
    val elementThoContent: String get() = context?.getString(R.string.td_element_tho_content) ?: ""
    val geminiIntroDesc: String get() = context?.getString(R.string.td_gemini_intro_desc) ?: ""
}

val VietnameseThongDiep = ThongDiep()
val EngThongDiep = ThongDiep()

val ThongDiepStrings = compositionLocalOf { VietnameseThongDiep }

@Composable
fun ProvideThongDiep(language: String = "vi", content: @Composable () -> Unit) {
    val context = LocalContext.current
    val localizedThongDiep = remember(language, context) {
        val locale = java.util.Locale.forLanguageTag(if (language == "en") "en" else "vi")
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        ThongDiep(localizedContext)
    }
    androidx.compose.runtime.CompositionLocalProvider(ThongDiepStrings provides localizedThongDiep, content = content)
}