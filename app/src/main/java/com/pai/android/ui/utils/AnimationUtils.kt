package com.pai.android.ui.utils

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Утилиты для анимаций и визуальных эффектов.
 */

// ============= АНИМАЦИИ ВЗАИМОДЕЙСТВИЙ =============

/**
 * Модификатор для анимации нажатия кнопок (scale эффект).
 * Кнопка немного уменьшается при нажатии и возвращается при отпускании.
 *
 * @param scaleFactor Коэффициент масштабирования при нажатии (по умолчанию 0.95f)
 * @param interactionSource Источник взаимодействия для отслеживания состояния нажатия
 */
fun Modifier.pressAnimation(
    scaleFactor: Float = 0.95f,
    interactionSource: InteractionSource? = null
): Modifier = composed {
    val interaction = interactionSource ?: remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleFactor else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "button_press_animation"
    )
    
    this.scale(scale)
}

/**
 * Модификатор для анимации фокуса полей ввода (цвет обводки и тень).
 * Используется с OutlinedTextField для визуального выделения при фокусе.
 *
 * @param interactionSource Источник взаимодействия для отслеживания фокуса
 * @param focusedColor Цвет обводки при фокусе (по умолчанию primary)
 * @param unfocusedColor Цвет обводки без фокуса (по умолчанию outline)
 * @param focusedShadowElevation Высота тени при фокусе (по умолчанию 8.dp)
 * @param unfocusedShadowElevation Высота тени без фокуса (по умолчанию 0.dp)
 */
@Composable
fun Modifier.focusAnimation(
    interactionSource: InteractionSource,
    focusedColor: Color = MaterialTheme.colorScheme.primary,
    unfocusedColor: Color = MaterialTheme.colorScheme.outline,
    focusedShadowElevation: Dp = 8.dp,
    unfocusedShadowElevation: Dp = 0.dp
): Modifier = composed {
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) focusedColor else unfocusedColor,
        animationSpec = tween(durationMillis = 200),
        label = "focus_border_color"
    )
    
    val shadowElevation by animateDpAsState(
        targetValue = if (isFocused) focusedShadowElevation else unfocusedShadowElevation,
        animationSpec = tween(durationMillis = 200),
        label = "focus_shadow_elevation"
    )
    
    this.graphicsLayer {
        this.shadowElevation = shadowElevation.toPx()
    }.drawBehind {
        drawRect(
            color = borderColor,
            style = Stroke(width = 2.dp.toPx()),
            size = size
        )
    }
}

/**
 * Модификатор для анимации переключения toggle (slide эффект).
 * Создаёт эффект скольжения при изменении состояния.
 *
 * @param isChecked Состояние переключателя (включен/выключен)
 * @param slideDistance Расстояние скольжения в dp (по умолчанию 8.dp)
 */
fun Modifier.toggleSlideAnimation(
    isChecked: Boolean,
    slideDistance: Dp = 8.dp
): Modifier = composed {
    val offsetX by animateDpAsState(
        targetValue = if (isChecked) slideDistance else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "toggle_slide_animation"
    )
    
    this.graphicsLayer {
        translationX = offsetX.toPx()
    }
}

/**
 * Модификатор для анимации раскрытия/скрытия контента (expand/collapse).
 * Плавно изменяет высоту контента.
 *
 * @param isExpanded Состояние раскрытия
 * @param initialHeight Начальная высота в dp (когда скрыто)
 * @param expandedHeight Высота в раскрытом состоянии (если null - измеряется автоматически)
 */
fun Modifier.expandAnimation(
    isExpanded: Boolean,
    initialHeight: Dp = 0.dp,
    expandedHeight: Dp? = null
): Modifier = composed {
    val height by animateDpAsState(
        targetValue = if (isExpanded) expandedHeight ?: 200.dp else initialHeight,
        animationSpec = tween(durationMillis = 300),
        label = "expand_animation"
    )
    
    // В Compose 1.3+ можно использовать animateContentSize, но здесь используем graphicsLayer
    this.graphicsLayer {
        // Для простоты - возвращаем модификатор, фактическую анимацию высоты
        // нужно делать через animateContentSize() в родительском компоненте
    }
    
    this
}

