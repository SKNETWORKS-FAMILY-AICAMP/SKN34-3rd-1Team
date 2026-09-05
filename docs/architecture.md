# GovBiz 아키텍처

현재 production 코드의 서비스 경계와 실행 흐름을 설명합니다. 기술·버전은 [기술 구성](technology.md),
완료 기능과 남은 제약은 [구현 현황](implementation-status.md), 환경 설정은
[인프라 README](../infrastructure/README.md)를 참고하세요.

## 서비스 경계

```text
브라우저 → React Web → Core API
                       ├→ MySQL: 현재 공개된 공고 카탈로그
                       ├→ 공공데이터포털: 기업마당 전체 공고 수집
                       └→ AI Service
                           ├→ OpenAI: 문서·질의 임베딩과 후보 점수화
                           └→ Qdrant: 공고 버전별 벡터 저장·의미 검색
```

Core API는 공개 HTTP 계약, 기업마당 수집, MySQL 접근과 접수 상태 계산을 소유합니다. AI Service는
Core가 전달한 문서의 색인·검색·점수화를 담당하며 MySQL에 직접 접근하지 않습니다.

브라우저는 Core API의 `/api`만 호출합니다. Compose에서 Vite는 `/api`를 `core-api:8080`으로 프록시하며,
AI Service는 호스트에 포트를 게시하지 않습니다. MySQL·Qdrant·Core API·Web의 개발용 포트는
`127.0.0.1`에 바인딩합니다. 기업마당 키는 Core API에, OpenAI 키는 AI Service에만 주입합니다.
이는 개발 환경의 서비스 배치이며 운영 인증·접근 제어가 구현됐다는 의미는 아닙니다.

## 검색과 상세 조회

```text
GET /api/v1/support-programs/search
  → SupportProgramController
    → SupportProgramSearchService
      → SupportProgramRepository → MyBatis Mapper → Mapper XML → MySQL
      → 접수 상태 계산·필터
      ├→ 빈 검색어: 최신순 최대 5개 반환
      └→ 검색어 있음:
          AiSupportProgramRetrievalFacade → AiSupportProgramIndexClient
            → AI Service → 질의 임베딩 → Qdrant 후보 최대 20개
          AiSupportProgramRankingFacade → HttpAiSupportProgramRankingClient
            → AI Service → 단일 Agent → OpenAI 점수화
          Core의 응답 검증 → 최종 추천 0~5개
```

1. Repository는 `is_source_present = TRUE`인 `BIZINFO` 공고 전체를 읽고, 저장된 신청 기간과
   서울 날짜로 접수 상태를 다시 계산합니다. `acceptingOnly=true`이면 `OPEN`만 남깁니다.
2. 검색어는 앞뒤 공백을 제거합니다. 대상 공고가 없으면 빈 목록, 검색어가 비어 있으면
   `source_sort_timestamp` 내림차순·원본 ID 오름차순의 최대 5개를 반환합니다. 이 두 경로는 AI를 호출하지 않습니다.
3. 비어 있지 않은 질의는 대상 공고 전체의 정확한 ID·내용 해시를 AI Service에 전달합니다.
   최신 공고 20개를 먼저 자르지 않습니다. Qdrant가 반환해야 할 개수는 `min(대상 공고 수, 20)`입니다.
4. Core는 의미 검색 응답의 질의·ID·해시·중복·유한 점수·내림차순·개수를 검증하고,
   해당 DB 공고만 점수화 요청의 후보로 전달합니다.
5. AI Service는 모든 후보의 구조화된 점수화 결과를 검증한 후 총점순으로 정렬하고 추천 기준을 적용합니다.
   Core도 최종 응답의 후보 ID·질의·계약 버전·점수·순서·추천 이유를 재검증해 공개 응답으로 변환합니다.

점수화 계약은 `govbiz-support-program-ranking-v2`입니다. 의미 관련성 20/40점 이상과 총점 60/100점 이상을
충족해야 하며, `targetEligibility` 또는 `regionEligibility`가 `INCOMPATIBLE`이면 추천에서 제외합니다.
`UNKNOWN`은 정보 부족을 뜻해 자동 제외하지 않지만 신청 자격을 확인했다는 의미도 아닙니다.
AI Service가 부적격 항목을 최종 응답에 넣으면 Core는 이를 응답 계약 위반으로 거부합니다.
결과가 0개인 것은 정상일 수 있으며 관련 없는 공고로 5개를 채우지 않습니다.

