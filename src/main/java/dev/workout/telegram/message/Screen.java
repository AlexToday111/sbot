package dev.workout.telegram.message;

import static dev.workout.common.I18n.t;

import java.util.*;

public record Screen(String text, List<List<Button>> rows) {
  public record Button(String text, String action) {}

  public static Builder title(String text) {
    return new Builder(text);
  }

  public static class Builder {
    private final StringBuilder text;
    private final List<List<Button>> rows = new ArrayList<>();

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
      rows.add(List.of(buttons));
      return this;
    }

    public Builder home() {
      return button(t("⌂ Main menu"), "menu:home");
    }

    public Screen build() {
      if (text.length() > 4000) throw new IllegalStateException("Screen needs pagination");
      return new Screen(text.toString(), List.copyOf(rows));
    }
  }

  public static Button b(String label, String action) {
    return new Button(label, action);
  }
}
