package io.openharness.core.persistence.mapper;

import io.openharness.core.persistence.model.InteractionRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface InteractionMapper {
    void insert(InteractionRecord record);
    List<InteractionRecord> findBySessionId(String sessionId);
    List<InteractionRecord> findByEvolutionVersion(String evolutionVersion);
}
