package kr.co.cudo.authoring.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.dataset.worker.MetaReplicationPayload;
import kr.co.cudo.authoring.dataset.worker.MetaReplicationWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 포털 메타 복제의 <b>payload 축</b>(엔티티 → 발신함 payload 직렬화 → 복원 빌더)이 동결 내용 전 컬럼을
 * 실어 나르는지 고정하는 회귀 가드 (@design INT-009 — 「복제 범위 — 원본과 동형이며 전 컬럼을 옮긴다」).
 *
 * <h3>왜 이 가드가 필요한가 — 실사고</h3>
 * 복제 경로는 4단(직렬화 → 전송 계약 record → 복원 빌더 → 포털 INSERT)인데,
 * {@code EVNT_ANNO_CN}(event_annotation 동결 payload)이 <b>앞 3단에서 통째로 빠져</b> 있었다.
 * 같은 클래스 안에서 동결 스냅샷 빌더와 멱등 해시는 이 값을 이미 반영하고 있었으므로, 값은 동결까지
 * 정상으로 흐르다가 <b>발신함 payload 직렬화 입구에서만</b> 끊겼다.
 *
 * <h3>SQL 축 가드와의 관계 — 두 가드가 합쳐져 전 구간을 덮는다</h3>
 * {@link PortalMetaReplicaColumnParityGuardTest} 는 <b>SQL 축</b>(엔티티 ↔ 포털 INSERT 컬럼 목록)을 덮고,
 * 이 가드는 그 앞의 <b>payload 축</b>을 덮는다. 어느 하나만으로는 결손이 통과한다 —
 * 실사고 당시 SQL 만 고쳤을 때 DDL·SQL 은 맞는데 payload 가 값을 나르지 않아 복제본이 여전히 비었다.
 *
 * <h3>왜 스프링을 띄우지 않는가</h3>
 * 판정에 필요한 것은 직렬화·복원 두 순수 함수와 엔티티 매핑뿐이라 컨텍스트·DB 가 필요 없다.
 * 새 {@code @SpringBootTest} 는 캐시 컨텍스트를 늘려 {@code TestContextDiversityRatchetTest} 상한에 걸린다.
 */
class PortalMetaReplicaPayloadParityGuardTest {

    /**
     * 관리 컬럼 — 복제 시 워커가 채우거나 DB 가 생성하므로 payload 계약에 싣지 않는다(의도된 제외).
     *
     * <p><b>이 목록은 가드의 탈출구다.</b> 여기에 컬럼을 넣으면 아래 계약 정합·왕복 시험이 그 컬럼을
     * 아예 보지 않게 되어 <b>가드가 조용히 침묵</b>한다. 그래서 두 겹으로 막는다 —
     * ① 제외 사유를 컬럼마다 값으로 강제하고(빈 사유 금지),
     * ② 명단 자체를 {@link #PINNED_MANAGEMENT_FIELDS} 로 못박아
     * {@link #managementFieldExclusions_cannotGrowSilently()} 가 증감을 실패로 잡는다.
     * 즉 항목을 늘리려면 그 시험이 먼저 붉어지므로, "그 값을 복제본에서 어떻게 얻는가"를 답한 뒤
     * 두 곳을 의도적으로 함께 고쳐야 한다.
     */
    private static final Map<String, String> MANAGEMENT_FIELDS = Map.of(
            "metaSnpshtSn", "복제본 DB 가 생성하는 식별자 — 원본 PK 를 옮기면 두 DB 의 채번이 충돌한다",
            "activeYn", "복제 write 경로(deactivate → upsert → activate)가 복제본에서 스스로 정한다",
            "regDt", "복제본에 행이 생긴 시각 — 원본 동결 시각과 다른 사실이라 옮기지 않는다",
            "regId", "복제를 수행한 주체 표기 — 원본 동결 주체와 다른 사실이라 옮기지 않는다");

    /**
     * 위 제외 목록의 확정 명단. 목록이 말없이 늘거나 줄지 못하게 하는 두 번째 못이며,
     * 상수 하나만 고쳐서는 제외가 성립하지 않도록 {@link #MANAGEMENT_FIELDS} 와 이중화한다.
     */
    private static final Set<String> PINNED_MANAGEMENT_FIELDS =
            Set.of("metaSnpshtSn", "activeYn", "regDt", "regId");

