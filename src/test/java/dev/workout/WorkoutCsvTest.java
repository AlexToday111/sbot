package dev.workout;

import static org.assertj.core.api.Assertions.*;

import dev.workout.common.DomainException;
import dev.workout.workout.application.WorkoutCsv;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WorkoutCsvTest {
  private static final String HEADER = String.join(",", WorkoutCsv.HEADER) + "\n";

  private WorkoutCsv.Parsed parse(String text) {
    return WorkoutCsv.parse(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void readsBomCyrillicQuotedNamesAndOptionalTargets() {
    var csv =
        parse(
            "\uFEFF"
                + HEADER
                + "\"Грудь, плечи\",\"Жим \"\"сидя\"\"\",STRENGTH,3,8,72.5,90\r\n"
                + "\"Грудь, плечи\",Планка,TIMED,-,,,\r\n");
    assertThat(csv.name()).isEqualTo("Грудь, плечи");
    assertThat(csv.rows()).hasSize(2);
    assertThat(csv.rows().get(0).exercise()).isEqualTo("Жим \"сидя\"");
    assertThat(csv.rows().get(0).weight()).isEqualByComparingTo("72.5");
    assertThat(csv.rows().get(1).sets()).isNull();
  }

  @Test
  void supportsSemicolonAndDecimalComma() {
    var csv = parse(HEADER.replace(',', ';') + "Ноги;Присед;strength;3;8;72,5;90");
    assertThat(csv.rows().get(0).weight()).isEqualByComparingTo("72.5");
  }

  @Test
  void rejectsInvalidEncodingHeadersQuotesCountsAndNumbers() {
    for (String invalid :
        new String[] {
          "",
          "bad\n",
          HEADER,
          HEADER + "A,E,STRENGTH,3,8,70",
          HEADER + "A,E,STRENGTH,101,8,70,90",
          HEADER + "A,E,STRENGTH,3,8,70.1234,90",
          HEADER + "A,E,unknown,3,8,70,90",
          HEADER + "A,\"E,STRENGTH,3,8,70,90",
          HEADER + "A,\"E\"x,STRENGTH,3,8,70,90",
          HEADER + "A,E,STRENGTH,3,8,70,90\nB,E,STRENGTH,3,8,70,90",
          HEADER + "A,E,STRENGTH,3,8,70,90\n".repeat(31)
        }) assertThatThrownBy(() -> parse(invalid)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> WorkoutCsv.parse(new byte[] {(byte) 0xff}))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> WorkoutCsv.parse(new byte[65537])).isInstanceOf(DomainException.class);
  }
}
