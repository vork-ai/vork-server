package sh.vork.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import sh.vork.orm.DatabaseRepository;
import sh.vork.security.VorkUser;

class SetupServiceFreshInstallTest {

    @Test
    void isSetupRequired_whenDatabaseNotConfiguredAndNoUsers_returnsTrue() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        DatabaseSetupService databaseSetupService = mock(DatabaseSetupService.class);

        when(databaseSetupService.isDatabaseConfigured()).thenReturn(false);
        when(userRepo.count()).thenReturn(0L);

        SetupService setupService = new SetupService(userRepo, encoder, databaseSetupService);

        assertTrue(setupService.isSetupRequired(),
                "Fresh install should require setup when database is not configured and no users exist");
    }

    @Test
    void isSetupRequired_whenDatabaseNotConfiguredButUsersExist_returnsFalseForLegacyInstall() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        DatabaseSetupService databaseSetupService = mock(DatabaseSetupService.class);

        when(databaseSetupService.isDatabaseConfigured()).thenReturn(false);
        when(userRepo.count()).thenReturn(1L);

        SetupService setupService = new SetupService(userRepo, encoder, databaseSetupService);

        assertFalse(setupService.isSetupRequired(),
                "Legacy install with existing users should not be redirected into setup");
    }
}
