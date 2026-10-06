package com.fd.jfetch.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface SearchRunRepository extends JpaRepository<SearchRun, Long> {

    Optional<SearchRun> findBySignature(String signature);

    List<SearchRun> findAllByOrderByFetchedAtDesc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SearchRun r set r.expiredAt = :now where r.expiredAt is null")
    int expireAll(@Param("now") Instant now);
}
