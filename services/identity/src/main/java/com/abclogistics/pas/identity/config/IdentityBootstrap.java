package com.abclogistics.pas.identity.config;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Department;
import com.abclogistics.pas.identity.domain.Role;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import com.abclogistics.pas.identity.repository.DepartmentRepository;
import com.abclogistics.pas.identity.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentityBootstrap.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties props;

    public IdentityBootstrap(AppUserRepository users, RoleRepository roles,
                             DepartmentRepository departments, PasswordEncoder passwordEncoder,
                             AdminBootstrapProperties props) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }

        Department it = departments.findByCode("IT")
                .orElseThrow(() -> new IllegalStateException("Seed department IT missing"));
        Role admin = roles.findByCode("SYSTEM_ADMIN")
                .orElseThrow(() -> new IllegalStateException("Seed role SYSTEM_ADMIN missing"));

        AppUser user = AppUser.create(
                props.username(),
                props.email(),
                passwordEncoder.encode(props.password()),
                props.fullName(),
                it);
        user.getRoles().add(admin);
        users.save(user);

        log.info("Bootstrapped admin user '{}' with role SYSTEM_ADMIN", props.username());
    }
}
