package com.krizaka.orazaka.conversationservice.architecture.fixture;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A planted violation of [AUDIT-001]: an admin write outside the audit filter's operator surface.
 * Never registered (no {@code @RestController}); it proves the rule fails.
 */
@RequestMapping("/api/v1/admin/unaudited")
public class UnauditedAdminController {

  @PostMapping("/{id}/do")
  public String act(@PathVariable String id) {
    return id;
  }
}
