package dev.workout.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.context.i18n.LocaleContextHolder;

/** Translates application literals only; user content is never passed through this catalog. */
public final class I18n {
  private static final Map<String, String> RU = load();

  private I18n() {}

  private static Map<String, String> load() {
    try (var stream = I18n.class.getResourceAsStream("/i18n/ru.json")) {
      return new ObjectMapper().readValue(stream, new TypeReference<Map<String, String>>() {});
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  public static void language(String language) {
    LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
  }

  public static String t(String english) {
    return "ru".equals(LocaleContextHolder.getLocale().getLanguage())
        ? RU.getOrDefault(english, english)
        : english;
  }
}
