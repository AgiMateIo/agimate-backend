package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.ReplaceSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.SkillConnector;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillConnectorRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillConnectorService {

    private final SkillConnectorRepository skillConnectorRepository;
    private final ConnectorRepository connectorRepository;

    public List<SkillConnectorResponse> getAll(Skill skill) {
        return skillConnectorRepository.findBySkillId(skill.getId())
                .stream()
                .map(SkillConnectorResponse::from)
                .toList();
    }

    @Transactional
    public List<SkillConnectorResponse> replaceAll(Skill skill, ReplaceSkillConnectorsRequest request) {
        request.connectors().forEach(this::validate);
        request.connectors().forEach(r -> validateConnectorExists(r.connectorCode()));

        skillConnectorRepository.deleteBySkillId(skill.getId());

        List<SkillConnector> entities = request.connectors().stream()
                .map(req -> toEntity(req, skill))
                .toList();

        List<SkillConnector> saved = skillConnectorRepository.saveAll(entities);
        try {
            skillConnectorRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Duplicate connector binding in request");
        }

        log.info("Replaced connector bindings for skill id={}, count={}", skill.getId(), saved.size());
        return saved.stream().map(SkillConnectorResponse::from).toList();
    }

    @Transactional
    public SkillConnectorResponse addOne(Skill skill, SkillConnectorRequest request) {
        validate(request);
        validateConnectorExists(request.connectorCode());

        SkillConnector entity = toEntity(request, skill);
        try {
            entity = skillConnectorRepository.save(entity);
            skillConnectorRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Connector binding already exists");
        }

        log.info("Added connector binding for skill id={}: {}:{}", skill.getId(), request.connectorCode(), request.type());
        return SkillConnectorResponse.from(entity);
    }

    @Transactional
    public void delete(Skill skill, UUID connectorSkillId) {
        SkillConnector binding = skillConnectorRepository.findById(connectorSkillId)
                .orElseThrow(() -> new NotFoundStatusException("Connector binding not found"));

        if (!binding.getSkill().getId().equals(skill.getId())) {
            throw new NotFoundStatusException("Connector binding not found");
        }

        skillConnectorRepository.delete(binding);
        log.info("Deleted connector binding {} from skill id={}", connectorSkillId, skill.getId());
    }

    @Transactional
    public void cloneBindings(Skill source, Skill target) {
        List<SkillConnector> sourceBindings = skillConnectorRepository.findBySkillId(source.getId());

        List<SkillConnector> clonedBindings = sourceBindings.stream()
                .map(cs -> SkillConnector.builder()
                        .skill(target)
                        .userId(target.getUserId())
                        .connectorCode(cs.getConnectorCode())
                        .type(cs.getType())
                        .name(cs.getName())
                        .build())
                .toList();

        skillConnectorRepository.saveAll(clonedBindings);
        log.info("Cloned {} connector bindings from skill id={} to id={}", clonedBindings.size(), source.getId(), target.getId());
    }

    private void validate(SkillConnectorRequest request) {
        if (request.type() == null && request.name() != null) {
            throw new BadRequestStatusException("name must be null when type is null");
        }
    }

    private void validateConnectorExists(String connectorCode) {
        if (!connectorRepository.existsById(connectorCode)) {
            throw new NotFoundStatusException("Connector not found: " + connectorCode);
        }
    }

    private SkillConnector toEntity(SkillConnectorRequest request, Skill skill) {
        return SkillConnector.builder()
                .skill(skill)
                .userId(skill.getUserId())
                .connectorCode(request.connectorCode())
                .type(request.type())
                .name(request.name())
                .build();
    }
}
