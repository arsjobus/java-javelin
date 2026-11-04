package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SensitiveFilesTest {
  @Test
  void filesMadeForSecretsAreSensitive() {
    for (String name :
        new String[] {
          ".env",
          ".env.local",
          ".ENV.production",
          "id_rsa",
          "server.pem",
          "my.key",
          "store.jks",
          "x.p12",
          ".npmrc",
          ".netrc",
          "credentials.json",
          "secrets.yml",
          "prod.tfvars",
          "terraform.tfstate",
          "passwords.kdbx",
          "/home/me/.ssh/id_ed25519",
          "C:\\keys\\id_rsa"
        }) assertTrue(SensitiveFiles.isSensitive(name), name);
  }

  @Test
  void ordinaryFilesAndEnvTemplatesAreNot() {
    for (String name :
        new String[] {
          "App.java",
          "README.md",
          "environment.md",
          ".env.example",
          ".env.sample",
          "keyboard.java",
          "monkey.txt",
          "pem.txt",
          "",
          null,
          "settings.conf"
        }) assertFalse(SensitiveFiles.isSensitive(name), String.valueOf(name));
  }

  @Test
  void textThatLooksLikeASecretIsFlagged() {
    assertTrue(
        SensitiveFiles.warnings("-----BEGIN RSA PRIVATE KEY-----\nabc")
            .contains("a private key block"));
    assertTrue(SensitiveFiles.warnings("key = AKIAABCDEFGHIJKLMNOP").contains("an access key id"));
    assertTrue(
        SensitiveFiles.warnings("token ghp_abcdefghijklmnopqrstuvwxyz0123")
            .contains("an access token"));
    assertFalse(SensitiveFiles.warnings("password = \"hunter2hunter2\"").isEmpty());
    assertFalse(SensitiveFiles.warnings("api_key: abcdef123456").isEmpty());
  }

  @Test
  void ordinaryCodeIsNotFlagged() {
    assertEquals(
        java.util.List.of(),
        SensitiveFiles.warnings(
            "int password = readPassword();\nString name = user.getName();\nif (password.length() < 8) fail();"));
    assertEquals(
        java.util.List.of(),
        SensitiveFiles.warnings(
            "String secret = ${SECRET};\nsetPassword(passwordField.getText());"));
  }
}
