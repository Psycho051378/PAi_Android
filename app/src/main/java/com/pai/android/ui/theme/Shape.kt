package com.pai.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Современные формы с закруглёнными углами для элегантного внешнего вида.
 * Соответствует принципам Material Design 3.
 */
val Shapes = Shapes(
    // Extra small rounding (для маленьких элементов)
    extraSmall = RoundedCornerShape(4.dp),
    
    // Small rounding (для кнопок, чипов)
    small = RoundedCornerShape(8.dp),
    
    // Medium rounding (для карточек, диалогов)
    medium = RoundedCornerShape(12.dp),
    
    // Large rounding (для больших карточек, панелей)
    large = RoundedCornerShape(16.dp),
    
    // Extra large rounding (для очень больших элементов)
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Дополнительные кастомные формы.
 */
object CustomShapes {
    // Полностью закруглённая форма (для круглых кнопок)
    val Circular = RoundedCornerShape(percent = 50)
    
    // Форма с закруглением только сверху (для bottom sheets)
    val TopRounded = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    
    // Форма с закруглением только снизу (для top app bars)
    val BottomRounded = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    
    // Асимметричное закругление (для современных карточек)
    val Asymmetric = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 20.dp,
        bottomEnd = 12.dp,
        bottomStart = 20.dp
    )
}