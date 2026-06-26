# Gemma 4 Аудио-ввод — план реализации

## Что уже есть (инфраструктура)

- ✅ `AttachmentType.AUDIO` (enum) — существует
- ✅ `Attachment.isAudio` — геттер
- ✅ `Attachment.determineType()` — определяет по mime-типу (`audio/*`) и расширениям (mp3, wav, ogg, m4a, flac)
- ✅ Изображения работают — `handleLocalInference` получает их через `LiteRTInput.addImage()`
- ✅ `SmartRouter` перекидывает мультимодальные запросы на Gemma

## Что не работает

- ❌ `Attachment.toContentPart()` возвращает `null` для AUDIO
- ❌ `AttachmentProcessor` пишет: *«[файл]: аудио файл (транскрипция не поддерживается)»*
- ❌ `handleLocalInference` не обрабатывает аудио-attachment для LiteRT
- ❌ LiteRTInput не получает аудио-PCM-данные

## Технические детали

### Gemma 4 E2B/E4B и аудио

Согласно спецификации, модели принимают:
- **Формат:** аудиопотоки / сырые PCM-данные (запекаемые в контекст)
- **Особенность:** сквозная обработка без внешнего STT/Whisper — модель сама анализирует речь, интонации, шумы

### LiteRT API для аудио

```kotlin
// Гипотетический API (на основе LiteRTInput):
inferenceInput.addAudio(pcmFloatArray: FloatArray, sampleRate: Int, channels: Int)
// или через TensorBuffer
val audioTensor = TensorBuffer.createFixedSize(intArrayOf(1, audioLength), DataType.FLOAT32)
audioTensor.loadArray(pcmData)
inferenceInput.createFrom(audioTensor)
```

*Точный API нужно уточнить по документации `com.google.ai.edge.litertlm`.*

## Этапы реализации

### Этап 1 — Декодирование аудиофайлов в PCM

**Файлы:**
- `AttachmentProcessor.kt` — обновить секцию `isAudio`
- `LocalAiInteraction.kt` — добавить метод `generateWithAudio()`
- `AiRepository.kt` — обновить `handleLocalInference()` для AUDIO attachments

**Что нужно сделать:**

1. **Декодировать** MP3/WAV/OGG → PCM float array
   ```kotlin
   fun decodeAudioToPcm(uri: Uri, context: Context): FloatArray? {
       val extractor = MediaExtractor()
       extractor.setDataSource(context, uri, null)
       // найти аудиотрек
       // MediaCodec → декодировать → PCM float array
       // downmix до моно, если стерео
       // ресемплинг до 16kHz (если нужно)
   }
   ```

2. **Создать LiteRTInput с аудио**
   ```kotlin
   // Аналогично addImage, но для аудио
   val input = LiteRTInput.create(...)
   input.addAudio(pcmData, sampleRate = 16000, channels = 1)
   ```

3. **Обновить `toContentPart()`** — возвращать PCM вместо null

4. **Обновить `AttachmentProcessor`** — заменить заглушку на реальную обработку

### Этап 2 — Прямая запись с микрофона

**Файлы:**
- `VoiceRecognitionViewModel.kt` — опциональный путь без STT
- `WakeWordService.kt` — альтернативный поток: AudioRecord → Gemma

**Что нужно сделать:**

1. Запись PCM через `AudioRecord` (16kHz, mono, 16-bit)
2. VAD (Voice Activity Detection) — определить конец речи
3. Отправить PCM напрямую в Gemma через `LiteRTInput.addAudio()`
4. Без `SpeechRecognizer` — модель сама транскрибирует и понимает

### Этап 3 — Тюнинг

- Настроить Visual Token Budget для аудио (вероятно, будет свой аналог)
- Определить оптимальный sample rate (16kHz, 44.1kHz)
- Тестирование качества распознавания: Gemma vs SpeechRecognizer

## Зависимости

- LiteRT LM (`com.google.ai.edge.litertlm:litertlm-android`) — основной API для инференса
- `MediaExtractor` + `MediaCodec` — встроенные в Android, для декодирования аудио
- `AudioRecord` — встроенный, для захвата с микрофона

## Стек

```
┌──────────┐    ┌──────────────┐    ┌───────────────┐    ┌─────────┐
│ Аудиофайл│───→│ MediaExtractor│───→│ PCM float[]   │───→│ LiteRT  │
│ (MP3/WAV)│    │ + MediaCodec │    │ (16kHz, mono) │    │ Input   │
└──────────┘    └──────────────┘    └───────────────┘    └────┬────┘
                                                              │
┌──────────┐    ┌──────────────┐                              │
│ Микрофон │───→│ AudioRecord  │───→ PCM float[] ─────────────┘
└──────────┘    └──────────────┘    (16kHz, mono)         Gemma 4
                                                          (E2B/E4B)
                                                              │
                                                         ┌────▼────┐
                                                         │ Текст   │
                                                         │ ответ   │
                                                         └─────────┘
```

## Ключевые вопросы (нужно исследовать)

1. Как именно Gemma 4 E2B принимает аудио через LiteRT LM API?
   - Есть ли готовый метод `addAudio()` или нужно через сырой тензор?
   - Какой формат PCM ожидается (float, int16, sample rate)?
2. В какой budget укладывается аудио?
3. Поддерживается ли одновременная передача изображения + аудио?
4. Работает ли аудио через CPU-делегат или только GPU/NNAPI?

---

*План составлен 25 июня 2026. Начать с Этапа 1 — декодирование аудиофайлов.*
