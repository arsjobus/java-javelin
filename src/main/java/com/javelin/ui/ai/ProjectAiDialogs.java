package com.javelin.ui.ai;

import com.javelin.ai.Finding;
import com.javelin.ai.plan.PlanReview;
import com.javelin.ai.plan.Planner;
import com.javelin.ai.plan.TaskPlan;
import com.javelin.ai.project.Adjustments;
import com.javelin.ai.project.ContextBudget;
import com.javelin.ai.project.ContextEntry;
import com.javelin.ai.project.ContextMode;
import com.javelin.ai.project.Omitted;
import com.javelin.ai.project.ProjectPlan;
import com.javelin.ai.project.ProjectPromptBuilder;
import com.javelin.ai.project.ProjectProposal;
import com.javelin.ai.project.ProposalApplier;
import com.javelin.ui.Dialogs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The three questions a project request asks: what is wanted and how much of the project to send,
 * exactly which files go and whether to send, and which of the proposed file changes to apply.
 * Everything shown is produced by {@code com.javelin.ai.project}; this class only lays it out. Like
 * the single-file dialogs, approving takes a deliberate click: Enter and Escape both mean no.
 */
final class ProjectAiDialogs {
  private ProjectAiDialogs() {}

  /** What the user asked for and how much of the project to send. */
  record Request(ContextMode mode, String instruction) {}

  /** The answer of the preview dialog. */
  enum Choice {
    SEND,
    UPDATE,
    CANCEL
  }

  record Decision(Choice choice, Adjustments adjustments) {}

  private static final ButtonType CONTINUE =
      new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
  private static final ButtonType SEND = new ButtonType("Send", ButtonBar.ButtonData.OK_DONE);
  private static final ButtonType UPDATE =
      new ButtonType("Update Preview", ButtonBar.ButtonData.APPLY);
  private static final ButtonType APPLY_SELECTED =
      new ButtonType("Apply Selected", ButtonBar.ButtonData.OK_DONE);
  private static final ButtonType REJECT_ALL =
      new ButtonType("Reject All", ButtonBar.ButtonData.CANCEL_CLOSE);

  // ---- the request ----

  static Optional<Request> ask() {
    return ask(false, 0);
  }

