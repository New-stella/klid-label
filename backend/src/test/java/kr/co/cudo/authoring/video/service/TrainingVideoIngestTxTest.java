package kr.co.cudo.authoring.video.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인입 1건 적재 트랜잭션 경계 빈({@link TrainingVideoIngestTx}) 단위 테스트 — Phase 3.
 *
 * <p>적재 소스가 관제 공유 클립 마스터에서 인입 테이블
 * ({@code LS_DATA_INGEST})로 교체된 뒤의 계약을 고정한다.
 *
 * <p>검증 축 (설계 §6-0 트랜잭션 규약 + Phase 3 HIGH 시나리오):
 * <ul>
 *   <li><b>클레임 최상단</b> — 식별자 가드보다 <b>먼저</b> {@code claimForProcessing} 을 부르고,
 *       1 이 아니면 아무것도 하지 않는다(CWE-362 잡 내부 레이스).</li>
 *   <li><b>REQUIRES_NEW 안 재조회</b> — 상태 전이는 {@code findById} 로 다시 읽은 인스턴스에만
 *       한다(외부 readOnly 세션 인스턴스는 dirty checking 이 없어 유실).</li>
 *   <li><b>파일 미도착 = 실패 아님</b> — {@code revertToPendingForRetry} 로 되돌리고 재시도
 *       횟수를 올리지 않는다. 단 <b>대기에는 상한</b>이 있고(설계 §6-0-1 ①) 관측마다 <b>다음 시도를
 *       뒤로 민다</b>(§6-0-1-a ㉢ backoff — 무한 복귀는 FIFO 앞자리를 영구 점유해 뒤의 정상 인입을
 *       굶긴다).</li>
 *   <li><b>대기 예산의 시계는 우리 것</b> — 상한은 관제가 준 {@code RCPTN_DT} 가 아니라 우리가
 *       스탬프한 <b>최초 미도착 관측 시각({@code PRCS_DT})</b> 기준이다(§6-0-1-a ㉠ — 관제가 과거
 *       시각을 INSERT 하면 도착 전에 종결된다).</li>
 *   <li><b>관제 계약 갭 관측</b> — {@code EVNT_TYPE_CD}·{@code SHT_DT} 는 대용값 없이 null 로
 *       적재하되 <b>조용히 비우지 않는다</b>(설계 §6-0-2 — WARN 1회 + 이후 DEBUG).</li>
 *   <li><b>영상 길이 단위</b> — 인입값은 <b>이미 초</b>다. ms 변환(÷1000) 금지.</li>
 *   <li><b>경로 신뢰 경계</b> — 관제가 준 경로가 허용 루트 밖이면 적재하지 않는다(CWE-22/59).</li>
 *   <li><b>{@code SRC_TYPE} allowlist</b> · <b>{@code LCLGV_CD} 전달</b>.</li>
 * </ul>
 *
 * <p>경로 검증기({@link VideoArtifactRootResolver})는 목이 아니라 <b>실물</b>을 쓴다 — 경로 가드는
 * 파일시스템 상태(존재·심링크 실경로)에 의존하므로 목으로 대체하면 검증이 아니라 흉내가 된다.
 *
 * <p><b>strictness 는 기본(STRICT_STUBS)</b>이다 — 클래스 단위 {@code LENIENT} 는 죽은 stub 을 숨겨
 * "검증한 줄 알았던" 경로를 만든다. 공용 픽스처가 심는 stub 만 {@code lenient()} 로 개별 표시한다.
 */
@ExtendWith(MockitoExtension.class)
class TrainingVideoIngestTxTest {

    private static final long RCPTN_SN = 7001L;

    /** 촬영일시 픽스처 — <b>판정에 쓰이지 않는</b> 왕복 값이라 고정 시각으로 둔다. */
    private static final LocalDateTime SHT_DT = LocalDateTime.of(2026, 7, 30, 14, 30);

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private LsDataIngestRepository ingestRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** 허용 마운트 루트(고정 allowlist) — 관제 NAS 마운트 모사. */
    @TempDir
    Path mountRoot;

    /** allowlist 밖 트리 — 경로 이탈 음성 케이스용. */
    @TempDir
    Path outsideRoot;

    private TrainingVideoIngestTx tx;

    /** 이벤트유형 자동등록기(V168) — 적재 경로가 부가 기능으로 호출한다. */
    @Mock
    private kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar eventTypeAutoRegistrar;

    /** 관측 로그(WARN) 단언용 — 결손·이상 상황이 "조용히" 지나가지 않는지 고정한다. */
    private ListAppender<ILoggingEvent> logs;

    /** 미도착 대기 상한(시간) — 기본값과 동일. 상한 초과 케이스는 수신일시를 과거로 밀어 만든다. */
    private static final long NOT_ARRIVED_TIMEOUT_HOURS = 24L;

    @BeforeEach
    void setUp() {
        VideoArtifactRootResolver rootResolver = ArtifactRootTestSupport.coLocate(mountRoot);
        tx = new TrainingVideoIngestTx(videoRepository, ingestRepository,
                rootResolver, eventPublisher, eventTypeAutoRegistrar, NOT_ARRIVED_TIMEOUT_HOURS);
        // 기본: 클레임 성공 + 재조회 성공(테스트별로 재정의).
        lenient().when(ingestRepository.claimForProcessing(anyLong())).thenReturn(1);
        // 미도착 복귀 UPDATE 는 기본 1행 성공(0행 케이스만 테스트에서 재정의).
        lenient().when(ingestRepository.revertToPendingForRetry(anyLong(), any(), any())).thenReturn(1);
        logs = attachLogAppender();
    }

    @AfterEach
    void tearDown() {
        txLogger().detachAppender(logs);
    }

