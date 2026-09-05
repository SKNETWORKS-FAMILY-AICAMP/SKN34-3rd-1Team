# GovBiz 아키텍처

## 서비스 경계

```text
Browser
  → React Web
      → 공개 HTTP API
          → Spring Boot Core API
              ├→ MySQL 8.4 → 지원사업 카탈로그
              ├→ 외부 HTTP API → 공공데이터포털
              └→ 내부 HTTP API → FastAPI AI Service
                                      ├→ OpenAI 임베딩·점수화
                                      └→ Qdrant 의미 검색 색인
```

React는 Core API만 호출합니다. FastAPI는 브라우저에 공개하지 않으며, Core API가 호출 결과를
자신의 공개 DTO와 오류 계약으로 변환합니다. 공공데이터포털 인증키는 Core API에, OpenAI 인증키는
AI Service에만 보관합니다. MySQL은 Core API가 소유하는 지원사업 카탈로그 저장소이며 Compose에서만
loopback 포트로 공개합니다. 외부 공고 응답을 GovBiz 지원사업 모델로 변환한 뒤 공개하며, LLM의
내부 의도 DTO는 브라우저 응답에 포함하지 않습니다. 이후 정책 엔진과 큐도 같은 방식으로 Core API
뒤에 추가합니다.

## Frontend

```text
앱 시작:
appContainer 모듈
  → Awilix Composition Root 한 번 생성
      ├→ Repository singleton
      ├→ UseCase singleton
      └→ 외부 API 함수

요청 실행:
View
  → ViewModel Hook
      ├→ Chat: typed Redux hooks
      │   → appContainer.resolve(SearchSupportProgramsUseCase)
      │   → ViewModel 안의 Redux Thunk
      │       → Domain UseCase.execute
      │           → Repository interface
      │               → Fixture 또는 HTTP Repository
      │   → Redux Toolkit slice·selector
      └→ SampleItem·Health
          ├→ Hook SampleItem·Health: 직접 실행 → React local state
          └→ Redux SampleItem: Thunk → 같은 UseCase → Redux slice·selector
```

- **View**는 JSX, 접근성, 표시를 담당합니다.
- **ViewModel**은 selector와 action을 화면이 사용하기 좋은 상태·행동으로 묶고, 검색 Thunk의 전체
  실행 순서를 한곳에 보여 줍니다.
- **Redux Toolkit**은 대화 메시지·검색 조건과 Redux SampleItem처럼 여러 화면에서 유지할 클라이언트
  상태를 관리합니다.
- **React Router**는 `/`, `/examples/sample-item/hook`, `/examples/sample-item/redux` URL을 각각의
  화면과 연결하고 브라우저 뒤로 가기와 직접 진입을 지원합니다.
- **React Hook state**는 Health와 Hook SampleItem처럼 한 화면에서 끝나는 요청의 로딩·성공·실패
  상태를 관리합니다.
- **Awilix Composition Root**는 `app/di`의 역할별 등록 모듈을 하나의 객체 graph로 조립하고 앱 단위
  singleton 수명주기를 관리합니다.
- **appContainer**는 GetIt처럼 앱 전체에서 동일한 Awilix root container를 조회하는 Service
  Locator입니다. ViewModel은 Repository가 아닌 UseCase·외부 함수 토큰만 resolve합니다.
- **UseCase**는 화면과 HTTP 구현 사이의 업무 행동입니다.
- **Repository interface**는 Domain이 필요한 통신을 정의합니다.
- **Data Layer**는 Fetch, URL, Zod 응답 검증을 소유합니다.

SampleItem의 두 화면은 같은 UseCase·Repository·endpoint를 사용하며 상태 보관 위치만 다릅니다.
Hook 화면은 이탈 시 초기화되고 Redux 화면의 완료 상태는 최상위 `appStore`의 `sampleItem` Slice가
유지합니다. 둘 다 새로고침
후에는 초기화됩니다. 사이드바 열림과 DOM 참조 같은 화면 전용 상태는 Redux에 넣지 않고 React 로컬 hook에 둡니다.
Health 조회처럼 업무 도메인이 아닌 연결 상태는 UseCase·Repository를 억지로 거치지 않지만, 서버
요청과 취소 lifecycle은 해당 Hook이 직접 관리합니다.

