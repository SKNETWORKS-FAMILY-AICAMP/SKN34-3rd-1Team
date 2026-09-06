# GovBiz Web

지원사업 검색 결과를 채팅 형태로 보여 주고 공고의 상세 조건과 원문을 연결하는 웹 화면입니다.
React·TypeScript·Vite·Tailwind CSS를 사용합니다. 전체 기술 구성은
[프로젝트 기술 문서](../docs/technology.md), 기능별 완료·미구현 범위는
[구현 현황](../docs/implementation-status.md)을 참고하세요. 계층·DI·MVVM·Redux의 역할은
[아키텍처 README](../docs/architecture/README.md#frontend-화면과-데이터-처리-분리)에 정리했습니다.

## 실행

### Docker Compose

저장소 루트의 `.env`와 API 키를 먼저 준비합니다. 상세 설정은
[Compose 실행 안내](../infrastructure/README.md#실행)에 있습니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml up --build
```

브라우저에서 `http://127.0.0.1:5173`에 접속합니다. React는 `/api` 상대 주소로 요청하고,
Vite 개발 서버가 `http://core-api:8080`으로 중계합니다.

### 네이티브 개발

Node.js `24.x`, pnpm `11.22.x`가 필요합니다. Core API와 검색에 필요한 MySQL·AI Service·Qdrant는
[Core API 실행 안내](../backend/core-api/README.md)에 따라 먼저 실행합니다.

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

기본 API 주소는 `http://localhost:8080`입니다. 다른 주소를 사용하려면 `frontend/.env.example`을
`frontend/.env.local`로 복사하고 `VITE_CORE_API_BASE_URL`을 변경합니다. `VITE_` 값은 브라우저에
노출되므로 비밀값을 넣지 않습니다. API를 다른 origin으로 직접 호출할 때는 Core API의 CORS 설정도
프론트엔드 접속 주소와 맞춰야 합니다.

`VITE_CORE_API_BASE_URL=/`이면 Vite의 `/api` 프록시를 사용합니다. 프록시 목적지는
`VITE_DEV_PROXY_TARGET`이며 기본값은 `http://localhost:8080`입니다. 이 프록시는 개발 서버 설정으로,
정적 빌드 배포 시에는 별도의 `/api` 라우팅과 SPA 경로 처리가 필요합니다.
배포용 빌드에서는 `VITE_CORE_API_BASE_URL`을 반드시 지정합니다. 같은 origin에서 API를 제공한다면
`/`, 별도 서버라면 브라우저가 접근할 수 있는 공개 API 주소를 사용합니다.

## 화면과 현재 동작

| 경로 | 기능 |
|---|---|
| `/` | 자연어 검색, 결과 카드, 새 대화 시작 |
| `/support-programs/detail?sourceCode=...&sourceProgramId=...` | 식별자로 상세 API를 조회해 공고 조건·출처 표시 |
| `/examples/sample-item/hook` | React Hook Form·로컬 요청 상태 예제 |
| `/examples/sample-item/redux` | Redux 상태 유지 예제 |

현재 채팅 화면은 `acceptingOnly=true`로 검색합니다. API가 지원하는 빈 검색어 최신 목록 조회와
접수 상태 전체 조회는 이 화면에 별도 조작 UI로 연결되어 있지 않습니다. 검색 제안은 입력창을
채우고, 사용자가 제출하면 요청을 보냅니다.

검색 결과에는 추천 점수·추천 이유·신청 기간·원문 링크가 표시됩니다. 상세 화면은 검색 결과의
메모리 상태에 의존하지 않고 URL의 `sourceCode`·`sourceProgramId`로 다시 조회하므로 새로고침과
직접 접속을 지원합니다. 상세 API는 검색별 점수·추천 이유를 제공하지 않으며, 공고 정보와 현재
접수 상태를 보여 줍니다. 잘못된 주소, 없는 공고, 조회 실패, 로딩 상태를 구분합니다.
원문 링크는 제공처 코드별 공식 도메인 allowlist와 `http(s)` 스킴을 함께 검증합니다. 현재
`BIZINFO`의 기업마당 도메인만 허용합니다. 실제 제공처를 추가할 때는 해당 제공처의 공식 도메인을
allowlist에 명시적으로 추가합니다. 테스트용 제공처는 production 허용 목록에 포함하지 않습니다.

채팅 형태의 화면이지만 각 검색 요청에는 현재 입력한 검색어만 전달합니다. 이전 대화를 이해하는
다중 턴 대화, 로그인, 북마크, 알림, 대화 이력의 서버 저장은 아직 구현하지 않았습니다.

## 구조와 상태 책임

```text
src/
├── app/                         # Redux Store, typed hook, Awilix 조립·등록
├── presentation/features/chat/ # 검색·상세 View, ViewModel, chat slice
├── presentation/features/sample-item/ # 상태관리 비교 예제
├── presentation/shared/        # Core API 상태 표시
├── domain/                      # Entity, Repository 계약, UseCase
└── data/                        # Fetch, Zod DTO 검증, Repository 구현, 테스트 fixture
```

검색은 `View → ViewModel → UseCase → Repository → Fetch → Core API` 순서입니다. ViewModel은
전역 `appContainer`에서 UseCase를 조회하고, ViewModel 내부 Thunk가 Redux의 요청·성공·실패 상태를
변경합니다. Awilix 등록은 `app/di`에 있으며 Domain은 컨테이너를 알지 못합니다. 상세 조회는 같은
UseCase·Repository 경계를 거치되 로딩·결과 상태를 ViewModel의 로컬 state에 둡니다.

| 소유자 | 현재 담당 상태 | 화면 이동·새로고침 동작 |
|---|---|---|
| React 로컬 상태 | 사이드바, 상세 조회, Health, Hook SampleItem | 해당 화면이 unmount되면 초기화 |
| Redux 메모리 | 채팅 메시지·입력·검색 상태, Redux SampleItem | 앱 내 이동 시 유지, 새로고침 시 초기화 |
| 서버 | MySQL 공고 카탈로그 | 브라우저 상태와 별개로 유지 |

Redux에는 직렬화 가능한 데이터만 저장하며 `AbortController`는 ViewModel의 `useRef`가 관리합니다.
새 대화 시작·화면 이탈 시 요청을 취소하고, `requestId`가 다른 과거 응답은 무시합니다. 새 대화
시작은 현재 메시지를 초기화하며 이전 대화 목록을 보관하지 않습니다.

입력과 요청에는 다음 처리가 적용됩니다.

- 검색 중 중복 제출 차단, 빈 입력 전송 차단
- 앞뒤 공백 제거 후 500자 초과 시 API를 호출하지 않고 입력값을 유지하며 안내
- 한글 IME 조합 중 Enter와 Safari `keyCode 229` Enter 제출 차단
- Enter 전송, Shift+Enter 줄바꿈
- 검색 실패 시 내부 예외 대신 안전한 오류 문구 표시
- 검색·원문 근거 질문의 요청량 제한과 동시 처리 혼잡을 일반 장애와 구별하여 안내
- 검색이 70초를 넘으면 요청 취소, 검색어 복원과 재시도 허용

요청량 제한은 HTTP `429`와 `SUPPORT_PROGRAM_RATE_LIMITED`, 동시 처리 혼잡은 HTTP `503`과
`SUPPORT_PROGRAM_BUSY`가 일치하는 `application/problem+json` 응답에만 적용합니다. Data Layer가
계약을 검증하고 Repository가 HTTP 코드 없는 Domain `SupportProgramRequestError`로 변환합니다.
ViewModel은 서버의 `detail` 대신 고정된 한국어 문구를 표시합니다. 알 수 없거나 잘못된 `503`
응답은 기존 장애 처리로 유지합니다.

본문 `retryAfterSeconds`가 정수 `1~60`이고 읽을 수 있는 `Retry-After` 헤더와 일치할 때만
권장 대기 시간을 표시합니다. 헤더가 없거나 두 값이 잘못되면 초 단위 안내를 생략합니다.
별도 origin의 Core API는 `Retry-After`를 CORS 노출 헤더에 포함해야 합니다. 자동 재시도나
카운트다운은 없으며, 검색 대화·검색어·근거 질문을 유지한 채 사용자가 직접 재시도합니다.
화면에서 취소해도 이미 시작된 서버의 AI 실행이 즉시 중단된다는 뜻은 아닙니다.

검색 제한 70초는 Core의 순차적인 의미 검색 읽기 제한 30초와 점수화 읽기 제한 35초에 여유를 둔 값입니다.
서버 제한시간을 변경할 때도 이 순차 호출 시간을 고려해야 하며, 70초는 응답시간 목표가 아닙니다.

스타일은 `src/index.css`의 Tailwind `@theme` 토큰과 View 옆 `*.styles.ts`를 사용합니다.
계층·DI의 상세 규칙은 [아키텍처 문서](../docs/architecture.md#frontend와-내부-계약), 예제 API는
[SampleItem 계약](../docs/sample-item-contract.md)을 참고하세요.

## 검증

```bash
pnpm test
pnpm lint
pnpm build
```

Vitest·Testing Library로 화면과 ViewModel, 입력 검증, 응답 변환, 요청 취소·늦은 응답 처리를
검증합니다. 개발 화면의 공고는 Core API에서 받으며 `data/fixtures`는 테스트용입니다.
전체 서비스 연결과 장애 복구 검증은 저장소 루트의 `./infrastructure/scripts/verify-compose.sh`를
사용합니다. 실행 조건과 검증 범위는 [통합 smoke 안내](../infrastructure/README.md#통합-smoke)를
참고하세요.