검색 카드의 상세 링크는 `/support-programs/detail?sourceCode={sourceCode}&sourceProgramId={id}`로
이동합니다. 상세 화면은 해당 URL의 식별자로 공개 상세 API를 다시 호출하므로 직접 진입·새로고침이 가능합니다.

```text
GET /api/v1/support-programs/detail
  → SupportProgramController → SupportProgramDetailService
  → SupportProgramRepository → MyBatis Mapper → Mapper XML → MySQL
```

상세는 외부 API·AI를 호출하지 않습니다. 현재 노출된 복합 식별자 행만 반환하며 없는·미노출 행은
`SUPPORT_PROGRAM_NOT_FOUND`(404)입니다. 검색 문맥이 없으므로 추천 이유는 빈 배열, 점수는 `null`입니다.
공개 입력 제한과 JSON·오류 코드의 전체 계약은 [지원사업 API 계약](support-program-search-contract.md)에 있습니다.

## 검색 품질 평가 fixture 내보내기와 캡처

```text
evaluation-fixture-export profile (비웹 실행)
  → MySQL의 현재 공개 BIZINFO 공고 조회 → OPEN 공고만 선정
  → SupportProgramIndexDocumentMapper와 같은 ID·내용 해시·검색 문서 생성
  → 전체 적격 카탈로그와 cases: []인 미라벨 fixture 초안을 원자적으로 JSON 기록
  → 사람이 질문·관련 공고를 라벨링

evaluation-capture profile (비웹 실행)
  → 질문 묶음 JSON 검증
  → SupportProgramSearchService.searchWithTrace
      → MySQL 현재 공고 → Qdrant 후보 최대 20개 → AI 최종 추천 최대 5개
  → 질문별 후보 ID·최종 ID·카탈로그 지문을 원자적으로 JSON 기록
  → 별도 Python 평가 도구가 사람 라벨 fixture와 대조
```

두 경로 모두 공개 Controller나 디버그 HTTP endpoint가 아닙니다. `evaluation-fixture-export`는 자신의 웹 서버와
두 동기화 스케줄러를 끄고 공고 데이터는 MySQL에서만 조회합니다. 따라서 Qdrant·AI Service·OpenAI를 호출하지
않으며, 전체 카탈로그 검증이 끝난 뒤에만 출력 파일을 원자적으로 교체합니다. 생성된 `cases: []`에는 사람이
`id`·`query`·`split`·`relevantIds`를 채워야 합니다. 그 뒤 질문 묶음의 `name`과 각 `id`·`query`·`split`을
fixture의 `cases`와 같은 순서·내용으로 맞춥니다.

`evaluation-capture` profile도 자신의 웹 서버와 두 동기화 스케줄러를 끄며, 모든 질문이 성공하고 캡처 중
카탈로그 지문이 같을 때만 출력 파일을 교체합니다. 별도 Core API 인스턴스가 카탈로그를 갱신한 경우에는 지문
변화로 결과 파일 기록을 거부합니다. 후보 ID는 `sourceCode:sourceProgramId` 형태이고, 같은 Search Service가
만든 후보·최종 결과를 기록하므로 평가 코드가 운영 검색 흐름을 별도로 재현하지 않습니다. 실제 AI Service를
호출할 수 있으므로 기본 실행·CI에는 포함하지 않습니다. fixture 내보내기·라벨·캡처·평가 실행 규칙은
[검색 평가 자료](../evaluation/support-program-search/README.md)를 따릅니다.

## 기업마당 동기화와 공개 순서

```text
BizInfoSupportProgramCatalogSyncScheduler (기본: 최초 PT0S, 완료 후 PT6H)
  → Repository: 수집 시작 세대 발급 [짧은 DB transaction]
  → BizInfoSupportProgramCatalogFacade → BizInfoClient: 전체 페이지 수집·검증
  → BizInfoProgramMapper: 필수 필드 검증·공고 정규화
  → SupportProgramIndexSyncService.indexBizInfoSnapshot: 모든 공고의 벡터 준비
      → AiSupportProgramIndexClient → AI Service → OpenAI 임베딩 → Qdrant
  → Repository: 최신 시작 세대일 때만 MySQL에 공개 [짧은 DB transaction]
      → BIZINFO 기존 행 미노출 처리 + 수집 목록 UPSERT
```

공공데이터포털의 전체 건수, 페이지 번호·크기, 페이지별 항목 수와 실제 수집 수가 일치해야 합니다.
기업마당 수집·응답 검증·필수 필드 정규화 중 하나라도 실패하면 카탈로그를 바꾸지 않습니다.
현재 Client는 페이지당 1,000건을 요청하며 최대 20페이지·20,000건으로 제한합니다.

