package dev.workout.common.api;

import dev.workout.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> domain(DomainException ex) {
    return problem(ex.status, ex.getMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    MethodArgumentNotValidException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception ex) {
    return problem(400, "Invalid request. Check the documented fields and value formats.");
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<ProblemDetail> uploadTooLarge(Exception ex) {
    return problem(413, "CSV must be at most 64 KiB.");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException ex) {
    log.warn("Database constraint prevented a conflicting write", ex);
    return problem(409, "This change conflicts with saved data. Refresh and try again.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
    log.error("Request failed: {} {}", request.getMethod(), request.getRequestURI(), ex);
    return problem(500, "This action could not be saved. Retry using the same Idempotency-Key.");
  }

  private ResponseEntity<ProblemDetail> problem(int status, String detail) {
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
  }
}
