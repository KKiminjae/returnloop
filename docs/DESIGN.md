# ReturnLoop 핵심 설계

E01은 환불 생성과 중복 방지, E02는 PG 응답 유실, E03은 Worker 비정상 종료 후 복구를 다룬다. E04에는 대사 설계 방향을 기록한다.
관측 결과는 [스키마 Evidence](evidence/E01-01-schema.md)와
[엔티티 Evidence](evidence/E01-02-entities.md), [Service Evidence](evidence/E01-03-service.md)에 기록한다.

## 범위

- 단일 Spring Boot 애플리케이션, MySQL, 독립 Mock PG로 실험
- 전액 환불만 지원. KRW 양의 정수 원 단위이며 원본 결제 금액과 환불 금액은 생성 후 변경하지 않음
- Redis, 메시지 브로커, MSA는 초기 범위에서 제외

## E01 — 중복 환불 생성 방지

사전 조회만으로는 동시 요청의 중복 생성을 막을 수 없다. Payment당 Refund는 최대
하나로 제한하고 refund.payment_id의 NOT NULL / FK / UNIQUE를 최종 방어 수단으로 사용한다.

중복 요청은 기존 Refund를 반환한다. 생성 중 UNIQUE 충돌이 발생하면 해당 트랜잭션을
롤백한 뒤 별도 트랜잭션에서 조회한다. 다른 무결성 오류를 중복 요청으로 숨기지 않는다.

RefundService는 NOT_SUPPORTED로 트랜잭션을 유지하지 않고 별도 빈인
RefundTransactionService의 REQUIRES_NEW 생성·조회 메서드를 호출한다.
생성 시 saveAndFlush 및 트랜잭션 커밋을 마친 뒤 결과를 반환한다.
예외는 해당 트랜잭션 종료 이후 조정 서비스에서 처리한다. MySQL 1062와 정확한
uk_refund_payment_id 제약 이름을 확인한 경우만 새 트랜잭션에서 기존 환불을 조회한다.
재조회로도 찾지 못하면 원래 오류를 전파하며 새 환불 생성을 반복하지 않는다.

기존 Refund가 있으면 상태와 관계없이 반환한다. Refund 없이 Payment가 REFUNDED인
경우 정합성 예외와 로그를 남긴다. RefundResult는 엔티티가 아닌 값으로 결과 및 신규
생성 여부를 전달한다. `POST /payments/{paymentId}/refund`는 이 결과를 최초 생성
`201`과 기존 환불 반환 `200`으로 매핑한다. 응답은 환불 ID, 결제 ID, 금액과 상태를 제공한다.
결제가 없으면 `404`, 결제 상태와 환불 기록이 불일치하면 `409`를 반환한다.

서비스 요청 100건은 워커 10개로 처리해 반환 ID와 최종 DB 행이 각각 하나임을 확인했다.
별도 경쟁 테스트는 두 생성 트랜잭션의 사전 조회를 맞춘 뒤 MySQL UNIQUE 충돌,
실패 트랜잭션 롤백 및 새 JPA 트랜잭션에서의 재조회를 확인했다.

엔티티는 상태를 문자열 enum으로 매핑하고 Refund에서 Payment를 단방향 OneToOne으로 참조하며 지연 로딩한다.
Refund 생성 시 Payment의 금액을 복사하고 PENDING 및 UUID 멱등 키를 설정한다.
금액·결제 참조·멱등 키는 setter를 제공하지 않고 JPA UPDATE 대상에서 제외한다.
직접 SQL에 의한 변경까지 차단하는 DB 불변성 제약을 도입한 것은 아니다.

## 스키마 관리

Hibernate 자동 갱신 대신 Flyway 버전별 SQL로 제약을 명시하고 같은 마이그레이션을
개발·테스트 DB에 적용한다. Hibernate validate는 엔티티와 스키마의 호환성 검사에 사용하며
모든 DB 제약을 검증한다고 간주하지 않는다. 제약은 실제 MySQL SQL 실행으로 검증한다.

## E02 — PG 응답 유실

DB 중복 생성 방지와 PG의 중복 금전 처리 방지는 별개다. Refund마다 고정 멱등 키를
사용하며, 응답 유실은 FAILED가 아닌 UNKNOWN으로 처리한다. 조회의 '거래 없음'도
최종 실패로 간주하지 않고 UNKNOWN을 유지한다.

최소 RefundJob과 단일 Worker를 E02에 함께 도입한다. Refund / Job을 원자적으로 저장하고
커밋 후 DB 트랜잭션 밖에서 PG를 호출한다. 결과 반영은 별도 트랜잭션으로 수행한다.
성공 시 Payment의 REFUNDED, Refund의 SUCCEEDED, Attempt 기록과 Job 갱신을 함께 반영한다.
RefundAttempt의 `attempted_at`에는 Worker가 PG에 요청하기 직전의 시각을 저장한다.
POST는 성공·실패·타임아웃, GET은 성공·실패·거래 없음·조회 불가를 기록한다.
Attempt INSERT와 해당 호출 결과의 상태 갱신은 하나의 트랜잭션에서 커밋한다.
호출 결과를 반영하기 전에 프로세스가 종료되거나 결과 반영이 롤백되면 이력이 남지 않는다.

