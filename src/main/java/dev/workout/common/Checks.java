package dev.workout.common;

import java.math.BigDecimal;

public final class Checks {
  private Checks() {}

  public static String name(String value) {
    if (value == null || value.isBlank() || value.strip().length() > 80)
      throw new DomainException("Enter a name between 1 and 80 characters.");
    return value.strip();
  }

  public static String text(String value, int max) {
    if (value != null && value.length() > max)
      throw new DomainException("Text must be at most " + max + " characters.");
    return value;
  }

  public static void range(Number value, double min, double max, String label) {
    if (value != null
        && (!Double.isFinite(value.doubleValue())
            || value.doubleValue() < min
            || value.doubleValue() > max))
      throw new DomainException(label + " must be between " + min + " and " + max + ".");
  }

  public static BigDecimal decimal(String text) {
    try {
      return new BigDecimal(text.trim().replace(',', '.'));
    } catch (NumberFormatException ex) {
      throw new DomainException("Enter a valid number, for example 70 or 72.5.");
    }
  }

  public static void scale(BigDecimal value, int max, String label) {
    if (value != null && value.stripTrailingZeros().scale() > max)
      throw new DomainException(label + " supports at most " + max + " decimal places.");
  }

  public static int integer(String text) {
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException ex) {
      throw new DomainException("Enter a whole number.");
    }
  }
}
