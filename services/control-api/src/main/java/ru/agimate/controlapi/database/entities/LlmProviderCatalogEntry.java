package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A known LLM provider offered as a prefill for the «add a provider» form: what to put in
 * {@code base_url}, which media dialect the gateway speaks, which models to start from. Seeded from
 * {@code resources/seed/llm-providers.yaml} on every start.
 *
 * <p>The catalogue is a suggestion and nothing else — it is read when a form is opened and never at
 * call time, so the working configuration stays entirely in {@link LlmProvider}. That is what
 * separates it from the {@code base_url}-prefix table deferred in
 * {@code docs/decisions/media-transport.md}: no URL normalisation, no silent mismatch, and an error
 * surfaces while the user is still filling in the form.
 *
 * <p>Ownership is split between the seed and the installation — see {@link #enabled}.
 */
@Entity
@Table(name = "llm_provider_catalog", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_provider_catalog_code", columnNames = {"code"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderCatalogEntry extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Key of the seed entry ({@code openrouter}, {@code polza}); the upsert addresses rows by it. */
    @Column(name = "code", nullable = false, columnDefinition = "TEXT")
    private String code;

    /** Brand name for the picker — never translated, hence no bundle key of its own. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, columnDefinition = "TEXT")
    private LlmProviderType providerType;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    /** The dialect this gateway speaks for images; {@code null} — the default {@code CHAT_MODALITIES}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "media_transport", columnDefinition = "TEXT")
    private MediaTransportType mediaTransport;

    /**
     * Models to start from, per purpose. A suggestion for the form, so — unlike
     * {@link LlmProvider#getPurposePriority()} — nothing resolves against it and a stale id costs
     * nothing until the user submits the form. Several models per purpose on purpose: the resolver
     * keeps the declared order and skips registry rows marked UNAVAILABLE, so a model that dies is
     * a step down the list rather than a failure.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "purpose_priority", columnDefinition = "JSONB")
    private Map<LlmPurpose, List<String>> purposePriority;

    /** Where the user gets an API key — the question that stops an onboarding cold. */
    @Column(name = "api_key_url", columnDefinition = "TEXT")
    private String apiKeyUrl;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    /**
     * Whether the entry is offered. The one field the seed never writes: an installation that
     * switched a recommendation off keeps it off across upgrades, while its content goes on being
     * updated. Everything else is overwritten on every start — the database is the switch here, not
     * the editor.
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
