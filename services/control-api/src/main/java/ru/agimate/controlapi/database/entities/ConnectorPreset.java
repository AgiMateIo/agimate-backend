package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;
import ru.agimate.controlapi.database.model.PresetHeaderGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A connector preset — a projection of one file under {@code seed/connectors/<connectorCode>/}.
 *
 * <p>Unique by {@code code}; the pair {@code (connector_code, identity)} is unique among live rows
 * only — the partial index {@code uq_connector_presets_connector_code_identity ... WHERE
 * source_present = TRUE}, which JPA cannot express. The partial shape lets a removed file hide its
 * row and a renamed file reuse the identity.
 *
 * <p>{@code auth_methods} already contains only supported methods: {@code OAUTH} is filtered out at
 * seed time when the server does not declare CIMD. The raw probe inputs that produce
 * {@code availability} stay in the JSON; the catalogue query reads this one column.
 */
@Entity
@Table(name = "connector_presets", uniqueConstraints =
        @UniqueConstraint(name = "uq_connector_presets_code", columnNames = "code"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorPreset extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Stable slug from the file name — the deep-link and React key. */
    @Column(name = "code", nullable = false, columnDefinition = "TEXT")
    private String code;

    /** The connector this preset belongs to; in v1 always {@code mcp}. */
    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /** Default-language name; the text table carries translations. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "icon_url", columnDefinition = "TEXT")
    private String iconUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, columnDefinition = "TEXT")
    private ContentCategory category;

    /** ISO 3166-1 alpha-2; NULL — the country was not researched. */
    @Column(name = "jurisdiction", columnDefinition = "TEXT")
    private String jurisdiction;

    /** Already filtered to what the platform supports; see the class javadoc. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_methods", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private List<ConnectorPresetAuth> authMethods = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false, columnDefinition = "TEXT")
    private ConnectorPresetAvailability availability;

    /** "Where to get a token" link; not part of the form. */
    @Column(name = "docs_url", columnDefinition = "TEXT")
    private String docsUrl;

    /** Non-secret form values; for MCP {@code {"url": ...}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private Map<String, String> params = new LinkedHashMap<>();

    /** The working endpoint, exactly what MCP puts into {@code subCode} — {@code params.url.trim()}. */
    @Column(name = "identity", nullable = false, columnDefinition = "TEXT")
    private String identity;

    /** Overrides of the connector's credential fields; the connector stays the source of truth. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fields", columnDefinition = "JSONB")
    private Map<String, PresetFieldOverride> fields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "header_fields", columnDefinition = "JSONB")
    private List<PresetHeaderField> headerFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "header_groups", columnDefinition = "JSONB")
    private List<PresetHeaderGroup> headerGroups;

    /** Installation switch; the seed never overwrites it. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** The file is gone: the row is hidden, not deleted, so a returning file keeps its history. */
    @Column(name = "source_present", nullable = false)
    @Builder.Default
    private boolean sourcePresent = true;
}
