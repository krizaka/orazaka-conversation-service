package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.jobs.domain.model.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wire page carries the paging figures the clients read, totalPages included. */
class JobPageTest {

  @Test
  void theWirePageSpellsOutItsTotalPages() {
    JobPage page = JobPage.of(new PageResult<>(List.of(), 2, 10, 21));

    assertThat(page.number()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.totalElements()).isEqualTo(21);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.content()).isEmpty();
  }
}
