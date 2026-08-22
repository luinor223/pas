package com.abclogistics.pas.identity.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected AppUser() { }

    public static AppUser create(String username, String email, String passwordHash,
                                 String fullName, Department department) {
        AppUser u = new AppUser();
        u.username = username;
        u.email = email;
        u.passwordHash = passwordHash;
        u.fullName = fullName;
        u.department = department;
        u.status = UserStatus.ACTIVE;
        return u;
    }

    public boolean isActive() { return status == UserStatus.ACTIVE; }
    public void recordLogin(Instant at) { this.lastLoginAt = at; }
    public void disable() { this.status = UserStatus.DISABLED; }
    public void enable() { this.status = UserStatus.ACTIVE; }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public UserStatus getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Set<Role> getRoles() { return roles; }
}