    /**
     * outbox 키를 권위값으로 쓰는 필드 — payload 에도 실리지만 복원은 outbox 컬럼에서 한다.
     * 왕복 검증 시 outbox 를 같은 값으로 만들어 대조에 포함시킨다(제외하지 않는다).
     */
    private static final Set<String> OUTBOX_AUTHORITATIVE_FIELDS = Set.of("rawSn", "snpshtHash");

    /** 프로덕션과 같은 well-known 모듈 구성(JavaTimeModule 포함)의 매퍼. */
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    // ── ⓪ 가드 자신의 탈출구 봉쇄 ─────────────────────────────────────────────

    @Test
    @DisplayName("payload_제외목록은_말없이_늘어날_수_없다_가드_탈출구_봉쇄")
    void managementFieldExclusions_cannotGrowSilently() {
        // ① 명단 증감 자체가 실패다 — 제외는 "가드가 그 컬럼을 안 본다"는 뜻이라 조용히 늘면 안 된다.
        assertThat(MANAGEMENT_FIELDS.keySet())
                .as("제외 목록에 컬럼을 넣으면 계약 정합·왕복 시험이 그 컬럼을 보지 않는다(가드 침묵). "
                        + "늘리려면 「그 값을 복제본에서 어떻게 얻는가」를 먼저 답하고 이 확정 명단도 함께 "
                        + "고칠 것(INT-009). 확정 명단=%s / 현재=%s",
                        PINNED_MANAGEMENT_FIELDS, MANAGEMENT_FIELDS.keySet())
                .containsExactlyInAnyOrderElementsOf(PINNED_MANAGEMENT_FIELDS);

        // ② 사유 없는 제외 금지 — 근거를 남기지 않으면 다음 사람이 판단을 되짚을 수 없다.
        MANAGEMENT_FIELDS.forEach((field, reason) -> assertThat(reason)
                .as("제외 컬럼 %s 에는 제외 사유(복제본에서 그 값을 어떻게 얻는가)가 있어야 한다", field)
                .isNotBlank()
                .hasSizeGreaterThan(15));

        // ③ 제외 이름이 엔티티에 실재해야 한다 — 개명되면 제외가 조용히 무효가 되거나
        //    반대로 실재하지 않는 이름이 남아 명단이 사실과 어긋난다.
        Set<String> declared = new LinkedHashSet<>();
        for (Field field : LsDatasetVideoMeta.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                declared.add(field.getName());
            }
        }
        assertThat(declared)
                .as("제외 목록의 이름이 엔티티에 실재해야 한다(개명 시 이 가드를 함께 갱신할 것)")
                .containsAll(MANAGEMENT_FIELDS.keySet());
    }

    // ── ① 계약 정합 — 엔티티 동결 내용 ↔ payload record 컴포넌트 ─────────────────

    @Test
    @DisplayName("엔티티_동결내용_컬럼과_payload_계약_컴포넌트가_정확히_일치한다")
    void payloadComponents_matchEntityContentFields() {
        // given
        List<String> entityContentFields = entityContentFields();
        List<String> payloadComponents = payloadComponents();

        // then: 순서까지 동일 — 세 축(엔티티·payload·INSERT)을 나란히 대조할 수 있어야 한다.
        assertThat(payloadComponents)
                .as("엔티티에 동결 내용 컬럼이 추가되면 payload 전송 계약도 함께 따라와야 한다(INT-009). "
                        + "엔티티에만 있음=%s / payload 에만 있음=%s",
                        minus(entityContentFields, payloadComponents),
                        minus(payloadComponents, entityContentFields))
                .containsExactlyElementsOf(entityContentFields);
    }

    @Test
    @DisplayName("payload_컴포넌트는_전부_boxed_타입이다_구_페이로드_하위호환의_전제")
    void payloadComponents_areAllBoxedTypes() {
        // primitive 컴포넌트가 하나라도 생기면 키가 없는 구 페이로드 역직렬화가 실패해
        // 미처리 발신함이 전량 dead-letter 로 간다.
        for (RecordComponent component : MetaReplicationPayload.class.getRecordComponents()) {
            assertThat(component.getType().isPrimitive())
                    .as("payload 컴포넌트는 boxed 타입이어야 한다(구 페이로드 키 부재 시 null 복원). 위반=%s",
                            component.getName())
                    .isFalse();
        }
    }

    // ── ② 왕복 — 직렬화가 싣고 복원이 되돌리는지 ────────────────────────────────

    @Test
    @DisplayName("동결내용_전_필드가_payload_직렬화와_복원을_왕복해_보존된다")
    void everyContentField_survivesPayloadRoundTrip() throws Exception {
        // given: 동결 내용 전 필드에 서로 구별되는 값을 채운 스냅샷
        LsDatasetVideoMeta frozen = fullyPopulatedSnapshot();

        // when: 직렬화 → 복원
        LsDatasetVideoMeta restored = roundTrip(frozen);

        // then: 한 필드라도 payload/복원 빌더에서 빠지면 여기서 null 로 떨어져 실패한다.
        List<String> lost = new ArrayList<>();
        for (String field : entityContentFields()) {
            Object before = readField(frozen, field);
            Object after = readField(restored, field);
            if (!sameValue(before, after)) {
                lost.add(field + "(동결=" + before + " / 복제=" + after + ")");
            }
        }
        assertThat(lost)
                .as("동결 내용 필드는 payload 직렬화·복원을 왕복해 보존돼야 한다(INT-009). "
                        + "값이 유실된 필드는 포털 복제본에서 영구히 비어 있게 된다. 유실=%s", lost)
                .isEmpty();
    }

    @Test
    @DisplayName("event_annotation_동결값이_payload로_실려_복제_스냅샷까지_도달한다")
    void eventAnnotation_reachesReplicaSnapshot() throws Exception {
        // given: 실사고 컬럼을 명시적으로 못박는다(왕복 가드가 리팩토링으로 약해져도 이 축은 남는다).
        LsDatasetVideoMeta frozen = fullyPopulatedSnapshot();
        setField(frozen, "evntAnnoCn", "{\"event\":\"fire\",\"caption\":\"연기 발생\"}");

        // when
        String payload = toPayload(frozen);
        LsDatasetVideoMeta restored = restore(payload, frozen.getRawSn(), frozen.getSnpshtHash());

        // then
        assertThat(payload).contains("evntAnnoCn");
        assertThat(restored.getEvntAnnoCn())
                .as("event_annotation 동결값이 payload 를 거쳐 복제 스냅샷까지 도달해야 한다(INT-009)")
                .isEqualTo("{\"event\":\"fire\",\"caption\":\"연기 발생\"}");
    }

    @Test
    @DisplayName("동결내용이_전부_null이어도_직렬화와_복원이_실패하지_않는다")
    void allNullContent_roundTripsWithoutError() {
        LsDatasetVideoMeta empty = newSnapshot(); // 전 필드 null
        assertThatCode(() -> {
            LsDatasetVideoMeta restored = roundTrip(empty);
            assertThat(restored.getEvntAnnoCn()).isNull();
            assertThat(restored.getWthrNm()).isNull();
        }).doesNotThrowAnyException();
    }

    // ── ③ 하위호환 — 이미 적재된 구 페이로드 ────────────────────────────────────

    @Test
    @DisplayName("evntAnnoCn_키가_없는_구_페이로드도_오류없이_복원되고_해당값은_null이_된다")
    void legacyPayloadWithoutEventAnnotation_restoresWithNull() throws Exception {
        // given: 이 변경 이전에 적재돼 아직 처리되지 않은 발신함 행(키 자체가 없다)
        String legacyPayload = "{\"rawSn\":42,\"snpshtHash\":\"abc\",\"vmsClipId\":\"CLIP-42\","
                + "\"wthrNm\":\"맑음\",\"rvwCmplDt\":\"2026-08-26T10:15:30\"}";

        // when
        LsDatasetVideoMeta restored = restore(legacyPayload, 42L, "abc");

        // then: 실패로 돌면 미처리 발신함이 전량 dead-letter 로 간다.
        assertThat(restored.getEvntAnnoCn()).isNull();
        assertThat(restored.getVmsClipId()).isEqualTo("CLIP-42");
        assertThat(restored.getWthrNm()).isEqualTo("맑음");
        assertThat(restored.getRvwCmplDt()).isEqualTo(LocalDateTime.of(2026, 8, 26, 10, 15, 30));
    }

    @Test
    @DisplayName("payload_키가_하나씩_빠져도_복원이_실패하지_않는다_전방_하위호환")
    void anyMissingPayloadKey_doesNotBreakRestore() throws Exception {
        // given: 완전한 payload 에서 키를 하나씩 제거해 본다(앞으로 늘어날 컬럼까지 함께 덮는다).
        String full = toPayload(fullyPopulatedSnapshot());
        ObjectNode node = (ObjectNode) MAPPER.readTree(full);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        assertThat(keys).as("payload 키를 하나도 읽지 못했다 — 직렬화 형태가 바뀌었는지 확인할 것").isNotEmpty();

        // then
        for (String key : keys) {
            ObjectNode without = node.deepCopy();
            without.remove(key);
            String payload = MAPPER.writeValueAsString(without);
            assertThatCode(() -> restore(payload, 1L, "h"))
                    .as("키 '%s' 가 없는 구 페이로드도 복원돼야 한다(하위호환). 실패 시 미처리 발신함이 dead-letter 로 간다", key)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("모르는_키가_있는_페이로드도_복원이_실패하지_않는다_전방호환")
    void unknownPayloadKey_doesNotBreakRestore() throws Exception {
        String full = toPayload(fullyPopulatedSnapshot());
        ObjectNode node = (ObjectNode) MAPPER.readTree(full);
        node.put("someFutureColumn", "value");

        assertThatCode(() -> restore(MAPPER.writeValueAsString(node), 1L, "h"))
                .doesNotThrowAnyException();
    }

    // ── 실행 헬퍼 — 프로덕션 private 경로를 그대로 호출한다 ──────────────────────

    /** {@code DatasetVideoMetaSnapshotService#toPayload} — 발신함 payload 직렬화(프로덕션 코드 그대로). */
    private static String toPayload(LsDatasetVideoMeta snapshot) throws Exception {
        Constructor<DatasetVideoMetaSnapshotService> ctor =
                firstConstructor(DatasetVideoMetaSnapshotService.class);
        Object[] args = new Object[ctor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            // 직렬화에 쓰이는 협력자는 ObjectMapper 뿐 — 나머지는 미사용이라 null 로 둔다.
            args[i] = ObjectMapper.class.equals(ctor.getParameterTypes()[i]) ? MAPPER : null;
        }
        ctor.setAccessible(true);
        DatasetVideoMetaSnapshotService service = ctor.newInstance(args);

        Method toPayload = DatasetVideoMetaSnapshotService.class
                .getDeclaredMethod("toPayload", LsDatasetVideoMeta.class);
        toPayload.setAccessible(true);
        return (String) toPayload.invoke(service, snapshot);
    }

    /** {@code MetaReplicationWorker#toSnapshot} — 발신함 payload → 복제 스냅샷(프로덕션 코드 그대로). */
    private static LsDatasetVideoMeta restore(String payload, Long rawSn, String hash) throws Exception {
        Constructor<MetaReplicationWorker> ctor = firstConstructor(MetaReplicationWorker.class);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (ObjectMapper.class.equals(types[i])) {
                args[i] = MAPPER;
            } else if (int.class.equals(types[i])) {
                args[i] = 1;
            } else {
                args[i] = null; // 복원에 쓰이지 않는 협력자
            }
        }
        ctor.setAccessible(true);
        MetaReplicationWorker worker = ctor.newInstance(args);

        Method toSnapshot = MetaReplicationWorker.class
                .getDeclaredMethod("toSnapshot", LsMetaReplOutbox.class);
        toSnapshot.setAccessible(true);
        return (LsDatasetVideoMeta) toSnapshot.invoke(worker, LsMetaReplOutbox.create(rawSn, hash, payload));
    }

    private static LsDatasetVideoMeta roundTrip(LsDatasetVideoMeta frozen) throws Exception {
        return restore(toPayload(frozen), frozen.getRawSn(), frozen.getSnpshtHash());
    }

    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> firstConstructor(Class<T> type) {
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        Constructor<?> widest = ctors[0];
        for (Constructor<?> c : ctors) {
            if (c.getParameterCount() > widest.getParameterCount()) {
                widest = c;
            }
        }
        return (Constructor<T>) widest;
    }

    // ── 리플렉션 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * 엔티티의 <b>동결 내용</b> 필드 목록(선언 순서) — 관리 컬럼은 제외한다.
     * 컬럼명을 문자열로 추측하지 않고 {@code @Column} 매핑 보유 여부로 판정한다.
     */
    private static List<String> entityContentFields() {
        List<String> fields = new ArrayList<>();
        for (Field field : LsDatasetVideoMeta.class.getDeclaredFields()) {
            if (field.isSynthetic() || field.isAnnotationPresent(Id.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column == null || MANAGEMENT_FIELDS.containsKey(field.getName())) {
                continue;
            }
            fields.add(field.getName());
        }
        assertThat(fields)
                .as("엔티티 @Column 매핑을 읽지 못했다 — 매핑 방식이 바뀌었다면 이 가드를 함께 갱신할 것")
                .isNotEmpty();
        return fields;
    }

    private static List<String> payloadComponents() {
        List<String> names = new ArrayList<>(new LinkedHashSet<>());
        for (RecordComponent component : MetaReplicationPayload.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** 전 동결 내용 필드에 타입별로 서로 구별되는 값을 채운다(새 컬럼도 자동 포함). */
    private static LsDatasetVideoMeta fullyPopulatedSnapshot() throws Exception {
        LsDatasetVideoMeta snapshot = newSnapshot();
        int seed = 1;
        for (String name : entityContentFields()) {
            setField(snapshot, name, sampleValue(fieldOf(name).getType(), name, seed++));
        }
        // outbox 키 권위 필드는 복원 시 outbox 에서 오므로 왕복 대조가 성립하도록 값을 맞춘다.
        assertThat(OUTBOX_AUTHORITATIVE_FIELDS).allSatisfy(f ->
                assertThat(readField(snapshot, f)).as("outbox 권위 필드 %s 가 채워져야 한다", f).isNotNull());
        return snapshot;
    }

    private static Object sampleValue(Class<?> type, String name, int seed) {
        if (String.class.equals(type)) {
            return name + "-" + seed;
        }
        if (Long.class.equals(type)) {
            return (long) seed;
        }
        if (Integer.class.equals(type)) {
            return seed;
        }
        if (BigDecimal.class.equals(type)) {
            return new BigDecimal(seed + ".500000");
        }
        if (LocalDateTime.class.equals(type)) {
            return LocalDateTime.of(2026, 8, 26, 10, 15, 30).plusSeconds(seed);
        }
        throw new AssertionError("샘플값을 만들 수 없는 타입이 엔티티에 추가됐다: " + type.getName()
                + " (필드 " + name + ") — 이 가드의 sampleValue 를 함께 갱신할 것");
    }

    /** BigDecimal 은 표기 편차(스케일)를 무시하고 값으로 비교한다. */
    private static boolean sameValue(Object before, Object after) {
        if (before instanceof BigDecimal a && after instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        return before == null ? after == null : before.equals(after);
    }

    private static LsDatasetVideoMeta newSnapshot() {
        try {
            Constructor<LsDatasetVideoMeta> ctor = LsDatasetVideoMeta.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("엔티티 기본 생성자를 찾지 못했다", e);
        }
    }

    private static Field fieldOf(String name) throws NoSuchFieldException {
        Field field = LsDatasetVideoMeta.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(LsDatasetVideoMeta target, String name, Object value) throws Exception {
        fieldOf(name).set(target, value);
    }

    private static Object readField(LsDatasetVideoMeta target, String name) {
        try {
            return fieldOf(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("필드를 읽지 못했다: " + name, e);
        }
    }

    private static List<String> minus(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(right);
        return result;
    }
}
