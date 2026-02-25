package com.example.turnover.repository;

import com.example.turnover.model.entity.Turnover;
import com.example.turnover.model.enums.TurnoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TurnoverRepository extends JpaRepository<Turnover, UUID> {
    Optional<Turnover> findByPropertyIdAndStatus(String propertyId, TurnoverStatus status);
}
