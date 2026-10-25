package com.krizaka.orazaka.conversationservice.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.conversationservice.architecture.fixture.UnauditedAdminController;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.OperatorAuditPaths;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * [AUDIT-001] Every write under {@code /api/v1/admin} goes through the operator audit (ADR-085):
 * each POST, PUT, PATCH or DELETE mapping of a controller of this service whose path is an admin
 * path is on the audit filter's operator surface. A new admin write the filter misses fails here,
 * before it ships unaudited. The rule is also run on a planted controller, so it is known to fail.
 */
class AdminWritesAreAuditedTest {

  private static final String ADMIN = "/api/v1/admin";
  private static final List<RequestMethod> WRITES =
      List.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

  @Test
  @DisplayName("[AUDIT-001] every admin write of this service is audited")
  void everyAdminWriteIsAudited() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scan =
        new ClassPathScanningCandidateComponentProvider(false);
    scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<String> writes = new ArrayList<>();
    List<String> unaudited = new ArrayList<>();
    for (var candidate :
        scan.findCandidateComponents("com.krizaka.orazaka.conversationservice.infrastructure")) {
      Class<?> controller = Class.forName(candidate.getBeanClassName());
      writes.addAll(adminWrites(controller));
      unaudited.addAll(unaudited(controller));
    }

    assertThat(writes).as("the rule has admin writes to check").isNotEmpty();
    assertThat(writes).anyMatch(w -> w.contains("/api/v1/admin/law/kinds/{kindId}/promote"));
    assertThat(unaudited).isEmpty();
  }

  @Test
  @DisplayName("[AUDIT-001] fails on its planted violation")
  void theRuleFailsOnAPlantedUnauditedWrite() {
    assertThat(unaudited(UnauditedAdminController.class))
        .containsExactly("POST /api/v1/admin/unaudited/{id}/do");
  }

  /** The admin writes of a controller the audit filter would not record. */
  static List<String> unaudited(Class<?> controller) {
    return adminWrites(controller).stream()
        .filter(write -> !OperatorAuditPaths.audits(write.substring(write.indexOf(' ') + 1)))
        .toList();
  }

  /** {@code METHOD /path} of each admin write of a controller. */
  static List<String> adminWrites(Class<?> controller) {
    RequestMapping root =
        AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
    String[] prefixes = root == null || root.path().length == 0 ? new String[] {""} : root.path();
    List<String> found = new ArrayList<>();
    for (Method method : controller.getDeclaredMethods()) {
      RequestMapping mapping =
          AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
      if (mapping == null) {
        continue;
      }
      String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
      for (RequestMethod verb : mapping.method()) {
        if (!WRITES.contains(verb)) {
          continue;
        }
        for (String prefix : prefixes) {
          for (String path : paths) {
            String full = prefix + path;
            if (full.startsWith(ADMIN)) {
              found.add(verb + " " + full);
            }
          }
        }
      }
    }
    return found;
  }
}
