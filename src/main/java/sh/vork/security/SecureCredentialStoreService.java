package sh.vork.security;

import org.springframework.stereotype.Service;

@Service
public class SecureCredentialStoreService {

    private final SecureCredentialStore secureCredentialStore;

    public SecureCredentialStoreService(SecureCredentialStore secureCredentialStore) {
        this.secureCredentialStore = secureCredentialStore;
    }

    public void saveSecret(VorkUser user, String name, String value) {
        secureCredentialStore.saveSecret(user, name, value);
    }

    public String getSecret(VorkUser user, String name) {
        return secureCredentialStore.getSecret(user, name);
    }
}