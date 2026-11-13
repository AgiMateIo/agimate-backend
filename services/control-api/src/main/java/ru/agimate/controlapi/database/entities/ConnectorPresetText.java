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
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.UUID;

/**
 * A preset's text in a non-default language. English lives in {@link ConnectorPreset} and is the
 * fallback; a null field means the translation is missing, not empty.
 */
@Entity
@Table(name = "connector_preset_texts", uniqueConstraints =
        @UniqueConstraint(name = "uq_connector_preset_texts_preset_id_language",
                columnNames = {"preset_id", "language"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorPresetText extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Flat FK: the preset, as everywhere in the project, is referenced by id, not an association. */
    @Column(name = "preset_id", nullable = false)
    private UUID presetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, columnDefinition = "TEXT")
    private ContentLanguage language;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
