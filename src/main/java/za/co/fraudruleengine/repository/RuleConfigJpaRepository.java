package za.co.fraudruleengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.entity.RuleConfigEntity;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link RuleConfigEntity} — the persistent store for
 * per-rule configuration records (enabled flag, risk weight, and parameter map).
 *
 * <p>Inherits the standard CRUD operations from {@link JpaRepository}:
 * {@code findById}, {@code findAll}, {@code save}, {@code delete}, and their pageable
 * variants.  No custom queries are required for the current feature set — rule lookups
 * are all by primary key (UUID) and full-table list.
 *
 * <p>Flyway migration {@code V3__seed_rules.sql} populates the initial rule set on first
 * startup so that the engine is immediately operational without manual data entry.
 */
@Repository
public interface RuleConfigJpaRepository extends JpaRepository<RuleConfigEntity, UUID> {
}
