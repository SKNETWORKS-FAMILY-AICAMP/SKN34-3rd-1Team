# GovBiz Core API

브라우저에 공개하는 Spring Boot API입니다. 기업마당 공고를 수집해 벡터 색인을 준비한 뒤 MySQL에
공개하고, 저장된 공고의 검색·상세 조회와 기업마당 공식 원문 근거 질문을 담당합니다.

프로젝트 전체 설명은 [메인 README](../../README.md), 기술 선택과 구현 범위는
[기술 구성](../../docs/technology.md)과 [구현 현황](../../docs/implementation-status.md),
실제 호출·데이터 흐름은 [아키텍처](../../docs/architecture.md)를 참고하세요.

## 실행

JDK 21과 MySQL 8.4가 필요합니다. 실제 공고 동기화·의미 검색에는 실행 중인 AI Service와 Qdrant도
필요합니다. 전체 서비스를 함께 실행하는 방법은 [인프라 README](../../infrastructure/README.md)를 참고하세요.

저장소 루트에서 다음 명령으로 실행합니다. 네이티브 실행은 루트 `.env`를 자동으로 읽지 않으므로
필요한 환경변수를 현재 프로세스에 설정해야 합니다.

```bash
cd backend/core-api
./gradlew bootRun
```

기본 주소는 `http://127.0.0.1:8080`입니다. 실행 시 Flyway가 MySQL 스키마를 적용합니다.
`DATA_GO_KR_SERVICE_KEY`가 비어 있으면 수집 작업은 실패를 기록하며 다음 주기에 다시 시도합니다.
기존 DB의 빈 검색어 목록·상세 조회는 가능하고, 검색어가 있는 검색은 AI Service와 해당 공고 버전의
색인이 있어야 성공합니다. 새 카탈로그 공개에도 색인 준비가 필수이므로 기업마당 키만으로 동기화가
완료되지는 않습니다.

## 검색 품질 평가 fixture 내보내기와 캡처

### 실제 공고 fixture 초안 내보내기

실데이터 평가의 시작점은 `evaluation-fixture-export` 프로필입니다. 이 비웹 프로필은 현재 MySQL의 모든
제공처 공개 공고 중 지정한 `referenceDate` 기준 `OPEN` 공고만 읽고, 운영 색인과 같은 `SupportProgramIndexDocumentMapper.fromCatalog`로
`id`·`contentHash`·`text`를 만듭니다. 공고 수와 카탈로그 지문을 포함한 전체 fixture 초안을 기록하므로,
이후 캡처 결과가 같은 공고 스냅샷에서 나왔는지 확인할 수 있습니다.

이 프로필은 웹 서버·기업마당 동기화·누락 색인 복구를 끄며 Qdrant, AI Service, OpenAI를 호출하지 않습니다.
`name`, `reference-date`, `output-path`는 반드시 지정해야 합니다. 기준 날짜는 실행 시점의 오늘이 아니라
저장된 신청 시작·종료일로 접수 상태를 다시 계산하는 기준입니다.

```bash
cd backend/core-api
./gradlew bootJar

SPRING_PROFILES_ACTIVE=evaluation-fixture-export \
APP_SUPPORT_PROGRAM_SEARCH_FIXTURE_EXPORT_NAME=support-program-catalog-20260905-v1 \
APP_SUPPORT_PROGRAM_SEARCH_FIXTURE_EXPORT_REFERENCE_DATE=2026-09-05 \
APP_SUPPORT_PROGRAM_SEARCH_FIXTURE_EXPORT_OUTPUT_PATH=/absolute/path/support-program-fixture.json \
java -jar build/libs/govbiz-core-api-0.0.1-SNAPSHOT.jar
```

생성 파일의 `cases`는 빈 배열(`[]`)입니다. 사람이 각 질문의 `id`·`query`·`split`·`relevantIds`를 라벨링한
뒤에만 질문 묶음과 캡처·평가를 진행할 수 있습니다. 질문 묶음의 `name`과 각 `cases`의 `id`·`query`·`split`은
fixture와 순서·내용까지 같아야 합니다. 내보내기는 빈 적격 카탈로그, 누락된 정렬 시각, 중복 검색 문서 ID 등
검증에 실패하면 기존 출력 파일을 바꾸지 않으며, 모든 검증이 끝난 결과만 원자적으로 교체합니다.