## Core API

```text
supportprogram/controller
└→ supportprogram/service/search
   ├→ supportprogram/repository
   │   └→ MyBatis Mapper XML → MySQL 지원사업 카탈로그
   ├→ supportprogram/facade/AiSupportProgramRetrievalFacade
   │   └→ supportprogram/client/ai/AiSupportProgramIndexClient → FastAPI → Qdrant
   ├→ supportprogram/facade/AiSupportProgramRankingFacade
   │   └→ supportprogram/client/ai
   │       └→ HttpAiSupportProgramRankingClient → FastAPI → OpenAI
   └→ supportprogram/domain

동기화 정기 실행
└→ supportprogram/service/sync/BizInfoSupportProgramCatalogSyncScheduler
   └→ supportprogram/service/sync/BizInfoSupportProgramCatalogSyncService
      ├→ Repository → MySQL에서 수집 시작 세대 발급
      ├→ supportprogram/facade/BizInfoSupportProgramCatalogFacade
      │   └→ supportprogram/client/bizinfo/BizInfoClient → 공공데이터포털
      ├→ SupportProgramIndexSyncService.indexBizInfoSnapshot
      │   └→ AiSupportProgramIndexClient → FastAPI → OpenAI 임베딩 → Qdrant (전체 준비)
      └→ supportprogram/repository
          └→ MyBatis Mapper XML → 최신 시작 세대만 MySQL 카탈로그 공개

누락 벡터 복구 정기 실행 (기본 PT1M, 기업마당 수집과 별도 스케줄러)
└→ supportprogram/service/sync/SupportProgramIndexSyncScheduler
   └→ SupportProgramIndexSyncService.repair
      ├→ Repository → MySQL 전체 현재 공고
      └→ AiSupportProgramIndexClient → FastAPI → OpenAI 임베딩 → Qdrant (누락 복구, 삭제 없음)

supportprogram/repository
└→ supportprogram/repository/mapper/SupportProgramMapper
    └→ MyBatis Mapper XML → MySQL 지원사업 카탈로그

_health_ai_service/controller
→ _health_ai_service/service
→ _health_ai_service/client/AiServiceHealthClient
    → FastAPI Health API
```

