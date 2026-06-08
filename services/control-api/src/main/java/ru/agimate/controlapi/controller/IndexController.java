package ru.agimate.controlapi.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.buildinfo.BuildInfoService;
import ru.agimate.common.rest.SuccessResponse;

@RestController
@RequestMapping("/")
@RequiredArgsConstructor
@Tag(name = "00 Index", description = "Index endpoint")
public class IndexController {

    private final BuildInfoService buildInfoService;

    @GetMapping("favicon.ico")
    public void returnEmptyFavicon() {
        // empty
    }

    @Operation(summary = "Returns up time info")
    @GetMapping
    public SuccessResponse<BuildInfoService.BuildInfo> index() {
        return SuccessResponse.ok(buildInfoService.getBuildInfo());
    }
}
