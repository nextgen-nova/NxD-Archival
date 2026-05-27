package com.swift.platform.repository;

import com.swift.platform.model.SwiftMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SwiftMessageRepository extends MongoRepository<SwiftMessage, String> {}
