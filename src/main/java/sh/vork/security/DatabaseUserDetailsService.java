package sh.vork.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import sh.vork.database.DatabaseRepository;

/**
 * Database-backed UserDetailsService.
 * Loads user credentials from MongoDB VorkUser collection.
 * On first run (empty DB), seeds with default admin/user accounts.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final DatabaseRepository<VorkUser> userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public DatabaseUserDetailsService(DatabaseRepository<VorkUser> userRepository,
                                     PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        seedDefaultUsersIfEmpty();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        VorkUser vorkUser = userRepository.get(username);
        if (vorkUser == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        return User.withUsername(vorkUser.uuid())
                .password(vorkUser.passwordHash())
                .roles(vorkUser.role().replace("ROLE_", ""))
                .build();
    }

    /**
     * Seeds the database with default admin and user accounts if empty.
     * Returns true if seeding occurred.
     */
    private boolean seedDefaultUsersIfEmpty() {
        try {
            long count = userRepository.count();
            if (count > 0) {
                return false;
            }

            // Create default admin user
            VorkUser admin = new VorkUser(
                "admin",
                passwordEncoder.encode("admin123"),
                "ADMIN",
                System.currentTimeMillis(),
                System.currentTimeMillis()
            );
            userRepository.save(admin);

            // Create default user
            VorkUser user = new VorkUser(
                "user",
                passwordEncoder.encode("user123"),
                "USER",
                System.currentTimeMillis(),
                System.currentTimeMillis()
            );
            userRepository.save(user);

            return true;
        } catch (Exception e) {
            // If seeding fails, continue (in-memory config will be used)
            return false;
        }
    }

    /**
     * Public method to update a user's password.
     * Called by ChangePasswordController.
     * Returns true if update succeeded.
     */
    public boolean updatePassword(String username, String newPassword) {
        VorkUser user = userRepository.get(username);
        if (user == null) {
            return false;
        }

        VorkUser updated = new VorkUser(
            user.uuid(),
            passwordEncoder.encode(newPassword),
            user.role(),
            user.createdAt(),
            System.currentTimeMillis()
        );
        userRepository.save(updated);
        return true;
    }
}