  /**
   * As {@link #ask()}. With {@code planning} the request is split into steps (ai.plan): how much of
   * the project is sent is then decided per step, so the two modes are not offered and the request
   * is made in Smart mode.
   */
  static Optional<Request> ask(boolean planning, int maxSteps) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Project Request");
    dialog.setHeaderText("Ask about, or change, the whole project");
    RadioButton smart =
        new RadioButton("Smart Context: only the files that look relevant to the request");
    RadioButton full =
        new RadioButton(
            "Full Project: every file that fits the context window, most important first");
    ToggleGroup group = new ToggleGroup();
    smart.setToggleGroup(group);
    full.setToggleGroup(group);
    smart.setSelected(true);
    TextArea text = new TextArea();
    text.setWrapText(true);
    text.setPrefRowCount(6);
    text.setPrefColumnCount(70);
    text.setPromptText(
        "for example: add a UML generator to the editor (up to "
            + ProjectPromptBuilder.MAX_INSTRUCTION_CHARS
            + " characters)");
    Label note =
        new Label(
            planning
                ? "Planning is on (ai.plan): the request is split into at most "
                    + maxSteps
                    + " steps, each about one method. Next you will see exactly what the plan"
                    + " request sends, then the plan itself. Nothing is sent until you choose"
                    + " Send."
                : "Next you will see exactly which files would be sent, and where. Nothing is sent"
                    + " until you choose Send.");
    note.setWrapText(true);
    VBox box =
        planning
            ? new VBox(8, new Label("What do you want?"), text, note)
            : new VBox(8, new Label("What do you want?"), text, smart, full, note);
    // Add margins to each element for consistent spacing like other dialogs
    VBox.setMargin(box.getChildren().get(0), new Insets(12, 16, 8, 16));
    VBox.setMargin(text, new Insets(8, 16, 8, 16));
    VBox.setMargin(smart, new Insets(8, 16, 8, 16));
    VBox.setMargin(full, new Insets(8, 16, 8, 16));
    VBox.setMargin(note, new Insets(8, 16, 0, 16));
    dialog.getDialogPane().setContent(box);
    dialog.getDialogPane().getButtonTypes().setAll(CONTINUE, ButtonType.CANCEL);
    dialog.setResizable(true);
    Optional<ButtonType> result = Dialogs.show(dialog);
    if (result.filter(CONTINUE::equals).isEmpty()) return Optional.empty();
    String instruction = text.getText().strip();
    if (instruction.isEmpty()) return Optional.empty();
    return Optional.of(
        new Request(
            smart.isSelected() ? ContextMode.SMART : ContextMode.FULL_PROJECT, instruction));
  }

  // ---- what is sent ----

  /** A file of the project with a box that says whether it is sent. */
  private static final class FileRow {
    final String path;
    final boolean wasIncluded;
    final CheckBox box = new CheckBox();
    final Label label = new Label();
    final HBox node = new HBox(8, box, label);

    FileRow(String path, boolean included, String text) {
      this.path = path;
      this.wasIncluded = included;
      box.setSelected(included);
      label.setText(text);
    }
  }

  /**
   * Shows the plan: the files with a box each, and on the right the full preview of what would be
   * sent. Send sends the plan as it is shown; Update Preview takes the changed boxes into account
   * and shows the plan again; Cancel sends nothing.
   */
  static Decision confirm(ProjectPlan plan, Adjustments current) {
    return confirm(plan, current, plan.preview());
  }

  /**
   * The same, with {@code previewText} shown on the right (the preview plus what happens after).
   */
  static Decision confirm(ProjectPlan plan, Adjustments current, String previewText) {
    List<FileRow> rows = new ArrayList<>();
    for (ContextEntry e : plan.context().included())
      rows.add(
          new FileRow(
              e.file().path(),
              true,
              e.file().path()
                  + "  ("
                  + ContextBudget.format(e.tokens())
                  + " tokens; "
                  + e.reason()
                  + ")"));
    for (Omitted o : plan.context().omitted())
      rows.add(new FileRow(o.file().path(), false, o.file().path() + "  (" + o.reason() + ")"));
    ListView<FileRow> list = new ListView<>();
    list.getItems().setAll(rows);
    list.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(FileRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : row.node);
                setText(null);
              }
            });
    TextArea preview = new TextArea(previewText);
    preview.setEditable(false);
    preview.setWrapText(false);
    preview.setStyle("-fx-font-family: monospace;");
    SplitPane split = new SplitPane(list, preview);
    split.setOrientation(Orientation.HORIZONTAL);
    split.setDividerPositions(0.38);
    split.setPrefSize(1100, 560);
    VBox.setVgrow(split, Priority.ALWAYS);
    Label intro =
        new Label(
            plan.local()
                ? "This is everything that will be sent, to "
                    + plan.location()
                    + " on this computer. Tick the files you want sent, choose Update Preview, and"
                    + " Send when it is right."
                : "WARNING: this sends your project files to "
                    + plan.location()
                    + ", which is not this computer. Tick the files you want sent, choose Update"
                    + " Preview, and Send when it is right.");
    intro.setWrapText(true);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Send project to AI?");
    dialog.setHeaderText(plan.mode().label() + ": " + plan.indicator());
    dialog.getDialogPane().setContent(new VBox(8, intro, split));
    dialog.getDialogPane().getButtonTypes().setAll(SEND, UPDATE, ButtonType.CANCEL);
    deliberate(dialog, SEND, ButtonType.CANCEL);
    dialog.setResizable(true);
    Optional<ButtonType> result = Dialogs.show(dialog);
    if (result.isEmpty() || result.get() == ButtonType.CANCEL)
      return new Decision(Choice.CANCEL, current);
    Adjustments next = current;
    for (FileRow row : rows) {
      if (row.box.isSelected() && !row.wasIncluded) next = next.including(row.path);
      else if (!row.box.isSelected() && row.wasIncluded) next = next.excluding(row.path);
    }
    boolean changed = !next.equals(current);
    if (result.get() == UPDATE) return new Decision(Choice.UPDATE, next);
    // Send: a changed box means the preview the user saw is no longer what would be sent
    return new Decision(changed ? Choice.UPDATE : Choice.SEND, next);
  }

  // ---- the plan (ai.plan) ----

  /**
   * Step 1 of a planned request: shows exactly what the planning request sends (file names and
   * signatures, no code) and where. Send is a deliberate click.
   */
  static boolean confirmPlanRequest(Planner.PlanRequest request) {
    TextArea preview = new TextArea(request.preview());
    preview.setEditable(false);
    preview.setWrapText(false);
    preview.setStyle("-fx-font-family: monospace;");
    preview.setPrefSize(1000, 520);
    VBox.setVgrow(preview, Priority.ALWAYS);
    Label intro =
        new Label(
            request.local()
                ? "This is everything the planning request sends, to "
                    + request.location()
                    + " on this computer: file names and signatures, no code. The plan comes back"
                    + " to you before any step runs."
                : "WARNING: this sends file names and signatures to "
                    + request.location()
                    + ", which is not this computer.");
    intro.setWrapText(true);
    intro.setMaxWidth(980);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Plan the request?");
    dialog.setHeaderText("Step 1 of 2, the plan: " + request.indicator());
    dialog.getDialogPane().setContent(new VBox(8, intro, preview));
    dialog.getDialogPane().getButtonTypes().setAll(SEND, ButtonType.CANCEL);
    deliberate(dialog, SEND, ButtonType.CANCEL);
    dialog.setResizable(true);
    return Dialogs.show(dialog).filter(SEND::equals).isPresent();
  }

  /**
   * Step 2 of a planned request: the plan, one step per line, which the user may edit, reorder or
   * delete, and beside it what each step would send. {@code reviewer} reads and checks the text
   * (see {@link PlanReview}); Send is possible only for text that was checked, and editing the text
   * makes it need checking again, so what is approved is what runs. Returns the checked plan, or
   * empty when the user cancelled.
   */
  static Optional<TaskPlan> editPlan(
      String indicator,
      String note,
      String initialText,
      int maxSteps,
      Function<String, PlanReview.Reviewed> reviewer) {
    TextArea steps = new TextArea(initialText);
    steps.setWrapText(false);
    steps.setStyle("-fx-font-family: monospace;");
    steps.setPrefColumnCount(60);
    TextArea shown = new TextArea();
    shown.setEditable(false);
    shown.setWrapText(false);
    shown.setStyle("-fx-font-family: monospace;");
    Label status = new Label();
    status.setWrapText(true);
    Button check = new Button("Check Plan");
    Label help =
        new Label(
            "One step per line:  file | method | what to do.  The method is a name, 'new name'"
                + " for a method the step adds, or 'new' for a file it creates. Edit, reorder or"
                + " delete lines, then choose Check Plan. At most "
                + maxSteps
                + " steps."
                + (note == null || note.isBlank() ? "" : "\n\n" + note));
    help.setWrapText(true);
    VBox left = new VBox(8, help, steps, new HBox(8, check, status));
    VBox.setVgrow(steps, Priority.ALWAYS);
    SplitPane split = new SplitPane(left, shown);
    split.setOrientation(Orientation.HORIZONTAL);
    split.setDividerPositions(0.45);
    split.setPrefSize(1100, 560);
    VBox.setVgrow(split, Priority.ALWAYS);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Send the plan?");
    dialog.setHeaderText("Step 2 of 2, the steps: " + indicator);
    dialog.getDialogPane().setContent(split);
    dialog.getDialogPane().getButtonTypes().setAll(SEND, ButtonType.CANCEL);
    deliberate(dialog, SEND, ButtonType.CANCEL);
    dialog.setResizable(true);
    Button send = (Button) dialog.getDialogPane().lookupButton(SEND);
    TaskPlan[] checked = {null};
    Runnable run =
        () -> {
          PlanReview.Reviewed r = reviewer.apply(steps.getText());
          shown.setText(r.shown());
          checked[0] = r.ok() ? r.plan() : null;
          send.setDisable(!r.ok());
          status.setText(
              r.ok()
                  ? "The plan checks. Each step sends what is listed on the right."
                  : "The plan cannot be sent yet.");
        };
    check.setOnAction(e -> run.run());
    run.run();
    steps
        .textProperty()
        .addListener(
            (obs, was, now) -> {
              checked[0] = null;
              send.setDisable(true);
              status.setText("Edited: choose Check Plan to see what would be sent.");
            });
    Optional<ButtonType> result = Dialogs.show(dialog);
    if (result.filter(SEND::equals).isEmpty() || checked[0] == null) return Optional.empty();
    return Optional.of(checked[0]);
  }

  // ---- what is applied ----

  private static final class ChangeRow {
    final ProposalApplier.Check check;
    final CheckBox box = new CheckBox();
    final Label label = new Label();
    final HBox node = new HBox(8, box, label);

    ChangeRow(ProposalApplier.Check check) {
      this.check = check;
      boolean errors = check.change().hasErrors();
      boolean ok = check.status().applicable();
      box.setSelected(ok && !errors);
      box.setDisable(!ok);
      label.setText(
          (errors ? "[!] " : "")
              + check.change().summary()
              + (check.status() == ProposalApplier.Status.OK
                  ? ""
                  : "  (" + check.status().name().toLowerCase().replace('_', ' ') + ")"));
    }

    String detail() {
      StringBuilder out = new StringBuilder();
      out.append(check.change().path()).append("\n");
      if (check.status() != ProposalApplier.Status.OK)
        out.append(check.status().name()).append(": ").append(check.message()).append("\n");
      for (Finding f : check.change().findings()) out.append(f.label()).append("\n");
      out.append("\n");
      out.append((check.status().applicable() ? check.diff() : check.change().diff()).text());
      return out.toString();
    }
  }

  /**
   * Shows the proposed changes file by file; returns the paths the user chose to apply, or empty
   * when they rejected it all. Files that cannot be applied are listed with the reason and cannot
   * be ticked.
   */
  static Optional<Set<String>> review(
      ProjectProposal proposal, List<ProposalApplier.Check> checks, String note, String warning) {
    List<ChangeRow> rows = checks.stream().map(ChangeRow::new).toList();
    ListView<ChangeRow> list = new ListView<>();
    list.getItems().setAll(rows);
    list.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(ChangeRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : row.node);
                setText(null);
              }
            });
    TextArea detail = new TextArea();
    detail.setEditable(false);
    detail.setWrapText(false);
    detail.setStyle("-fx-font-family: monospace;");
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, row) -> detail.setText(row == null ? "" : row.detail()));
    if (!rows.isEmpty()) list.getSelectionModel().select(0);
    SplitPane split = new SplitPane(list, detail);
    split.setDividerPositions(0.34);
    split.setPrefSize(1100, 520);
    VBox.setVgrow(split, Priority.ALWAYS);
    StringBuilder intro = new StringBuilder();
    if (!proposal.explanation().isBlank()) intro.append(proposal.explanation()).append("\n\n");
    intro.append(
        "Tick the files to apply. Applying writes them to disk right away; AI > Undo Last Project"
            + " Change takes them back. Open tabs of changed files reload.");
    if (warning != null && !warning.isBlank()) intro.append("\n\nWARNING: ").append(warning);
    if (note != null && !note.isBlank()) intro.append("\n\nNote: ").append(note);
    Label label = new Label(intro.toString());
    label.setWrapText(true);
    label.setMaxWidth(1060);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Review proposed changes");
    dialog.setHeaderText(proposal.summary() + " proposed");
    dialog.getDialogPane().setContent(new VBox(8, label, split));
    dialog.getDialogPane().getButtonTypes().setAll(APPLY_SELECTED, REJECT_ALL);
    deliberate(dialog, APPLY_SELECTED, REJECT_ALL);
    dialog.setResizable(true);
    Optional<ButtonType> result = Dialogs.show(dialog);
    if (result.filter(APPLY_SELECTED::equals).isEmpty()) return Optional.empty();
    Set<String> approved = new HashSet<>();
    for (ChangeRow row : rows)
      if (row.box.isSelected() && row.check.status().applicable())
        approved.add(row.check.change().path());
    return approved.isEmpty() ? Optional.empty() : Optional.of(approved);
  }

  /** Enter and Escape mean no: approving takes a deliberate click. */
  private static void deliberate(Dialog<ButtonType> dialog, ButtonType yes, ButtonType no) {
    if (dialog.getDialogPane().lookupButton(yes) instanceof Button b) b.setDefaultButton(false);
    if (dialog.getDialogPane().lookupButton(no) instanceof Button b) b.setDefaultButton(true);
  }
}
