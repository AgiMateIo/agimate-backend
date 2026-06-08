package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

@Entity
@Table(name = "waitlist_entries", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter
@Setter
public class WaitlistEntry extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, columnDefinition = "TEXT")
    private String email;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;
}
