# ReturnLoop 핵심 설계

E01은 Payment / Refund 스키마, DB 제약, 엔티티, Repository, 환불 생성 Service와 API를 포함한다. E02~E04에는 후속 설계 방향을 기록한다.
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

이 단계는 Worker가 계속 실행되는 조건에서의 응답 유실 복구를 검증한다.
비정상 종료 후 자동 복구는 보장하지 않는다.

## E03 — 비정상 종료 후 복구

E02의 영속 작업을 확장하여 점유 만료와 재시작 복구를 검증한다. 점유 만료는 PG 실패를
뜻하지 않는다. 재개 시 기존 PG 결과를 우선 확인하며 상세 점유 규칙은 구현 전에 결정한다.
다중 Worker는 초기 범위에 포함하지 않는다.

## E04 — 내부 상태와 PG 기록 대사

상태뿐 아니라 결제 ID, 금액, 멱등 키도 비교한다. 확정 상태와 PG 기록이 충돌하면
Payment / Refund를 자동 변경하거나 UNKNOWN으로 되돌리지 않고 별도 조사 기록을 남긴다.
이는 내부 상태가 옳다고 인정한다는 의미가 아니다. 탐지만으로 추가 환불을 보내지 않는다.

확정된 FAILED 이후 재신청·자동 및 수동 재처리는 MVP에서 지원하지 않는다.
API 재요청은 기존 FAILED를 반환한다. 자동 복구의 세부 조건과 조사 기록 구조는 E04 전에 결정한다.

## 기록 원칙

AI 제안을 그대로 확정하지 않고 개발자의 검토·합의를 거쳐 구현한다. 문서에는 선택 이유와
실제 검증 결과를 구분하고, 실행하지 않은 실험이나 운영 경험을 주장하지 않는다.
공개 기록은 README, 핵심 설계, Engineering Evidence에 집중한다.