### 실제 검색 흐름 캡처

사람이 라벨링한 fixture와 같은 질문 묶음을 준비한 뒤에는 공개 API를 반복 호출하지 않고
`evaluation-capture` 프로필을 실행합니다. 이 프로필은 웹 서버·기업마당 동기화·누락 색인 복구를 끈 뒤,
질문 묶음의 각 항목을 현재 `SupportProgramSearchService`에 전달합니다. 따라서 MySQL의 적격 공고 선정,
Qdrant·키워드 순위를 결합한 후보 최대 20개, AI 최종 추천 최대 5개라는 운영 검색 흐름에서 나온 ID를
그대로 JSON 파일에 기록합니다.

질문 파일은 [예시](../../evaluation/support-program-search/query-set.example.json)를 복사해 준비합니다.
fixture 내보내기는 지정한 기준 날짜의 `OPEN` 공고만 담으므로, 캡처도 기본값인 `acceptingOnly=true`와
**같은 기준 날짜**로 실행해야 합니다.
실행 환경의 MySQL·AI Service·Qdrant는 실제 검색과 같은 상태여야 하며, AI 점수화 호출 비용이 발생할 수
있으므로 기본 실행이나 CI에는 포함하지 않습니다.

```bash
cd backend/core-api
./gradlew bootJar
SPRING_PROFILES_ACTIVE=evaluation-capture \
APP_SUPPORT_PROGRAM_SEARCH_CAPTURE_QUERY_SET_PATH=/absolute/path/query-set.json \
APP_SUPPORT_PROGRAM_SEARCH_CAPTURE_OUTPUT_PATH=/absolute/path/capture.json \
APP_SUPPORT_PROGRAM_SEARCH_CAPTURE_REFERENCE_DATE=2026-09-05 \
java -jar build/libs/govbiz-core-api-0.0.1-SNAPSHOT.jar
```

질문은 최대 100개이며, 하나라도 실패하거나 실행 중 카탈로그가 바뀌면 결과 파일을 쓰지 않습니다.
성공한 v2 캡처에는 기준 날짜·공고 수·카탈로그 지문·후보 ID·최종 추천 ID가 포함됩니다. 평가기는 fixture와
capture의 기준 날짜가 다르면 점수 계산을 거부합니다. 실제 공고의 검색 문서가 들어갈 수 있는 파일은
[평가 실행 보관 폴더](../../evaluation/support-program-search/runs/README.md)에 로컬로 보관하며 Git에는 올리지
않습니다. 사람이 검토한 정답 fixture와 비교해 실제 지표를 계산하는 방법은
[검색 평가 자료](../../evaluation/support-program-search/README.md)를 참고하세요.

## 공개 API

| 메서드·경로 | 용도 |
|---|---|
| `GET /api/v1/health` | Core API 자체 생존 상태 |
| `GET /api/v1/health/ai-service` | AI Service의 내부 Health 응답 확인 |
| `GET /api/v1/support-programs/readiness` | 공개 공고 스냅샷·검색 색인·최근 동기화 결과 상태 |
| `GET /api/v1/support-programs/search` | 현재 MySQL 공고 카탈로그의 검색 또는 최신 목록 |
| `GET /api/v1/support-programs/detail` | 제공처 코드와 원본 ID로 현재 공고 상세 조회 |
| `POST /api/v1/support-programs/detail/answers` | 특정 공고의 공식 원문 근거 질문·답변 |
| `POST /api/v1/sample-items/prepare` | 계층 연결 학습용 예제 |

