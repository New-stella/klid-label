package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VlmDescriptionPolicy} 순수 함수 단위 테스트 — {@code video.vd_description} 조달 규칙. [req: R10]
 *
 * <h3>고정하는 규칙(사용자 확정 우선순위)</h3>
 * <ol>
 *   <li>{@code vlm.description}(verify 서술 전문)이 있으면 <b>그 값</b></li>
 *   <li>없고 {@code manual-timeseries}(사람이 직접 쓴 전문)가 있으면 <b>그 값</b></li>
 *   <li>없고 <b>보존된 레거시 구간 행</b>({@code 0-8}·{@code 8-16} …)만 있으면
 *       <b>{@code start_sec} 오름차순</b>으로 이어붙인 값</li>
 *   <li>모두 없으면 <b>{@code null}</b> — 값을 지어내지 않는다(빈 문자열도 아니다)</li>
 * </ol>
 */
class VlmDescriptionPolicyTest {

    private static LsDataMeta meta(String key, String value) {
        return LsDataMeta.create(42L, key, value);
    }

    // ---------- 우선순위 1: verify 서술 ----------

    @Test
    @DisplayName("verify_서술이_있으면_그_값이_vd_description에_들어간다")
    void verify_서술이_있으면_그_값이_vd_description에_들어간다() {
        // given
        List<LsDataMeta> metas = List.of(
                meta("video.fps", "30.0"),
                meta(VlmResultService.META_KEY_DESCRIPTION, "보행자가 횡단보도를 건넌다."));

        // when
        String actual = VlmDescriptionPolicy.resolve(metas);

        // then
        assertThat(actual).isEqualTo("보행자가 횡단보도를 건넌다.");
    }

