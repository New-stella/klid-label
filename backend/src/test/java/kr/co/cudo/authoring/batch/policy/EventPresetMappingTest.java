package kr.co.cudo.authoring.batch.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventPresetMappingTest {

    @Test
    @DisplayName("EVT_FALL_은_person_만_허용")
    void evtFallMapsToPersonOnly() {
        Optional<Set<String>> labels = EventPresetMapping.labelsFor("EVT_FALL");
        assertThat(labels).isPresent();
        assertThat(labels.get()).containsExactly("person");
    }

    @Test
    @DisplayName("EVT_VIOLENCE_은_person_만_허용")
    void evtViolenceMapsToPersonOnly() {
        Optional<Set<String>> labels = EventPresetMapping.labelsFor("EVT_VIOLENCE");
        assertThat(labels).isPresent();
        assertThat(labels.get()).containsExactly("person");
    }

    @Test
    @DisplayName("EVT_ACCIDENT_은_차량_사람_등_6종_허용")
    void evtAccidentMapsToVehicleAndPerson() {
        Optional<Set<String>> labels = EventPresetMapping.labelsFor("EVT_ACCIDENT");
        assertThat(labels).isPresent();
        assertThat(labels.get())
                .containsExactlyInAnyOrder("car", "truck", "motorcycle", "bus", "bicycle", "person");
    }

    @Test
    @DisplayName("EVT_FIRE_은_fire_와_person_허용")
    void evtFireMapsToFireAndPerson() {
        Optional<Set<String>> labels = EventPresetMapping.labelsFor("EVT_FIRE");
        assertThat(labels).isPresent();
        assertThat(labels.get()).containsExactlyInAnyOrder("fire", "person");
    }

    @Test
    @DisplayName("EVT_TRASH_은_trash_만_허용")
    void evtTrashMapsToTrashOnly() {
        Optional<Set<String>> labels = EventPresetMapping.labelsFor("EVT_TRASH");
        assertThat(labels).isPresent();
        assertThat(labels.get()).containsExactly("trash");
    }

    @Test
    @DisplayName("null_이벤트는_빈_Optional_반환_fail_safe")
    void nullEventTypeReturnsEmpty() {
        assertThat(EventPresetMapping.labelsFor(null)).isEmpty();
    }

    @Test
    @DisplayName("blank_이벤트는_빈_Optional_반환_fail_safe")
    void blankEventTypeReturnsEmpty() {
        assertThat(EventPresetMapping.labelsFor("")).isEmpty();
        assertThat(EventPresetMapping.labelsFor("   ")).isEmpty();
    }

    @Test
    @DisplayName("미정의_이벤트는_빈_Optional_반환_fail_safe")
    void unknownEventTypeReturnsEmpty() {
        assertThat(EventPresetMapping.labelsFor("EVT_UNKNOWN")).isEmpty();
        assertThat(EventPresetMapping.labelsFor("RANDOM_CODE")).isEmpty();
    }
}
