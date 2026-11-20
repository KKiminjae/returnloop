# E01-03 — 환불 생성 Service와 트랜잭션 경계

## 문제와 선택

2026-11-16. 생성 중 UNIQUE 위반으로 실패한 JPA 트랜잭션 안에서 기존 Refund 조회를
계속하지 않도록 조정 서비스와 트랜잭션 실행 빈을 분리했다.

- RefundService.createRefund: NOT_SUPPORTED, 결과 조정과 오류 분류
- RefundTransactionService.createOrFind: REQUIRES_NEW, 결제 및 기존 환불 조회 후 생성·커밋
- RefundTransactionService.findExisting: readOnly / REQUIRES_NEW, 충돌 이후 새 조회 경계
- MySQL 1062 및 Hibernate의 정확한 uk_refund_payment_id 제약 이름이 모두 일치해야 복구 분기로 진입
- 그 밖의 무결성 오류 또는 재조회 실패는 원래 예외 전파
- RefundResult에 id, paymentId, amount, status, created를 담으며 엔티티나 멱등 키는 전달하지 않음

별도 빈의 Spring 프록시를 통해 호출하여 트랜잭션 경계를 적용한다. 생성 트랜잭션은
saveAndFlush 실행과 커밋까지 수행하고, 예외는 그 호출 바깥에서 처리한다.
NOT_SUPPORTED / REQUIRES_NEW는 호출자 트랜잭션이 있더라도 환불 처리를 독립시킨다.
호출자의 데이터 변경과 환불을 하나의 트랜잭션으로 묶는 용도로 사용하면 안 된다.

## 실행 및 관측

```bash
./gradlew clean test
git diff --check
```

최종 결과 BUILD SUCCESSFUL, 전체 42건 실패 0건.

### MySQL 통합 테스트 11건

테스트 클래스에는 Transactional을 붙이지 않았다. 준비 데이터를 별도 트랜잭션으로
커밋하고 Service 호출 후 다시 별도 트랜잭션에서 DB 상태를 확인했다.
테스트 데이터 정리는 해당 클래스의 Testcontainers DB에서만 수행했다.

- 최초 요청: created=true, PENDING, 원본 15,000원 반환 및 별도 트랜잭션에서 저장 결과 조회
- 생성 직후 Payment는 PAID 유지. PG 성공 처리는 이 단계에서 수행하지 않음
- 기존 요청: Payment 상태 2종 × Refund 상태 4종의 8개 조합에서 created=false, 같은 ID·금액·현재 상태 반환
- 재요청 후 Refund 1건 및 기존 멱등 키와 Payment 상태 보존 확인
- Payment 없음: PaymentNotFoundException, 저장된 Refund 0건
- REFUNDED이지만 Refund 없음: RefundStateInconsistentException 및 정합성 이상 로그, Refund 0건

상태 조합은 SQL로 만든 분기 검증용 데이터다. 업무에서 모든 상태 조합의 생성을 허용하거나
상태 전이 정합성을 검증했다는 의미가 아니다.

### 오류 분기 단위 테스트 8건

Mockito로 트랜잭션 실행 빈의 예외·반환값을 제어했다. 실제 동시 요청 테스트가 아니다.

- 제약 이름 uk_refund_payment_id 및 refund.uk_refund_payment_id를 인식하고 기존 결과 반환
- 생성 호출 실패 후 조회 호출 순서 확인
- 멱등 키 제약, 다른 제약 및 유사한 이름은 복구 대상으로 취급하지 않음
- 오류 코드가 1062가 아니거나 제약 이름을 알 수 없으면 그대로 전파
- 중복 오류 이후 재조회에서도 Refund를 찾지 못하면 원래 예외 전파

기존 DB 제약 15건 및 엔티티 / Repository 8건도 함께 통과했다.
원본 보고서는 build/test-results/test/TEST-*.xml 및 build/reports/tests/test/index.html에
생성되며 Git 추적 대상은 아니다.

## 한계와 다음 검증

- 실제 두 생성 트랜잭션 충돌 후 롤백 및 새 트랜잭션 조회의 전체 흐름은 미검증
- 100건 요청과 제어된 두 트랜잭션 경쟁 재현은 별도 단계에서 수행
- MySQL / Hibernate의 현재 제약 이름 추출 방식에 의존. 실제 충돌 실험에서 확인 필요
- 호출자 트랜잭션이 있는 경우의 중단·재개는 설정에 표현했으며 별도 실행 검증하지 않음
- HTTP 상태 코드와 오류 JSON 매핑, PG 호출·Job 및 상태 전이는 아직 미구현
- 개발용 Compose DB는 변경하지 않음
