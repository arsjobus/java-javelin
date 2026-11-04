package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LspTest {
  private static Object j(String json) {
    return Json.parse(json);
  }

  private static final String R =
      "{\"start\":{\"line\":1,\"character\":2},\"end\":{\"line\":3,\"character\":4}}";

  @Test
  void positionsAndRangesRoundTrip() {
    Lsp.Range range = Lsp.Range.from(j(R));
    assertEquals(new Lsp.Range(new Lsp.Position(1, 2), new Lsp.Position(3, 4)), range);
    assertEquals(j(R), j(Json.write(range.toJson())));
    assertNull(Lsp.Range.from(j("{\"start\":{}}")));
    assertEquals(new Lsp.Position(0, 0), Lsp.Position.from(j("{}")));
    assertEquals(new Lsp.Position(0, 0), Lsp.Position.from(null));
  }

  @Test
  void requestParams() {
    URI uri = URI.create("file:///a.java");
    assertEquals(
        "{\"textDocument\":{\"uri\":\"file:///a.java\"},\"position\":{\"line\":1,\"character\":2}}",
        Json.write(Lsp.positionParams(uri, new Lsp.Position(1, 2))));
  }

  @Test
  void locationsAcceptLocationsLinksSinglesAndGarbage() {
    String loc = "{\"uri\":\"file:///a.java\",\"range\":" + R + "}";
    String link =
        "{\"targetUri\":\"file:///b.java\",\"targetRange\":"
            + R
            + ",\"targetSelectionRange\":{\"start\":{\"line\":9,\"character\":9},\"end\":{\"line\":9,\"character\":12}}}";
    List<Lsp.Location> found =
        Lsp.locations(
            j(
                "["
                    + loc
                    + ","
                    + link
                    + ",{\"uri\":\"file:///c\"},{\"range\":"
                    + R
                    + "},{\"uri\":\"bad uri\",\"range\":"
                    + R
                    + "},5]"));
    assertEquals(2, found.size());
    assertEquals(URI.create("file:///a.java"), found.get(0).uri());
    assertEquals(9, found.get(1).range().start().line());
    assertEquals(1, Lsp.locations(j(loc)).size(), "a single Location is allowed");
    assertTrue(Lsp.locations(null).isEmpty());
    assertTrue(Lsp.locations(j("\"nonsense\"")).isEmpty());
  }

  @Test
  void completionsFromListsAndItemsWithEdits() {
    String json =
        "{\"isIncomplete\":false,\"items\":[{\"label\":\"foo\",\"detail\":\"int\",\"kind\":2},{\"label\":\"bar\",\"insertText\":\"bar()\",\"filterText\":\"b\"},"
            + "{\"label\":\"baz\",\"textEdit\":{\"range\":"
            + R
            + ",\"newText\":\"baz(${1:x})\"},\"insertTextFormat\":2},"
            + "{\"label\":\"ins\",\"textEdit\":{\"insert\":"
            + R
            + ",\"replace\":"
            + R
            + ",\"newText\":\"ins\"}},{\"label\":\"broken\",\"textEdit\":{\"newText\":\"x\"}},{\"detail\":\"no label\"}]}";
    List<Lsp.CompletionItem> items = Lsp.completions(j(json));
    assertEquals(5, items.size());
    assertEquals("foo", items.get(0).text());
    assertEquals("foo", items.get(0).filter());
    assertEquals("bar()", items.get(1).text());
    assertEquals("b", items.get(1).filter());
    assertEquals("baz(x)", items.get(2).text(), "snippets are flattened");
    assertNotNull(items.get(2).edit());
    assertNotNull(items.get(3).edit(), "InsertReplaceEdit uses its insert range");
    assertNull(items.get(4).edit());
    assertEquals(2, items.get(0).kind());
    assertEquals(5, Lsp.completions(j("[{\"label\":\"a\"}]")).size() + 4);
    assertTrue(Lsp.completions(null).isEmpty());
    assertTrue(Lsp.completions(j("{}")).isEmpty());
  }

  @Test
  void snippetFlattening() {
    assertEquals("foo(a, b)", Lsp.flattenSnippet("foo(${1:a}, ${2:b})$0"));
    assertEquals("println()", Lsp.flattenSnippet("println($1)"));
    assertEquals("x", Lsp.flattenSnippet("${1|x,y,z|}"));
    assertEquals("a", Lsp.flattenSnippet("$1${2}a$10"));
    assertEquals("cost: $5 {}", Lsp.flattenSnippet("cost: \\$5 {\\}"));
    assertEquals("price $", Lsp.flattenSnippet("price \\$"));
    assertEquals("outer(inner)", Lsp.flattenSnippet("outer(${1:${2:inner}})"));
    assertEquals("a}b", Lsp.flattenSnippet("${1:a\\}b}"));
    assertEquals("plain", Lsp.flattenSnippet("plain"));
    assertEquals("name", Lsp.flattenSnippet("${TM_FILENAME:name}"));
    assertEquals("", Lsp.flattenSnippet(""));
    assertEquals("a\\b", Lsp.flattenSnippet("a\\b"));
    assertEquals("trailing\\", Lsp.flattenSnippet("trailing\\"));
    assertEquals("file  and ", Lsp.flattenSnippet("file $TM_FILENAME and $_x1x"));
    assertEquals("${1:never closed", Lsp.flattenSnippet("${1:never closed"));
    assertEquals("${} ${!x}", Lsp.flattenSnippet("${} ${!x}"));
    assertEquals("${1+2}", Lsp.flattenSnippet("${1+2}"));
    assertEquals("a|b", Lsp.flattenSnippet("${1|a\\|b,c|}"));
    assertEquals("$ $-", Lsp.flattenSnippet("$ $-"));
    assertEquals("1 $", Lsp.flattenSnippet("1 $"));
  }

  @Test
  void hoverFlattensAllContentShapes() {
    assertEquals("", Lsp.hoverText(null));
    assertEquals("", Lsp.hoverText(j("{}")));
    assertEquals("plain", Lsp.hoverText(j("{\"contents\":\"plain\"}")));
    assertEquals(
        "int x",
        Lsp.hoverText(
            j("{\"contents\":{\"kind\":\"markdown\",\"value\":\"```java\\nint x\\n```\"}}")));
    assertEquals(
        "one\n\ntwo",
        Lsp.hoverText(
            j("{\"contents\":[\"one\",{\"language\":\"java\",\"value\":\"two\"},\"  \",{}]}")));
    assertEquals(
        "see docs for more",
        Lsp.hoverText(j("{\"contents\":\"see [docs](http://x.y/z) for more\"}")));
    assertEquals("a\n\nb", Lsp.hoverText(j("{\"contents\":\"a\\n\\n\\n\\n\\nb\"}")));
  }

  @Test
  void workspaceEditsFromBothShapesAndUnsupportedOperations() {
    String json =
        "{\"changes\":{\"file:///a\":[{\"range\":"
            + R
            + ",\"newText\":\"x\"},{\"range\":"
            + R
            + "}],\"bad uri\":[{\"range\":"
            + R
            + ",\"newText\":\"y\"}],\"file:///n\":5},"
            + "\"documentChanges\":[{\"textDocument\":{\"uri\":\"file:///b\",\"version\":1},\"edits\":[{\"range\":"
            + R
            + ",\"newText\":\"z\"}]},{\"kind\":\"rename\",\"oldUri\":\"a\",\"newUri\":\"b\"},{\"textDocument\":{},\"edits\":[]}]}";
    Lsp.WorkspaceEdit edit = Lsp.workspaceEdit(j(json));
    assertTrue(edit.unsupported());
    assertEquals(2, edit.changes().size());
    assertEquals("x", edit.changes().get(URI.create("file:///a")).get(0).newText());
    assertEquals(1, edit.changes().get(URI.create("file:///a")).size());
    assertEquals("z", edit.changes().get(URI.create("file:///b")).get(0).newText());
    assertFalse(Lsp.workspaceEdit(null).unsupported());
    assertTrue(Lsp.workspaceEdit(j("{}")).changes().isEmpty());
  }

  @Test
  void diagnosticsSkipIncompleteEntries() {
    String json =
        "[{\"range\":"
            + R
            + ",\"message\":\"boom\",\"severity\":2,\"source\":\"javac\"},{\"range\":"
            + R
            + ",\"message\":\"default severity\"},{\"message\":\"no range\"},{\"range\":"
            + R
            + "}]";
    List<Lsp.Diagnostic> list = Lsp.diagnostics(j(json));
    assertEquals(2, list.size());
    assertEquals(Lsp.Diagnostic.WARNING, list.get(0).severity());
    assertEquals("javac", list.get(0).source());
    assertEquals(Lsp.Diagnostic.ERROR, list.get(1).severity());
    assertNull(list.get(1).source());
    assertTrue(Lsp.diagnostics(null).isEmpty());
    assertTrue(Lsp.diagnostics(j("{}")).isEmpty());
  }

  @Test
  void signatureHelp() {
    assertEquals("", Lsp.signatureText(null));
    assertEquals("", Lsp.signatureText(j("{\"signatures\":[]}")));
    assertEquals("", Lsp.signatureText(j("{\"signatures\":[{}]}")));
    String one =
        "{\"signatures\":[{\"label\":\"f(int a, String b)\",\"documentation\":{\"kind\":\"markdown\",\"value\":\"Does **f**.\"},"
            + "\"parameters\":[{\"label\":[2,7],\"documentation\":\"first\"},{\"label\":\"String b\"}]}],\"activeParameter\":1}";
    assertEquals(
        "f(int a, String b)\n\nParameter: String b\n\nDoes **f**.", Lsp.signatureText(j(one)));
    String span = one.replace("\"activeParameter\":1", "\"activeParameter\":0");
    assertEquals(
        "f(int a, String b)\n\nParameter: int a\nfirst\n\nDoes **f**.", Lsp.signatureText(j(span)));
    String overloads =
        "{\"signatures\":[{\"label\":\"f()\"},{\"label\":\"f(int)\",\"activeParameter\":0,\"parameters\":[{\"label\":\"int\"}]}],\"activeSignature\":9,\"activeParameter\":5}";
    assertEquals("f(int)\n\nParameter: int\n\n(overload 2 of 2)", Lsp.signatureText(j(overloads)));
    assertEquals(
        "f(a)",
        Lsp.signatureText(
            j("{\"signatures\":[{\"label\":\"f(a)\",\"parameters\":[{\"label\":[5,2]}]}]}")));
    assertEquals(
        "f(a)",
        Lsp.signatureText(
            j("{\"signatures\":[{\"label\":\"f(a)\",\"parameters\":[{\"label\":[0,99]}]}]}")));
    assertEquals(
        "f(a)",
        Lsp.signatureText(
            j("{\"signatures\":[{\"label\":\"f(a)\",\"parameters\":[{\"label\":7}]}]}")));
    assertEquals(
        "f(a)",
        Lsp.signatureText(
            j(
                "{\"signatures\":[{\"label\":\"f(a)\",\"parameters\":[{\"label\":\"a\"}]}],\"activeParameter\":-1}")));
  }

  @Test
  void symbolsFromTreesAndFlatLists() {
    String tree =
        "[{\"name\":\"Outer\",\"kind\":5,\"detail\":\"class\",\"range\":"
            + R
            + ",\"selectionRange\":{\"start\":{\"line\":0,\"character\":6},\"end\":{\"line\":0,\"character\":11}},"
            + "\"children\":[{\"name\":\"m\",\"kind\":6,\"range\":"
            + R
            + "},{\"name\":\"noRange\",\"kind\":6}]},{\"kind\":1}]";
    List<Lsp.Symbol> s = Lsp.symbols(j(tree));
    assertEquals(2, s.size());
    assertEquals(0, s.get(0).depth());
    assertEquals(
        6, s.get(0).range().start().character(), "the selection range wins over the full range");
    assertEquals(1, s.get(1).depth());
    assertEquals("class", s.get(0).kindName());
    assertEquals("method", s.get(1).kindName());
    List<Lsp.Symbol> flat =
        Lsp.symbols(
            j(
                "[{\"name\":\"x\",\"kind\":13,\"location\":{\"uri\":\"file:///a\",\"range\":"
                    + R
                    + "}}]"));
    assertEquals("variable", flat.get(0).kindName());
    assertEquals("symbol", new Lsp.Symbol("q", null, 0, null, 0).kindName());
    assertEquals("symbol", new Lsp.Symbol("q", null, 999, null, 0).kindName());
    assertTrue(Lsp.symbols(null).isEmpty());
    StringBuilder deep = new StringBuilder("[");
    for (int i = 0; i < 50; i++)
      deep.append("{\"name\":\"n")
          .append(i)
          .append("\",\"range\":")
          .append(R)
          .append(",\"children\":[");
    deep.append("]}".repeat(50)).append("]");
    assertEquals(33, Lsp.symbols(j(deep.toString())).size(), "nesting is cut off at depth 32");
  }

  @Test
  void textEdits() {
    List<Lsp.TextEdit> edits =
        Lsp.textEdits(
            j(
                "[{\"range\":"
                    + R
                    + ",\"newText\":\"a\"},{\"range\":"
                    + R
                    + "},{\"newText\":\"b\"}]"));
    assertEquals(1, edits.size());
    assertTrue(Lsp.textEdits(null).isEmpty());
    assertTrue(Lsp.textEdits(Map.of()).isEmpty());
  }
}
