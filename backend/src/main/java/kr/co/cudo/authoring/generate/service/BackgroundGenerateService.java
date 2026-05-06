package kr.co.cudo.authoring.generate.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.generate.client.ExternalGenerateClient;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateRequest;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Phase 9 — 외부 SFR-06/11 시스템에 배경영상 생성 요청.
 *
 * <p>V1.5 정책: 본 저작도구는 인터페이스만 보유. 요청 이력은 in-memory 큐에 적재하여 감사 용도로만 사용.
 * (별도 테이블 미생성 — 외부 시스템 책임 영역이므로 본체 DB 부담 최소화)
 *
 * <p>RBAC: REVIEWER 만 호출 가능 (Service 이중 검증).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundGenerateService {

    private static final Set<String> ALLOWED_GEN_TYPES = Set.of("WILDFIRE", "FLOOD");

    private final ExternalGenerateClient externalClient;

    /** 최근 요청 이력 (in-memory, 최대 1000건 유지). */
    private final ConcurrentLinkedDeque<RequestRecord> history = new ConcurrentLinkedDeque<>();
    private static final int HISTORY_MAX = 1000;

    /**
     * 외부 시스템에 배경영상 생성을 요청.
     *
     * @throws CustomException FORBIDDEN(403) — REVIEWER 아님
     * @throws CustomException INVALID_INPUT(400) — genType 화이트리스트 위반
     * @throws CustomException EXTERNAL_API_ERROR(502) — 외부 시스템 장애 (Resilience4j circuit OPEN 등)
     */
    public BackgroundGenerateResponse requestExternal(BackgroundGenerateRequest req, TokenClaims actor) {
        requireReviewer(actor);
        if (req == null || req.genType() == null || !ALLOWED_GEN_TYPES.contains(req.genType())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "genType 은 WILDFIRE / FLOOD 중 하나여야 합니다.");
        }

        try {
            BackgroundGenerateResponse resp = externalClient.request(req)
                    .block(Duration.ofSeconds(60));
            recordHistory(actor.sub(), req, resp);
            log.info("[Generate] external request ack genType={} requestId={} actor={}",
                    req.genType(), resp == null ? "null" : resp.requestId(), actor.sub());
            return resp;
        } catch (CallNotPermittedException e) {
            // Circuit Breaker OPEN
            log.warn("[Generate] circuit OPEN genType={} actor={}", req.genType(), actor.sub());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "외부 생성 시스템이 일시적으로 사용 불가합니다.");
        } catch (WebClientResponseException e) {
            log.warn("[Generate] external returned {} genType={} actor={}",
                    e.getStatusCode(), req.genType(), actor.sub());
            HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
            if (status.is5xxServerError()) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "외부 생성 시스템 오류: " + status.value());
            }
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "외부 시스템이 요청을 거부했습니다: " + status.value());
        } catch (RuntimeException e) {
            log.warn("[Generate] external call failed genType={} err={}", req.genType(), e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "외부 생성 시스템 호출에 실패했습니다.");
        }
    }

    /**
     * 감사용 — 최근 요청 이력 (테스트/관리 화면 노출 가능).
     */
    public java.util.List<RequestRecord> recentHistory(int limit) {
        return history.stream().limit(limit).toList();
    }

    private void recordHistory(String actor, BackgroundGenerateRequest req, BackgroundGenerateResponse resp) {
        history.addFirst(new RequestRecord(
                actor,
                req.genType(),
                resp == null ? null : resp.requestId(),
                resp == null ? "UNKNOWN" : resp.status(),
                LocalDateTime.now()
        ));
        // 메모리 누수 방지: 최대 건수 초과 시 가장 오래된 항목 제거
        while (history.size() > HISTORY_MAX) {
            history.pollLast();
        }
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }

    public record RequestRecord(
            String actorUserNo,
            String genType,
            String requestId,
            String status,
            LocalDateTime requestedAt
    ) {}
}
