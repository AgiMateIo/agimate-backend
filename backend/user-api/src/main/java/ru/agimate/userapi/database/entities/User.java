package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    
    @Column(name = "pub_id", unique = true, nullable = false)
    private UUID pubId = UUIDUtils.generateUUIDv8();
    
    @Column(name = "email", nullable = false, unique = true, columnDefinition = "TEXT")
    private String email;
    
    @Column(name = "first_name", columnDefinition = "TEXT")
    private String firstName;
    
    @Column(name = "last_name", columnDefinition = "TEXT")
    private String lastName;
    
    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    // Bi-directional relationship with OAuth accounts
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<UserOAuthAccount> oauthAccounts = new ArrayList<>();
    
    // Constructors
    public User() {}
    
    public User(String email, String firstName, String lastName, String displayName) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.displayName = displayName;
    }
}