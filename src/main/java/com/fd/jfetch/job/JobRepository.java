package com.fd.jfetch.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, String> {

    List<Job> findByStatus(JobStatus status);

    long countByStatus(JobStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Job j set j.status = com.fd.jfetch.job.JobStatus.SKIPPED where j.status = com.fd.jfetch.job.JobStatus.PENDING")
    int skipAllPending();

    @Query("select j from SearchRun r join r.jobs j where r.id = :runId")
    List<Job> findBySearchRun(@Param("runId") Long runId);
}