- **supportprogram/controller**는 HTTP 요청을 처리하고, 하위 `dto`는 브라우저 공개 요청·응답 계약을 소유합니다.
- **supportprogram/service/search**는 MySQL 카탈로그 조회, 접수 상태 필터, 의미 후보 검색과 AI 점수화 순서를 소유합니다. 빈 질의만 최신 목록을 반환합니다. **supportprogram/service/detail**의 `SupportProgramDetailService`는 제공처 코드와 원본 ID를 별도로 받아 현재 노출된 공고만 Repository에서 조회하고, 없는·미노출 공고는 404로 분류합니다. **supportprogram/service/sync**의 `BizInfoSupportProgramCatalogSyncScheduler`는 `app.bizinfo.sync.enabled`가 `true`일 때 `BizInfoSupportProgramCatalogSyncService.sync()`를 호출합니다. 기본값은 앱 준비 뒤 `PT0S`에 한 번 실행하고, 이전 동기화가 끝난 뒤 `PT6H` 후 다시 실행하는 것입니다. Scheduler는 동기화 실패를 기록하되 Core API를 멈추지 않고 다음 실행을 계속합니다. `BizInfoSupportProgramCatalogSyncService`는 수집 전에 시작 세대를 발급하고 transaction 밖에서 기업마당 전체 수집·검증·색인 준비를 완료한 뒤, Repository에서 최신 시작 세대일 때만 스냅샷을 공개합니다. **supportprogram/facade**는 `SupportProgramCatalogFacade`·`SupportProgramRankingFacade` 계약과 구현을 관리하고, 하위 `exception`은 상위 Service에 전달할 안정적인 Facade 실패 계약을 소유합니다. `BizInfoSupportProgramCatalogFacade`는 동기화에 필요한 기업마당 조회·실패 변환·공고 정규화를 단일 `load` 진입점으로 제공하고, `AiSupportProgramRankingFacade`는 AI 요청 생성·Client 호출·응답 검증·도메인 변환을 단일 `rank` 진입점으로 제공합니다. `AiSupportProgramRetrievalFacade`는 현재 공고의 정확한 ID·해시로 의미 후보를 요청하고 응답을 도메인 공고로 변환합니다. `supportprogram/config`는 접수 상태 계산에 쓰는 서울 기준 시계를, `service/dto`는 이 흐름이 공유하는 검증된 실행 결과를 둡니다.
- **supportprogram/domain**은 프레임워크에 의존하지 않는 지원사업 모델과 상태를 둡니다. `SupportProgramStatusResolver`는 저장된 신청 기간과 서울 기준 날짜로 현재 접수 상태를 계산합니다.
- **supportprogram/repository**는 MySQL의 지원사업 카탈로그 저장·조회와 JSON 배열 복원을 담당합니다. `repository/mapper/SupportProgramMapper`는 SQL 실행 계약이고, 실제 UPDATE·UPSERT·SELECT는 `src/main/resources/mybatis/supportprogram/repository/SupportProgramMapper.xml`에 둡니다. 검색 Service는 현재 노출된 BIZINFO 공고만 Repository에서 읽고, Repository는 저장된 신청 기간과 서울 날짜로 접수 상태를 다시 계산합니다. 기업마당 동기화는 BIZINFO 범위의 기존 행 미노출 처리와 이번 스냅샷 UPSERT를 하나의 transaction으로 수행하고, 중간 오류 시 전부 롤백합니다. 테이블은 `source_code`와 `source_program_id` 복합 식별자로 제공처별 원본 ID 충돌을 막습니다. 공개 검색·상세 응답은 원본 `id`와 `sourceCode`를 함께 노출하며, 상세 Repository 조회도 두 값을 사용합니다. 따라서 두 번째 제공처가 같은 원본 ID를 사용해도 식별 충돌 없이 조회할 수 있습니다. 제공처 표시 이름과 전체 검색·벡터 색인 범위 확장은 실제 제공처를 추가할 때 함께 결정합니다.
- **supportprogram/client/bizinfo**는 기업마당 HTTP·pagination을 담당하며, 하위 `mapper`의 `BizInfoProgramMapper`는 외부 DTO를 검색 후보로 정규화합니다. 하위 `config`는 전용 Client 설정·속성을, `dto`는 응답 전송 객체를, `exception`은 기업마당 전용 실패 계약을, `helper`는 기업마당 전용 HTTP 예외 변환을 관리합니다. 기업마당 Client 오류는 동기화 Scheduler가 기록하고 다음 주기에 재시도하며, 기존 MySQL 스냅샷 검색에는 영향을 주지 않습니다.
- **supportprogram/client/ai**는 AI 점수화 Client 인터페이스·HTTP 구현과 색인·의미 검색용 `AiSupportProgramIndexClient`를 관리하고, 하위 `dto`에 내부 요청과 응답 계약을 둡니다. `mapper`는 색인과 검색이 공유하는 텍스트·해시 생성을 담당합니다.
- **_common/ai_config**는 AI HTTP 클라이언트가 공유하는 FastAPI 주소·timeout·`RestClient` 설정만 관리합니다.
- **_common/helper**는 공용 `RestClient` 생성·설정 검증과 AI·기업마당 Client가 공유하는 Spring 연결 실패·timeout·응답 해석 실패 분류를 담당합니다. 각 외부 시스템은 이를 자기 예외 계약으로 변환하고, HTTP 상태의 업무상 의미는 해당 Client에 남깁니다. **_common/exception**은 공통 AI 호출 예외와 공개 ProblemDetail 변환을 관리합니다.
- **_health/controller**, **_health_ai_service/controller**, **_sampleitem/controller**는 각각 자기 공개 API를 처리하고 하위 `dto`에 공개 요청·응답 계약을 둡니다.
- **_health_ai_service**는 AI Service 상태 조회의 Controller·Service·전용 HTTP Client를 독립적으로 관리합니다.

