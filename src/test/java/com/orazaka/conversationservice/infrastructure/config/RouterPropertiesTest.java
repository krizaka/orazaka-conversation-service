package com.orazaka.conversationservice.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RouterPropertiesTest {

  @Test
  void uploadsConfig_validConstruction() {
    var uc = new RouterProperties.UploadsConfig("uploads/", "/uploads/**", 3600);
    assertEquals("uploads/", uc.directory());
    assertEquals("/uploads/**", uc.handlerPath());
    assertEquals(3600, uc.cachePeriod());
  }

  @Test
  void uploadsConfig_nullDirectory_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouterProperties.UploadsConfig(null, "/uploads/**", 3600));
  }

  @Test
  void uploadsConfig_blankDirectory_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouterProperties.UploadsConfig("  ", "/uploads/**", 3600));
  }

  @Test
  void uploadsConfig_nullHandlerPath_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouterProperties.UploadsConfig("uploads/", null, 3600));
  }

  @Test
  void uploadsConfig_blankHandlerPath_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouterProperties.UploadsConfig("uploads/", "  ", 3600));
  }

  @Test
  void routerProperties_validConstruction() {
    var uploads = new RouterProperties.UploadsConfig("uploads/", "/uploads/**", 3600);
    var gp = new RouterProperties(uploads);
    assertEquals(uploads, gp.uploads());
  }
}
