package kr.co.cudo.authoring.architecture;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.repository.PortalDatasetVideoMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 복제본 upsert 가 control 원본 upsert 와 <b>같은 컬럼 집합</b>을 싣는지 고정하는 회귀 가드
 * (@design INT-009 — 「복제 범위 — 원본과 동형이며 전 컬럼을 옮긴다」).
 *
 * <h3>왜 이 가드가 필요한가 — 실사고</h3>
 * {@code EVNT_ANNO_CN}(event_annotation 동결 payload)이 control INSERT 에는 있고 포털 INSERT 에만
 * 없어, <b>복제본 DDL 에는 컬럼이 존재하는데 값은 영구히 NULL</b> 이었다. 포털 채널은 이 복제본을
 * read-only 로 소비하므로 그 값을 아예 받지 못했다.
 *
 * <p>이 결함은 <b>컬럼 수를 세는 검사로는 잡히지 않는다</b> — DDL 은 맞고 SQL 만 틀렸기 때문이다.
 * 그래서 판정을 「DDL 대조」가 아니라 <b>「두 {@code @Query} 가 실제로 싣는 컬럼 목록 대조」</b>로 둔다.
 *
 * <h3>왜 스프링을 띄우지 않는가</h3>
 * 판정에 필요한 것은 리포지토리 인터페이스의 {@code @Query} 애노테이션 값과 엔티티의 {@code @Column}
 * 매핑뿐이라 컨텍스트·DB 가 전혀 필요 없다. 새 {@code @SpringBootTest} 를 만들면 캐시되는 테스트
 * 컨텍스트가 늘어 {@code TestContextDiversityRatchetTest} 상한에 걸린다(순수 JUnit 이 정답).
 */
class PortalMetaReplicaColumnParityGuardTest {

    /** 두 리포지토리가 쓰는 물리 테이블. */
    private static final String TABLE = "LS_DATASET_VIDEO_META";

    /** {@code :#{#m.xxx}} SpEL 엔티티 프로퍼티 바인딩에서 프로퍼티명만 뽑는다. */
    private static final Pattern BINDING = Pattern.compile("#m\\.([A-Za-z0-9_]+)");

    /**
     * control 원본 upsert 의 SQL 단계. {@code upsertSnapshot} 은 advisory 락 + 이 메서드를 부르는
     * {@code default} 메서드라 {@code @Query} 를 갖지 않는다 — SQL 은 여기에 있다.
     */
    private static final String CONTROL_METHOD = "insertSnapshotIfAbsent";

    /** 포털 복제본 upsert(= 복제 수행 경로의 최종 write). */
    private static final String PORTAL_METHOD = "upsertSnapshot";

    @Test
    @DisplayName("포털_복제_INSERT_컬럼목록이_control_원본과_완전히_같다")
    void portalInsertColumns_matchControlExactly() {
        // given: 두 리포지토리의 실제 INSERT SQL
        String controlSql = queryOf(LsDatasetVideoMetaRepository.class, CONTROL_METHOD);
        String portalSql = queryOf(PortalDatasetVideoMetaRepository.class, PORTAL_METHOD);

        // when: INSERT 컬럼 목록 파싱
        List<String> controlColumns = insertColumns(controlSql);
        List<String> portalColumns = insertColumns(portalSql);

        // then: 순서까지 동일 — 한쪽에만 컬럼이 생기면 그 값은 복제본에서 영구히 비어 있게 된다.
        assertThat(portalColumns)
                .as("포털 복제 INSERT 는 control 원본과 같은 컬럼 집합을 실어야 한다(INT-009 복제 범위). "
                        + "control 에만 있는 컬럼=%s / 포털에만 있는 컬럼=%s",
                        minus(controlColumns, portalColumns), minus(portalColumns, controlColumns))
                .containsExactlyElementsOf(controlColumns);
    }

    @Test
    @DisplayName("엔티티_전_컬럼이_포털_복제_INSERT에_실린다_식별자_제외")
    void everyEntityColumn_isCarriedByPortalInsert() {
        // given: 엔티티가 매핑한 전 컬럼(식별자는 DB 생성이라 INSERT 대상이 아니다)
        Map<String, String> columnToField = entityColumnToField();
        List<String> portalColumns = insertColumns(queryOf(PortalDatasetVideoMetaRepository.class, PORTAL_METHOD));

        // when: 복제 INSERT 가 빠뜨린 컬럼
        List<String> missing = minus(new ArrayList<>(columnToField.keySet()), portalColumns);

        // then: 엔티티에 컬럼을 새로 추가했는데 복제 경로가 따라오지 않으면 여기서 실패한다.
        assertThat(missing)
                .as("엔티티에 컬럼이 추가되면 포털 복제 INSERT 도 함께 따라와야 한다(INT-009). 누락=%s", missing)
                .isEmpty();

        // 역방향 — 엔티티에 없는 컬럼을 싣고 있으면 매핑이 어긋난 것이다.
        assertThat(minus(portalColumns, new ArrayList<>(columnToField.keySet())))
                .as("포털 복제 INSERT 가 엔티티에 없는 컬럼을 싣고 있다")
                .isEmpty();
    }

