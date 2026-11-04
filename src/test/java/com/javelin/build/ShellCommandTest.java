package com.javelin.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellCommandTest {
  @Test
  void wrapRunsTheLineThroughTheShell() {
    List<String> cmd = ShellCommand.wrap("echo hi | cat");
    assertEquals("echo hi | cat", cmd.get(cmd.size() - 1));
    if (ShellCommand.isWindows()) assertEquals(List.of("cmd.exe", "/c", "echo hi | cat"), cmd);
    else {
      assertEquals("-c", cmd.get(1));
      assertEquals(3, cmd.size());
      assertFalse(cmd.get(0).isBlank());
    }
  }

  @Test
  void interactiveIsJustAShell() {
    List<String> cmd = ShellCommand.interactive();
    assertEquals(1, cmd.size());
    assertFalse(cmd.get(0).isBlank());
  }

  @Test
  void isWindowsFollowsTheOsNameProperty() {
    String original = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Windows 11");
      assertTrue(ShellCommand.isWindows());
      assertEquals(List.of("cmd.exe"), ShellCommand.interactive());
      assertEquals(List.of("cmd.exe", "/c", "dir"), ShellCommand.wrap("dir"));
      System.setProperty("os.name", "Linux");
      assertFalse(ShellCommand.isWindows());
    } finally {
      System.setProperty("os.name", original);
    }
  }
}
