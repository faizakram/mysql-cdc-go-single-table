package com.migration.platform.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;

/**
 * Runs the metadata store as an in-process PostgreSQL when {@code platform.metadata.embedded=true}
 * (the default), so the application starts with NO external database or Docker. Data persists in a
 * local directory across restarts. Set {@code platform.metadata.embedded=false} to use an external
 * managed Postgres (production) via the normal {@code spring.datasource.*} properties.
 */
@Configuration
@ConditionalOnProperty(name = "platform.metadata.embedded", havingValue = "true", matchIfMissing = true)
public class EmbeddedMetadataDbConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMetadataDbConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(
            @org.springframework.beans.factory.annotation.Value(
                    "${platform.metadata.embedded-data-dir:${user.home}/.migration-platform/pgdata}") String dataDir)
            throws IOException {
        File dir = new File(dataDir);
        //noinspection ResultOfMethodCallIgnored
        dir.getParentFile().mkdirs();
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(dir)
                .setCleanDataDirectory(false)   // persist metadata across restarts
                .start();
        log.info("Embedded metadata Postgres started on port {} (data dir: {})", pg.getPort(), dir);
        return pg;
    }

    @Bean
    @Primary
    public DataSource metadataDataSource(EmbeddedPostgres embeddedPostgres) {
        return embeddedPostgres.getPostgresDatabase();
    }
}
