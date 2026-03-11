package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.service.dto.IToolResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppApiService {

    private final CentrifugoService centrifugoService;


    public void pushToAgent(String agentId, IToolResult toolResult) {
        centrifugoService.publishMessage("agent:" + agentId, toolResult);
    }


}
