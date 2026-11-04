package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
  private static IllegalArgumentException bad(String text) {
    return assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
  }

  @Test
  void scalars() {
    assertNull(Json.parse("null"));
    assertEquals(true, Json.parse(" true "));
    assertEquals(false, Json.parse("false"));
    assertEquals(42L, Json.parse("42"));
    assertEquals(-7L, Json.parse("-7"));
    assertEquals(1.5, Json.parse("1.5"));
    assertEquals(1e3, Json.parse("1E3"));
    assertEquals("hi", Json.parse("\"hi\""));
  }

  @Test
  void stringEscapes() {
    assertEquals(
        "a\"b\\c/d\n\r\t\b\f\u00e9", Json.parse("\"a\\\"b\\\\c\\/d\\n\\r\\t\\b\\f\\u00e9\""));
    assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
  }

  @Test
  void nestedStructures() {
    Object v = Json.parse("{\"a\":[1,2,{\"b\":null}],\"c\":{},\"d\":[]}");
    assertEquals(List.of(1L, 2L, Json.obj("b", null)), Json.get(v, "a"));
    assertEquals(Map.of(), Json.get(v, "c"));
    assertEquals(List.of(), Json.get(v, "d"));
  }

  @Test
  void malformedInputIsRejectedWithAnOffset() {
    for (String s :
        new String[] {
          "",
          "   ",
          "{",
          "[",
          "[1,",
          "[1,]",
          "{\"a\"}",
          "{\"a\":}",
          "{\"a\":1,}",
          "{a:1}",
          "{\"a\":1 \"b\":2}",
          "[1 2]",
          "\"abc",
          "\"a\\",
          "\"\\x\"",
          "\"\\u12\"",
          "\"\\uZZZZ\"",
          "tru",
          "nul",
          "fals",
          "-",
          "1-2",
          "@",
          "1 2",
          "{} x",
          "99999999999999999999"
        }) bad(s);
    assertTrue(bad("[1,]").getMessage().contains("offset"));
  }

  @Test
  void deeplyNestedInputFailsCleanlyInsteadOfOverflowingTheStack() {
    String deep = "[".repeat(100_000) + "]".repeat(100_000);
    assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    String deepObjects = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000);
    assertThrows(IllegalArgumentException.class, () -> Json.parse(deepObjects));
    String fine = "[".repeat(200) + "]".repeat(200);
    assertNotNull(Json.parse(fine));
  }

  @Test
  void writerProducesCompactJsonAndEscapes() {
    assertEquals("null", Json.write(null));
    assertEquals(
        "{\"a\":[1,2.5,\"x\\n\\\"\\u0001\",true,null],\"b\":{}}",
        Json.write(
            Json.obj(
                "a", java.util.Arrays.asList(1, 2.5, "x\n\"\u0001", true, null), "b", Json.obj())));
    assertEquals("\"\\\\ \\r\\t\\b\\f\"", Json.write("\\ \r\t\b\f"));
    assertEquals("[1,2]", Json.write(List.of(1, 2)));
  }

  @Test
  void writerHandlesAllNumberTypes() {
    assertEquals("3", Json.write(3.0));
    assertEquals("3", Json.write((short) 3));
    assertEquals("3", Json.write(new java.math.BigInteger("3")));
    assertEquals("0.25", Json.write(0.25f));
    assertEquals("null", Json.write(Double.NaN));
    assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
    assertEquals("1.0E15", Json.write(1e15));
    assertEquals("9", Json.write(9L));
    assertEquals("true", Json.write(true));
  }

  @Test
  void writerRejectsTypesItCannotSerialise() {
    assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("a", new int[0])));
  }

  @Test
  void writingAPathOrACycleDoesNotRecurseForever() {
    assertDoesNotThrow(() -> Json.write(Json.obj("p", Path.of("/a/b").toString())));
    assertThrows(
        IllegalArgumentException.class,
        () -> Json.write(Path.of("/a/b"))); // a Path is an Iterable of Paths
    Map<String, Object> cyclic = Json.obj();
    cyclic.put("self", cyclic);
    assertThrows(IllegalArgumentException.class, () -> Json.write(cyclic));
  }

  @Test
  void roundTrip() {
    Map<String, Object> original =
        Json.obj(
            "s",
            "é\u2028\u0000",
            "n",
            12L,
            "d",
            0.5,
            "l",
            List.of(true, false),
            "o",
            Json.obj("k", "v"));
    assertEquals(original, Json.parse(Json.write(original)));
    assertEquals("\uD83D\uDE00", Json.parse(Json.write("\uD83D\uDE00")));
  }

  @Test
  void objBuilderKeepsOrderAndNullsAndDropsADanglingKey() {
    Map<String, Object> m = Json.obj("b", 1, "a", null, "dangling");
    assertEquals(List.of("b", "a"), List.copyOf(m.keySet()));
    assertTrue(m.containsKey("a"));
  }

  @Test
  void accessorsTolerateMissingAndMistypedFields() {
    Object o = Json.parse("{\"s\":\"x\",\"i\":3,\"f\":2.9,\"b\":true,\"n\":null,\"l\":[1]}");
    assertEquals("x", Json.string(o, "s"));
    assertNull(Json.string(o, "i"));
    assertNull(Json.string(o, "missing"));
    assertNull(Json.string(null, "s"));
    assertNull(Json.string("not an object", "s"));
    assertEquals(3, Json.integer(o, "i", -1));
    assertEquals(2, Json.integer(o, "f", -1));
    assertEquals(-1, Json.integer(o, "s", -1));
    assertTrue(Json.bool(o, "b"));
    assertFalse(Json.bool(o, "i"));
    assertFalse(Json.bool(null, "b"));
    assertNotNull(Json.asArray(Json.get(o, "l")));
    assertNull(Json.asArray("x"));
    assertNull(Json.asObject(List.of()));
    assertNull(Json.get(List.of(), "x"));
  }

  @Test
  void bigPayloadsParseQuickly() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 200_000; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"line\":").append(i).append(",\"text\":\"item ").append(i).append("\"}");
    }
    sb.append(']');
    long start = System.nanoTime();
    List<Object> parsed = Json.asArray(Json.parse(sb.toString()));
    assertEquals(200_000, parsed.size());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
  }
}
