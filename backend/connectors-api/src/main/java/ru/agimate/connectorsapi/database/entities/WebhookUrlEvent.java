package ru.agimate.connectorsapi.database.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "webhook_url_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookUrlEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "webhook_url_id", nullable = false)
    private Long webhookUrlId;

    @Column(name = "event_type", nullable = false, columnDefinition = "TEXT")
    private String eventType;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;
}