    @Test
    @DisplayName("verify_서술과_레거시가_함께_있으면_verify_서술이_우선한다")
    void verify_서술과_레거시가_함께_있으면_verify_서술이_우선한다() {
        // given — 레거시 구간이 남아 있어도(보존 정책) 전문이 우선이다.
        List<LsDataMeta> metas = List.of(
                meta("0-8", "구간 A"),
                meta("8-16", "구간 B"),
                meta(VlmResultService.META_KEY_DESCRIPTION, "검증 서술 전문"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("검증 서술 전문");
    }

    // ---------- 우선순위 2: 사람이 직접 쓴 전문(manual-timeseries) ----------

    @Test
    @DisplayName("manual_timeseries로_작성한_전문이_vd_description에_들어간다")
    void manual_timeseries로_작성한_전문이_vd_description에_들어간다() {
        // given — 편집 가능한 항목이 하나도 없는 영상에서 사람이 신규 슬롯에 직접 쓴 전문.
        List<LsDataMeta> metas = List.of(
                meta("video.fps", "30.0"),
                meta(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "작업자가 직접 쓴 상황묘사"));

        // when / then — 조달에서 빠지면 사람이 쓴 전문이 산출물에 실리지 않는다(조용한 손실).
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("작업자가 직접 쓴 상황묘사");
    }

    @Test
    @DisplayName("manual_timeseries와_레거시가_함께_있으면_manual이_우선한다")
    void manual_timeseries와_레거시가_함께_있으면_manual이_우선한다() {
        // given — 레거시 구간은 폐기된 자동 산출물이고 manual 은 사람이 직접 쓴 전문이다.
        //   FE 는 레거시뿐인 영상에도 manual 신규 슬롯을 띄운다(그 구간은 편집 대상이 아니다).
        List<LsDataMeta> metas = List.of(
                meta("0-8", "구간 A"),
                meta("8-16", "구간 B"),
                meta(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "사람이 쓴 전문"));

        // when / then — 사람 입력이 옛 자동 산출보다 우선한다.
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("사람이 쓴 전문");
    }

    @Test
    @DisplayName("vlm_description과_manual이_함께_있으면_vlm_description이_우선한다")
    void vlm_description과_manual이_함께_있으면_vlm_description이_우선한다() {
        // given — FE 는 전문 키가 있으면 manual 슬롯을 띄우지 않으므로 정상 상태에서 둘은 공존하지 않는다.
        //   공존하면 과거 데이터이며, 현행 편집 대상인 vlm.description 이 최신이다.
        List<LsDataMeta> metas = List.of(
                meta(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "옛 수기 전문"),
                meta(VlmResultService.META_KEY_DESCRIPTION, "검증 서술 전문"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("검증 서술 전문");
    }

    @Test
    @DisplayName("manual_timeseries가_공백이면_레거시로_내려간다")
    void manual_timeseries가_공백이면_레거시로_내려간다() {
        // given — blank 는 "미입력" 으로 다룬다(프로젝트 공통 규약, 전문 키와 동일).
        List<LsDataMeta> metas = List.of(
                meta(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY, "   "),
                meta("0-8", "seg-00"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("seg-00");
    }

    // ---------- 우선순위 3: 레거시 구간 이어붙임 ----------

    @Test
    @DisplayName("레거시_구간만_있으면_start_sec_오름차순으로_이어붙인다")
    void 레거시_구간만_있으면_start_sec_오름차순으로_이어붙인다() {
        // given — 10 구간 초과. 문자열 정렬이면 "10-18" 이 "8-16" 앞으로 온다(HIGH 시나리오 2).
        //   입력 순서도 뒤섞어 정렬이 실제로 수행되는지 본다.
        List<LsDataMeta> metas = new ArrayList<>(List.of(
                meta("80-88", "seg-80"),
                meta("8-16", "seg-08"),
                meta("104-112", "seg-104"),
                meta("0-8", "seg-00"),
                meta("16-24", "seg-16"),
                meta("96-104", "seg-96"),
                meta("24-32", "seg-24"),
                meta("32-40", "seg-32"),
                meta("40-48", "seg-40"),
                meta("48-56", "seg-48"),
                meta("56-64", "seg-56"),
                meta("64-72", "seg-64"),
                meta("72-80", "seg-72"),
                meta("88-96", "seg-88")));
        Collections.shuffle(metas, new java.util.Random(7));

        // when
        String actual = VlmDescriptionPolicy.resolve(metas);

        // then — 숫자 오름차순 (0,8,16,...,104)
        assertThat(actual).isEqualTo(String.join("\n",
                "seg-00", "seg-08", "seg-16", "seg-24", "seg-32", "seg-40", "seg-48",
                "seg-56", "seg-64", "seg-72", "seg-80", "seg-88", "seg-96", "seg-104"));
    }

    @Test
    @DisplayName("이어붙임_구분자가_있어_구간_문장이_붙지_않는다")
    void 이어붙임_구분자가_있어_구간_문장이_붙지_않는다() {
        // given
        List<LsDataMeta> metas = List.of(meta("0-8", "앞 문장"), meta("8-16", "뒤 문장"));

        // when / then — 구분자 없이 "앞 문장뒤 문장" 이 되면 안 된다.
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("앞 문장\n뒤 문장");
    }

    @Test
    @DisplayName("구간형이_아닌_키가_섞여도_깨지지_않는다")
    void 구간형이_아닌_키가_섞여도_깨지지_않는다() {
        // given — 기술메타(video.*)·화면전용(vlm.accuracy)·비규격 키 혼재.
        //   ⚠ manual-timeseries 는 여기 넣지 않는다 — 그 키는 무시 대상이 아니라 조달 우선순위 2다.
        List<LsDataMeta> metas = List.of(
                meta("video.fps", "30.0"),
                meta(VlmResultService.META_KEY_ACCURACY, "0.92"),
                meta("8-16", "seg-08"),
                meta("이상한키", "무시 대상"),
                meta("-8", "선행 하이픈"),
                meta("0-", "후행 하이픈"),
                meta("a-b", "숫자 아님"),
                meta("0-8", "seg-00"));

        // when / then — 구간형 2건만 숫자순으로 이어붙는다.
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("seg-00\nseg-08");
    }

    @Test
    @DisplayName("같은_start_sec_구간이_여럿이어도_결정적으로_이어붙는다")
    void 같은_start_sec_구간이_여럿이어도_결정적으로_이어붙는다() {
        // given — end_sec 로 2차 정렬(입력 순서에 의존하지 않는다)
        List<LsDataMeta> metas = List.of(meta("0-16", "긴 구간"), meta("0-8", "짧은 구간"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("짧은 구간\n긴 구간");
    }

    // ---------- 우선순위 3: 원천 없음 → null ----------

    @Test
    @DisplayName("메타가_없으면_vd_description은_null이다")
    void 메타가_없으면_vd_description은_null이다() {
        assertThat(VlmDescriptionPolicy.resolve(List.of())).isNull();
        assertThat(VlmDescriptionPolicy.resolve(null)).isNull();
    }

    @Test
    @DisplayName("빈_문자열이_아니라_null이다")
    void 빈_문자열이_아니라_null이다() {
        // given — 값이 공백뿐이거나 null 인 행만 존재(원천 부재와 동치)
        List<LsDataMeta> metas = new ArrayList<>(List.of(
                meta(VlmResultService.META_KEY_DESCRIPTION, "   "),
                meta("0-8", "\t\n")));
        metas.add(meta("8-16", null));

        // when
        String actual = VlmDescriptionPolicy.resolve(metas);

        // then — "" 로 채우면 "판정했는데 내용이 없다"는 거짓 사실이 된다.
        assertThat(actual).isNull();
    }

    @Test
    @DisplayName("전문이_공백뿐이면_레거시_구간으로_내려간다")
    void 전문이_공백뿐이면_레거시_구간으로_내려간다() {
        // given
        List<LsDataMeta> metas = List.of(
                meta(VlmResultService.META_KEY_DESCRIPTION, "  "),
                meta("0-8", "seg-00"));

        // when / then — blank 는 "미입력" 으로 다룬다(프로젝트 공통 규약).
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("seg-00");
    }

    @Test
    @DisplayName("null_원소가_섞여도_NPE_없이_처리된다")
    void null_원소가_섞여도_NPE_없이_처리된다() {
        // given — 방어적 입력(CWE-20)
        List<LsDataMeta> metas = new ArrayList<>();
        metas.add(null);
        metas.add(meta("0-8", "seg-00"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("seg-00");
    }

    @Test
    @DisplayName("과대_구간초는_구간키로_보지_않는다")
    void 과대_구간초는_구간키로_보지_않는다() {
        // given — long 오버플로 유발 자릿수(11자리)는 구간으로 취급하지 않는다(CWE-20).
        List<LsDataMeta> metas = List.of(
                meta("99999999999-99999999999", "과대"),
                meta("0-8", "seg-00"));

        // when / then
        assertThat(VlmDescriptionPolicy.resolve(metas)).isEqualTo("seg-00");
    }

    // ---------- 재산출 트리거 판정(participates) ----------

    @Test
    @DisplayName("조달에_참여하는_키만_participates_true_다")
    void 조달에_참여하는_키만_participates_true_다() {
        assertThat(VlmDescriptionPolicy.participates(VlmResultService.META_KEY_DESCRIPTION)).isTrue();
        // 사람이 직접 쓴 전문도 조달 축이다 — 빠지면 그 키를 고쳐도 산출물이 옛 값으로 고착된다.
        assertThat(VlmDescriptionPolicy.participates(VlmDescriptionPolicy.MANUAL_TIMESERIES_META_KEY)).isTrue();
        assertThat(VlmDescriptionPolicy.participates("0-8")).isTrue();
        assertThat(VlmDescriptionPolicy.participates("104-112")).isTrue();

        assertThat(VlmDescriptionPolicy.participates(VlmResultService.META_KEY_ACCURACY)).isFalse();
        assertThat(VlmDescriptionPolicy.participates("video.fps")).isFalse();
        assertThat(VlmDescriptionPolicy.participates("a-b")).isFalse();
        assertThat(VlmDescriptionPolicy.participates(null)).isFalse();
    }
}
