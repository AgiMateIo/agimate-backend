package ru.agimate.controlapi.database.enums;

/**
 * Назначение LLM-биндинга агента ({@code agent_llms.purpose}): какую роль модель играет для агента.
 * {@link #CHAT} — основная модель агентного цикла (её выдаёт {@code GetLlmCredentials});
 * остальные — модели-инструменты медиа-коннектора, резолвятся по назначению с фолбэком на
 * капабилити-матч по реестру ({@code input/output_modalities}).
 */
public enum LlmPurpose {

    /** Основная chat-модель агентного цикла. */
    CHAT,

    /** Генерация/редактирование изображений ({@code output_modalities ⊇ ["image"]}). */
    IMAGE,

    /** Зрение: описание изображения по файлу ({@code input_modalities ⊇ ["image"]}). */
    VISION,

    /** Распознавание аудио/голосовых ({@code input_modalities ⊇ ["audio"]}). */
    AUDIO_IN,

    /** Синтез речи ({@code output_modalities ⊇ ["audio"]}). */
    AUDIO_OUT
}