색인은 전체 공고를 16개씩 나누어 요청합니다. AI Service는 동일 ID·해시의 벡터가 이미 있으면 재사용하고
없는 버전만 생성합니다. 모든 배치의 성공과 처리 건수를 확인한 후에만 DB 공개를 시도합니다.
색인 도중 실패하면 기존 공개 카탈로그를 유지하며, 이미 준비된 벡터는 재시도 시 재사용할 수 있습니다.

MySQL의 `support_program_sync_generation`은 제공처별 최신 **시작** 세대를 관리합니다. 이전 실행이 늦게
끝나도 더 최근에 시작된 작업이 있으면 공개를 건너뜁니다. 후발 작업이 실패하더라도 이전 세대가 다시
공개 권한을 얻지는 않으며 마지막으로 공개된 카탈로그를 다음 성공까지 유지합니다.

시작 세대 발급과 공개는 각각 행 잠금을 사용하는 짧은 DB transaction입니다. 공개 transaction 안의
미노출 처리와 UPSERT 중 하나라도 실패하면 전체를 rollback합니다. 외부 HTTP 수집·색인은 DB transaction
밖에서 실행하며, 수집 실패를 이유로 기존 행을 삭제하거나 다른 제공처 데이터를 변경하지 않습니다.
Scheduler는 실패를 기록하고 다음 주기에 계속 실행합니다.

## 벡터 정합성과 복구

`SupportProgramIndexDocumentMapper`가 제목·기관·지원 대상·분야·지역·신청 기간 원문·요약으로 검색 문서를
구성합니다. 제어·형식 문자는 개행·탭을 제외하고 정리하며 Unicode 코드 포인트 기준 최대 12,000자로
제한합니다. UTF-8 문서의 SHA-256이 내용 해시이고, 내부 문서 ID는 `BIZINFO:{원본 ID}`입니다.

AI Service는 문서 ID·내용 해시에서 Qdrant point ID를 결정하며, 임베딩 모델·차원·색인 규격에 따라
컬렉션을 분리합니다. 현재 DB의 정확한 문서 버전에 해당하는 point ID만 검색하도록 필터링하므로
이전 버전·미노출 공고·아직 공개하지 않은 세대의 벡터는 결과에 섞이지 않습니다.

```text
SupportProgramIndexSyncScheduler (기본: 최초 PT0S, 완료 후 PT1M)
  → SupportProgramIndexSyncService.repair
  → Repository: 현재 MySQL BIZINFO 목록 조회
  → AI Service: 해당 버전의 누락 벡터 생성·저장
```

복구는 기업마당 수집과 별도 단일 스레드에서 실행합니다. `SUPPORT_PROGRAM_INDEX_ENABLED=false`는
이 복구 작업만 끄며, 새 카탈로그 공개 전의 필수 색인은 끄지 않습니다.

공개 준비와 복구는 모두 `prune`을 호출하지 않습니다. 이전 스냅샷 기준의 삭제가 공개 준비 중인 새 벡터를
지우는 상황을 피하기 위해 현재 자동 삭제를 연결하지 않았습니다. 내부 `prune` API는 존재하지만,
안전한 보존·삭제 수명주기와 전체 다중 인스턴스 운영 검증은 후속 과제입니다.

검색 대상의 벡터가 하나라도 없거나 Qdrant·AI Service가 실패하면 일부 후보만으로 성공하지 않고 오류를
반환합니다. 별도 복구 작업의 성공 후 다시 검색할 수 있습니다. 빈 검색어 목록과 상세는 AI에 의존하지 않습니다.
조회 때 변하는 접수 상태는 벡터에 고정하지 않으며, DB의 `content_hash` 컬럼도 영속 색인 완료 기록으로
사용하지 않습니다. 현재 검색은 매번 전체 대상 공고와 ID·해시 목록을 읽고 전송하므로 대규모 데이터 성능은
별도 개선이 필요합니다.

## 데이터와 접수 상태

MySQL의 `support_program`은 `(source_code, source_program_id)` 고유키로 원본 공고를 식별합니다.
`categories`·`regions`는 JSON, 신청 기간은 원문과 nullable 날짜로 저장합니다. 원본에서 사라진 공고는
삭제하지 않고 `is_source_present=false`로 바꿉니다. UPSERT는 대소문자만 바뀐 원본 ID도 최신 표기로
갱신하여 MySQL과 벡터 식별자를 맞춥니다. 고유키 비교는 MySQL의 `utf8mb4_0900_ai_ci` collation을 따릅니다.

