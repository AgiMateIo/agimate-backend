package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.TaxonomyResponse;
import ru.agimate.controlapi.service.ContentTaxonomyService;

@RestController
@RequestMapping(ManageTaxonomyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Taxonomy", description = "Vocabulary of the skill and preset catalogue")
public class ManageTaxonomyController {

    public static final String PATH = "/manage/taxonomy";

    private final ContentTaxonomyService contentTaxonomyService;

    @Operation(summary = "Categories and tags with localised labels — the codes used by "
            + "GET /manage/skills/ filters and returned on skills and presets")
    @GetMapping("/")
    public SuccessResponse<TaxonomyResponse> getTaxonomy() {
        return SuccessResponse.ok(contentTaxonomyService.taxonomy());
    }
}
