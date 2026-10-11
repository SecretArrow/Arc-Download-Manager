package com.arc.downloadmanager.ui.theme

import androidx.compose.ui.graphics.Color
import com.arc.downloadmanager.data.repository.FileCategory

/**
 * Distinct accent per file category. Color is always paired with a category icon and label,
 * so it supplements rather than carries meaning (accessibility-safe).
 */
fun categoryAccent(category: FileCategory): Color =
    when (category) {
        FileCategory.IMAGE -> Color(0xFF1E88E5)
        FileCategory.VIDEO -> Color(0xFF8E24AA)
        FileCategory.AUDIO -> Color(0xFF00897B)
        FileCategory.DOCUMENT -> Color(0xFFFB8C00)
        FileCategory.ARCHIVE -> Color(0xFF8D6E63)
        FileCategory.APP -> Color(0xFF43A047)
        FileCategory.OTHER -> Color(0xFF607D8B)
    }
