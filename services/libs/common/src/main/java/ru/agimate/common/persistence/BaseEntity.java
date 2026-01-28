package ru.agimate.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Base entity class providing automatic timestamp management for all entities.
 * <p>
 * All JPA entities should extend this class to automatically get:
 * <ul>
 *   <li>{@code createdAt} - timestamp of entity creation (set once, never updated)</li>
 *   <li>{@code updatedAt} - timestamp of last modification (updated automatically)</li>
 * </ul>
 * <p>
 * These fields are managed by Hibernate and do not require manual setting.
 */
@Getter
@Setter
@MappedSuperclass
@FieldNameConstants
public abstract class BaseEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;
}