각 기능은 필요한 `controller`, `service`, `facade`, `domain`, `client`, `config`를 자기 기능 아래에 소유합니다. Facade는 하위 Client 호출·응답 검증·도메인 변환처럼 Service가 몰라도 되는 복잡성을 하나의 진입점으로 감출 때만 둡니다. 공개 요청·응답은 각 기능의 `controller/dto`, 외부 요청·응답은 해당 `client/dto`, 검증된 실행 결과는 `service/dto`에 두며 프로젝트 전체를 아우르는 중앙 DTO 폴더는 만들지 않습니다. 두 AI 기능이 공유하는 연결 설정은 `_common/ai_config`, AI·기업마당이 함께 사용하는 HTTP·RestClient 보조 함수는 `_common/helper`, AI 예외 분류와 공개 오류 변환은 `_common/exception`에 둡니다. Health·점수화·기업마당 응답 규칙은 각 기능에 남깁니다. 여러 기능에서 실제로 함께 쓰는 JSON·CORS 정책은 `_common/config`에 둡니다. `_common`의 밑줄은 IDE에서 공통 코드를 기능보다 위에 표시하기 위한 프로젝트 규칙입니다. 데이터베이스를 도입할 때도 해당 기능 아래에 필요한 저장 계층을 추가합니다.

## LLM 추천 점수화와 장애 격리

Core API는 공개 검색 요청을 받으면 AI Service에 다음 내부 요청을 보냅니다.

```http
POST /internal/v1/support-program-rankings/rank
Content-Type: application/json

{"originalQuery":"서울 AI 창업지원 찾아줘","scoringVersion":"govbiz-support-program-ranking-v2","resultLimit":5,"candidates":["Core가 검증한 공식 공고 후보"]}
```

AI Service는 필수 [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/)의 단일 typed
agent를 실행합니다. 프롬프트의 버전된 100점 기준에 따라 모든 후보의 의미 관련성·대상·지역·접수
상태·지원 유형을 점수화하고 추천 이유를 반환합니다. 프롬프트는 공고에 없는 사실의 생성을 금지합니다.
v2 응답에는 `targetEligibility`와 `regionEligibility`가 필수이며 각 값은 `MATCH`, `INCOMPATIBLE`,
`UNKNOWN` 중 하나입니다. 명백한 지원대상·지역 불일치인 `INCOMPATIBLE`은 높은 총점이어도 제외합니다.
정보 부족인 `UNKNOWN`은 자동 제외하지 않으며 신청 자격을 확정하는 값도 아닙니다. 자격 조건과
의미 관련성 20점 이상·총점 60점 이상을 모두 통과한 공고만 최종 추천으로 남기고 Core도 이를 재검증합니다.

```text
AI Service
  ├→ Agent + Runner 성공 + schema 검증 성공 → 후보별 자격 판정·세부 점수·총점·추천 이유
  └→ 키 누락은 시작 실패, 실행·검증 실패는 안전한 HTTP 503

Core API
  ├→ 접수 상태를 먼저 필터링하고 전체 현재 공고에서 Qdrant 관련 후보 최대 20개 검색
  ├→ 후보 ID·중복·점수 범위·합계·최소 추천 기준·내림차순을 재검증
  ├→ 적격 공고가 없으면 빈 목록을 정상 응답
  └→ AI HTTP 오류·timeout·JSON 오류·echo 불일치·계약 위반 → 공개 오류
```

AI Service가 유효하지 않은 요청을 받으면 422를 반환합니다. LLM 실패를 Kotlin 고정 가중치나
단어 사전으로 숨기지 않으므로 AI Service나 OpenAI 장애는 공개 검색 오류로 전파됩니다. 성공 응답은 계속
`{query, programs}` 계약을 유지합니다.

