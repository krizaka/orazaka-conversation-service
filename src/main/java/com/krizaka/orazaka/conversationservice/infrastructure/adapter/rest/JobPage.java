package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.core.domain.model.job.JobInfo;
import com.krizaka.orazaka.jobs.domain.model.PageResult;
import java.util.List;

/**
 * One page of jobs on the wire: {@code content} and the paging figures the clients read. The core
 * hands a plain {@link PageResult}; {@code totalPages} is a method there, so it is spelled out here
 * for Jackson (orazaka-ai-engine#121).
 */
public record JobPage(
    List<JobInfo> content, int number, int size, long totalElements, int totalPages) {

  static JobPage of(PageResult<JobInfo> page) {
    return new JobPage(
        page.content(), page.number(), page.size(), page.totalElements(), page.totalPages());
  }
}
