package sh.vork.orm.nitrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dizitart.no2.Nitrite;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * Spring-managed factory for creating Nitrite-backed {@link DatabaseRepository} instances.
 *
 * <p>Active only when {@code db.backend=nitrite}.
 */
@Component
@ConditionalOnProperty(name = "db.backend", havingValue = "nitrite", matchIfMissing = true)
public class NitriteRepositoryFactory implements RepositoryFactory {

    private final Nitrite       nitriteDb;
    private final ObjectMapper  objectMapper;

    public NitriteRepositoryFactory(Nitrite nitriteDb, ObjectMapper objectMapper) {
        this.nitriteDb    = nitriteDb;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
        return new NitriteRepository<>(entityClass, nitriteDb, objectMapper);
    }
}