현재는 한 번의 구조화된 후보 점수화만 필요하므로 agent 하나를 `max_turns=1`로 실행합니다. tool,
handoff, session이나 manager agent는 실제 역할이 없어 추가하지 않습니다. 추천 기능의 Agent,
prompt, model과 서비스 흐름은 `support_program_ranking` 수직 기능 디렉터리에 모으고, OpenAI
client 소유권과 DI는 root `bootstrap.py`에 둡니다. 실제 사업 조회 도구나 서로 다른 전문가로 실행권을
넘기는 요구가 생길 때 `<feature_name>` 모듈과 tool 또는 handoff 도입을 다시 평가합니다.

모델 HTTP 호출과 전체 Runner 실행은 별도 timeout으로 제한합니다. 전체 run 제한을 모델 호출
제한보다 길고 Core API의 AI Service 읽기 제한보다 짧게 두어, Core가 timeout을 명확한 공개 오류로
변환할 시간을 확보합니다.

## Docker Compose 개발 흐름

```text
Browser (127.0.0.1:5173)
  → Vite web container
      → /api proxy
          → core-api:8080
              ├→ mysql:3306
              ├→ https://apis.data.go.kr (백그라운드 동기화)
              └→ ai-service:8000
                    ├→ qdrant:6333
                    └→ https://api.openai.com (임베딩 및 점수화)
```

브라우저는 `core-api`와 `ai-service`라는 Compose 내부 DNS 이름을 알 수 없습니다. React는 `/api`
상대 주소를 호출하고, Vite 컨테이너가 내부 DNS를 사용해 Core API로 중계합니다.

## 공고 의미 검색과 색인 정합성

`SupportProgramSearchService`는 비어 있지 않은 질의에 대해 최신순 후보 제한을 적용하지 않습니다.
MySQL의 현재 공고 전체를 읽고 서울 날짜 기준 상태를 적용한 뒤, `AiSupportProgramRetrievalFacade`가
허용 ID·내용 해시와 질의를 `support_program_index` 내부 API로 보냅니다. 응답의 ID·내용 해시·점수·순서를
검증한 후 해당 DB 공고만 기존 `AiSupportProgramRankingFacade`에 전달합니다. 빈 질의는 최신 목록 조회로 유지합니다.

`SupportProgramIndexDocumentMapper`는 색인·검색 양쪽에서 같은 검색 텍스트와 SHA-256을 만듭니다.
내부 식별자는 `BIZINFO:원본ID`이며, Qdrant point는 ID·내용 해시로 결정됩니다. 모델·차원·색인 규격에
따라 컬렉션을 분리하여 호환되지 않는 벡터를 함께 검색하지 않습니다. 공개 공고 ID 계약은 그대로입니다.

카탈로그 동기화는 기업마당 수집 전에 MySQL에서 시작 세대를 발급하고, 들어온 전체 공고를 16개씩
색인합니다. AI Service는 이미 존재하는 ID·해시의 벡터는 재사용하고 없는 버전만 임베딩하여 저장합니다.
모든 배치 성공 후 Repository의 공개 transaction에서 최신 시작 세대인지 확인해 해당 세대만 반영합니다.
수집·검증·색인이 실패하거나 더 최근에 시작한 세대가 있으면 이전 공개 카탈로그를 유지합니다.
V2 migration의 `support_program_sync_generation`이 제공처별 최신 시작 세대를 관리하며, 시작 세대 발급과
공개는 각각 짧은 DB transaction입니다. 외부 HTTP 작업은 MySQL transaction 밖에서 실행합니다.

별도 `PT1M` 스케줄러는 `repair()`로 현재 공개된 MySQL 공고의 누락 벡터를 복구합니다. 프로세스가
재시작돼도 현재 DB에서 필요한 버전을 다시 계산할 수 있습니다. 공개 준비와 복구 모두 `prune`을 호출하지
않습니다. 이전 스냅샷 기준의 정리가 아직 공개하지 않은 새 벡터를 지우지 않도록 삭제를 보류한 것입니다.

