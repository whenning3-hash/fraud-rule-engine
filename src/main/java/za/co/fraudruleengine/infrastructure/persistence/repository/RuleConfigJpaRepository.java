package za.co.fraudruleengine.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RuleConfigJpaRepository extends JpaRepository<RuleConfigEntity, UUID> {
    Optional<RuleConfigEntity> findByRuleName(String ruleName);
}