    private static ch.qos.logback.classic.Logger txLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TrainingVideoIngestTx.class);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        txLogger().addAppender(appender);
        return appender;
    }

    /** 이번 실행에서 남은 WARN 메시지(포맷 문자열) 목록. */
    private java.util.List<String> warnMessages() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getMessage)
                .toList();
    }

    // ---------------------------------------------------------------- fixtures

    /** 허용 루트 하위에 실제 영상 파일을 만든다(파일 도착 상태 모사). */
    private Path seedArrivedVideo(String name) throws IOException {
        Path dir = mountRoot.resolve("videos");
        Files.createDirectories(dir);
        Path video = dir.resolve(name);
        Files.writeString(video, "raw-bytes");
        return video;
    }

    /**
     * 인입 행 1건 — 관제 수신값 + 저작도구 운영 컬럼 초기값(PENDING/RTY_CNT=0).
     *
     * <p>★ <b>시각 픽스처는 전부 상대 시각</b>이다. 절대 시각(예: {@code 2026-07-31 09:00})을 박으면
     * "지금"이 그 시각에서 상한만큼 멀어지는 순간 <b>테스트가 실행 시점에 따라 판정 분기를 갈아탄다</b>
     * (미도착 복귀 케이스가 어느 날부터 상한 초과 종결로 넘어가 RED). 판정에 쓰이지 않는 값
     * ({@code shtDt} — 단순 왕복 단언)만 고정 시각을 유지한다.
     *
     * <p>{@code rcptnDt} 는 <b>더 이상 대기 예산 축이 아니다</b>(설계 §6-0-1-a ㉠ — 예산 앵커는 우리가
     * 스탬프하는 {@code prcsDt}). 여기서는 "관제가 준 값"으로만 존재하며, 그 값이 과거여도 종결로
     * 이어지지 않는다는 것을 별도 테스트가 고정한다.
     */
    private LsDataIngest ingestRow(String vmsClipId, String rawFilePathNm) {
        LsDataIngest row = newIngest();
        ReflectionTestUtils.setField(row, "rcptnSn", RCPTN_SN);
        ReflectionTestUtils.setField(row, "rcptnDt", LocalDateTime.now().minusMinutes(10));
        ReflectionTestUtils.setField(row, "prcsSttsCd", LsDataIngest.PRCS_STTS_PENDING);
        ReflectionTestUtils.setField(row, "rtyCnt", 0);
        ReflectionTestUtils.setField(row, "vmsClipId", vmsClipId);
        ReflectionTestUtils.setField(row, "vmsCctvId", "CCTV-001");
        ReflectionTestUtils.setField(row, "vdoFileNm", "clip.mp4");
        ReflectionTestUtils.setField(row, "rawFilePathNm", rawFilePathNm);
        ReflectionTestUtils.setField(row, "srcType", "RELAY");
        // 촬영일시는 판정에 쓰이지 않고 그대로 왕복하는 값이라 고정 시각을 둔다(단언도 같은 값 비교).
        ReflectionTestUtils.setField(row, "shtDt", SHT_DT);
        ReflectionTestUtils.setField(row, "vdoLenSec", new BigDecimal("600"));
        ReflectionTestUtils.setField(row, "lclgvCd", "11680");
        ReflectionTestUtils.setField(row, "evntId", "ABA_0001");
        // 재조회(REQUIRES_NEW 안) 결과로 이 인스턴스를 돌려준다.
        lenient().when(ingestRepository.findById(RCPTN_SN)).thenReturn(Optional.of(row));
        return row;
    }

    private static LsDataIngest newIngest() {
        try {
            var ctor = LsDataIngest.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** save() 가 rawSn 을 채운 영속 엔티티를 반환하도록 stub (IDENTITY 생성 모사). */
    private void stubSaveAssigningRawSn(long rawSn) {
        lenient().when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            ReflectionTestUtils.setField(raw, "rawSn", rawSn);
            return raw;
        });
    }

    private LsDataRaw savedRaw() {
        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- 정상 적재

    @Test
    @DisplayName("PENDING_인입행이_LS_DATA_RAW로_적재되고_VideoIngestedEvent가_발행된다")
    void ingestsPendingRowAndPublishesEvent() throws IOException {
        // given — 파일이 이미 NAS 에 도착한 인입 행.
        Path video = seedArrivedVideo("clip-ok.mp4");
        LsDataIngest row = ingestRow("CLIP-OK", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-OK")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9100L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 작업 테이블 적재 + 비식별 선두 트리거 이벤트 발행.
        assertThat(ingested).isTrue();
        LsDataRaw saved = savedRaw();
        assertThat(saved.getVmsClipId()).isEqualTo("CLIP-OK");
        assertThat(saved.getVmsCctvId()).isEqualTo("CCTV-001");
        assertThat(saved.getRawFilePathNm()).isEqualTo(video.toString());
        assertThat(saved.getShtDt()).isEqualTo(SHT_DT);
        assertThat(saved.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        ArgumentCaptor<VideoIngestedEvent> event = ArgumentCaptor.forClass(VideoIngestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().rawSn()).isEqualTo(9100L);
    }

    @Test
    @DisplayName("개인정보유형은_관제_미제공이므로_PRVC로_적재된다")
    void ingestsWithFailClosedPrivacyType() throws IOException {
        // given — 관제 인입에는 개인정보유형 컬럼 자체가 없다(설계 R6).
        Path video = seedArrivedVideo("clip-prvc.mp4");
        LsDataIngest row = ingestRow("CLIP-PRVC", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-PRVC")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9300L);

        // when
        tx.ingestOne(row);

        // then — 값이 없으면 "개인정보 있음"으로 본다(fail-closed). ANONY 로 적재하면 export 의
        //        privacy_included 가 N 으로 거짓 주장되고, needsDeidentify()=false 라
        //        비식별 미준비 프레임이 원본으로 폴백 서빙된다(CWE-359).
        LsDataRaw saved = savedRaw();
        assertThat(saved.getPrvcTypeCd()).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(saved.getPrvcYn()).isEqualTo("Y");
        assertThat(saved.needsDeidentify()).isTrue();
    }

    @Test
    @DisplayName("적재_성공시_인입행에_RAW_SN과_처리일시가_기록된다")
    void marksIngestRowDoneWithRawSn() throws IOException {
        // given
        Path video = seedArrivedVideo("clip-done.mp4");
        LsDataIngest row = ingestRow("CLIP-DONE", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-DONE")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9200L);

        // when
        tx.ingestOne(row);

        // then — 인입 행은 삭제되지 않고 상태만 종결로 갱신된다(감사 추적).
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(row.getRawSn()).isEqualTo(9200L);
        assertThat(row.getPrcsDt()).isNotNull();
    }

    @Test
    @DisplayName("상태전이는_REQUIRES_NEW안에서_재조회한_인스턴스에_적용된다")
    void appliesStateTransitionToReloadedInstance() throws IOException {
        // given — 외부 readOnly 세션이 넘긴 인스턴스와 REQUIRES_NEW 안 재조회 인스턴스가 서로 다르다.
        Path video = seedArrivedVideo("clip-reload.mp4");
        LsDataIngest outer = ingestRow("CLIP-RELOAD", video.toString());
        LsDataIngest reloaded = ingestRow("CLIP-RELOAD", video.toString());
        when(ingestRepository.findById(RCPTN_SN)).thenReturn(Optional.of(reloaded));
        when(videoRepository.findByVmsClipId("CLIP-RELOAD")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9300L);

        // when
        tx.ingestOne(outer);

        // then — 재조회 인스턴스만 전이된다(외부 인스턴스 변경은 dirty checking 이 없어 유실되므로).
        verify(ingestRepository).findById(RCPTN_SN);
        assertThat(reloaded.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(outer.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
    }

    // ---------------------------------------------------------------- 원자 클레임

    @Test
    @DisplayName("클레임에_실패하면_적재하지_않고_스킵한다")
    void skipsWhenClaimLost() throws IOException {
        // given — 다른 실행(노드)이 이미 같은 행을 가져갔다 → 조건부 UPDATE 영향 행 수 0.
        Path video = seedArrivedVideo("clip-race.mp4");
        LsDataIngest row = ingestRow("CLIP-RACE", video.toString());
        when(ingestRepository.claimForProcessing(RCPTN_SN)).thenReturn(0);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 조회·적재·이벤트 전부 없음(중복 적재 차단).
        assertThat(ingested).isFalse();
        verify(ingestRepository, never()).findById(anyLong());
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("클레임은_식별자_가드보다_먼저_호출된다")
    void claimsBeforeIdentifierGuards() throws IOException {
        // given — 식별자가 깨진 행이어도 클레임이 먼저 일어나야 한다(그래야 종결을 찍을 수 있다).
        LsDataIngest row = ingestRow("   ", "/nowhere/x.mp4");

        // when
        tx.ingestOne(row);

        // then — ★호출 여부가 아니라 <순서>를 단언한다. 가드가 먼저면 클레임 없이 종결을 찍게 되어
        //   설계 §6-0 규약 1(클레임 최상단)이 깨진다.
        InOrder order = inOrder(ingestRepository);
        order.verify(ingestRepository).claimForProcessing(RCPTN_SN);
        order.verify(ingestRepository).findById(RCPTN_SN);
    }

    @Test
    @DisplayName("클레임_직후_행이_사라졌으면_아무것도_하지_않고_스킵한다")
    void skipsWhenClaimedRowDisappeared() {
        // given — 인입 행은 영구 보존이라 도달 불가한 경로(외부 삭제 신호). 좀비로 굳지 않아야 한다.
        LsDataIngest row = ingestRow("CLIP-GONE", "/nowhere/gone.mp4");
        when(ingestRepository.findById(RCPTN_SN)).thenReturn(Optional.empty());

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재·이벤트·복귀 전부 없음(NPE 로 터지지도 않는다).
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(ingestRepository, never()).revertToPendingForRetry(anyLong(), any(), any());
    }

    // ---------------------------------------------------------------- 식별자 가드 3종

    @Test
    @DisplayName("VMS_CLIP_ID가_blank면_적재하지_않고_WARN만_남긴다")
    void skipsBlankVmsClipId() throws IOException {
        // given
        Path video = seedArrivedVideo("clip-blankid.mp4");
        LsDataIngest row = ingestRow("   ", video.toString());

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재/이벤트 없음. 좀비 방지를 위해 인입 행은 사유와 함께 종결된다.
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).findByVmsClipId(anyString());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat(row.getErrMsg()).isNotBlank();
    }

    @Test
    @DisplayName("VMS_CCTV_ID가_blank면_스킵한다")
    void skipsBlankVmsCctvId() throws IOException {
        // given — LS_DATA_RAW.VMS_CCTV_ID 는 NOT NULL 이라 사전 skip 이 없으면 UK race 로 오인된다.
        Path video = seedArrivedVideo("clip-blankcctv.mp4");
        LsDataIngest row = ingestRow("CLIP-BLANK-CCTV", video.toString());
        ReflectionTestUtils.setField(row, "vmsCctvId", "  ");

        // when
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("RAW_FILE_PATH_NM이_blank면_스킵한다")
    void skipsBlankRawFilePath() {
        // given — 비식별이 열 파일이 없으므로 깨진 적재를 만들지 않는다.
        LsDataIngest row = ingestRow("CLIP-BLANK-PATH", "   ");

        // when
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    // ---------------------------------------------------------------- 파일 미도착 (S3)

    @Test
    @DisplayName("파일이_아직_없으면_실패가_아니라_PENDING으로_복귀해_다음주기에_재조회된다")
    void revertsToPendingWhenFileNotArrived() throws IOException {
        // given — 관제가 메타를 먼저 넣고 파일 복사가 아직 끝나지 않은 상태(허용 루트 하위 경로이나 파일 부재).
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("not-yet.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-NOTYET", notArrived);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 실패 종결(FAILED)도 좀비(PROCESSING 방치)도 아니고 PENDING 복귀다.
        assertThat(ingested).isFalse();
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("H1_0바이트_파일은_아직_도착하지_않은_것으로_본다 — 빈_영상을_적재하지_않는다")
    void zeroByteFileIsTreatedAsNotArrived() throws IOException {
        // given — 경로에 파일이 <존재하지만> 내용이 0바이트다. 구 판정은
        //   exists → toRealPath → isRegularFile 만 봐서 이걸 READY 로 통과시켰다.
        //   실제 관측: 내부 업로드의 예약 파일(0바이트)이나 관제가 <복사 중인> 파일이 여기 해당한다.
        //   그대로 적재하면 LS_DATA_RAW 가 생기고 비식별 파이프라인이 빈 파일로 기동하는데,
        //   인입 행은 DONE 이라 재큐 통로(FAILED 전용)조차 없다.
        Files.createDirectories(mountRoot.resolve("videos"));
        Path empty = mountRoot.resolve("videos").resolve("still-copying-empty.mp4");
        Files.createFile(empty);
        LsDataIngest row = ingestRow("CLIP-EMPTY", empty.toString());
        when(videoRepository.findByVmsClipId("CLIP-EMPTY")).thenReturn(Optional.empty());
        // 구 판정에서 READY 로 새면 여기까지 와서 실제 적재를 시도한다(그 자체가 RED 근거).
        stubSaveAssigningRawSn(9200L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 실패가 아니라 <대기>다(복사가 끝나면 다음 주기에 적재된다)
        assertThat(ingested).isFalse();
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("1바이트라도_있으면_적재한다 — 완결성_게이트가_정상_영상을_막지_않는다")
    void nonEmptyFileIsIngested() throws IOException {
        // 경계 — 게이트 기준은 "크기 0" 하나뿐이다(임의 최소 크기를 두면 정상 영상을 굶긴다).
        Files.createDirectories(mountRoot.resolve("videos"));
        Path oneByte = mountRoot.resolve("videos").resolve("tiny.mp4");
        Files.write(oneByte, new byte[]{0x00});
        LsDataIngest row = ingestRow("CLIP-TINY", oneByte.toString());
        when(videoRepository.findByVmsClipId("CLIP-TINY")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9300L);

        assertThat(tx.ingestOne(row)).isTrue();
    }

    @Test
    @DisplayName("파일_미도착_복귀는_재시도횟수를_증가시키지_않는다")
    void doesNotIncrementRetryCountOnFileWait() throws IOException {
        // given
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("still-copying.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-WAIT", notArrived);

        // when
        tx.ingestOne(row);

        // then — 대기는 실패가 아니다. RTY_CNT 가 오르면 실패 이력과 대기 이력이 섞인다.
        assertThat(row.getRtyCnt()).isZero();
        assertThat(row.getErrMsg()).isNull();
    }

    // ---------------------------------------------------------------- 멱등

    @Test
    @DisplayName("이미_적재된_VMS_CLIP_ID면_중복적재하지_않고_인입행을_완료처리한다")
    void marksDoneWhenClipAlreadyIngested() throws IOException {
        // given — 관제 재송신 등으로 같은 클립이 이미 LS_DATA_RAW 에 있다.
        Path video = seedArrivedVideo("clip-dup.mp4");
        LsDataIngest row = ingestRow("CLIP-DUP", video.toString());
        LsDataRaw existing = LsDataRaw.createFromIngest("CLIP-DUP", "CCTV-001", null, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, video.toString(), null, null);
        ReflectionTestUtils.setField(existing, "rawSn", 8800L);
        when(videoRepository.findByVmsClipId("CLIP-DUP")).thenReturn(Optional.of(existing));

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재는 하지 않되 기적재 확인으로 종결한다(PENDING 재조회 무한 반복 방지).
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_DONE);
        assertThat(row.getRawSn()).isEqualTo(8800L);
    }

    @Test
    @DisplayName("UK충돌_DataIntegrityViolationException은_중복_skip으로_처리된다")
    void treatsUniqueViolationAsDuplicateSkip() throws IOException {
        // given — 사전 조회를 통과한 뒤(동시 race) save 에서 UK 위반.
        Path video = seedArrivedVideo("clip-ukrace.mp4");
        LsDataIngest row = ingestRow("CLIP-UKRACE", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-UKRACE")).thenReturn(Optional.empty());
        when(videoRepository.save(any(LsDataRaw.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key VMS_CLIP_ID"));

        // when — 예외를 밖으로 던지지 않고 false 로 정상 skip.
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------- 영상 길이 (S4)

    @Test
    @DisplayName("인입값의_영상길이_초단위가_ms변환없이_그대로_적재된다")
    void copiesDurationSecondsWithoutMillisConversion() throws IOException {
        // given — LS_DATA_INGEST.VDO_LEN_SEC 는 <이미 초>다(NUMERIC(10)). 600 = 10분.
        Path video = seedArrivedVideo("clip-len.mp4");
        LsDataIngest row = ingestRow("CLIP-LEN", video.toString());
        ReflectionTestUtils.setField(row, "vdoLenSec", new BigDecimal("600"));
        when(videoRepository.findByVmsClipId("CLIP-LEN")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9400L);

        // when
        tx.ingestOne(row);

        // then — ÷1000 하면 0.6 → null 이 되어 길이가 통째로 사라진다.
        assertThat(savedRaw().getDurationSec()).isEqualTo(600);
    }

    @Test
    @DisplayName("과도하게_큰_영상길이값도_오버플로우없이_적재된다")
    void handlesOversizedDurationWithoutOverflow() throws IOException {
        // given — NUMERIC(10) 최대(99억)는 Integer 범위를 넘는다.
        Path video = seedArrivedVideo("clip-big.mp4");
        LsDataIngest row = ingestRow("CLIP-BIG", video.toString());
        ReflectionTestUtils.setField(row, "vdoLenSec", new BigDecimal("9999999999"));
        when(videoRepository.findByVmsClipId("CLIP-BIG")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9500L);

        // when — 예외 없이 적재는 성공한다.
        boolean ingested = tx.ingestOne(row);

        // then — 음수로 접히거나 예외가 나지 않고, 불명 값은 null(ffprobe back-fill 위임).
        assertThat(ingested).isTrue();
        assertThat(savedRaw().getDurationSec()).isNull();
    }

    @Test
    @DisplayName("영상길이가_1초미만이면_null로_두고_backfill에_위임한다")
    void keepsDurationNullBelowOneSecond() throws IOException {
        // given
        Path video = seedArrivedVideo("clip-short.mp4");
        LsDataIngest row = ingestRow("CLIP-SHORT", video.toString());
        ReflectionTestUtils.setField(row, "vdoLenSec", BigDecimal.ZERO);
        when(videoRepository.findByVmsClipId("CLIP-SHORT")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9600L);

        // when
        tx.ingestOne(row);

        // then — 0 을 영속하면 "길이 0" 오값으로 굳는다.
        assertThat(savedRaw().getDurationSec()).isNull();
    }

    // ---------------------------------------------------------------- SRC_TYPE / LCLGV_CD

    @Test
    @DisplayName("SRC_TYPE이_인입값_그대로_LS_DATA_RAW에_복사된다")
    void copiesSrcTypeAsIs() throws IOException {
        // given
        Path video = seedArrivedVideo("clip-src.mp4");
        LsDataIngest row = ingestRow("CLIP-SRC", video.toString());
        ReflectionTestUtils.setField(row, "srcType", "GENERATED");
        when(videoRepository.findByVmsClipId("CLIP-SRC")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9700L);

        // when
        tx.ingestOne(row);

        // then
        assertThat(savedRaw().getSrcType()).isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("SRC_TYPE이_허용값이_아니면_복사하지_않고_WARN을_남긴다")
    void doesNotCopyUnknownSrcType() throws IOException {
        // given — 관제 수신값은 신뢰 경계 밖이다. 미지의 값이 작업 테이블 분기축에 들어오면 안 된다.
        Path video = seedArrivedVideo("clip-badsrc.mp4");
        LsDataIngest row = ingestRow("CLIP-BADSRC", video.toString());
        ReflectionTestUtils.setField(row, "srcType", "'; DROP TABLE LS_DATA_RAW; --");
        when(videoRepository.findByVmsClipId("CLIP-BADSRC")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9800L);

        // when — fail-closed: 적재 자체는 계속하되 그 값은 복사하지 않는다.
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isTrue();
        assertThat(savedRaw().getSrcType()).isNull();
    }

    @Test
    @DisplayName("LCLGV_CD가_LS_DATA_RAW로_전달된다")
    void passesLclgvCd() throws IOException {
        // given — 관제 완료통지 페이로드 lclgv_cd(required)의 값 출처다.
        Path video = seedArrivedVideo("clip-lclgv.mp4");
        LsDataIngest row = ingestRow("CLIP-LCLGV", video.toString());
        ReflectionTestUtils.setField(row, "lclgvCd", "41135");
        when(videoRepository.findByVmsClipId("CLIP-LCLGV")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9900L);

        // when
        tx.ingestOne(row);

        // then
        assertThat(savedRaw().getLclgvCd()).isEqualTo("41135");
    }

    @Test
    @DisplayName("미도착_대기가_상한을_넘으면_사유와_함께_종결해_큐를_비운다")
    void terminatesNotArrivedRowAfterWaitDeadline() throws IOException {
        // given — <우리가 스탬프한 최초 미도착 관측 시각(PRCS_DT)>이 상한을 넘긴 행.
        //   NAS 권한 오류·깨진 경로처럼 스스로 낫지 않는 상태다. 무한 복귀시키면 FIFO 앞자리를
        //   영구 점유해 뒤의 정상 인입이 굶는다(head-of-line blocking).
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("never.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-DEADLINE", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt",
                LocalDateTime.now().minusHours(NOT_ARRIVED_TIMEOUT_HOURS + 1));

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 복귀가 아니라 종결. 사유가 남고 경로 원문은 담지 않는다(CWE-209/359).
        assertThat(ingested).isFalse();
        verify(ingestRepository, never()).revertToPendingForRetry(anyLong(), any(), any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat(row.getErrMsg()).isNotBlank().doesNotContain(notArrived);
        assertThat(warnMessages()).anyMatch(m -> m.contains("not arrived within"));
    }

    @Test
    @DisplayName("미도착_대기가_상한_이내면_종결하지_않고_PENDING으로_복귀한다")
    void keepsWaitingWithinDeadline() throws IOException {
        // given — 상한 직전까지 기다린 행. 관제 파일 복사 지연은 정상 흐름이라 조기 종결하면 안 된다.
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("copying.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-WAITING", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt",
                LocalDateTime.now().minusHours(NOT_ARRIVED_TIMEOUT_HOURS).plusMinutes(5));

        // when
        tx.ingestOne(row);

        // then
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("대기_상한이_0으로_오설정돼도_하한으로_보정돼_즉시_전량종결되지_않는다")
    void clampsMisconfiguredWaitDeadlineToLowerBound() throws IOException {
        // given — 상한 0(또는 음수) 설정은 <모든 미도착 행을 즉시 종결>시켜 정상 지연 파일까지 날린다.
        VideoArtifactRootResolver rootResolver = ArtifactRootTestSupport.coLocate(mountRoot);
        TrainingVideoIngestTx misconfigured = new TrainingVideoIngestTx(
                videoRepository, ingestRepository, rootResolver, eventPublisher,
                eventTypeAutoRegistrar, 0L);
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("clamped.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-CLAMP", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt", LocalDateTime.now().minusMinutes(10));

        // when — 10분 전부터 대기 중인 미도착 행
        misconfigured.ingestOne(row);

        // then — 하한(1시간)으로 보정돼 종결되지 않는다(fail-safe).
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    // ------------------------------------------- 대기 예산의 시계 (설계 §6-0-1-a ㉠/㉢)

    @Test
    @DisplayName("상한_판정은_관제가_준_수신일시가_아니라_최초_미도착_관측시각을_기준으로_한다")
    void measuresWaitBudgetFromOurObservationNotControlReceiptDate() throws IOException {
        // given — 관제가 <아주 과거>의 수신일시를 명시 INSERT 한 행(RCPTN_DT 는 DEFAULT 일 뿐 강제가
        //   없고 INSERT 주체가 관제다). 우리 관측 이력(PRCS_DT)은 아직 없다 = 이번이 최초 관측.
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("control-backdated.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-BACKDATED", notArrived);
        ReflectionTestUtils.setField(row, "rcptnDt",
                LocalDateTime.now().minusHours(NOT_ARRIVED_TIMEOUT_HOURS * 10));
        ReflectionTestUtils.setField(row, "prcsDt", null);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — ★도착도 하기 전에 첫 픽업에서 종결되면 안 된다. 관제 수신값은 예산 축이 아니다.
        assertThat(ingested).isFalse();
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
    }

    @Test
    @DisplayName("최초_미도착_관측시_우리_시계로_예산앵커를_스탬프하고_다음_시도를_뒤로_민다")
    void stampsWaitAnchorAndDefersNextRetryOnFirstObservation() throws IOException {
        // given — 예산 앵커가 없는 행(최초 관측)
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("first-observation.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-ANCHOR", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt", null);
        LocalDateTime before = LocalDateTime.now();

        // when
        tx.ingestOne(row);

        // then — 관측 시각(우리 시계)과 <미래의> 재시도 예정 시각이 같은 UPDATE 로 기록된다.
        //   예정 시각이 과거·현재면 다음 tick 이 곧바로 다시 집어 backoff 가 무의미해진다.
        ArgumentCaptor<LocalDateTime> observedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ingestRepository).revertToPendingForRetry(
                eq(RCPTN_SN), observedAt.capture(), nextRetryAt.capture());
        assertThat(observedAt.getValue()).isAfterOrEqualTo(before);
        assertThat(nextRetryAt.getValue()).isAfter(observedAt.getValue());
    }

    @Test
    @DisplayName("대기가_길어질수록_다음_재시도_간격이_늘어난다")
    void backsOffFurtherAsWaitGrows() throws IOException {
        // given — 이미 30분째 대기 중인 미도착 행(앵커가 30분 전)
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("long-wait.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-BACKOFF", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt", LocalDateTime.now().minusMinutes(30));

        // when
        tx.ingestOne(row);

        // then — 최소 간격(1분)이 아니라 <기다린 만큼> 뒤로 민다. 고착 행이 매 tick 앞자리를 다시
        //   점유하지 못하게 하는 것이 backoff 의 목적이므로, 대기가 길수록 간격이 벌어져야 한다.
        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), nextRetryAt.capture());
        assertThat(nextRetryAt.getValue()).isAfter(LocalDateTime.now().plusMinutes(20));
    }

    @Test
    @DisplayName("예산앵커가_없으면_경과를_판정할_수_없어_종결하지_않는다")
    void doesNotTerminateWhenWaitAnchorMissing() throws IOException {
        // given — 앵커(PRCS_DT)가 비어 있다 = 최초 관측. 판정 근거가 없으면 조기 종결보다 대기가 안전하다.
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("no-anchor.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-NOANCHOR", notArrived);
        ReflectionTestUtils.setField(row, "prcsDt", null);
        ReflectionTestUtils.setField(row, "rcptnDt", null);

        // when
        tx.ingestOne(row);

        // then
        verify(ingestRepository).revertToPendingForRetry(eq(RCPTN_SN), any(), any());
        assertThat(row.getPrcsSttsCd()).isNotEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    // ---------------------------------------------------------------- 관제 계약 갭 관측 (§6-0-2)

    @Test
    @DisplayName("인입_EVNT_TYPE_CD_가_있으면_그_값으로_적재된다")
    void ingestsEvntTypeCdFromIngestColumn() throws IOException {
        // given — 관제가 유형코드를 <직접> 실어 보낸 인입 행(V166 신설 컬럼). V167 로 관제 공유
        //   이벤트리스트가 제거된 뒤로는 이것이 <유일한> 조달처다.
        //   이 값이 비면 마킹 프리컨디션(MarkingGuards)이 400 으로 막아 자동마킹이 전량 실패한다.
        Path video = seedArrivedVideo("clip-evnt-direct.mp4");
        LsDataIngest row = ingestRow("CLIP-EVNT-DIRECT", video.toString());
        ReflectionTestUtils.setField(row, "evntTypeCd", "LOITERING");
        when(videoRepository.findByVmsClipId("CLIP-EVNT-DIRECT")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9902L);

        // when
        tx.ingestOne(row);

        // then — 인입값 그대로 작업 테이블에 적재된다.
        assertThat(savedRaw().getEvntTypeCd()).isEqualTo("LOITERING");
        // then — 결손이 아니므로 계약갭 WARN 도 없다(경고 인플레이션 방지).
        assertThat(warnMessages()).noneMatch(m -> m.contains("EVNT_TYPE_CD"));
    }

    @Test
    @DisplayName("인입_EVNT_TYPE_CD_가_공백이면_null_로_적재하되_적재는_성공하고_WARN으로_관측된다")
    void ingestsNullEvntTypeCdWithWarningWhenBlank() throws IOException {
        // given — 관제가 공백 문자열을 보냈다(미수신과 같은 취급).
        //   ★구 테스트 '인입_EVNT_TYPE_CD_가_비면_기존_EVNT_ID_해석이_그대로_동작한다'는 폐기됐다 —
        //     그 과도기 폴백(EVNT_ID → 관제 공유 이벤트리스트 조인)은 V167 로 테이블 자체가
        //     사라져 동작할 근거가 없다. 이제 결손은 그대로 결손이다.
        Path video = seedArrivedVideo("clip-evnt-blank.mp4");
        LsDataIngest row = ingestRow("CLIP-EVNT-BLANK", video.toString());
        ReflectionTestUtils.setField(row, "evntTypeCd", "   ");
        when(videoRepository.findByVmsClipId("CLIP-EVNT-BLANK")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9903L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 임의 대용값(EVNT_ID) 대입 금지 + 결손이 조용히 지나가지 않는다. 적재 자체는 성공.
        assertThat(ingested).isTrue();
        assertThat(savedRaw().getEvntTypeCd()).isNull();
        assertThat(warnMessages()).anyMatch(m -> m.contains("EVNT_TYPE_CD"));
    }

    @Test
    @DisplayName("이벤트유형_미수신이면_null로_적재하되_적재는_성공하고_WARN으로_관측된다")
    void ingestsNullEvntTypeCdWithWarning() throws IOException {
        // given — 관제 미송신(null). 적재를 실패시키면 영상이 아예 들어오지 않아
        //   되돌리기가 더 어렵다 — 결손은 마킹 단계 가드가 막는다(사용자 확정).
        Path video = seedArrivedVideo("clip-evnt.mp4");
        LsDataIngest row = ingestRow("CLIP-EVNT", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-EVNT")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9910L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isTrue();
        assertThat(savedRaw().getEvntTypeCd()).isNull();
        assertThat(warnMessages()).anyMatch(m -> m.contains("EVNT_TYPE_CD"));
    }

    @Test
    @DisplayName("EVNT_ID_는_이벤트유형코드의_대용값으로_쓰이지_않는다")
    void doesNotSubstituteEvntIdForEvntTypeCd() throws IOException {
        // given — 인입 행에는 이벤트<식별자>(EVNT_ID)만 있고 유형코드는 없다. 식별자는 유형이
        //   아니므로 대입하면 <틀린 값으로 확정>된다(null 보다 나쁘다 — 관제 통지·필터·통계 오염).
        Path video = seedArrivedVideo("clip-evnt-idonly.mp4");
        LsDataIngest row = ingestRow("CLIP-EVNT-IDONLY", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-EVNT-IDONLY")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9911L);
        assertThat(row.getEvntId()).isNotBlank();

        // when
        tx.ingestOne(row);

        // then
        assertThat(savedRaw().getEvntTypeCd()).isNull();
    }

    // ---------------------------------- 인입 신설 컬럼 (V166) — RAW 로 복사하지 않는다
    //
    // ★ 이름·포맷·좌표·개인정보 3필드는 LS_DATA_INGEST 가 단일 진실원이며 조회 시 조인으로 읽는다.
    //   적재가 LS_DATA_RAW 로 복사하는 것은 EVNT_TYPE_CD <하나뿐>이다(마킹 프리컨디션이 그 컬럼을
    //   직접 읽어 조인으로 대체할 수 없다). 아래 두 테스트가 그 경계를 고정한다.

    @Test
    @DisplayName("신설_인입_6개_컬럼이_null_이어도_적재가_실패하지_않는다")
    void ingestsSuccessfullyWhenNewIngestColumnsAreNull() throws IOException {
        // given — 관제가 신설 컬럼을 아직 채우지 않는 과도기(전부 nullable). 값이 없다고 적재가
        //   깨지면 그 자체가 더 큰 사고다 — 대용값도 지어내지 않는다.
        Path video = seedArrivedVideo("clip-new-null.mp4");
        LsDataIngest row = ingestRow("CLIP-NEW-NULL", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-NEW-NULL")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9930L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재 성공. 신설 컬럼은 인입 행에 null 로 남고 RAW 로 옮겨가지 않는다.
        assertThat(ingested).isTrue();
        assertThat(row.getEvntTypeCd()).isNull();
        assertThat(row.getAnonyInclYn()).isNull();
        assertThat(row.getPsdoInclYn()).isNull();
        assertThat(row.getPrvcInclYn()).isNull();
    }

    @Test
    @DisplayName("인입_수신값이_비식별축으로_새지_않는다 (구 기대 '적재로 채워지지 않는다' 폐기 — 2026-08-04)")
    void doesNotContaminateDeidentifiedAxisPrivacyFields() throws IOException {
        // given — 관제가 원천 영상 개인정보 3필드를 전부 보낸 인입 행.
        //   ★ 픽스처는 <세 자리 모두 적재 기본값(Y/N/N)과 다르게> 심는다(N/Y/Y). 구 픽스처는 anony 를
        //   'Y' 로 심어 기대값 'Y' 와 우연히 같았고, 그래서 인입 anony 가 비식별 축으로 새어도 이
        //   테스트가 통과했다(그 자리만 판별력 0). 이제 세 자리 전부가 증거다.
        Path video = seedArrivedVideo("clip-axis-guard.mp4");
        LsDataIngest row = ingestRow("CLIP-AXIS-GUARD", video.toString());
        ReflectionTestUtils.setField(row, "anonyInclYn", "N");
        ReflectionTestUtils.setField(row, "psdoInclYn", "Y");
        ReflectionTestUtils.setField(row, "prvcInclYn", "Y");
        when(videoRepository.findByVmsClipId("CLIP-AXIS-GUARD")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9944L);

        // when
        tx.ingestOne(row);

        // then — ★★ LS_DATA_RAW 의 동명 3컬럼(V163)은 <비식별 영상>에 대한 축이고, 인입의 동명 3컬럼은
        //   <원천 영상>에 대한 관제 판정이다. 이름이 같아 섞기 쉬운 지점이라 회귀 가드로 고정한다.
        //   ★ 구 기대("적재가 채우지 않아 null") 폐기(2026-08-04): 비식별 축은 이제 적재 시점에
        //   기본값 Y/N/N 이 실제로 들어간다. 여기서 <고정하는 것은 여전히 "인입 수신값이 새지 않는다">이며,
        //   관제가 N/Y/Y 를 보냈는데 저장값이 Y/N/N 이라는 사실이 그 증거다 — <세 자리 모두> 인입값과
        //   다르므로 어느 한 자리가 새어도 RED 가 된다(구 픽스처는 anony 가 양쪽 다 'Y' 라 무증거였다).
        LsDataRaw saved = savedRaw();
        assertThat(saved.getAnonyInclYn()).as("인입 N 이 새지 않았다(적재 기본값 Y)").isEqualTo("Y");
        assertThat(saved.getPsdoInclYn()).as("인입 Y 가 새지 않았다").isEqualTo("N");
        assertThat(saved.getPrvcInclYn()).as("인입 Y 가 새지 않았다").isEqualTo("N");
        // then — 인입 행의 수신값 자체는 그대로 보존된다(수신 원장 — 서버가 보정하지 않는다).
        assertThat(row.getAnonyInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("촬영일시_미수신은_폴백없이_null로_적재되고_WARN으로_관측된다")
    void ingestsNullShtDtWithWarning() throws IOException {
        // given — 구 CRT_DT 폴백은 복원하지 않는다. 촬영환경 파생의 근거라 대용값은 틀린 값으로 확정된다.
        Path video = seedArrivedVideo("clip-shtdt.mp4");
        LsDataIngest row = ingestRow("CLIP-SHTDT", video.toString());
        ReflectionTestUtils.setField(row, "shtDt", null);
        when(videoRepository.findByVmsClipId("CLIP-SHTDT")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9920L);

        // when
        tx.ingestOne(row);

        // then
        assertThat(savedRaw().getShtDt()).isNull();
        assertThat(warnMessages()).anyMatch(m -> m.contains("SHT_DT"));
    }

    @Test
    @DisplayName("촬영일시가_있으면_촬영일시_WARN은_남기지_않는다")
    void doesNotWarnWhenShtDtPresent() throws IOException {
        // given — 정상 수신(픽스처 기본값에 촬영일시 포함).
        Path video = seedArrivedVideo("clip-shtdt-ok.mp4");
        LsDataIngest row = ingestRow("CLIP-SHTDT-OK", video.toString());
        when(videoRepository.findByVmsClipId("CLIP-SHTDT-OK")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9930L);

        // when
        tx.ingestOne(row);

        // then — 결손이 없으면 경고도 없다(경고 인플레이션 방지).
        assertThat(warnMessages()).noneMatch(m -> m.contains("SHT_DT"));
    }

    @Test
    @DisplayName("계약갭_WARN은_건마다_반복하지_않고_1회만_남긴다")
    void warnsControlContractGapOnlyOnce() throws IOException {
        // given — tick 당 상한(100건)만큼 적재되므로 건마다 WARN 이면 실패 로그가 묻힌다.
        Path first = seedArrivedVideo("clip-once-1.mp4");
        Path second = seedArrivedVideo("clip-once-2.mp4");
        LsDataIngest row1 = ingestRow("CLIP-ONCE-1", first.toString());
        ReflectionTestUtils.setField(row1, "shtDt", null);
        when(videoRepository.findByVmsClipId("CLIP-ONCE-1")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9940L);
        tx.ingestOne(row1);
        LsDataIngest row2 = ingestRow("CLIP-ONCE-2", second.toString());
        ReflectionTestUtils.setField(row2, "shtDt", null);
        when(videoRepository.findByVmsClipId("CLIP-ONCE-2")).thenReturn(Optional.empty());

        // when — 같은 결손을 가진 두 번째 행 적재
        tx.ingestOne(row2);

        // then — 각 갭마다 정확히 1회(이후는 DEBUG 로만 남는다).
        assertThat(warnMessages().stream().filter(m -> m.contains("EVNT_TYPE_CD")).count()).isEqualTo(1);
        assertThat(warnMessages().stream().filter(m -> m.contains("SHT_DT")).count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 미도착 복귀 실패 관측

    @Test
    @DisplayName("PENDING_복귀가_0행이면_경고로_남긴다")
    void warnsWhenPendingRevertAffectsNoRow() throws IOException {
        // given — 복귀 UPDATE 가 0행(그 사이 다른 상태가 됨)이면 이 행은 PROCESSING 좀비로 남을 수 있다.
        Files.createDirectories(mountRoot.resolve("videos"));
        String notArrived = mountRoot.resolve("videos").resolve("lost.mp4").toString();
        LsDataIngest row = ingestRow("CLIP-REVERT0", notArrived);
        when(ingestRepository.revertToPendingForRetry(eq(RCPTN_SN), any(), any())).thenReturn(0);

        // when
        tx.ingestOne(row);

        // then — 반환값을 무시하지 않고 관측 가능하게 남긴다(조용한 좀비 금지).
        assertThat(warnMessages()).anyMatch(m -> m.contains("revert"));
    }

    // ---------------------------------------------------------------- 경로 검증 (CWE-22/59)

    @Test
    @DisplayName("허용_루트_하위를_가리키는_심링크는_적재된다")
    void acceptsSymlinkPointingInsideAllowedRoot() throws IOException {
        // given — /nas/videos/clip.mp4 → /nas/videos/2026/07/clip.mp4 같은 평범한 NAS 레이아웃.
        //   판정 기준은 "심링크 타깃이 같은 디렉터리인가" 가 아니라 "최종 실경로가 허용 루트 하위인가" 다.
        Path targetDir = mountRoot.resolve("archive").resolve("2026").resolve("07");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve("clip-linked.mp4");
        Files.writeString(target, "raw-bytes");
        Path linkDir = mountRoot.resolve("videos");
        Files.createDirectories(linkDir);
        Path link = linkDir.resolve("clip-link.mp4");
        Files.createSymbolicLink(link, target);
        LsDataIngest row = ingestRow("CLIP-SYMLINK-IN", link.toString());
        when(videoRepository.findByVmsClipId("CLIP-SYMLINK-IN")).thenReturn(Optional.empty());
        stubSaveAssigningRawSn(9950L);

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 오탐 없이 적재된다(경로는 관제가 준 원문 그대로 적재).
        assertThat(ingested).isTrue();
        assertThat(savedRaw().getRawFilePathNm()).isEqualTo(link.toString());
        verify(ingestRepository, never()).revertToPendingForRetry(anyLong(), any(), any());
    }

    @Test
    @DisplayName("허용_루트_밖을_가리키는_심링크는_적재하지_않는다")
    void rejectsSymlinkEscapingAllowedRoot() throws IOException {
        // given — 검증된 디렉터리 안의 파일명이 allowlist 밖을 가리킨다(CWE-59 — 실제 열릴 파일이 다르다).
        Path outside = outsideRoot.resolve("secret.mp4");
        Files.writeString(outside, "raw-bytes");
        Path linkDir = mountRoot.resolve("videos");
        Files.createDirectories(linkDir);
        Path link = linkDir.resolve("clip-escape.mp4");
        Files.createSymbolicLink(link, outside);
        LsDataIngest row = ingestRow("CLIP-SYMLINK-OUT", link.toString());

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재 금지 + 영구 사유로 종결(재시도해도 통과할 수 없다).
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(ingestRepository, never()).revertToPendingForRetry(anyLong(), any(), any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }


    @Test
    @DisplayName("허용_루트_밖_경로는_적재하지_않는다")
    void rejectsPathOutsideAllowedRoot() throws IOException {
        // given — 관제가 준 경로가 허용 마운트 루트 밖을 가리킨다(실재하는 파일이어도 거부).
        Path outside = outsideRoot.resolve("evil.mp4");
        Files.writeString(outside, "raw-bytes");
        LsDataIngest row = ingestRow("CLIP-OUTSIDE", outside.toString());

        // when
        boolean ingested = tx.ingestOne(row);

        // then — 적재 금지 + 종결(재시도해도 통과할 수 없는 영구 사유).
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(ingestRepository, never()).revertToPendingForRetry(anyLong(), any(), any());
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("상위경로_순회가_포함된_경로는_적재하지_않는다")
    void rejectsTraversalPath() throws IOException {
        // given — '..' 순회로 허용 루트를 벗어나는 경로(CWE-22).
        Path outside = outsideRoot.resolve("traversal.mp4");
        Files.writeString(outside, "raw-bytes");
        // mountRoot/videos/../.. == mountRoot 의 상위 → 거기서 outside 파일까지의 상대경로를 이어 붙인다.
        String traversal = mountRoot.resolve("videos").resolve("..").resolve("..")
                .resolve(mountRoot.getParent().relativize(outside)).toString();
        LsDataIngest row = ingestRow("CLIP-TRAVERSAL", traversal);

        // when
        boolean ingested = tx.ingestOne(row);

        // then
        assertThat(ingested).isFalse();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
    }

    @Test
    @DisplayName("내부_업로드_출처유형은_적재_allowlist를_통과한다")
    void internalUploadSrcTypeIsAllowed() {
        // given/then — 내부 TUS 업로드는 SRC_TYPE=USER_ULD 로 스스로 인입 행을 만든다
        //   (InternalUploadIngestWriter). 이 값이 적재 allowlist 밖이면 fail-closed 규칙에 걸려
        //   업로드분의 출처유형만 조용히 null 로 적재되고 파생 판별·화면 분기가 미정의가 된다.
        assertThat(TrainingVideoIngestTx.ALLOWED_SRC_TYPES)
                .as("내부 업로드 출처유형은 인입 적재 allowlist 와 한 세트다")
                .contains(LsDataIngest.SRC_TYPE_USER_ULD);
    }
}
