package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 포털 보존기간의 <b>단일 판정 지점</b> — 읽기 축(만료 예정 시각)과 삭제 축(커트라인)을 함께 소유한다.
 * @design DFEAT-055, AC-1068, AC-1070
 *
 * <h3>두 축은 역함수다 — 한쪽만 여기 두면 갈린다</h3>
 * <p>읽기 축은 {@code 기준점 + 보존일수}(만료 예정 시각), 삭제 축은 {@code 지금 − 보존일수}(커트라인)로
 * <b>같은 사실의 두 표현</b>이다({@code 기준점 + N < 지금} ⟺ {@code 기준점 < 지금 − N}). 그래서 삭제
 * 배치가 자기 클래스에서 {@code now().minusDays(...)} 를 다시 유도하면 <b>화면이 고지한 만료일과 실제
 * 삭제일이 따로 논다</b>. 커트라인 산술도 이 클래스가 내주고({@link #datamartCutoff()}·
 * {@link #uploadCutoffs()}) 소비자는 받아 쓰기만 한다.
 *
 * <h3>저장하지 않는다 — 조회 시점 파생값이다</h3>
 * <p>AC-1068(데이터마트)·AC-1070(업로드)이 이 값을 <b>조회 시점 설정값 기준 파생값</b>으로 함께
 * 못박았다. 보존기간을 7일에서 14일로
 * 바꾸면 <b>이미 저장된 라벨의 만료 예정도 다음 조회부터 즉시</b> 달라져야 하므로, 엔티티 컬럼으로
 * 굳히거나 계산 결과를 캐시하지 않는다(마이그레이션 대상 아님). 설정값 자체는
 * {@code SystemConfigService} 의 Caffeine 캐시(TTL 60s)를 타지만 그 캐시는 설정 갱신 시
 * {@code allEntries=true} 로 비워지므로 즉시 반영이 성립한다.
 *
 * <h3>기준점이 축마다 다르다 — 한쪽 축의 확정을 다른 축으로 옮기지 말 것</h3>
 * <table>
 *   <caption>보존기간 기준점</caption>
 *   <tr><th>축</th><th>기준점</th><th>설정 키</th></tr>
 *   <tr><td>데이터마트 라벨</td><td>그 (사용자, 영상) 본인 저장 라벨의 {@code MIN(REG_DT)} — <b>최초</b> 저장 시각</td>
 *       <td>{@code portal.datamart.retention-days}</td></tr>
 *   <tr><td>업로드 {@code UPLOADED}(마킹 대기)</td><td>자산 {@code REG_DT}(등록일) — 그 상태에는
 *       프레임이 없어 라벨이 있을 수 없으므로 「늦은 쪽」 조건을 두지 않는다</td>
 *       <td>{@code portal.upload.retention-days} <b>재사용</b></td></tr>
 *   <tr><td>업로드 {@code READY}</td><td>자산 {@code REG_DT} 와 그 자산 라벨 {@code MAX(REG_DT)} 중 <b>늦은 쪽</b></td>
 *       <td>{@code portal.upload.retention-days}</td></tr>
 *   <tr><td>업로드 {@code FAILED}</td><td>{@code MDFCN_DT}(FAILED 전이 시각)</td>
 *       <td>{@code portal.upload.failed-retention-days}</td></tr>
 *   <tr><td>업로드 {@code PROCESSING}</td><td colspan="2">만료 없음 — {@code null}</td></tr>
 * </table>
 * <p>업로드 축들은 <b>독립 판정</b>이다(AC-1070) — 같은 시각에 등록된 READY 자산과 FAILED 자산은 서로
 * 다른 보존기간으로 계산된다. 마킹 대기와 준비 완료는 <b>같은 설정을 공유</b>하되(같은 정상 자산 축이라
 * 새 설정 키를 만들지 않는다) 기산점이 다르다.
 *
 * <h3>★ 마킹 대기의 보존기간을 「방치 판정」과 혼동하지 말 것 (2026-09-05 확정)</h3>
 * <p>2026-09-02 에 <b>닫은</b> 것은 「분 단위 방치 타이머가 마킹 대기 자산을 <b>실패로 마감</b>하던
 * 것」이고, 여기서 <b>여는</b> 것은 「일 단위 보존기간으로 <b>정상 만료</b>시키는 것」이다. 타이머
 * 길이도 종착점도 다른 별개 경로다(AC-1070). 마킹 대기는 사람이 아직 마킹하지 않은 <b>정상 상태</b>라
 * 방치 전이의 출발점이 아니며 그 출발점은 {@code PROCESSING} 하나로 남는다. 이 구분을 모르면
 * <i>"그거 닫았던 거 아니냐"</i> 며 되돌리게 되므로 근거를 남긴다 — 이 반전 전에는 마킹하지 않은
 * 자산이 어느 스윕에도 걸리지 않아 <b>파일째 영구히</b> 남았다.
 *
 * <p>★ <b>데이터마트만 「최초」이고 업로드 READY 는 「늦은 쪽」이다 — 통일하지 말 것.</b> 포털 확정
 * 회신(2026-09-03)이 못박은 것은 <b>데이터마트 채널의 기산점뿐</b>이고, 두 채널의 기산점이 같은지는
 * 언급이 없다(DFEAT-055). 「일관성」을 이유로 한쪽 축의 확정을 다른 축에 옮기면 <b>확정되지 않은
 * 사양으로 사용자 데이터를 비가역 삭제</b>하게 된다.
 *
 * <h3>설정이 없거나 비정상이면 값을 만들지 않는다 (fail-closed)</h3>
 * <p>{@code SystemConfigService.getInt} 는 행이 없으면 <b>예외를 던진다</b>(이 3키는 폴백하지 않는다 —
 * {@link ConfigKeys#PORTAL_DATAMART_RETENTION_DAYS} javadoc). 조회 경로에서는 그 예외를 그대로 올리면
 * <b>목록 화면 전체가 500</b> 이 되므로 <b>그 필드만 {@code null}</b> 로 내린다. 삭제 배치는 반대로 그
 * 회차를 건너뛰는데, 둘 다 "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다.
 *
 * <p>★ <b>보존일수가 0 이하면 「설정 없음」과 같이 다룬다</b>({@link #retentionDays}). 0 을 그대로
 * 적용하면 커트라인이 <b>현재 시각</b>이 되어 방금 저장한 라벨까지 <b>전량이 즉시 삭제 대상</b>이 되고,
 * 음수면 커트라인이 미래가 되어 더 넓어진다. 어느 쪽도 복구 수단이 없다. 임의 기본값(7 등)으로 대체하지도
 * 않는다 — 파괴적 기능이 fail-open 하면 "알아서 지웠다"가 되기 때문이다(DFEAT-055 — 방치 판정 시간의
 * 「0 이하·해석 불가면 그 회차를 수행하지 않는다」와 같은 취지).
 *
 * <p>⚠ 저장 입구는 이미 하한 1 을 강제한다({@link ConfigKeys#NUMBER_RANGE}). 그런데도 <b>읽는 쪽에</b>
 * 같은 판정을 두는 이유는 이 값이 <b>기동 시점 상수가 아니라 런타임에 설정 테이블에서 읽는 값</b>이라
 * 시드 오류·DB 직접 수정으로 0 이 앉을 수 있고, 그때 기동을 실패시킬 수단이 없기 때문이다. 그래서
 * 기동 차단이 아니라 <b>그 축을 건너뛰는 쪽</b>을 골랐다 — 아무것도 지우지 않는 것이 언제나 안전하다.
 * 대신 <b>ERROR 로그</b>를 남겨 "왜 안 지워지지"로 묻히지 않게 한다.
 *
 * <h3>스냅샷을 돌려주는 이유 (설정 읽기의 N+1 회피)</h3>
 * <p>{@link #datamartExpiry()}/{@link #uploadExpiry()} 는 설정을 <b>1회</b> 읽어 굳힌 계산기를 준다.
 * 행마다 정책을 부르면 설정 부재 시 <b>예외는 캐시되지 않으므로</b> 행 수만큼 DB 왕복이 난다(목록은
 * 페이지당 최대 100행). 덤으로 한 페이지 안에서 설정이 바뀌어 행마다 다른 기준이 섞이는 것도 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalRetentionPolicy {

    private final SystemConfigService systemConfigService;

    /** 데이터마트 라벨 보존일수. 설정 부재·비정상값이면 {@code empty}(= 만료 판정 불가). */
    public OptionalInt datamartRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS);
    }

    /** 업로드 자산(READY) 보존일수. 설정 부재·비정상값이면 {@code empty}. */
    public OptionalInt uploadRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS);
    }

    /** 업로드 자산(FAILED) 보존일수. 설정 부재·비정상값이면 {@code empty}. */
    public OptionalInt uploadFailedRetentionDays() {
        return retentionDays(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);
    }

    /** 데이터마트 축 계산기 — 조회 1회분 설정 스냅샷. */
    public DatamartExpiry datamartExpiry() {
        return new DatamartExpiry(datamartRetentionDays());
    }

    /** 업로드 축 계산기 — 조회 1회분 설정 스냅샷(READY·FAILED 두 축을 함께 굳힌다). */
    public UploadExpiry uploadExpiry() {
        return new UploadExpiry(uploadRetentionDays(), uploadFailedRetentionDays());
    }

    // ======================== 삭제 축 — 커트라인 ========================

    /**
     * 데이터마트 라벨 삭제 커트라인({@code 지금 − 보존일수}). @design DFEAT-055, AC-1068
     *
     * <p>설정 부재·0 이하면 <b>값을 만들지 않는다</b>({@code empty}) — 호출자는 그 축을 건너뛴다.
     * 임의 기본값으로 때우지 않는 이유는 위 클래스 주석 fail-closed 절과 같다.
     *
     * @return 이 시각보다 <b>이른</b> 최초 저장을 가진 그룹이 삭제 대상이다(경계는 포함하지 않는다)
     */
    public Optional<LocalDateTime> datamartCutoff() {
        return datamartCutoff(LocalDateTime.now());
    }

    /** 기준시각을 주입하는 변형 — 한 회차의 기준시각을 굳혀 넘길 때 쓴다. @design DFEAT-055, AC-1068 */
    public Optional<LocalDateTime> datamartCutoff(LocalDateTime now) {
        return minusDays(now, datamartRetentionDays());
    }

    /**
     * 업로드 삭제 커트라인 스냅샷 — <b>기준시각 하나</b>를 마킹 대기·READY·FAILED 세 축이 공유한다.
     * @design DFEAT-055, AC-1070
     *
     * <p>★ 두 축이 각자 {@code now()} 를 뜨면 <b>한 회차 안에서 기준시각이 갈린다</b>. 지금은 그
     * 어긋남이 밀리초라 무해해 보이지만 판정 기준이 둘이 되는 것 자체가 조용한 동작 변경이라,
     * 스냅샷이 {@code capturedAt} 하나만 들고 두 커트라인을 <b>파생</b>하게 해 구조로 막는다
     * (규율이 아니라 자료구조가 지킨다).
     */
    public UploadCutoffs uploadCutoffs() {
        return uploadCutoffs(LocalDateTime.now());
    }

    /** 기준시각을 주입하는 변형. @design DFEAT-055, AC-1070 */
    public UploadCutoffs uploadCutoffs(LocalDateTime now) {
        // 읽는 순서는 READY → FAILED 로 고정한다(설정 부재 시 남기는 진단 로그 순서와 맞춘다).
        // 마킹 대기 축은 READY 설정을 재사용하므로 여기서 설정을 한 번 더 읽지 않는다.
        return new UploadCutoffs(now, uploadRetentionDays(), uploadFailedRetentionDays());
    }

    /**
     * 업로드 두 축 커트라인 — 같은 {@code capturedAt} 에서 각자의 보존일수로 파생된다.
     * @design DFEAT-055, AC-1070
     *
     * @param capturedAt          이 회차의 기준시각 — <b>두 축이 공유한다</b>
     * @param readyRetentionDays  {@code portal.upload.retention-days}(부재·비정상이면 empty) —
     *                            <b>마킹 대기 축과 준비 완료 축이 함께 쓴다</b>
     * @param failedRetentionDays {@code portal.upload.failed-retention-days}(부재·비정상이면 empty)
     */
    public record UploadCutoffs(LocalDateTime capturedAt,
                                OptionalInt readyRetentionDays,
                                OptionalInt failedRetentionDays) {

        /**
         * 마킹 대기({@code UPLOADED}) 축 커트라인 — 기산점이 등록일이라 <b>판정 대상이 다르다</b>.
         * @design AC-1070
         *
         * <p>보존일수는 {@link #ready()} 와 <b>같은 설정</b>이라 값도 같다. 그럼에도 창구를 따로 두는
         * 것은 ①호출부가 어느 축을 판정하는지 드러나고 ②나중에 설정이 갈릴 때 고칠 자리가 여기
         * 하나이기 때문이다. ★ 무엇보다 <b>같은 {@code capturedAt} 에서 파생</b>되므로 새 축이
         * 자기 {@code now()} 를 뜨는 일이 구조적으로 생기지 않는다.
         */
        public Optional<LocalDateTime> uploaded() {
            return minusDays(capturedAt, readyRetentionDays);
        }

        /** READY 축 커트라인. 설정이 없으면 {@code empty}(그 축만 건너뛴다). */
        public Optional<LocalDateTime> ready() {
            return minusDays(capturedAt, readyRetentionDays);
        }

        /** FAILED 축 커트라인 — READY 와 <b>독립</b>이며 보존기간이 더 짧다. */
        public Optional<LocalDateTime> failed() {
            return minusDays(capturedAt, failedRetentionDays);
        }
    }

    /**
     * 데이터마트 라벨 만료 예정 시각 계산기 — 기준점은 <b>최초</b> 저장 시각이다.
     * @design DFEAT-055, AC-1068
     *
     * @param retentionDays 조회 시점 {@code portal.datamart.retention-days}(부재면 empty)
     */
    public record DatamartExpiry(OptionalInt retentionDays) {

        /**
         * @param firstLabelSavedAt 그 (사용자, 영상) 본인 저장 라벨의 {@code MIN(REG_DT)} —
         *                          <b>그 사용자의 저작 최초 저장 시각</b>. 저장 라벨이 없으면 {@code null}
         * @return 만료 예정 시각. 저장 라벨이 없거나 설정이 없으면 {@code null}
         */
        public LocalDateTime expiresAt(LocalDateTime firstLabelSavedAt) {
            // ★ 마지막 저장이 아니라 최초 저장이다(DFEAT-055 — 포털 확정 회신 2026-09-03).
            // 마지막 저장에서 다시 계산하면 사용자가 저장할 때마다 만료가 뒤로 밀려, 작업을 이어 가는
            // 한 만료가 영영 오지 않는다 — 이 채널의 자동 삭제가 사실상 실행되지 않고 화면이 고지한
            // 만료 예정일도 계속 어긋난다. 편의를 이유로 되돌리지 말 것.
            // ⚠ 업로드 축(UploadExpiry)은 반대로 「늦은 쪽」이 맞다 — 그쪽은 확정 대상이 아니었다.
            return plusDays(firstLabelSavedAt, retentionDays);
        }
    }

    /**
     * 업로드 자산 만료 예정 시각 계산기 — 상태별로 기준점과 보존기간이 다르다. @design AC-1070, AC-037
     *
     * @param readyRetentionDays  {@code portal.upload.retention-days}(부재면 empty).
     *                            <b>마킹 대기 축이 이 값을 함께 쓴다</b> — 기산점만 등록일로 다르다
     * @param failedRetentionDays {@code portal.upload.failed-retention-days}(부재면 empty)
     */
    public record UploadExpiry(OptionalInt readyRetentionDays, OptionalInt failedRetentionDays) {

        /**
         * @param uldSttsCd        자산 상태({@code READY}/{@code FAILED}/{@code PROCESSING}/{@code UPLOADED})
         * @param regDt            자산 등록 일시
         * @param mdfcnDt          자산 최종 상태 변경 일시(FAILED 축의 기준점 = 전이 시각)
         * @param lastLabelSavedAt 그 자산 라벨의 {@code MAX(REG_DT)} — 라벨이 없으면 {@code null}
         * @return 만료 예정 시각. 삭제 대상이 아닌 상태이거나 설정이 없으면 {@code null}
         */
        public LocalDateTime expiresAt(String uldSttsCd, LocalDateTime regDt, LocalDateTime mdfcnDt,
                                       LocalDateTime lastLabelSavedAt) {
            if (PortalUploadLedger.STATUS_READY.equals(uldSttsCd)) {
                // 작업 중이면 라벨 저장이 기준점을 계속 밀어낸다 — 그래서 늦은 쪽을 쓴다(DFEAT-055).
                return plusDays(later(regDt, lastLabelSavedAt), readyRetentionDays);
            }
            if (PortalUploadLedger.STATUS_FAILED.equals(uldSttsCd)) {
                // 실패 자산은 되찾을 수 없어 더 짧게 정리한다 — READY 축과 독립(AC-037).
                return plusDays(mdfcnDt, failedRetentionDays);
            }
            if (PortalUploadLedger.STATUS_UPLOADED.equals(uldSttsCd)) {
                // 마킹 대기 — 기산점은 <등록일>이다(AC-1070). 그 상태에는 프레임이 없어 라벨이 있을
                // 수 없으므로 READY 축의 「라벨 저장이 기준점을 밀어낸다」 조건을 넣지 않는다.
                // 보존일수는 READY 축 설정을 재사용한다 — 같은 정상 자산 축이라 새 키를 만들지 않는다.
                return plusDays(regDt, readyRetentionDays);
            }
            // PROCESSING 만 삭제 대상이 아니므로 고지할 만료도 없다 — 프레임 추출과 경쟁하면 파일과
            // 원장이 어긋난다. 그 상태는 방치 판정이 따로 회수한다(AC-1070).
            return null;
        }
    }

    /**
     * 보존일수 설정 조회 — <b>부재·타입불일치·파싱실패·0 이하</b> 어느 쪽이든 {@code empty}.
     * 조회 경로를 500 으로 깨지 않는다. @design DFEAT-055, AC-1068, AC-1070
     *
     * <p>★ 판정은 <b>여기 한 곳</b>이다. {@code plusDays} 에 같은 검사를 겹쳐 두지 않는다 — 두 곳에
     * 두면 한쪽이 망가져도 다른 쪽이 가려 <b>가드가 조용히 죽어도 시험이 알아채지 못한다</b>.
     * 두 계산기({@link DatamartExpiry}·{@link UploadExpiry})와 커트라인 창구({@link #datamartCutoff()}·
     * {@link #uploadCutoffs()})는 전부 이 메서드가 만든 스냅샷만 받으므로 이 한 곳이 읽기·삭제 두 축을
     * 모두 덮는다.
     */
    private OptionalInt retentionDays(String key) {
        Integer days;
        try {
            days = systemConfigService.getInt(key);
        } catch (RuntimeException e) {
            log.warn("[PortalRetention] 보존기간 설정을 읽지 못해 만료 예정 시각을 내리지 않는다 key={} causeType={}",
                    key, e.getClass().getSimpleName());
            return OptionalInt.empty();
        }
        if (days == null) {
            return OptionalInt.empty();
        }
        if (days <= 0) {
            // ★ 0 이면 커트라인이 «현재 시각», 음수면 «미래»가 되어 전량이 즉시 삭제 대상이 된다.
            //   임의 기본값으로 때우지 않고 그 축을 통째로 건너뛴다(위 클래스 주석 fail-closed 절).
            //   WARN 이 아니라 ERROR 인 것은 «기능이 죽어 있다»는 사실을 운영자가 알아야 하기 때문이다.
            log.error("[PortalRetention] 보존일수 설정이 0 이하라 만료 판정을 하지 않는다"
                            + " (즉시 삭제 방지) key={} days={}", key, days);
            return OptionalInt.empty();
        }
        return OptionalInt.of(days);
    }

    /**
     * 기준점 + 보존일수. 기준점이나 설정이 없으면 {@code null}(값을 지어내지 않는다).
     *
     * <p>여기서 보존일수의 유효성을 <b>다시 판정하지 않는다</b> — 0 이하 차단은
     * {@link #retentionDays} 단일 지점이 소유한다(가드 중복 = 가드 무력화).
     */
    private static LocalDateTime plusDays(LocalDateTime base, OptionalInt days) {
        if (base == null || days.isEmpty()) {
            return null;
        }
        return base.plusDays(days.getAsInt());
    }

    /**
     * 기준시각 − 보존일수 = 삭제 커트라인. 설정이 없으면 {@code empty}(커트라인을 만들지 않는다).
     *
     * <p>{@link #plusDays} 의 <b>역연산</b>이며 같은 {@code days} 스냅샷을 쓴다 — 그래서
     * 「고지한 만료일이 지났다」와 「삭제 대상이다」가 같은 사실을 가리킨다.
     *
     * <p>여기서도 보존일수의 유효성을 <b>다시 판정하지 않는다</b> — 0 이하 차단은
     * {@link #retentionDays} 단일 지점이 소유한다(가드 중복 = 가드 무력화).
     */
    private static Optional<LocalDateTime> minusDays(LocalDateTime now, OptionalInt days) {
        if (days.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(now.minusDays(days.getAsInt()));
    }

    /** null 안전 최댓값 — 둘 다 없으면 {@code null}. */
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
