package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build-time guard for the copied JSON: {@link ConnectorPresetSeedReader#readAll()} parses and
 * validates every file under {@code seed/connectors}. Before the files are copied there is nothing to
 * read and the guard is a no-op; after a copy it is what fails the build on a malformed or
 * contradictory seed, without Spring or a database.
 */
@DisplayName("ConnectorPresetSeedReader — guard-тест ресурсов сида")
class ConnectorPresetSeedGuardTest {

    @Test
    @DisplayName("все файлы seed/connectors читаются и проходят валидацию")
    void allSeedFilesAreValid() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:seed/connectors/*/*.json");
        if (resources.length == 0) {
            return;
        }

        List<ConnectorPresetSeed> seeds = new ConnectorPresetSeedReader().readAll();

        assertNotNull(seeds);
        assertTrue(seeds.stream().allMatch(seed -> seed.code() != null && seed.availability() != null));
    }
}