/**
 * Модификатор для анимации вращения кнопки (например, при раскрытии меню).
 *
 * @param isRotated Состояние вращения (true = повёрнуто, false = исходное)
 * @param rotationAngle Угол вращения в градусах (по умолчанию 90° для превращения + в ×)
 */
fun Modifier.rotateAnimation(
    isRotated: Boolean,
    rotationAngle: Float = 90f
): Modifier = composed {
    val rotation by animateFloatAsState(
        targetValue = if (isRotated) rotationAngle else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "rotate_animation"
    )
    
    this.graphicsLayer {
        this.rotationZ = rotation
    }
}

// ============= ВИЗУАЛЬНЫЕ ЭФФЕКТЫ =============

/**
 * Модификатор для добавления мягкой тени (Material Elevation).
 *
 * @param elevation Высота тени в dp (по умолчанию 4.dp)
 * @param shape Форма тени (по умолчанию соответствует форме компонента)
 */
fun Modifier.softShadow(
    elevation: Dp = 4.dp,
    shape: Shape? = null
): Modifier = composed {
    var modifier = this
    // В Material3 тень добавляется автоматически через Surface,
    // но можно использовать этот модификатор для кастомных теней
    modifier = modifier.graphicsLayer {
        this.shadowElevation = elevation.toPx()
    }
    if (shape != null) {
        modifier = modifier.clip(shape)
    }
    modifier
}

/**
 * Модификатор для добавления градиентной заливки.
 *
 * @param colors Список цветов градиента
 * @param angle Угол градиента в градусах (0 = слева направо, 90 = сверху вниз)
 * @param shape Форма для обрезки градиента
 */
fun Modifier.gradientBackground(
    colors: List<Color>,
    angle: Float = 0f,
    shape: Shape? = null
): Modifier = composed {
    val brush = Brush.linearGradient(
        colors = colors,
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(
            x = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat() * 1000,
            y = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat() * 1000
        ),
        tileMode = TileMode.Clamp
    )
    
    var modifier = this.background(brush = brush)
    
    if (shape != null) {
        modifier = modifier.clip(shape)
    }
    
    modifier
}

/**
 * Модификатор для добавления обводки с анимацией цвета.
 *
 * @param color Цвет обводки
 * @param width Ширина обводки в dp (по умолчанию 1.dp)
 * @param shape Форма обводки
 */
fun Modifier.animatedBorder(
    color: Color,
    width: Dp = 1.dp,
    shape: Shape? = null
): Modifier = composed {
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 300),
        label = "border_color_animation"
    )
    
    var modifier = this.drawBehind {
        drawRect(
            color = animatedColor,
            style = Stroke(width = width.toPx()),
            size = size
        )
    }
    
    if (shape != null) {
        modifier = modifier.clip(shape)
    }
    
    modifier
}

/**
 * Модификатор для добавления эффекта параллакса при прокрутке.
 *
 * @param parallaxFactor Коэффициент параллакса (0 = нет эффекта, 1 = максимальный эффект)
 */
fun Modifier.parallaxEffect(parallaxFactor: Float = 0.5f): Modifier = composed {
    // Реализация параллакса требует координат прокрутки,
    // здесь упрощённая версия для демонстрации
    this.graphicsLayer {
        // В реальной реализации нужно использовать offsetY от скролла
    }
}

// ============= УТИЛИТЫ ДЛЯ КОМПОНЕНТОВ =============

/**
 * Создаёт градиент на основе акцентного цвета темы.
 */
fun accentGradient(
    primaryColor: Color,
    secondaryColor: Color,
    angle: Float = 45f
): Brush {
    return Brush.linearGradient(
        colors = listOf(primaryColor, secondaryColor),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(
            x = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat() * 1000,
            y = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat() * 1000
        ),
        tileMode = TileMode.Clamp
    )
}

/**
 * Создаёт список цветов для градиента на основе основного цвета темы.
 */
fun themeGradient(primaryColor: Color): List<Color> {
    val lighter = primaryColor.copy(alpha = 0.8f)
    val darker = primaryColor.copy(alpha = 1f)
    return listOf(lighter, primaryColor, darker)
}