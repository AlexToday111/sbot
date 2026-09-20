package dev.workout.common;

public class DomainException extends RuntimeException {
  public final int status;

  public DomainException(String message) {
    this(400, message);
  }

  public DomainException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static DomainException missing() {
    return new DomainException(
        404, "This item is no longer available. Open the menu and try again.");
  }
}
