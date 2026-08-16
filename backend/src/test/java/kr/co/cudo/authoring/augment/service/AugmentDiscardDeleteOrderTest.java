package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 실삭제 <b>순서 · 대상 드리프트 가드</b> (H4 · FIX-5).
 *
 * <p>파생 1건을 지우려면 FK 가 없는 테이블까지 손으로 지워야 하고, 순서를 틀리면 ①FK 위반으로 전량
 * 실패하거나 ②조용히 고아가 남는다. 순서는 {@link AugmentDiscardPurgeTxService#DELETE_ORDER} 가
 * 고정하는데, 그것이 <b>주석뿐인 상수</b>가 되면 SQL 만 바뀌어도 아무도 모른다.
 *
 * <h2>세 축을 함께 본다 (FIX-F)</h2>
 * <ol>
 *   <li><b>순서(선언)</b> — 선언 순서가 상수와 같은가.</li>
 *   <li><b>대상 집합</b> — 리포지토리의 <b>모든</b> {@code DELETE FROM} 문을 전수 수집해 상수와
 *       대조한다. 구 구현은 메서드 이름 화이트리스트만 훑어, <b>새 {@code DELETE} 메서드를 추가해도</b>
 *       두 리스트가 여전히 일치해 통과했다(실제로 잡는 것은 기존 문장의 대상 변경과 메서드 삭제뿐이었다).
 *       삭제 대상이 느는 것이야말로 가장 위험한 변경이므로 전수 수집으로 바꾼다.</li>
 *   <li><b>순서(집행)</b> — {@code AugmentDiscardPurgeTxService.deleteChildren} 이 <b>실제로 호출하는
 *       순서</b>가 상수와 같은가. 구 구현은 선언 목록과만 대조해 <b>집행 순서를 뒤바꿔도 초록</b>이었고,
 *       그 경우 FK 위반(전량 실패) 또는 조용한 고아가 그대로 재현된다.</li>
 * </ol>
 *
 * <h2>왜 {@code @Query} 스캔만으로는 부족한가 (FIX-F)</h2>
 * <p>대상 전수 수집은 {@code @Query} 문자열을 읽는다. 그래서 {@code int deleteByNewRawSn(Long)} 같은
 * <b>파생 쿼리 메서드</b>를 추가하면 SQL 이 어디에도 없어 수집되지 않고 두 축 모두 통과한다. 삭제 대상이
 * 정적으로 드러나야 하므로 <b>선언된 delete/remove 메서드는 전부 {@code @Query} 여야 한다</b>고 못박고,
 * 상속분({@code deleteById}/{@code deleteAll…})은 집행 축의 {@code verifyNoMoreInteractions} 가 잡는다.
 */
class AugmentDiscardDeleteOrderTest {

    /** 공백·개행에 의존하지 않는 대상 추출(문장이 여러 줄이든 한 줄이든 동일 판정). */
    private static final Pattern DELETE_TARGET =
            Pattern.compile("DELETE\\s+FROM\\s+([A-Za-z_0-9]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 절대 삭제 대상이 될 수 없는 테이블 — 이름이 비슷해 오타 한 번에 겨눠질 수 있는 것들.
     * {@code LS_LABEL_ATTR}(마스터 속성 <b>정의</b>)를 지우면 전체 프로젝트의 라벨 속성 정의가 사라진다.
     */
    private static final Set<String> FORBIDDEN_TARGETS = Set.of(
            "LS_LABEL", "LS_LABEL_ATTR", "LS_LABEL_PRESET", "LS_LABEL_PRESET_CODE");

    private static final Long DSCD_SN = 4001L;
    private static final Long RAW_SN = 5002L;
    private static final Long AUG_SN = 6003L;

    /**
     * 테이블 → <b>집행 시점에 호출돼야 하는 리포지토리 메서드</b>. 키 순서는
     * {@link AugmentDiscardPurgeTxService#DELETE_ORDER} 와 정확히 같아야 한다(테스트가 대조한다).
     */
    private static final Map<String, Consumer<LsDataAugDscdRepository>> EXECUTION_BY_TABLE =
            new LinkedHashMap<>();

    static {
        EXECUTION_BY_TABLE.put("LS_DATA_LBL_ATTR_VAL", r -> r.deleteDerivativeLabelAttrValues(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_AUG_LBL_MAP", r -> r.deleteDerivativeLabelMaps(RAW_SN, AUG_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_LBL_HSTRY", r -> r.deleteDerivativeLabelHistory(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_LBL", r -> r.deleteDerivativeLabels(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_SRC_HSTRY", r -> r.deleteDerivativeFrameHistory(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_AUG_RVW", r -> r.deleteDerivativeReviews(AUG_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_AUG", r -> r.deleteDerivativeAugment(AUG_SN, RAW_SN));
        EXECUTION_BY_TABLE.put("LS_ISSUE_COMMENT", r -> r.deleteDerivativeIssueComments(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_EVNT_ANNO_REVIEW",
                r -> r.deleteDerivativeEventAnnotationReviews(RAW_SN));
        EXECUTION_BY_TABLE.put("LS_DATA_RAW",
                r -> r.deleteDiscardedDerivativeRaw(eq(RAW_SN), eq(DSCD_SN), any()));
    }

    /** 리포지토리에 선언된 순서대로의 DELETE 메서드. 집합 검증이 누락·추가를 별도로 잡는다. */
    private static final List<String> METHOD_ORDER = List.of(
            "deleteDerivativeLabelAttrValues",
            "deleteDerivativeLabelMaps",
            "deleteDerivativeLabelHistory",
            "deleteDerivativeLabels",
            "deleteDerivativeFrameHistory",
            "deleteDerivativeReviews",
            "deleteDerivativeAugment",
            "deleteDerivativeIssueComments",
            "deleteDerivativeEventAnnotationReviews",
            "deleteDiscardedDerivativeRaw");

    @Test
    @DisplayName("실삭제_SQL_대상_테이블이_고정된_순서_상수와_일치한다")
    void deleteStatementsMatchDeclaredOrder() {
        List<String> actual = new ArrayList<>();
        for (String methodName : METHOD_ORDER) {
            actual.add(deleteTargetOf(methodName));
        }
        assertThat(actual).isEqualTo(AugmentDiscardPurgeTxService.DELETE_ORDER);
    }

    @Test
    @DisplayName("리포지토리의_모든_DELETE_문이_고정된_대상_집합_안에_있다")
    void everyDeleteStatementIsDeclaredInTheOrderConstant() {
        // 메서드 이름 화이트리스트를 훑는 것이 아니라 <전수> 수집한다 — 새 DELETE 메서드를 추가하면
        // 그 순간 여기서 드러나야 한다(삭제 대상이 느는 것이 가장 위험한 변경이다).
        Set<String> declared = new LinkedHashSet<>(AugmentDiscardPurgeTxService.DELETE_ORDER);
        Set<String> found = new LinkedHashSet<>(allDeleteTargets());

        assertThat(found)
                .as("리포지토리의 DELETE 대상이 DELETE_ORDER 와 정확히 일치해야 한다 "
                        + "(추가/삭제 모두 상수와 순서·집행 코드를 함께 갱신할 것)")
                .isEqualTo(declared);
        assertThat(METHOD_ORDER)
                .as("DELETE 메서드 수와 순서 목록 크기가 어긋났다 — 새 DELETE 메서드를 순서 목록에도 넣어라")
                .hasSize(found.size());
    }

    @Test
    @DisplayName("라벨_마스터_속성정의_등_금지_테이블은_삭제_대상이_아니다")
    void labelMasterAttributeDefinitionIsNeverDeleted() {
        // 구 구현은 doesNotContain("DELETE FROM LS_LABEL ") 처럼 <후행 공백>에 의존해
        // "DELETE FROM LS_LABEL\n" 형태를 통과시켰다. 대상 이름을 정규식으로 뽑아 정확 비교한다.
        assertThat(allDeleteTargets()).doesNotContainAnyElementsOf(FORBIDDEN_TARGETS);
    }

    /**
     * ★ FIX-F ① — <b>파생 쿼리 메서드 금지.</b> 선언된 삭제 메서드는 전부 {@code @Query} 여야 한다.
     * 이름만으로 SQL 이 생성되는 메서드({@code deleteByNewRawSn} 등)를 추가하면 대상 테이블이 정적으로
     * 드러나지 않아 위 전수 수집이 통째로 눈이 먼다.
     */
    @Test
    @DisplayName("삭제_메서드는_모두_명시_Query_다_파생쿼리는_금지된다")
    void deleteMethodsAreAllExplicitQueries() {
        List<String> derived = new ArrayList<>();
        for (Method m : LsDataAugDscdRepository.class.getDeclaredMethods()) {
            String name = m.getName();
            if (!name.startsWith("delete") && !name.startsWith("remove")) {
                continue;
            }
            Query query = m.getAnnotation(Query.class);
            if (query == null || !DELETE_TARGET.matcher(query.value()).find()) {
                derived.add(name);
            }
        }

        assertThat(derived)
                .as("파생 쿼리 메서드는 삭제 대상이 정적으로 드러나지 않아 순서·대상 가드를 통째로 무력화한다: %s",
                        derived)
                .isEmpty();
    }

    /**
     * ★ FIX-F ② — <b>집행 순서</b>가 상수와 같은가. 선언 목록만 보면 {@code deleteChildren} 안에서
     * 호출 순서를 뒤바꿔도 초록이다(FK 위반 또는 조용한 고아가 그대로 재현된다).
     *
     * <p>{@code verifyNoMoreInteractions} 로 <b>표에 없는 삭제 호출</b>(상속받은 {@code deleteById} 등)도
     * 함께 막는다 — 새 삭제를 추가하면 이 표와 {@code DELETE_ORDER} 를 같이 갱신해야 한다.
     */
    @Test
    @DisplayName("실제_집행_호출_순서가_고정된_순서_상수와_일치한다")
    void executionOrderMatchesTheOrderConstant() {
        // given
        LsDataAugDscdRepository repository = mock(LsDataAugDscdRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        AugmentDiscardProperties properties =
                new AugmentDiscardProperties(true, 7, 3_600_000L, 600_000L, 50, 60, 5);

        LsDataAugDscd discard = mock(LsDataAugDscd.class);
        given(discard.isClaimed()).willReturn(true);
        given(discard.getNewRawSn()).willReturn(RAW_SN);
        given(discard.getDataAugSn()).willReturn(AUG_SN);
        LsDataRaw raw = mock(LsDataRaw.class);
        given(raw.isDerivative()).willReturn(true);

        given(repository.findById(DSCD_SN)).willReturn(Optional.of(discard));
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        given(repository.countApprovedStatus(RAW_SN)).willReturn(0L);
        given(repository.deleteDerivativeAugment(AUG_SN, RAW_SN)).willReturn(1);
        given(repository.deleteDiscardedDerivativeRaw(eq(RAW_SN), eq(DSCD_SN), any())).willReturn(1);

        // when
        new AugmentDiscardPurgeTxService(repository, videoRepository, properties)
                .purge(DSCD_SN, LocalDateTime.now().minusDays(30));

        // then: 호출 순서가 DELETE_ORDER 그대로여야 한다
        assertThat(EXECUTION_BY_TABLE.keySet())
                .as("집행 검증 표가 DELETE_ORDER 와 어긋났다 — 새 삭제는 양쪽을 함께 갱신할 것")
                .containsExactlyElementsOf(AugmentDiscardPurgeTxService.DELETE_ORDER);
        InOrder inOrder = inOrder(repository);
        for (String table : AugmentDiscardPurgeTxService.DELETE_ORDER) {
            EXECUTION_BY_TABLE.get(table).accept(inOrder.verify(repository));
        }

        // and: 표에 없는 삭제 호출(상속 CRUD 포함)이 끼어들지 않았다
        verify(repository).findById(DSCD_SN);
        verify(repository).countApprovedStatus(RAW_SN);
        verifyNoMoreInteractions(repository);
    }

    /** 리포지토리의 모든 {@code @Query} 에서 DELETE 대상 테이블명을 전수 수집한다(대문자 정규화). */
    private List<String> allDeleteTargets() {
        List<String> targets = new ArrayList<>();
        for (Method m : LsDataAugDscdRepository.class.getDeclaredMethods()) {
            Query query = m.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            Matcher matcher = DELETE_TARGET.matcher(query.value());
            while (matcher.find()) {
                targets.add(matcher.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return targets;
    }

    private String deleteTargetOf(String methodName) {
        for (Method m : LsDataAugDscdRepository.class.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            Query query = m.getAnnotation(Query.class);
            assertThat(query).as("%s 에 @Query 가 없다", methodName).isNotNull();
            Matcher matcher = DELETE_TARGET.matcher(query.value());
            assertThat(matcher.find()).as("%s 는 DELETE 문이어야 한다", methodName).isTrue();
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        throw new AssertionError("메서드를 찾을 수 없다: " + methodName);
    }
}
