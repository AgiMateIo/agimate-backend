package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.connectorsapi.controller.manage.dto.response.EventDescriptionResponse;
import ru.agimate.connectorsapi.database.entities.EventDescription;
import ru.agimate.connectorsapi.database.repositories.EventDescriptionRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventDescriptionService {

    private final EventDescriptionRepository eventDescriptionRepository;

    public List<EventDescriptionResponse> findAll() {
        return eventDescriptionRepository.findAllOrdered().stream()
                .map(EventDescriptionResponse::from)
                .toList();
    }

    public List<EventDescriptionResponse> findByEventTypeLike(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return findAll();
        }
        return eventDescriptionRepository.findByEventTypeLike(pattern).stream()
                .map(EventDescriptionResponse::from)
                .toList();
    }

    @Transactional
    public EventDescriptionResponse create(String eventType, String title, String description) {
        EventDescription event = EventDescription.builder()
                .eventType(eventType)
                .title(title)
                .description(description)
                .build();
        EventDescription saved = eventDescriptionRepository.save(event);
        log.info("Created event description: {}", eventType);
        return EventDescriptionResponse.from(saved);
    }
}
