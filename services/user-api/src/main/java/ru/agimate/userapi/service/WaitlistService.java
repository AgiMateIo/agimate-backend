package ru.agimate.userapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.userapi.database.entities.WaitlistEntry;
import ru.agimate.userapi.database.repositories.WaitlistEntryRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitlistService {

    private final WaitlistEntryRepository waitlistEntryRepository;

    @Transactional
    public WaitlistEntry create(String email, String name, String message) {
        if (waitlistEntryRepository.existsByEmail(email)) {
            throw new ValidationErrorStatusException("email", "This email is already registered in the waitlist");
        }

        var entry = new WaitlistEntry();
        entry.setEmail(email);
        entry.setName(name);
        entry.setMessage(message);

        return waitlistEntryRepository.save(entry);
    }
}
