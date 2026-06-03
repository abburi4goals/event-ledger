package com.eventledger.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing so {@code @CreatedDate} / {@code @LastModifiedDate} on
 * {@code AuditableEntity} are populated automatically on persist and update.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