- 검색: 필수 `query`는 최대 500자이며 빈 문자열을 허용합니다. `acceptingOnly`의 기본값은 `true`이고
  이때 `OPEN` 공고만 대상으로 삼습니다. 검색어가 있으면 검증된 의미 검색 상위 20개와 전체 적격 공고의
  키워드 상위 20개를 같은 가중치의 RRF(`1 / (60 + 순위)`)로 결합하고, 최대 20개를 AI가 점수화하여
  기준을 통과한 0~5개를 반환합니다. 키워드는 색인 본문의 NFC·소문자 토큰 집합 교집합 수로 정렬하고
  동점은 최신순·제공처 포함 ID순입니다. RRF 동점은 의미 검색 순위·제공처 포함 ID순입니다.
  의미 검색 실패는 오류로 반환합니다. 빈 검색어는 AI를 호출하지 않고 최신순 최대 5개를 반환합니다.
- 검색 준비 상태: `readiness`는 현재 신뢰할 수 있는 공개 스냅샷의 공고 수, 마지막 동기화 성공·실패 시각,
  해당 스냅샷의 전체 색인 준비 여부를 반환합니다. `SEARCHABLE`은 공고 수가 0인 성공 스냅샷도 포함하며,
  `SEARCHABLE_WITH_SYNC_FAILURE`은 이전 스냅샷은 검색 가능하지만 최신 수집·사전 색인 시도가 실패한 경우입니다.
  `PREPARING`은 아직 신뢰할 수 있는 상태 행이 없는 초기 상태이고, `UNAVAILABLE`은 상태 행은 있으나 현재
  공개 스냅샷의 색인 준비가 확인되지 않은 경우입니다. 시각은 `Asia/Seoul` 오프셋을 포함한 ISO-8601 문자열입니다.
- 상세: 필수 `sourceCode`는 `[A-Z][A-Z0-9_]{0,63}` 형식, `sourceProgramId`는 최대 255자이며 공백만 있는 값은 허용하지
  않습니다. 현재 노출된 행만 반환하며, 없는·미노출 공고는 404입니다. 검색 문맥이 없으므로 추천 이유는
  빈 배열, 추천 점수는 `null`입니다.
- 원문 근거 질문: `sourceCode`, `sourceProgramId`, 최대 500자의 `question`을 JSON body로 보냅니다. 현재 공개된
  `BIZINFO` 공고에만 제공하며, 사용자가 이 endpoint를 호출했을 때만 공식 HTTPS 상세 HTML을 수집합니다.
  MySQL 원문 캐시가 같은 URL로 6시간 이내면 재사용하고, 아니면 읽기 가능한 텍스트를 검증·저장한 뒤 최대 50개
  결정적 청크를 별도 Qdrant evidence 컬렉션에서 검색합니다. 답변은 `ANSWERED`(공식 원문 인용 1개 이상) 또는
  `INSUFFICIENT_EVIDENCE`(인용 없음)와 최대 5개 인용 발췌·공식 URL·청크 순서를 반환합니다. 첨부파일·PDF·OCR·다른
  제공처 원문은 지원하지 않습니다. 공식 원문 수집 실패는 503, 지원하지 않는 현재 제공처는 422입니다.
  상세 URL의 리디렉션은 매번 공식 HTTPS 호스트와 같은 `pblancId`인지 검증하며 최대 3회 따릅니다.
  HTML은 jsoup `1.23.2`로 파싱하고 `.support_project_detail`의 제목이 요청한 공고와 일치할 때
  `.view_cont` 본문만 추출합니다. 인용에는 검색된 청크 전체를 반환하며 청크당 최대 1,500 UTF-16 코드 단위입니다.
- 현재 수집기는 `BIZINFO` 한 제공처만 구현되어 있습니다. 전체 검색·색인·평가 fixture는 현재 MySQL의
  모든 제공처 공고를 다루며, 내부 식별자 `sourceCode:sourceProgramId`로 같은 원본 ID를 구분합니다.
  다른 제공처를 실제로 수집하려면 별도 Client·Facade·동기화 설정을 구현해야 합니다.

요청·응답 JSON과 상세 오류 계약은 [지원사업 API 계약](../../docs/support-program-search-contract.md),
SampleItem 예제는 [별도 계약](../../docs/sample-item-contract.md)에 있습니다.

## 설정