검색은 현재 DB에 맞는 정확한 ID·내용 해시로 결정된 point ID 목록으로 Qdrant를 필터링합니다. 따라서
미노출·이전 버전·미공개 세대의 벡터는 검색되지 않습니다. 공개 후 벡터 유실 등으로 허용 공고 중 하나라도
벡터가 없으면 일부 공고만으로 성공하지 않고
명시적인 503을 반환합니다. AI Service/Qdrant 장애도 최신 목록 fallback 없이 공개 오류로 전달합니다.

현재 카탈로그 상한은 20,000건입니다. 매 검색의 전체 DB 조회와 허용 ID 전송은 이후 최적화 범위입니다.
이전 버전·미공개 세대의 벡터와 이전 모델 collection의 저장 공간 정리도 후속 과제입니다. 진행 중인
작업·검색을 보호하는 보존·삭제 수명주기 없이 `prune`을 연결하지 않으며, 현재 `prune` API 자체는
다중 인스턴스·동시 실행의 안전한 삭제를 보장하지 않습니다. 시작 세대 비교는 카탈로그 공개 순서를
보호하지만 전체 다중 인스턴스 운영 검증을 대체하지 않습니다.
조회 때 계산하는 `OPEN` 상태는 벡터에 고정하지 않습니다. 검색 텍스트 해시는 요청에서 계산하며 기존 DB의
`content_hash` 컬럼을 영속 색인 완료 기록으로 사용하지 않습니다.

## 검색 결과 상세 화면

검색 카드의 `상세 조건 보기`는 React Router의
`/support-programs/detail?sourceCode={sourceCode}&sourceProgramId={id}` 내부 경로로 이동합니다.
상세 화면은 route state를 신뢰하지 않고 `GET /api/v1/support-programs/detail?sourceCode={sourceCode}&sourceProgramId={id}`를
호출합니다. Core는 `SupportProgramController → SupportProgramDetailService → SupportProgramRepository →
SupportProgramMapper → MySQL` 흐름으로 현재 노출 행만 찾습니다. 성공하면 제목·기관·요약, 접수 상태·기간,
지원 대상·분야·지역과 원문 링크를 표시하고, 검색 문맥이 없는 상세 응답의 추천 이유·점수는 각각 빈 배열과
`null`입니다. 새로고침·공유 URL도 이 API로 다시 조회하며, 없는·미노출 공고는 `SUPPORT_PROGRAM_NOT_FOUND`(404)를
보여 줍니다. `sourceCode`와 `id`는 별도 값으로 유지해 제공처별 원본 ID 충돌을 피합니다.

## 의존성 규칙

- React View는 Redux Store 또는 Data Layer를 직접 호출하지 않고 ViewModel을 사용합니다.
- ViewModel은 전역 `appContainer`에서 필요한 UseCase나 외부 API 함수만 resolve하고, Repository를
  직접 resolve하거나 생성하지 않습니다.
- `createAppContainer()`는 운영 코드에서 `app/appContainer.ts`만 호출합니다. 테스트는 격리된 새
  컨테이너를 만들 수 있습니다.
- Core API는 FastAPI 전송 DTO를 브라우저에 그대로 노출하지 않습니다.
- FastAPI는 Core API 소스 코드를 import하거나 Core API 데이터 저장소를 수정하지 않습니다.
- 공공데이터포털 인증키는 Core API 환경변수에만 주입하고 Frontend bundle·응답·로그에 노출하지
  않습니다.
- OpenAI 인증키는 AI Service 환경변수에만 주입하며 Core API·Frontend·공개 응답·로그에 노출하지
  않습니다.
- LLM은 Core가 제공한 후보만 점수화하며 공고 사실, 신청 가능 여부, 금액과 날짜의 최종 근거는
  기업마당 원문입니다. Core는 존재하지 않는 공고 ID와 잘못된 점수 합계를 거부합니다.
- 외부 공고의 신청기간을 확실히 해석할 수 없으면 `UNKNOWN`으로 유지하며 접수 상태를 추정하지
  않습니다.
- 서비스 간 통신은 명시적인 HTTP·JSON 계약과 테스트로 검증합니다.
- 필요하지 않은 빈 계층이나 인프라를 미리 만들지 않습니다.