    @Test
    @DisplayName("포털_복제_INSERT의_각_컬럼이_대응_엔티티_속성에_바인딩된다")
    void portalInsertBindings_alignWithColumns() {
        // given
        Map<String, String> columnToField = entityColumnToField();
        String portalSql = queryOf(PortalDatasetVideoMetaRepository.class, PORTAL_METHOD);
        List<String> columns = insertColumns(portalSql);
        List<String> values = valueExpressions(portalSql);

        // then: 개수가 어긋나면 VALUES 정렬이 통째로 밀린다(다른 컬럼에 다른 값이 들어간다).
        assertThat(values)
                .as("INSERT 컬럼 수와 VALUES 항목 수가 같아야 한다")
                .hasSameSizeAs(columns);

        // then: i 번째 값 표현식이 i 번째 컬럼의 엔티티 속성을 바인딩해야 한다.
        //       CAST(:#{#m.evntAnnoCn} AS jsonb) 처럼 함수로 감싸도 프로퍼티명만 뽑아 비교한다.
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            String expectedField = columnToField.get(column);
            assertThat(expectedField)
                    .as("엔티티에 매핑되지 않은 컬럼이 INSERT 에 있다: %s", column)
                    .isNotNull();
            assertThat(boundProperty(values.get(i)))
                    .as("컬럼 %s (인덱스 %d) 의 VALUES 표현식이 대응 엔티티 속성을 바인딩해야 한다. 표현식=%s",
                            column, i, values.get(i))
                    .isEqualTo(expectedField);
        }
    }

    // ── 파싱 헬퍼 ──────────────────────────────────────────────────────────────

    /** 리포지토리 인터페이스 메서드의 {@code @Query(value=...)} 를 리플렉션으로 읽는다. */
    private static String queryOf(Class<?> repository, String methodName) {
        for (Method method : repository.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Query query = method.getAnnotation(Query.class);
            if (query != null) {
                return query.value();
            }
        }
        throw new AssertionError(
                "@Query 를 가진 메서드를 찾지 못했다: " + repository.getSimpleName() + "#" + methodName
                        + " — 메서드가 개명·삭제됐다면 이 가드의 상수를 함께 갱신할 것");
    }

    /** {@code INSERT INTO <TABLE> ( ... ) VALUES} 의 컬럼 목록. */
    private static List<String> insertColumns(String sql) {
        String head = between(sql, "INSERT INTO " + TABLE + " (", ") VALUES (",
                "INSERT 컬럼 목록을 찾지 못했다");
        return splitTopLevel(head);
    }

    /** {@code VALUES ( ... )} 의 값 표현식 목록. */
    private static List<String> valueExpressions(String sql) {
        String body = between(sql, ") VALUES (", ") ON CONFLICT",
                "VALUES 목록을 찾지 못했다");
        return splitTopLevel(body);
    }

    private static String between(String sql, String open, String close, String failureMessage) {
        int start = sql.indexOf(open);
        int end = sql.indexOf(close, start + open.length());
        if (start < 0 || end < 0) {
            throw new AssertionError(failureMessage + " — SQL 형태가 바뀌었다면 이 가드의 파서를 함께 갱신할 것");
        }
        return sql.substring(start + open.length(), end);
    }

    /**
     * 괄호 깊이 0 의 콤마로만 분리한다 — {@code CAST(x AS jsonb)} 같은 함수 표현식을 쪼개지 않기 위해.
     * (현재 SQL 에는 다인자 함수가 없지만, 생기더라도 파서가 조용히 틀리지 않도록 한다.)
     */
    private static List<String> splitTopLevel(String csv) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : csv.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    /** 값 표현식에서 바인딩된 엔티티 프로퍼티명을 뽑는다(정확히 1개여야 한다). */
    private static String boundProperty(String valueExpression) {
        Matcher matcher = BINDING.matcher(valueExpression);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(found)
                .as("값 표현식은 엔티티 프로퍼티 하나만 바인딩해야 한다(문자열 결합 금지 — CWE-89). 표현식=%s",
                        valueExpression)
                .hasSize(1);
        return found.get(0);
    }

    /**
     * 엔티티의 {@code @Column} 매핑(컬럼명 → 필드명). 식별자({@code @Id})는 DB 생성이라 INSERT 대상이
     * 아니므로 제외한다. 컬럼명을 문자열로 추측(snake→camel)하지 않고 매핑에서 직접 얻어 오탐을 없앤다.
     */
    private static Map<String, String> entityColumnToField() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Field field : LsDatasetVideoMeta.class.getDeclaredFields()) {
            if (field.isSynthetic() || field.isAnnotationPresent(Id.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column == null || column.name().isBlank()) {
                continue;
            }
            mapping.put(column.name(), field.getName());
        }
        assertThat(mapping)
                .as("엔티티 @Column 매핑을 읽지 못했다 — 매핑 방식이 바뀌었다면 이 가드를 함께 갱신할 것")
                .isNotEmpty();
        return mapping;
    }

    private static List<String> minus(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(right);
        return result;
    }
}
