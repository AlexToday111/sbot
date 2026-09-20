package dev.workout.common.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalApiFilter extends OncePerRequestFilter {
  private final String key;

  public InternalApiFilter(@Value("${app.api-key}") String key) {
    this.key = key;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (path.isEmpty()) path = request.getRequestURI();
    boolean api = path.startsWith("/api/"),
        privateMetrics = path.startsWith("/actuator/") && !path.equals("/actuator/health");
    if (!api && !privateMetrics) {
      chain.doFilter(request, response);
      return;
    }
    if (key.isBlank()) {
      reject(response, 503, "Internal API is disabled until INTERNAL_API_KEY is configured.");
      return;
    }
    String provided = request.getHeader("X-Api-Key");
    if (provided == null
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
      reject(response, 401, "Invalid API key.");
      return;
    }
    if (api) {
      try {
        long telegramId = Long.parseLong(request.getHeader("X-Telegram-User-Id"));
        if (telegramId <= 0) throw new NumberFormatException();
        request.setAttribute("telegramId", telegramId);
      } catch (Exception ex) {
        reject(response, 400, "A positive X-Telegram-User-Id header is required.");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + message + "\"}");
  }
}