기본값의 기준은 [`application.properties`](src/main/resources/application.properties)입니다.
Compose는 일부 주소·CORS 값을 내부 네트워크에 맞게 덮어씁니다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3306/govbiz` | MySQL JDBC 주소 |
| `SPRING_DATASOURCE_USERNAME` | `govbiz` | MySQL 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | `govbiz-local` | 로컬 개발용 MySQL 비밀번호 |
| `DATA_GO_KR_SERVICE_KEY` | 빈 값 | 기업마당 수집용 공공데이터포털 키 |
| `BIZINFO_API_BASE_URL` | `https://apis.data.go.kr` | 기업마당 API 주소 |
| `BIZINFO_API_CONNECT_TIMEOUT` | `2s` | 기업마당 연결 제한시간 |
| `BIZINFO_API_READ_TIMEOUT` | `10s` | 기업마당 응답 제한시간 |
| `BIZINFO_SOURCE_DOCUMENT_BASE_URL` | `https://www.bizinfo.go.kr` | RestClient 기본 origin. 실제 요청은 검증된 공고 `sourceUrl`의 절대 URI를 사용하므로 이 값으로 요청 주소를 변경하지 않음 |
| `BIZINFO_SOURCE_DOCUMENT_CONNECT_TIMEOUT` | `2s` | 공식 원문 연결 제한시간 |
| `BIZINFO_SOURCE_DOCUMENT_READ_TIMEOUT` | `10s` | 공식 원문 응답 제한시간 |
| `BIZINFO_SYNC_ENABLED` | `true` | 기업마당 수집·색인 준비·DB 공개 작업 실행 여부 |
| `BIZINFO_SYNC_INITIAL_DELAY` | `PT0S` | 첫 수집 작업까지의 지연 |
| `BIZINFO_SYNC_FIXED_DELAY` | `PT6H` | 이전 수집 작업 종료 후 다음 실행까지의 지연 |
| `AI_SERVICE_BASE_URL` | `http://127.0.0.1:8000` | 내부 AI Service 주소 |
| `AI_SERVICE_CONNECT_TIMEOUT` | `1s` | AI Service 연결 제한시간 |
| `AI_SERVICE_READ_TIMEOUT` | `35s` | AI Health·점수화·원문 근거 답변 응답 제한시간 |
| `AI_SEMANTIC_SEARCH_READ_TIMEOUT` | `30s` | 의미 검색·색인 응답 제한시간 |
| `SUPPORT_PROGRAM_INDEX_ENABLED` | `true` | 이미 공개된 공고의 누락 벡터 자동 복구 여부 |
| `SUPPORT_PROGRAM_INDEX_INITIAL_DELAY` | `PT0S` | 첫 누락 벡터 복구까지의 지연 |
| `SUPPORT_PROGRAM_INDEX_FIXED_DELAY` | `PT1M` | 이전 복구 작업 종료 후 다음 실행까지의 지연 |
| `APP_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | 허용할 Web origin |

추천 점수화와 원문 근거 답변의 AI 모델·Agent 기본 제한은 각각 `25s`·`30s`이며 Core 읽기 제한
`35s`보다 짧습니다. 검색은 의미 검색과 점수화를 순서대로 호출하므로 브라우저 검색 제한은 두 Core
읽기 제한 `30s + 35s`에 여유를 둔 `70s`입니다. 이 값은 요청 제한이며 응답시간 보장은 아닙니다.

`SUPPORT_PROGRAM_INDEX_ENABLED=false`는 별도 복구 스케줄러만 끕니다. 새 카탈로그 공개 전의 필수
색인은 계속 실행됩니다. 두 스케줄러는 각각 별도의 단일 스레드에서 실행되며 실패를 기록한 뒤
다음 주기에 다시 시도합니다. 현재 공개된 수동 동기화 HTTP API는 없습니다.

기업마당 키는 Encoding·Decoding 형식 모두 받을 수 있으며 Client가 요청 전에 정규화합니다.
실제 인증키·운영 DB 비밀번호는 환경변수로 주입하고 Git에 기록하지 않습니다. OpenAI 키는
Core API가 아닌 AI Service에만 설정합니다.

## 코드 구조

기본 패키지는 `ai.govbiz.core`, Gradle 프로젝트명은 `govbiz-core-api`입니다.

```text
supportprogram/
├── controller            # 공개 HTTP 진입점
│   └── dto               # 공개 응답 계약
├── service/
│   ├── search             # DB 조회 → 의미·키워드 순위 결합 → AI 점수화
│   ├── detail             # 현재 공고 상세 조회
│   ├── evidence           # 공식 원문 캐시·청킹 → 근거 검색·답변
│   ├── sync               # 수집·색인 준비·DB 공개와 별도 벡터 복구
│   ├── evaluation         # 비웹 fixture 내보내기·검색 품질 평가 캡처 프로필
│   └── dto                # 검증된 내부 실행 결과
├── facade                 # 기업마당 수집·공식 원문·AI 응답 검증·도메인 변환
├── client/
│   ├── bizinfo            # 기업마당 HTTP·목록/공식 HTML 검증·외부 DTO 정규화
│   └── ai                 # AI 내부 HTTP 계약·공고/원문 청크 색인과 답변
├── repository            # 도메인↔DB 행 변환·트랜잭션·저장·조회
│   └── mapper            # MyBatis Mapper, DbRow
├── domain                 # 업무 모델·서울 날짜 기준 접수 상태 규칙
├── helper                 # 지원사업 하위 흐름이 함께 쓰는 보조 작업
└── config                 # 지원사업 공용 시계 설정
_health                    # Core API Health
_health_ai_service         # AI Service Health의 Controller → Service → Client
_sampleitem                # 학습 예제
_common                    # 실제 공유하는 HTTP·AI 설정·JSON·CORS·오류 처리
```

외부 호출의 기본 흐름은 `Controller → Service → Facade → Client`입니다. Facade는 하위 호출·검증·변환을
묶을 때 사용하며, DB 접근은 `Service → Repository → MyBatis Mapper → Mapper XML → MySQL`입니다.
Facade와 Domain은 MyBatis Mapper를 직접 호출하지 않습니다.

| 타입 | 소유 위치·역할 |
|---|---|
| `Request`, `Response` | 공개 HTTP 계약은 해당 기능의 `controller/dto` |
| 외부 `Request`, `Payload` | 상대 시스템별 `client/dto` |
| `Result` | 검증된 실행 결과는 `service/dto` |
| 업무 모델 | 프레임워크에 의존하지 않는 `domain` |
| `DbRow` | `repository/mapper`의 DB 행 타입. Repository 밖으로 노출하지 않음 |
| `Helper` | 실제 반복 보조 작업. 특정 기능의 하위 흐름이 함께 쓰면 해당 기능의 `helper`, 둘 이상의 기능이 함께 쓰면 `_common/helper` |

SQL은 [`SupportProgramMapper.xml`](src/main/resources/mybatis/supportprogram/repository/SupportProgramMapper.xml)에
명시하며 JPA·JdbcClient·annotation SQL을 혼용하지 않습니다. Repository가 JSON 배열과 DbRow를
변환하고 `SupportProgramStatusResolver`를 호출합니다. 상세 규칙은 [AGENTS.md](../../AGENTS.md)를 따릅니다.

## 데이터 관리와 오류 처리

- Flyway [V1](src/main/resources/db/migration/V1__create_support_program.sql)은 공고 테이블,
  [V2](src/main/resources/db/migration/V2__add_support_program_sync_generation.sql)는 최신 수집 시작 세대,
  [V3](src/main/resources/db/migration/V3__create_support_program_source_document.sql)는 공고별 공식 원문
  테이블, [V4](src/main/resources/db/migration/V4__create_support_program_sync_status.sql)는 공개 스냅샷의
  세대·지문·공고 수·색인 준비와 최근 동기화 결과를 만듭니다. 적용된 migration은 수정하지 않고 새 버전을 추가합니다.
- 전체 수집·검증·색인이 끝난 뒤 최신 시작 세대만 공개합니다. BIZINFO 행 미노출 처리와 UPSERT를
  하나의 짧은 DB transaction으로 묶고, 같은 transaction에서 스냅샷 지문·공고 수·`indexReady=true`·성공
  시각을 기록합니다. 외부 HTTP 호출은 transaction 밖에서 수행합니다.
- 수집 또는 공개 전 필수 색인이 실패하면 현재 세대일 때만 실패 시각을 기록합니다. 이때 이전 공개 스냅샷의
  공고·색인 준비 상태는 바꾸지 않습니다. 별도 복구가 실패하면 자신이 읽어 색인한 세대·지문·공고 수와 상태 행이
  여전히 일치할 때만 `indexReady=false`로 바꿉니다. 복구 성공도 같은 조건에서만 준비 완료를 기록합니다.
- V4 적용 전부터 있던 공고에는 공개 세대·검색 문서 지문·과거 색인 성공 여부가 없습니다. 다만 현재 공개된
  기업마당 공고가 1건 이상이고 별도 전체 색인 복구가 성공하면, 그때 읽어 색인한 지문·공고 수를 sentinel 세대 `0`과
  함께 한 번만 채택합니다. 빈 초기 DB·복구 전 legacy 공고는 `PREPARING`을 유지하며, 실제 새 스냅샷의 지문은 이
  bootstrap이 덮어쓰지 않습니다.
- 원본 ID 표기의 대소문자만 바뀌어도 UPSERT가 최신 표기를 저장하여 DB ID와 벡터 ID를 맞춥니다.
  DB 고유키의 비교는 `utf8mb4_0900_ai_ci` collation을 따릅니다.
- 접수 상태를 DB에 고정 저장하지 않습니다. 조회 시 날짜를 우선 적용하고, 날짜만으로 판단할 수
  없는 경우 원문 표현을 확인합니다. 명시적 종료 표현은 상시 접수 표현보다 우선합니다.
- 검색·색인 흐름은 [아키텍처](../../docs/architecture.md)에, 20,000건 상한·자동 벡터 삭제 미연결 등
  현재 제약은 [구현 현황](../../docs/implementation-status.md)에 정리합니다.

AI 경계의 실패는 `application/problem+json`으로 변환합니다. 내부 URL·라이브러리 예외는 공개하지 않습니다.

| AI 호출에서 관측한 상황 | 공개 HTTP | `code` |
|---|---:|---|
| 내부 503 또는 연결 불가 | 503 | `AI_SERVICE_UNAVAILABLE` |
| 점수화·색인 API의 내부 408·504 또는 연결·읽기 시간 초과 | 504 | `AI_SERVICE_TIMEOUT` |
| 예상하지 않은 HTTP 상태 | 502 | `AI_SERVICE_UPSTREAM_ERROR` |
| 잘못된 JSON·빈 body·응답 계약 위반 | 502 | `AI_SERVICE_INVALID_RESPONSE` |
| 공식 원문 제공처 수집·HTML 검증 실패 | 503 | `SUPPORT_PROGRAM_EVIDENCE_UNAVAILABLE` |
| 현재 공고 제공처가 원문 근거 질문을 지원하지 않음 | 422 | `SUPPORT_PROGRAM_EVIDENCE_NOT_SUPPORTED` |

AI Service는 LLM 실행 실패와 색인 미준비·Qdrant 실패를 내부 503으로 반환하므로 일반적으로 공개 503이
됩니다. Health API의 내부 408·504는 점수화 API와 달리 `UPSTREAM_ERROR`로 분류합니다.
기업마당 수집 오류는 검색 요청에서 발생하는 오류가 아니라 백그라운드 작업의 실패로 기록됩니다.

## 검증

`backend/core-api` 디렉터리에서 JDK 21 환경으로 실행합니다. Repository 통합 테스트가 실제
`mysql:8.4` Testcontainers를 실행하므로 Docker가 필요합니다.

```bash
./gradlew clean test --no-daemon
```

테스트는 Controller 계약·Client/Facade 응답 검증·상태 계산·동기화 순서·공식 원문 HTML 검증·근거 청크/인용 계약과
MySQL의 JSON, 복합 식별자, UPSERT, rollback, 시작 세대에 따른 공개 제어, 공개 스냅샷 준비 상태 전이를 검증합니다. 전체 서비스 연결 검증은
[인프라 README](../../infrastructure/README.md)의 Compose 검증 절차를 참고하세요.
