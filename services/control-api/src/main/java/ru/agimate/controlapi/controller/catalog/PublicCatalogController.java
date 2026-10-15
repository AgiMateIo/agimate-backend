package ru.agimate.controlapi.controller.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorRef;
import ru.agimate.controlapi.controller.catalog.dto.CatalogPresetResponse;
import ru.agimate.controlapi.controller.catalog.dto.CatalogSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.TaxonomyResponse;
import ru.agimate.controlapi.service.ContentTaxonomyService;
import ru.agimate.controlapi.service.PublicCatalogService;

import java.time.Duration;
import java.util.List;

/**
 * The catalogue without sign-in, for the site's pages. Every answer is in the request's language and
 * cacheable by anyone: it is the same for every visitor, and {@code Vary: Accept-Language} keeps the
 * languages apart.
 */
@RestController
@RequestMapping(PublicCatalogController.PATH)
@RequiredArgsConstructor
@Tag(name = "Public catalog", description = "Agent presets and skills without authentication")
public class PublicCatalogController {

    public static final String PATH = "/public/catalog";

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final PublicCatalogService publicCatalogService;
    private final ContentTaxonomyService contentTaxonomyService;

    @Operation(summary = "Enabled agent presets")
    @GetMapping("/agent-presets/")
    public ResponseEntity<SuccessResponse<List<CatalogPresetResponse>>> getPresets() {
        return cached(publicCatalogService.presets());
    }

    @Operation(summary = "Agent preset by code")
    @GetMapping("/agent-presets/{name}")
    public ResponseEntity<SuccessResponse<CatalogPresetResponse>> getPreset(@PathVariable String name) {
        return cached(publicCatalogService.preset(name));
    }

    @Operation(summary = "The platform's public skills")
    @GetMapping("/skills/")
    public ResponseEntity<SuccessResponse<List<CatalogSkillResponse>>> getSkills() {
        return cached(publicCatalogService.skills());
    }

    @Operation(summary = "Skill by code")
    @GetMapping("/skills/{name}")
    public ResponseEntity<SuccessResponse<CatalogSkillResponse>> getSkill(@PathVariable String name) {
        return cached(publicCatalogService.skill(name));
    }

    @Operation(summary = "Categories and tags with labels")
    @GetMapping("/taxonomy/")
    public ResponseEntity<SuccessResponse<TaxonomyResponse>> getTaxonomy() {
        return cached(contentTaxonomyService.taxonomy());
    }

    @Operation(summary = "Connector names and descriptions")
    @GetMapping("/connectors/")
    public ResponseEntity<SuccessResponse<List<CatalogConnectorRef>>> getConnectors() {
        return cached(publicCatalogService.connectors());
    }

    private static <T> ResponseEntity<SuccessResponse<T>> cached(T body) {
        return ResponseEntity.ok().cacheControl(CACHE).body(SuccessResponse.ok(body));
    }
}