단일 인스턴스에서 스케줄러가 실행 시각에 도달한 PENDING Job과 실행 시각이 없는
PENDING Job을 ID 오름차순으로 최대 20개 조회한다. 각 Job을 순차 처리하고, 마지막
처리가 끝나면 1초 뒤 다시 조회한다. Worker는 PG 호출 직전에 실행 가능 여부를 재확인한다.
자동 실행은 기본 비활성화하며, `returnloop.refund.worker.enabled=true`로 켤 때
`returnloop.refund.worker.pg-base-url`에 PG 주소를 지정한다.
`returnloop.refund.worker.pg-timeout`의 기본값은 1초다.

E02는 Worker가 계속 실행되는 조건에서의 응답 유실 복구를 다룬다.

## E03 — 비정상 종료 후 복구

Worker는 PG 호출 전에 별도 트랜잭션에서 Job을 PROCESSING으로 변경하고
`refund_job.lease_expires_at`을 저장한다. 점유 기간은 PG 타임아웃에 5초를 더한 값이다.
MySQL CHECK 제약으로 PROCESSING 상태에서만 만료 시각과 점유 토큰을 갖도록 한다.

스케줄러는 실행 가능한 PENDING Job과 만료 시각에 도달한 PROCESSING Job을 조회한다.
만료된 Job을 다시 점유할 때 Refund를 UNKNOWN으로 바꾸고 기존 멱등 키의 PG 결과를 조회한다.
종료 시점에 POST가 PG에 도달했는지 구분할 수 없어 만료된 작업은 일관되게 조회부터 시작한다.
PG에서 거래 없음이나 조회 불가가 돌아오면 기존 재시도 간격과 횟수를 적용하고,
세 번째 미확정 조회 결과를 저장하면 NEEDS_REVIEW로 전환한다.

PG 결과, 호출 이력 및 점유 해제는 같은 트랜잭션에서 반영한다.
결과 트랜잭션이 커밋되기 전에 종료된 조회는 조회 횟수와 이력에 포함되지 않는다.

여러 Worker가 같은 후보를 조회할 수 있다. 후보 조회는 잠금 없이 유지하고, 점유
트랜잭션에서 Job 한 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 실행 조건을 다시 확인한다.
점유할 때마다 새 토큰을 저장하며, PG를 호출하기 전에 점유를 커밋하고 잠금을 해제한다.
결과 반영도 같은 Job을 잠그고 점유 토큰과 작업 유형이 일치할 때만 수행한다.
다른 Worker가 만료된 점유를 회수하면 이전 토큰으로는 상태나 이력을 변경할 수 없다.
회수 전에 결과 반영이 행 잠금을 먼저 얻으면 현재 토큰으로 완료할 수 있다.
점유 만료 후에도 이미 전송된 PG 요청은 처리될 수 있어 기존 멱등 키와 조회 우선 복구를 유지한다.

## E04 — 내부 상태와 PG 기록 대사

대사는 내부 환불을 기준으로 PG 원장을 조회한다. 실행 시작 시 최대 Job ID를 읽고,
그 범위의 COMPLETED 및 NEEDS_REVIEW Job을 ID 오름차순으로 20건씩 읽어 한 번의
실행에서 처리한다. 마지막으로 읽은 ID 이후부터 다음 페이지를 조회한다.
`returnloop.refund.reconciliation.enabled`의 기본값은 false다. 활성화하면 대사 전용
스케줄러에서 실행하며, `returnloop.refund.reconciliation.interval`의 기본값은 실행 완료 후 5분이다.
PG 주소와 타임아웃은 Worker와 공유한다.

PG 조회는 트랜잭션 밖에서 수행한다. 결과를 반영할 때 Job 행을 잠그고 현재 상태를
재확인한다. Refund가 UNKNOWN이고 Job이 NEEDS_REVIEW이며 Payment가 PAID인 상태에서
PG 성공과 결제 ID·금액·멱등 키가 모두 일치하면 Refund와 Payment를 성공 상태로 변경하고,
Job 완료 및 RECONCILE 성공 이력을 같은 트랜잭션에 저장한다. Worker의 조회 횟수는 유지한다.

확정 상태 충돌, 거래 정보 불일치, 복구 조건에서의 Payment 상태 충돌과 내부 성공 건의
PG 거래 없음은 조사 기록에 남긴다. 환불 ID와 불일치 유형별로 한 행을 유지하고 최초·최근
탐지 시각과 최근 관측값을 저장한다. NEEDS_REVIEW의 거래 없음과 조회 불가는 다음 주기에
다시 확인한다. 확정된 FAILED는 상태를 보존하며, 대사 과정에서는 PG 조회만 수행한다.

## 기록 원칙

AI 제안을 그대로 확정하지 않고 개발자의 검토·합의를 거쳐 구현한다. 문서에는 선택 이유와
실제 검증 결과를 구분하고, 실행하지 않은 실험이나 운영 경험을 주장하지 않는다.
공개 기록은 README, 핵심 설계, Engineering Evidence에 집중한다.
