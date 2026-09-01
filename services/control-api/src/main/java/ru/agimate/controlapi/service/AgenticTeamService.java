package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgenticTeamResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgenticTeamRequest;
import ru.agimate.controlapi.controller.manage.dto.PatchAgenticTeamRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgenticTeamRequest;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgenticTeamService {

    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentRepository agentRepository;
    private final BoardRepository boardRepository;

    public List<AgenticTeamResponse> getAllForUser(UUID userId) {
        return agenticTeamRepository.findByUserId(userId).stream()
                .map(AgenticTeamResponse::from)
                .toList();
    }

    public AgenticTeamResponse getById(UUID id, UUID userId) {
        AgenticTeam team = agenticTeamRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return AgenticTeamResponse.from(team);
    }

    @Transactional
    public AgenticTeamResponse create(UUID userId, CreateAgenticTeamRequest request) {
        if (agenticTeamRepository.existsByUserIdAndName(userId, request.name())) {
            throw new BadRequestStatusException("Team with this name already exists");
        }

        AgenticTeam team = AgenticTeam.builder()
                .name(request.name())
                .description(request.description())
                .userId(userId)
                .build();
        team = agenticTeamRepository.save(team);

        log.info("Created agentic team '{}' for user={}", request.name(), userId);
        return AgenticTeamResponse.from(team);
    }

    /**
     * Primitive-args counterpart of {@link #create(UUID, CreateAgenticTeamRequest)} for the platform
     * connector (no controller DTO in the connector layer). Same body, returns the entity.
     */
    @Transactional
    public AgenticTeam create(UUID userId, String name, String description) {
        if (agenticTeamRepository.existsByUserIdAndName(userId, name)) {
            throw new BadRequestStatusException("Team with this name already exists");
        }

        AgenticTeam team = AgenticTeam.builder()
                .name(name)
                .description(description)
                .userId(userId)
                .build();
        team = agenticTeamRepository.save(team);

        log.info("Created agentic team '{}' for user={}", name, userId);
        return team;
    }

    @Transactional
    public AgenticTeamResponse update(UUID id, UUID userId, UpdateAgenticTeamRequest request) {
        AgenticTeam team = agenticTeamRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        if (!team.getName().equals(request.name())
                && agenticTeamRepository.existsByUserIdAndName(userId, request.name())) {
            throw new BadRequestStatusException("Team with this name already exists");
        }

        team.setName(request.name());
        team.setDescription(request.description());
        team = agenticTeamRepository.save(team);

        log.info("Updated agentic team id={}", id);
        return AgenticTeamResponse.from(team);
    }

    /**
     * The partial counterpart of {@link #update}: {@code null} means "the field was not sent" and keeps
     * its value, a blank string is the explicit erase. The name is checked for a clash only when it
     * actually arrived — a PATCH that touches the description alone must not fail on a name it did not send.
     */
    @Transactional
    public AgenticTeamResponse patch(UUID id, UUID userId, PatchAgenticTeamRequest request) {
        AgenticTeam team = agenticTeamRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ValidationErrorStatusException("name", "Name must not be blank");
            }
            if (!team.getName().equals(request.name())
                    && agenticTeamRepository.existsByUserIdAndName(userId, request.name())) {
                throw new BadRequestStatusException("Team with this name already exists");
            }
            team.setName(request.name());
        }
        if (request.description() != null) {
            team.setDescription(request.description().isBlank() ? null : request.description());
        }
        team = agenticTeamRepository.save(team);

        log.info("Patched agentic team id={}", id);
        return AgenticTeamResponse.from(team);
    }

    /**
     * Primitive-args counterpart of {@link #patch(UUID, UUID, PatchAgenticTeamRequest)} for the
     * platform connector (no controller DTO in the connector layer). Same PATCH semantics:
     * {@code null} keeps the field, a blank {@code name} is rejected, a blank {@code description}
     * clears it. Returns the entity.
     */
    @Transactional
    public AgenticTeam patch(UUID id, UUID userId, String name, String description) {
        AgenticTeam team = agenticTeamRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        if (name != null) {
            if (name.isBlank()) {
                throw new ValidationErrorStatusException("name", "Name must not be blank");
            }
            if (!team.getName().equals(name)
                    && agenticTeamRepository.existsByUserIdAndName(userId, name)) {
                throw new BadRequestStatusException("Team with this name already exists");
            }
            team.setName(name);
        }
        if (description != null) {
            team.setDescription(description.isBlank() ? null : description);
        }
        team = agenticTeamRepository.save(team);

        log.info("Patched agentic team id={}", id);
        return team;
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        AgenticTeam team = agenticTeamRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        if (boardRepository.existsByAgenticTeam(team)) {
            throw new BadRequestStatusException("Cannot delete team that has a board");
        }

        if (agentRepository.existsByAgenticTeamId(team.getId())) {
            throw new BadRequestStatusException("Cannot delete team that has agents assigned to it");
        }

        agenticTeamRepository.delete(team);
        log.info("Deleted agentic team id={}", id);
    }
}
