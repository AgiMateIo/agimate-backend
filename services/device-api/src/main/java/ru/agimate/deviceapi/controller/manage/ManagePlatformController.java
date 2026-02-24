package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.PlatformResponse;
import ru.agimate.deviceapi.database.entities.Platform;
import ru.agimate.deviceapi.database.repositories.PlatformRepository;

import java.util.List;

@RestController
@RequestMapping(ManagePlatformController.PATH)
@RequiredArgsConstructor
@Tag(name = "Platforms", description = "Platform catalog")
public class ManagePlatformController {

    public static final String PATH = "/manage/platforms";

    private final PlatformRepository platformRepository;

    @Operation(summary = "Get all available platforms")
    @GetMapping("/")
    public SuccessResponse<List<PlatformResponse>> getPlatforms() {
        var platforms = platformRepository.findAllEnabled().stream()
                .map(PlatformResponse::from)
                .toList();
        return SuccessResponse.ok(platforms);
    }

    @Operation(summary = "Get platform details by code")
    @GetMapping("/{code}")
    public SuccessResponse<PlatformResponse> getPlatform(@PathVariable String code) {
        var platform = platformRepository.findByCode(code)
                .filter(Platform::getEnabled)
                .orElseThrow(() -> new NotFoundStatusException("Platform not found: " + code));
        return SuccessResponse.ok(PlatformResponse.from(platform));
    }
}
