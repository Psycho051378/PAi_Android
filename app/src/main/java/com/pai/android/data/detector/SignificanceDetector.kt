package com.pai.android.data.detector

import com.pai.android.data.model.Message

/**
 * Детектор значимости сообщений.
 * Определяет, содержит ли сообщение информацию, достойную сохранения в память.
 */
class SignificanceDetector {
    
    /**
     * Проверяет, содержит ли сообщение критически важную информацию.
     * Такие сообщения должны быть обработаны немедленно.
     */
    fun isCriticalMessage(message: Message): Boolean {
        val content = message.content.lowercase()
        
        // Паттерны критической информации (высокий приоритет)
        val criticalPatterns = listOf(
            Regex("меня зовут\\s+[а-яa-z]{2,}"),           // Имя
            Regex("мой (email|телефон)\\s+[^\\s]+"),      // Контакты
            Regex("живу в\\s+[а-яa-z\\s]+"),              // Местоположение
            Regex("аллерги(я|ен) на\\s+[а-яa-z]+"),       // Аллергии
            Regex("родил(ся|ась)\\s+[0-9\\.]+"),          // Дата рождения (13.05.1978)
            Regex("родил(ся|ась)\\s+\\d{1,2}\\s+[а-я]+\\s+\\d{4}"), // Дата рождения (13 мая 1978)
            Regex("паспорт|серия\\s+[0-9]+"),             // Документы (критично!)
            Regex("кредитн(ая|ой)\\s+карт[аы]"),          // Финансовая информация
            Regex("пароль\\s+[^\\s]+"),                   // Пароли (критично!)
            Regex("номер\\s+сч[её]та\\s+[0-9]+")          // Банковские реквизиты
        )
        
        return criticalPatterns.any { it.containsMatchIn(content) }
    }
    
    /**
     * Проверяет, содержит ли сообщение информацию высокой значимости.
     * Такие сообщения должны быть сохранены в постоянную память.
     */
    fun isHighSignificanceMessage(message: Message): Boolean {
        val content = message.content.lowercase()
        
        // Паттерны высокой значимости
        val highSignificancePatterns = listOf(
            Regex("люблю|обожаю|ненавижу|терпеть не могу"),  // Сильные предпочтения
            Regex("моя профессия|работаю\\s+[а-яa-z]+"),     // Профессия
            Regex("семья|жена|муж|дети|ребенок"),           // Семейное положение
            Regex("образование|университет|институт"),      // Образование
            Regex("болею|диагноз|заболевание"),             // Медицинская информация
            Regex("проект\\s+[а-яa-z]+"),                   // Проекты
            Regex("цель|мечта|планирую"),                   // Цели и планы
            Regex("тебя зовут|тво(ё|е) имя"),               // Информация об AI (имя)
            Regex("ты\\s+мой\\s+[а-яa-z]+"),                // Информация об AI (роль)
            Regex("запомни\\s+сво(ё|е)\\s+имя"),            // Инструкции для AI
            Regex("не забывай\\s+что")                      // Инструкции для AI
        )
        
        // Эмоциональные усилители
        val emotionalBoosters = listOf(
            "очень", "крайне", "сильно", "абсолютно", "полностью",
            "никогда", "всегда", "постоянно"
        )
        
        val hasHighSignificancePattern = highSignificancePatterns.any { 
            it.containsMatchIn(content) 
        }
        
        val hasEmotionalBooster = emotionalBoosters.any { 
            content.contains(it) 
        }
        
        return hasHighSignificancePattern || hasEmotionalBooster
    }
    
    /**
     * Проверяет, содержит ли сообщение информацию средней значимости.
     * Такие сообщения могут быть сохранены при наличии дополнительного контекста.
     */
    fun isMediumSignificanceMessage(message: Message): Boolean {
        val content = message.content.lowercase()
        
        // Паттерны средней значимости
        val mediumPatterns = listOf(
            Regex("нравится|любимый|предпочитаю"),          // Предпочтения
            Regex("хобби|увлечение|занимаюсь"),             // Хобби
            Regex("фильм|музыка|книга|игра"),               // Культурные предпочтения
            Regex("путешеств|отдых|отпуск"),                // Путешествия
            Regex("спорт|тренировк|занимаюсь спортом"),     // Спорт
            Regex("ем|питаюсь|люблю поесть"),               // Пищевые привычки
            Regex("автомобиль|машина|водитель")             // Транспорт
        )
        
        return mediumPatterns.any { it.containsMatchIn(content) }
    }
    
    /**
     * Проверяет, является ли сообщение шумом (не требует сохранения).
     */
    fun isNoiseMessage(message: Message): Boolean {
        val content = message.content.lowercase()
        
        // Паттерны шума
        val noisePatterns = listOf(
            Regex("привет|здравствуй|пока|до свидания"),    // Приветствия
            Regex("спасибо|пожалуйста|извини"),             // Вежливости
            Regex("сегодня|вчера|завтра"),                  // Временные указатели без контекста
            Regex("холодно|жарко|дождь|снег"),              // Погода
            Regex("устал|хочу спать|пора спать"),           // Состояния
            Regex("окей|ладно|понятно|ясно"),               // Подтверждения
            Regex("ага|угу|да|нет")                         // Короткие ответы
        )
        
        // Короткие сообщения (менее 10 символов) обычно шум
        val isShortMessage = content.length < 10
        
        return noisePatterns.any { it.containsMatchIn(content) } || isShortMessage
    }
    
    /**
     * Проверяет, является ли сообщение маркером завершения темы.
     * Такие сообщения могут триггерить суммаризацию текущего кластера.
     */
    fun isTopicCompletionMarker(message: Message): Boolean {
        val content = message.content.lowercase()
        
        val completionMarkers = listOf(
            "ладно", "понятно", "ясно", "окей", "спасибо",
            "все ясно", "все понятно", "хорошо", "договорились",
            "отлично", "прекрасно", "замечательно"
        )
        
        return completionMarkers.any { content.contains(it) }
    }
    
    /**
     * Оценивает общую значимость сообщения по шкале 0-10.
     */
    fun evaluateSignificanceScore(message: Message): Int {
        var score = 0
        
        when {
            isCriticalMessage(message) -> score += 10
            isHighSignificanceMessage(message) -> score += 7
            isMediumSignificanceMessage(message) -> score += 5
            isNoiseMessage(message) -> score -= 3
        }
        
        // Дополнительные факторы
        val content = message.content
        
        // Длина сообщения (длинные сообщения обычно содержат больше информации)
        if (content.length > 100) score += 2
        
        // Наличие цифр и специальных символов (часто указывает на конкретные данные)
        if (content.any { it.isDigit() }) score += 1
        
        // Наличие вопросительных знаков (вопросы обычно менее информативны для памяти)
        if (content.contains('?')) score -= 1
        
        // Ограничиваем score в диапазоне 0-10
        return score.coerceIn(0, 10)
    }
    
    /**
     * Определяет, нужно ли немедленно извлекать факты из этого сообщения.
     */
    fun shouldExtractFactsImmediately(message: Message): Boolean {
        return isCriticalMessage(message) || isHighSignificanceMessage(message)
    }
    
    /**
     * Определяет, может ли это сообщение быть частью кластера для суммаризации.
     */
    fun isClusterCandidate(message: Message): Boolean {
        // Игнорируем шумовые сообщения и очень короткие
        return !isNoiseMessage(message) && message.content.length >= 15
    }
}