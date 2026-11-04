package com.javelin.ui;

import com.javelin.io.ImageFiles;
import java.io.File;
import java.io.IOException;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * Shows one picture, read-only: a toolbar (fit, actual size, zoom) over a scrolling view. It starts
 * fitted to the window and keeps fitting while the window is resized until the person chooses
 * another zoom. The file is read once, from disk, and never written.
 */
public final class ImageViewerPane extends BorderPane {
  private final Image image;
  private final ImageView view = new ImageView();
  private final ScrollPane scroll = new ScrollPane();
  private final Label zoomLabel = new Label();
  private boolean fitting = true;
  private double zoom = 1;

  /**
   * Loads {@code file}; throws {@link IOException} if it is too large or is not a picture JavaFX
   * can decode.
   */
  public static ImageViewerPane load(File file) throws IOException {
    ImageFiles.checkLoadable(file.toPath());
    Image image =
        new Image(
            file.toURI().toString(), false); // local file, loaded now so a bad file fails here
    if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
      Throwable cause = image.getException();
      throw new IOException(
          file.getName()
              + " could not be read as an image"
              + (cause == null || cause.getMessage() == null ? "." : ": " + cause.getMessage()),
          cause);
    }
    return new ImageViewerPane(
        image, ImageFiles.describe((int) image.getWidth(), (int) image.getHeight(), file.length()));
  }

  private ImageViewerPane(Image image, String description) {
    this.image = image;
    getStyleClass().add("image-viewer");
    view.setImage(image);
    view.setPreserveRatio(true);
    view.setSmooth(true);

    StackPane holder = new StackPane(view); // centers a small picture in the viewport
    holder
        .minWidthProperty()
        .bind(
            Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
    holder
        .minHeightProperty()
        .bind(
            Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));
    scroll.setContent(holder);
    scroll.setPannable(true);
    scroll
        .viewportBoundsProperty()
        .addListener(
            (o, was, now) -> {
              if (fitting) fit();
            });

    Button fit = new Button("Fit"),
        actual = new Button("100%"),
        in = new Button("+"),
        out = new Button("−");
    fit.setOnAction(e -> fit());
    actual.setOnAction(e -> setZoom(1, false));
    in.setOnAction(e -> setZoom(zoom * 1.25, false));
    out.setOnAction(e -> setZoom(zoom / 1.25, false));
    Label info = new Label(description);
    HBox bar = new HBox(6, fit, actual, out, in, zoomLabel, info);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(4, 8, 4, 8));
    setTop(bar);
    setCenter(scroll);
    fit();
  }

  private void fit() {
    double w = scroll.getViewportBounds().getWidth(), h = scroll.getViewportBounds().getHeight();
    setZoom(ImageFiles.fitScale(image.getWidth(), image.getHeight(), w, h), true);
  }

  private void setZoom(double requested, boolean fromFit) {
    fitting = fromFit;
    zoom = ImageFiles.clampZoom(requested);
    view.setFitWidth(image.getWidth() * zoom);
    view.setFitHeight(image.getHeight() * zoom);
    zoomLabel.setText(Math.round(zoom * 100) + "%" + (fitting ? " (fit)" : ""));
  }
}
