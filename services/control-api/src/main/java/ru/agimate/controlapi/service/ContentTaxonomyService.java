package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.controller.manage.dto.TaxonomyResponse;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentTag;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;
import ru.agimate.controlapi.service.seed.TaxonomyTexts;

import java.util.Arrays;
import java.util.List;

/**
 * The catalogue's vocabulary for the frontend. Order is the order of declaration in the enums: it is
 * the order the sections are shown in, and keeping it in one place is why there is no {@code sortOrder}
 * here. See {@code docs/decisions/content-taxonomy.md}.
 */
@Service
@RequiredArgsConstructor
public class ContentTaxonomyService {

    private final TaxonomyTexts texts;
    private final ContentLanguageResolver contentLanguageResolver;

    public TaxonomyResponse taxonomy() {
        ContentLanguage language = contentLanguageResolver.requestLanguage();
        List<TaxonomyResponse.Item> categories = Arrays.stream(ContentCategory.values())
                .map(category -> new TaxonomyResponse.Item(category.name(), texts.label(language, category)))
                .toList();

        List<TaxonomyResponse.TagGroup> groups = Arrays.stream(ContentTag.Group.values())
                .map(group -> new TaxonomyResponse.TagGroup(group.name(), texts.label(language, group),
                        Arrays.stream(ContentTag.values())
                                .filter(tag -> tag.getGroup() == group)
                                .map(tag -> new TaxonomyResponse.Item(tag.name(), texts.label(language, tag)))
                                .toList()))
                .toList();

        return new TaxonomyResponse(categories, groups);
    }
}
