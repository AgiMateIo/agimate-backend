package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.AgenticTeamResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgenticTeamRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgenticTeamRequest;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;
import ru.agimate.deviceapi.database.repositories.BoardRepository;

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
