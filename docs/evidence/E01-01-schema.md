# E01-01 — Flyway 및 DB 제약 검증

## 범위와 가설

2026-11-16, E01의 첫 구현 단위. Payment / Refund 스키마와 DB 제약만 대상으로 한다.
API, JPA 엔티티, 동시 요청 처리는 아직 구현하지 않았다.

가설: 애플리케이션의 사전 검사 없이도 MySQL이 중복 환불, 잘못된 참조,
NULL 필수 값, 양수가 아닌 금액 및 정의되지 않은 상태를 거부할 수 있다.

## 실행 조건

- Java 21, Spring Boot 4.1.2, Testcontainers의 mysql:8.4
- Spring Boot 시작 시 V1 마이그레이션을 빈 테스트 DB에 적용
- 테스트는 JdbcTemplate으로 실제 INSERT 수행, 각 테스트의 DML은 트랜잭션 종료 시 롤백
- 같은 트랜잭션의 연결에서 LAST_INSERT_ID()로 테스트 Payment 식별자 확인
- 개발용 Compose DB에는 접속하거나 마이그레이션을 적용하지 않음

```bash
./gradlew clean test
git diff --check
```

## 관측 결과

- 최종 실행: BUILD SUCCESSFUL, 15 tests, failures=0, errors=0
- Flyway V1 적용 이력 확인, 재호출 시 migrationsExecuted=0 및 V1 성공 이력 1건 확인
- 정상 Payment / Refund 저장 및 DB 조회 금액 확인
- 동일 payment_id 중복: MySQL 1062 / uk_refund_payment_id
- 서로 다른 Payment의 동일 멱등 키: MySQL 1062 / uk_refund_idempotency_key
- 없는 Payment 참조: MySQL 1452 / fk_refund_payment
- 필수 payment_id, amount, status, idempotency_key의 NULL: MySQL 1048
- 두 테이블의 0 / 음수 금액: MySQL 3819 / 금액 CHECK
- 두 테이블의 잘못된 상태 및 소문자 상태: MySQL 3819 / 상태 CHECK
- git diff --check 통과
- 테스트 및 보조 메서드 이름을 한국어로 변경한 후 ./gradlew test 재실행: 15건 통과, 로직 변경 없음

첫 실행은 CHECK 위반을 DataIntegrityViolationException으로 예상하여 6건 실패했다.
실제 JDBC 결과는 MySQL 3819, SQLState HY000이었고 Spring에서는
UncategorizedSQLException으로 분류했다. 테스트를 DataAccessException의 실제
SQLException 오류 코드와 제약 이름을 확인하도록 수정한 뒤 전체 15건이 통과했다.

실행 원본은 build/test-results/test/TEST-returnloop.ReturnloopApplicationTests.xml,
HTML 보고서는 build/reports/tests/test/index.html에 생성된다.
build 디렉터리는 Git 추적 대상이 아니며 다음 clean 실행으로 삭제될 수 있다.

## 보장하지 않은 항목 및 다음 단계

- 순차 SQL 제약 검증이며, 100건 요청 / 두 트랜잭션 경쟁 재현은 아직 수행하지 않음
- 엔티티가 없으므로 ddl-auto=validate 설정만 적용되었고 엔티티 매핑 검증은 후속 단계
- Payment / Refund 금액 일치, 생성 후 금액 불변, 상태 간 일관성은 이 DDL만으로 보장되지 않음
- BIGINT는 정수 저장 타입이며 소수 입력 거부 정책은 후속 API 입력 검증에서 확인 필요
- VARCHAR(36)은 UUID 저장 공간이며 UUID 형식 자체를 DB가 검증하지는 않음
- 기존 로컬 DB의 비어 있지 않은 스키마 전환은 미검증. 자동 baseline 및 데이터 삭제 없음
- 적용된 V1은 이후 변경하지 않고 새 버전 마이그레이션으로 변경
- 다음 단위는 Payment / Refund 엔티티 및 Repository 구현·검증

의존성 선택 근거: [Spring Boot 공식 DB 초기화 문서](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
