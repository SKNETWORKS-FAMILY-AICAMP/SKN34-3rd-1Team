# GovBiz

자연어로 정부지원사업을 검색하고, 공고의 출처와 마감일을 확인할 수 있는 채팅형 웹앱입니다.
[공공데이터포털의 중소기업 지원사업 공고 조회 서비스](https://www.data.go.kr/data/15157820/openapi.do)에서
기업마당 공고를 Core API로 조회하고, 브라우저에는 GovBiz의 안정적인 검색 계약만 공개합니다.

```text
React Web
  → Spring Boot Core API
      ├→ 공공데이터포털 지원사업 공고 API
      └→ FastAPI AI Service
          └→ OpenAI Agents SDK typed agent (설정된 경우)
```

브라우저는 Core API만 호출하므로 공공데이터포털 인증키가 JavaScript bundle이나 브라우저 요청에
노출되지 않습니다. AI Service는 Core API가 호출하는 내부 서비스이며, LLM에는 공고 사실이 아니라
사용자의 검색 문장만 전달합니다.

## 현재 구현: 실제 공고 검색 채팅

브라우저에서 `/`로 진입하면 바로 GovBiz 채팅 화면이 열립니다.

상태관리 비교 예제는 React Router URL로 직접 열 수 있습니다.

```text
/                           → 지원사업 채팅
/examples/sample-item/hook  → React Hook 예제
/examples/sample-item/redux → Redux Toolkit 예제
```

```text
사용자 메시지
  → ChatPage
      → useSupportProgramChatViewModel
          → appContainer.resolve('searchSupportProgramsUseCase')
          → ViewModel 안의 Redux Thunk
              → SearchSupportProgramsUseCase.execute
                  → SupportProgramRepository
                      → GET /api/v1/support-programs/search
                          → 공공데이터포털 기업마당 공고 조회·검증
                          → 접수 상태 필터와 최신 후보 최대 20개 선정
                          → POST /internal/v1/support-program-rankings/rank
                              → OpenAI typed agent가 버전된 기준으로 후보별 점수화
                          → Core가 ID·점수 합계·순서를 검증하고 상위 5개 반환
              → Redux Toolkit chat slice
                  → 지원사업 카드·마감일·추천 이유 표시
```

현재 화면은 다음 질문을 처리할 수 있습니다.

- `서울 AI 창업지원 사업 찾아줘`
- `현재 접수 중인 수출 지원사업 알려줘`
- `제조기업 R&D 사업을 찾아줘`

Frontend는 HTTP 응답을 Zod로 검증하고, 화면 이탈이나 새 대화 시작 시 진행 중인 검색을 취소합니다.
공고의 공식 원문 주소와 신청기간 원문을 함께 제공하며, 날짜를 확실히 해석하지 못한 경우 접수 상태를
추정하지 않습니다. 공개 계약은 [지원사업 검색·추천 HTTP 계약](docs/support-program-search-contract.md)에
정리되어 있습니다.

LLM은 공식 공고 후보를 고정된 100점 평가 기준으로 점수화하는 필수 추천기입니다. OpenAI 설정 누락은 AI Service
시작 실패로 드러나고, 인증·rate limit·timeout·refusal·응답 검증 실패는 안전한 502·503·504로
반환합니다. Kotlin 단어 사전이나 고정 점수표로 자연어를 이해한 것처럼 성공시키지 않습니다.

### 채팅 상태 관리

React Hook은 사이드바·DOM 참조처럼 화면과 함께 사라지는 상태를 담당합니다. Redux Toolkit은
메시지·검색 진행 상태를 보관하고, ViewModel은 전역 `appContainer`에서 검색 UseCase를 직접 조회한 뒤
Thunk 안에서 검색 순서를 제어합니다. Health와 SampleItem도 같은 Service Locator에서 필요한 외부 함수와
UseCase를 직접 조회합니다. SampleItem은 React Hook과 Redux Toolkit 두 구현을 제공해 상태 수명 차이를
비교할 수 있습니다. 검색 중 중복 전송을 막고, 새
대화를 시작한 뒤 도착한 이전 응답은 요청 ID를 비교해 무시합니다.

Repository와 UseCase의 생성·연결·앱 단위 singleton 수명주기는 Awilix 컨테이너가 관리합니다.
`frontend/src/app/appContainer.ts`가 GetIt처럼 하나의 root container를 Service Locator로 공개하고,
ViewModel은 Repository가 아니라 필요한 UseCase만 resolve합니다. DI 등록은 `frontend/src/app/di`에서
Repository, UseCase와 외부 서비스 역할별 모듈로 분리해 관리합니다.

현재는 대화와 검색 결과를 브라우저 메모리에 보관합니다. Core API는 검색 요청마다 외부 공고를
조회하며 별도 메모리 캐시를 두지 않습니다. 다음 단계에서는 정기 수집 작업이 공고를 DB에 저장하고
사용자 검색은 DB만 조회하도록 전환합니다.
현재 구현 평가와 구체적인 확장 원칙은
[Frontend 상태 관리 설계](frontend/README.md#상태-관리-설계와-확장-원칙)와
[Provider와 Service Locator에서 ViewModel까지 전달](frontend/README.md#redux-provider와-service-locator에서-viewmodel까지-전달)을
참고하세요.

## 포함한 기반

- React의 View → ViewModel → UseCase → Repository → HTTP DTO 흐름
- Awilix 컨테이너를 이용한 decorator 없는 Frontend 의존성 주입
- Spring Boot의 기능별 controller → service → domain 레이어 구조
- Zod와 Bean Validation을 이용한 요청·응답 계약 검증
- FastAPI 내부 Health API와 Core API의 upstream 오류 변환
- OpenAI Agents SDK의 필수 typed agent를 사용하는 공고 후보 점수화
- 공공데이터포털 응답을 GovBiz 공고 모델로 변환하는 외부 API adapter
- Tailwind CSS 유틸리티와 Vite 프록시를 사용하는 Docker Compose 개발 환경
- 실제 키 없이 로컬 공공데이터 스텁을 사용하는 결정적 Compose smoke 검증

## SampleItem 예제

GovBiz에는 같은 업무 계층을 두 가지 상태 관리 방식으로 실행하는 최소 수직 슬라이스 예제가
포함되어 있습니다.

```text
React Hook 화면 ─┐
                 ├→ PrepareSampleItemUseCase
Redux 화면 ──────┘
  → SampleItemRepository
  → POST /api/v1/sample-items/prepare
  → SampleItemPreparationService
```

`name`은 필수이고 `category`, `note`는 선택입니다. 성공 응답은 처리 시작 전의
`READY_FOR_PROCESSING` / `NOT_STARTED` 상태만 반환합니다. 즉 실제 비동기 작업이나 영속성은
의도적으로 포함하지 않습니다. Hook 버전은 화면 이동 시 상태가 초기화되고, Redux 버전은 최상위
`Provider`의 `appStore` 안에 있는 `sampleItem` Slice가 상태를 보관하므로 화면을 이동해도 입력과
결과가 유지됩니다. 새로고침하면 둘 다 초기화되며 API,
UseCase와 Repository는 완전히 동일합니다. 자세한 비교는
[SampleItem 상태 관리 비교](frontend/README.md#sampleitem-react-hook과-redux-비교)를 참고하세요.

## 빠른 시작

Docker daemon과 Docker Compose가 준비되어 있어야 합니다.

저장소 루트의 `.env`에 공공데이터포털에서 발급한 일반 인증키와 필수 `OPENAI_API_KEY`를 설정합니다.
Encoding 또는 Decoding 키를 사용할 수 있으며 Core API가 호출 전에 정규화합니다. 새 환경에서는
예시 파일을 복사한 뒤 값만 채웁니다. `.env`는 Git에서 제외됩니다.

```bash
cp .env.example .env
# DATA_GO_KR_SERVICE_KEY=발급받은_인증키
# OPENAI_API_KEY=발급받은_OpenAI_API_키
```

```bash
docker compose --env-file .env --file infrastructure/compose.yaml up --build
```

브라우저에서 [http://127.0.0.1:5173](http://127.0.0.1:5173)을 열면 GovBiz 채팅 화면을 볼 수
있습니다.

| 주소 | 용도 |
|---|---|
| `http://127.0.0.1:5173` | React 개발 서버 |
| `http://127.0.0.1:5173/api/v1/health` | Vite 프록시를 거친 Core API Health |
| `http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true` | 실제 지원사업 검색 |
| `http://127.0.0.1:5173/api/v1/health/ai-service` | Core API를 거친 AI Service Health |
| `http://127.0.0.1:8080` | Core API 직접 디버깅 |

종료와 생성된 Compose volume 정리:

```bash
docker compose --file infrastructure/compose.yaml down --volumes --remove-orphans
```

## 검증

전체 컨테이너 경로, 지원사업 검색 계약, AI Service 장애·복구까지 확인합니다. 이 검증은 로컬
공공데이터 스텁과 외부로 전송하지 않는 dummy OpenAI key를 사용하므로 개인 인증키나 외부 네트워크가
필요하지 않습니다.

```bash
./infrastructure/scripts/verify-compose.sh
```

각 서비스만 빠르게 검증하는 방법은 해당 README에 있습니다.

## 저장소 구조

```text
govBiz/
├── frontend/             # React Web과 Data Layer 예제
├── backend/
│   ├── core-api/         # Spring Boot 공개 API와 업무 흐름
│   └── ai-service/       # FastAPI 내부 서비스
├── infrastructure/       # Docker Compose와 통합 smoke
└── docs/                 # 구조, 계약, 확장 안내
```

## 문서

- [아키텍처와 의존성 규칙](docs/architecture.md)
- [지원사업 검색 HTTP 계약](docs/support-program-search-contract.md)
- [SampleItem HTTP 계약](docs/sample-item-contract.md)
- [새 프로젝트로 바꾸는 방법](docs/customization-guide.md)
- [Frontend 실행·구조](frontend/README.md)
- [Core API 실행·구조](backend/core-api/README.md)
- [AI Service 실행·구조](backend/ai-service/README.md)
- [AI Agent 모듈 구조](backend/ai-service/docs/agent-structure.md)
- [Docker Compose 안내](infrastructure/README.md)

## 다음 단계

1. 대표 검색 질문과 Top-5 관련성 기준으로 현재 검색 품질을 측정합니다.
2. 외부 공고를 정기 수집해 DB에 저장하고 사용자 검색 경로에서 외부 API 호출을 제거합니다.
3. 데이터가 부족하다는 근거가 생기면 K-Startup 등 두 번째 공식 소스를 추가합니다.
4. 이후 기업정보 기반 추천과 GovClause의 PDF·조건 판정을 결합합니다.

GovBiz 계층을 유지하며 데이터 소스와 기능을 확장하는 방법은
[GovBiz 확장·적용 안내](docs/customization-guide.md)를 참고하세요.
