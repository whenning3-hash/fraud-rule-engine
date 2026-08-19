package za.co.fraudruleengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.entity.RuleConfigEntity;

import java.util.UUID;

@Repository
public interface RuleConfigJpaRepository extends JpaRepository<RuleConfigEntity, UUID> {
}
