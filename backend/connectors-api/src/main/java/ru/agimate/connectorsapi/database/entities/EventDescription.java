package ru.agimate.connectorsapi.database.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "event_descriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "event_type", unique = true, nullable = false, columnDefinition = "TEXT")
    private String eventType;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
