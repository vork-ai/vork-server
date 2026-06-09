package sh.vork.ssl;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers the {@link DatabaseRepository} bean for {@link SslCertificateConfig}.
 * Also enables Spring's {@code @Scheduled} support (required for certificate renewal).
 */
@Configuration
@EnableScheduling
public class SslConfig {

    @Bean
    public DatabaseRepository<SslCertificateConfig> sslCertificateConfigRepository(RepositoryFactory factory) {
        return factory.create(SslCertificateConfig.class);
    }
}
