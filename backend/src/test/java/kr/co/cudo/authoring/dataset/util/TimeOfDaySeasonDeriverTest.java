package kr.co.cudo.authoring.dataset.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SHT_DT → 주야간/계절 파생 순수 함수 검증 — 경계 시각·월·null 결정론.
 */
class TimeOfDaySeasonDeriverTest {

    @Test
    @DisplayName("주야간_경계시각_06시_DAY_18시_NGT")
    void dayNight_boundaryHours() {
        // 06:00(포함)=DAY, 18:00(미포함)=NGT
        assertThat(TimeOfDaySeasonDeriver.dayNight(LocalDateTime.of(2026, 7, 13, 6, 0)))
                .isEqualTo("DAY");
        assertThat(TimeOfDaySeasonDeriver.dayNight(LocalDateTime.of(2026, 7, 13, 5, 59)))
                .isEqualTo("NGT");
        assertThat(TimeOfDaySeasonDeriver.dayNight(LocalDateTime.of(2026, 7, 13, 17, 59)))
                .isEqualTo("DAY");
        assertThat(TimeOfDaySeasonDeriver.dayNight(LocalDateTime.of(2026, 7, 13, 18, 0)))
                .isEqualTo("NGT");
        assertThat(TimeOfDaySeasonDeriver.dayNight(LocalDateTime.of(2026, 7, 13, 0, 0)))
                .isEqualTo("NGT");
    }

    @Test
    @DisplayName("계절_월별_경계_SPRING_SUMMER_FALL_WINTER")
    void season_byMonth() {
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 3, 1, 12, 0))).isEqualTo("SPRING");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 5, 31, 12, 0))).isEqualTo("SPRING");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 6, 1, 12, 0))).isEqualTo("SUMMER");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 8, 31, 12, 0))).isEqualTo("SUMMER");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 9, 1, 12, 0))).isEqualTo("FALL");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 11, 30, 12, 0))).isEqualTo("FALL");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 12, 1, 12, 0))).isEqualTo("WINTER");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 1, 15, 12, 0))).isEqualTo("WINTER");
        assertThat(TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 2, 28, 12, 0))).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("null_입력시_두_파생값_모두_null")
    void nullInput_returnsNull() {
        assertThat(TimeOfDaySeasonDeriver.dayNight(null)).isNull();
        assertThat(TimeOfDaySeasonDeriver.season(null)).isNull();
    }
}
