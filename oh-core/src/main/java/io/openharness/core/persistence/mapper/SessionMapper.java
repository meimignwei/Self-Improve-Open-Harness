package io.openharness.core.persistence.mapper;

import io.openharness.core.persistence.model.Session;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SessionMapper {
    Session findById(String id);
    List<Session> findAll();
    void insert(Session session);
    void update(Session session);
    void deleteById(String id);
}
