package com.javelin.ui.ai;

import com.javelin.ai.AiPlan;
import com.javelin.ai.AiProposal;
import com.javelin.ai.Finding;
import com.javelin.ai.PromptBuilder;
import com.javelin.ui.Dialogs;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The three questions the AI workflow asks: what may be sent, what to change, and whether to apply
 * the answer. All go through {@link Dialogs}.
 */
final class AiDialogs {
  private AiDialogs() {}

  static final ButtonType SEND =
      new ButtonType("Send", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
  static final ButtonType APPLY =
      new ButtonType("Apply", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);

  /**
   * Shown instead of Apply when a check found the change very likely broken, so applying it is a
   * decision and not a habit.
   */
  static final ButtonType APPLY_ANYWAY =
      new ButtonType("Apply anyway", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);

  static final ButtonType REJECT =
      new ButtonType("Reject", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

  /** Shows exactly what would be sent and where; true only if the user chooses Send. */
  static boolean confirmSend(AiPlan plan) {
    Dialog<ButtonType> dialog =
        textDialog(
            "Send to AI?",
            plan.action().label() + ": " + plan.indicator(),
            "This is everything that will be sent. Nothing is sent until you choose Send.",
            plan.preview(),
            SEND,
            ButtonType.CANCEL);
    return Dialogs.show(dialog).filter(SEND::equals).isPresent();
  }

  /**
   * Shows the proposed change as a diff, with what the checks found above it; true only if the user
   * chooses Apply. When a check found an error the button reads "Apply anyway" and the intro says
   * what is wrong.
   */
  static boolean review(AiProposal proposal, String note, List<Finding> findings) {
    boolean errors = findings.stream().anyMatch(Finding::isError);
    String intro =
        proposal.kind() == AiProposal.Kind.NEW_DOCUMENT
            ? "Apply opens a new unsaved tab named "
                + proposal.suggestedName()
                + "; you choose where to save it."
            : "Apply changes the text in the editor. It stays unsaved and Undo takes it back.";
    if (errors)
      intro =
          "The checks below found problems, so this change is probably broken. Reject it, or Apply anyway and fix it by hand. "
              + intro;
    StringBuilder body = new StringBuilder();
    if (!proposal.explanation().isBlank()) body.append(proposal.explanation()).append("\n\n");
    if (note != null && !note.isBlank()) body.append("Note: ").append(note).append("\n\n");
    for (Finding finding : findings) body.append(finding.label()).append("\n");
    if (!findings.isEmpty()) body.append("\n");
    body.append(
        proposal.kind() == AiProposal.Kind.NEW_DOCUMENT
            ? "New file contents:\n" + proposal.replacement()
            : proposal.diff().text());
    ButtonType accept = errors ? APPLY_ANYWAY : APPLY;
    Dialog<ButtonType> dialog =
        textDialog(
            "Review proposed change", proposal.summary(), intro, body.toString(), accept, REJECT);
    return Dialogs.show(dialog).filter(accept::equals).isPresent();
  }

  /** Asks which installed model to use. */
  static Optional<String> chooseModel(List<String> models, String current) {
    ChoiceDialog<String> dialog =
        new ChoiceDialog<>(models.contains(current) ? current : models.get(0), models);
    dialog.setTitle("Choose AI Model");
    dialog.setHeaderText("Models installed in Ollama on this computer");
    dialog.setContentText("Model:");
    return Dialogs.show(dialog);
  }

  /** Asks what the change should be. */
  static Optional<String> askInstruction() {
    TextInputDialog dialog = new TextInputDialog();
    dialog.setTitle("Propose Change");
    dialog.setHeaderText("What should change in the selected code?");
    dialog.setContentText("Change:");
    dialog
        .getEditor()
        .setPromptText(
            "for example: rename x to count and add a null check (up to "
                + PromptBuilder.MAX_INSTRUCTION_CHARS
                + " characters)");
    return Dialogs.show(dialog).map(String::strip).filter(s -> !s.isEmpty());
  }

  private static Dialog<ButtonType> textDialog(
      String title,
      String header,
      String intro,
      String body,
      ButtonType accept,
      ButtonType decline) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    TextArea area = new TextArea(body);
    area.setEditable(false);
    area.setWrapText(false);
    area.setPrefColumnCount(90);
    area.setPrefRowCount(24);
    area.setStyle("-fx-font-family: monospace;");
    VBox.setVgrow(area, Priority.ALWAYS);
    Label label = new Label(intro);
    label.setWrapText(true);
    VBox box = new VBox(8, label, area);
    box.setPadding(new Insets(4, 0, 0, 0));
    dialog.getDialogPane().setContent(box);
    dialog.getDialogPane().getButtonTypes().setAll(accept, decline);
    // approving takes a deliberate click: Enter and Escape both mean no
    if (dialog.getDialogPane().lookupButton(accept) instanceof javafx.scene.control.Button yes)
      yes.setDefaultButton(false);
    if (dialog.getDialogPane().lookupButton(decline) instanceof javafx.scene.control.Button no)
      no.setDefaultButton(true);
    dialog.setResizable(true);
    return dialog;
  }
}