접수 상태는 `SupportProgramStatusResolver`가 읽을 때 계산합니다. 파싱된 시작일 전은 `UPCOMING`,
종료일 이후는 `CLOSED`, 시작일·종료일 범위 안은 `OPEN`입니다. 날짜 경계는 포함합니다.
날짜만으로 결정되지 않은 경우 예정 표현, 남아 있는 종료일, 명시적 종료 표현, 상시 접수 표현 등의
규칙을 순서대로 적용하고 판단 근거가 없으면 `UNKNOWN`을 유지합니다. 따라서 `접수 종료` 표현이
상시 접수보다 우선하더라도 파싱된 날짜를 무조건 덮어쓰지는 않습니다.

DB·상세 식별자는 제공처 구분을 갖추었지만 production 수집·전체 검색·색인은 현재 `BIZINFO`만 지원합니다.
AI 점수화 후보는 원본 ID를 사용하므로 두 번째 제공처를 추가할 때는 검색 범위, 색인 문서 ID,
점수화 식별자와 제공처 표시 이름을 함께 확장해야 합니다.

## Frontend와 내부 계약

Frontend의 업무 호출 흐름은 `View → ViewModel Hook → UseCase → Repository → data/api → Core API`입니다.
Awilix의 `app/di`에서 Repository·UseCase·외부 함수를 구성하고 `appContainer`가 앱 단위 인스턴스를
제공합니다. ViewModel은 UseCase·외부 함수 토큰을 조회하며 Repository를 직접 생성하지 않습니다.
`data/api`의 함수가 요청 URL·Fetch·Zod 응답 검증을 담당합니다.

채팅 메시지·검색 조건은 Redux Toolkit으로 관리하고 검색 요청 흐름은 ViewModel의 thunk에 둡니다.
화면 전용 DOM 참조·입력 조합 상태 등은 로컬 hook으로 관리합니다. React Router는 검색 화면,
지원사업 상세와 두 SampleItem 예제 화면을 연결합니다. SampleItem은 업무 기능이 아니라 같은 UseCase의
Hook 상태와 Redux 상태 차이를 비교하는 예제입니다.

Core의 공개 계약은 기능별 `controller/dto`, 외부 계약은 시스템별 `client/dto`, 검증된 실행 결과는
`service/dto`, 업무 모델은 `domain`에 둡니다. 관계형 DB 접근은 `Repository → Mapper → XML`이며
`DbRow`를 Repository 밖으로 노출하지 않습니다. 같은 필드가 있어도 외부 입력과 공개 응답을 하나의
타입으로 합치지 않습니다. 상세 배치 규칙은 [Core API README](../backend/core-api/README.md)에 있습니다.

AI Service는 `HTTP API → Service → Agent → OpenAI → Response` 흐름으로 점수화를 실행합니다.
`bootstrap.py`가 클라이언트와 서비스 수명주기를 구성하고, 단일 typed Agent를 `max_turns=1`로 실행합니다.
현재 tool·handoff·multi-agent orchestration은 없습니다. 색인·검색은 별도 `support_program_index` 기능이
OpenAI 임베딩과 Qdrant를 직접 호출합니다.

## 오류 경계

AI Service의 LLM 실행 실패·색인 미준비·Qdrant 실패는 내부 503으로 반환되고 Core는 공개
`503 AI_SERVICE_UNAVAILABLE`로 변환합니다. Core가 관측한 연결·읽기 timeout 및 점수화·색인 API의
내부 408·504는 504, 예상하지 않은 HTTP 상태나 잘못된 응답 계약은 502로 분류합니다.
공개 응답은 `application/problem+json`이며 내부 URL·원본 라이브러리 예외를 노출하지 않습니다.

Core의 Health는 프로세스 상태, AI Health는 AI Service의 정해진 Health 응답을 확인하는 기능입니다.
이들이 성공했다고 MySQL·Qdrant·OpenAI를 포함한 실제 검색 전체가 준비됐음을 보장하지 않습니다.
전체 연결 동작은 [Compose 검증 절차](../infrastructure/README.md)로 확인합니다.

현재 제품은 공고 요약의 의미 검색과 구조화된 추천 단계입니다. 실제 검색 후보·최종 추천을 캡처해 평가하는
도구는 있으나, 실제 공고 정답 데이터와 사람이 검토한 품질 보고서는 아직 없습니다. 첨부문서 수집·청킹·근거
인용 답변 RAG와 여러 제공처 수집도 구현 현황의 후속 범위로 구분합니다.
