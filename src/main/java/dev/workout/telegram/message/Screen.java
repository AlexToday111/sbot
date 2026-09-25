package dev.workout.telegram.message;

import static dev.workout.common.I18n.t;

import java.util.*;

public record Screen(String text, List<List<Button>> rows, Chart chart, Attachment attachment) {
  public Screen(String text, List<List<Button>> rows) {
    this(text, rows, null, null);
  }

  public Screen(String text, List<List<Button>> rows, Chart chart) {
    this(text, rows, chart, null);
  }

  public record Attachment(String filename, String content) {}

  public record Button(String text, String action, String style) {
    public Button(String text, String action) {
      this(text, action, null);
    }
  }

  public static Builder title(String text) {
    return new Builder(text);
  }

  public static class Builder {
    private final StringBuilder text;
    private final List<List<Button>> rows = new ArrayList<>();
    private Chart chart;
    private Attachment attachment;

    public Builder attachment(Attachment value) {
      attachment = value;
      return this;
    }

    public Builder(String text) {
      this.text = new StringBuilder(text);
    }

    public Builder line(String line) {
      text.append("\n").append(line);
      return this;
    }

    public Builder button(String label, String action) {
      rows.add(List.of(new Button(label, action)));
      return this;
    }

    public Builder row(Button... buttons) {
      var visible = Arrays.stream(buttons).filter(Objects::nonNull).toList();
      if (!visible.isEmpty()) rows.add(visible);
      return this;
    }

    public Builder primary(String label, String action) {
      return row(new Button(label, action, "primary"));
    }

    public Builder success(String label, String action) {
      return row(new Button(label, action, "success"));
    }

    public Builder danger(String label, String action) {
      return row(new Button(label, action, "danger"));
    }

    public Builder chart(Chart value) {
      chart = value;
      return this;
    }

    public Builder navigation(String backAction) {
      return row(b(t("← Back"), backAction), b(t("⌂ Menu"), "menu:home"));
    }

    public Builder pages(int page, boolean hasNext, String prefix) {
      return row(
          page > 0 ? b(t("← Previous"), prefix + (page - 1)) : null,
          hasNext ? b(t("Next →"), prefix + (page + 1)) : null);
    }

    public Builder home() {
      return button(t("⌂ Main menu"), "menu:home");
    }

    public Screen build() {
      if (text.length() > (chart == null && attachment == null ? 4000 : 1024))
        throw new IllegalStateException("Screen needs pagination");
      return new Screen(text.toString(), List.copyOf(rows), chart, attachment);
    }
  }

  public static Button b(String label, String action) {
    return new Button(label, action);
  }
}
