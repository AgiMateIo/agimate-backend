package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.dto.request.CallMethodRequest;
import ru.agimate.connectorsapi.controller.dto.response.CallResultResponse;
import ru.agimate.connectorsapi.service.CallService;

import java.util.UUID;

@RestController
@RequestMapping(MobileCallController.PATH)
@RequiredArgsConstructor
@Tag(name = "Call", description = "Execute mobile methods")
public class MobileCallController {

    public static final String PATH = CallController.PATH + "/mobile";

    public SuccessResponse<String> getConnectedDevice() {
        UUID apiKeyUserPubId = SecurityUtils.getApiKeyUserPubId();

        return SuccessResponse.ok(apiKeyUserPubId.toString());
    }

}
