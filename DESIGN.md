# Design

## Default workspace

```text
+-------------------------------------------------------------+
| File  Edit  Search  View  Navigate  Run  Tools  Help        |
+--------------+----------------------------------------------+
| PROJECT      | main.java                                    |
|              |                                              |
| src/         | 1  package example;                          |
|  ├ App.java  | 2                                            |
|  └ ...       | 3  public class App {                        |
|              | 4      ...                                   |
| README.md    | 5  }                                         |
| pom.xml      |                                              |
+--------------+----------------------------------------------+
| Problems: 0        Git: clean        Java        UTF-8      |
+-------------------------------------------------------------+
```

The interface should be familiar without being visually busy.

## Focus mode

Focus mode hides secondary panels and leaves the document as the primary surface. It is not a productivity scoring system.

## Navigation

Navigation should be fast and keyboard-friendly:
- Command palette
- Go to file
- Go to symbol
- Go to line
- Find
- Find in files

## AI

AI should not be a permanently open conversational surface. It should be invoked deliberately on a selection, document, project, or explicit task.

## Notifications

Notifications should be sparse and actionable. The editor should not use badges, feeds, or attention-grabbing alerts to create engagement.

## Configuration

Settings should be human-readable and exportable. The project should remain understandable outside the editor.
