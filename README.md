# GovBiz

자연어로 정부지원사업을 찾고 지원 대상·신청 기간·공식 원문을 확인하는 채팅형 웹앱입니다.
동기화된 지원사업 공고를 MySQL에서 읽고, 의미·키워드 결합 검색과 AI 점수화로 관련 공고를 최대 5개 추천합니다.

## 주요 기능

- 자연어 공고 검색과 추천 이유·점수 표시
- 공고 상세 조회, 원문 링크, 서울 날짜 기준 접수 상태 계산
- 기업마당 공식 상세 원문에 근거한 공고별 질문·답변과 인용 표시
- 기업마당 전체 공고 자동 동기화와 누락 벡터 복구
- 입력·응답 검증, 한글 입력 처리, 검색 취소 및 장애 안내

현재 수집 연동한 공고 제공처는 기업마당입니다. 검색·색인 내부에서는 제공처 코드와 원본 ID를 함께
사용하므로 서로 다른 제공처의 같은 원본 ID도 구분합니다. 기업마당 공고는 사용자가 상세 화면에서
명시적으로 질문할 때만 공식 HTTPS 상세 HTML을 읽어 MySQL에 저장하고, 결정적으로 나눈 최대 50개 청크를
별도 Qdrant 근거 컬렉션에서 검색해 답변과 원문 인용을 반환합니다. 이 원문 근거 답변은 목록 검색과
동기화 흐름을 바꾸지 않습니다. 첨부파일·PDF·OCR·다른 제공처 원문은 아직 지원하지 않으며, 대화 맥락을
이어가는 검색도 구현하지 않았습니다.

## 평가 작업 이어받기

[공유 평가 자료 안내](evaluation/support-program-search/runs/support-program-catalog-20260906-v1/README.md)에
공고 1,422건·질문 16개·실제 검색 캡처와 판정 원표를 보관합니다. **3단계의 AI-only 1차 측정·보고서 작성까지
완료**했으며 검색 품질 합격을 뜻하지는 않습니다. 실제 검색은 16개 모두 성공했고, 기존 321쌍의 판정을 재사용한 뒤
신규 249쌍만 새 Luna 작업 5개로 판정했습니다. 최종 570쌍 중 합의 541개·미확정 29개입니다.

점수를 계산한 질문은 **6/16개**, 그중 관련 공고가 있는 질문은 **2개**입니다. 이 제한적인 AI 참조 기준에서
후보 Recall@20과 최종 MRR@5는 각각 0.50이며, 후보 누락과 평균 약 14.5초의 추천 API 응답시간이 확인됐습니다.
[결과·한계·다음 개선 순서](evaluation/support-program-search/runs/support-program-catalog-20260906-v1/review-final-v1/report.md)를 확인하세요.
AI 총점 생성·자격 점수 모순·후보 ID 중복 오류는 출력 계약과 Service 합산을 분리해 해결했습니다.
기존 HTTP·점수 정책은 유지하고 실패 이력도 보존합니다. 저장소를 받은 개발자는 `verify-shared-run.py --with-capture`로
API 키·DB·Excel 없이 원표부터 최종 지표까지 재현할 수 있습니다.

**4단계 1차 개선**으로 의미·키워드 순위 결합과 제한시간 정렬을 구현하고 동일 16개 질문을 재측정했습니다.
기존 AI 참조의 양성 질문 2개에서 후보 Recall@20은 `0.50 → 0.75`, 최종 MRR@5는 `0.50 → 1.00`입니다.
새 미판정은 별도 표시하며, 지원 목적 판단·운영 부하 제한은 아직 남아 있습니다.
[변경 전후 결과와 재현 명령](evaluation/support-program-search/runs/support-program-catalog-20260906-v1/stage4-v1/README.md)을 확인하세요.

**4단계 2차 개선**에서는 후보를 고정한 채 지원 목적 채점 지침을 보완하고 전후 랭킹 32회를 비교했습니다.
dev의 기존 AI 판정상 무관 추천은 `6 → 4건`, 관련 추천은 `15 → 16건`으로 개선됐고 기존 양성·무결과
회귀 조건을 유지했습니다. 다만 heldout의 무관 추천 5건은 그대로이며 평균 API 응답시간은
`14.74 → 16.56초`로 증가했습니다. 목적 판단 일부 개선이지 전체 검색 품질 검증 완료는 아닙니다.
[2차 결과·한계·재현 명령](evaluation/support-program-search/runs/support-program-catalog-20260906-v1/stage4-v2/README.md)을 확인하세요.
**4단계 3차**로 검색·공고별 근거 답변의 요청량·동시 실행 제한을 추가했습니다.
한 Core 프로세스 기준 접속 주소별 최근 60초 6건·전체 60건·동시 4건이며 설정으로 조절합니다.
제한 시 429/503과 대기 시간을 안내하고 대화·질문·수동 재시도를 유지합니다.
프록시/NAT 뒤에서는 주소 한도를 공유하며 다중 서버의 공통 제한이나 월별 비용 상한은 아닙니다.
[구현·설정·검증·배포 한계](docs/support-program-request-limits.md)를 확인하세요.
다음은 배포 경계를 확정하고 실제 동시 부하·제한값을 검증하는 일입니다.

## 기술 구성

| 영역 | 주요 기술 |
|---|---|
| Frontend | React, TypeScript, Vite, Tailwind CSS, Redux Toolkit, Awilix, Zod |
| Core API | Kotlin, Spring Boot, MyBatis, Flyway |
| AI Service | Python, FastAPI, OpenAI Agents SDK, OpenAI 임베딩 |
| 데이터·실행 | MySQL 8.4, Qdrant, Docker Compose, GitHub Actions |

## 아키텍처와 코드 구성

### Frontend: 클린 아키텍처 원칙과 DI

지원사업 기능은 화면, 업무 규칙, 외부 데이터 처리를 분리합니다. 업무 코드가 HTTP 응답 형식이나
화면 상태 관리 방식의 변경에 함께 묶이지 않도록 클린 아키텍처의 의존성 분리 원칙을 적용했습니다.

| 디렉터리 | 책임 | 대표 코드 |
|---|---|---|
| `presentation` | 화면·ViewModel·화면 상태 | `ChatPage`, `useSupportProgramChatViewModel` |
| `domain` | 업무 모델·UseCase·Repository 계약 | `SearchSupportProgramsUseCase`, `SupportProgramRepository` |
| `data` | HTTP 호출·DTO 검증·Repository 구현 | `SupportProgramRepositoryImpl`, `supportProgramApi` |
| `app` | 객체 조립·DI 등록·Redux Store 구성 | `appContainer`, `app/di`, `store` |

실행 시에는 `View → ViewModel → UseCase → Repository 구현 → API`를 호출하지만, 코드의 의존 방향은
`Presentation → Domain ← Data`입니다. UseCase는 Domain에 정의된 Repository 계약을 받고,
Data의 구현체가 그 계약을 구현합니다. Domain은 React·Redux·Awilix를 직접 참조하지 않습니다.

DI(의존성 주입)는 객체가 필요한 협력 객체를 외부에서 받는 방식입니다. Awilix가 Repository와 UseCase를
앱 단위로 조립하고, Repository를 UseCase의 생성자에 전달합니다. ViewModel Hook에서는
`appContainer.resolve(...)`로 UseCase를 조회하는 Service Locator 방식을 함께 사용합니다.
테스트에서는 Hook 인수에 대역 UseCase를 전달할 수 있습니다.

### Frontend: Hook의 MVVM과 Redux의 Flux 흐름

MVVM은 **화면 표시와 화면 동작을 분리하는 방식**입니다. `ChatPage`가 View로서 입력·카드를 렌더링하고,
`useSupportProgramChatViewModel`이 ViewModel로서 `messages`, `isSearching`, `submitMessage` 등을
제공합니다. 업무 데이터와 검색 실행은 Model 측의 Domain·UseCase·Repository가 담당합니다.
이 프로젝트는 Custom Hook이 ViewModel 역할을 하도록 구성했으며, Hook 사용 자체가 MVVM을 의미하지는 않습니다.

Redux Toolkit은 Flux 계열의 **단방향 상태 변경 흐름**을 담당합니다.

```text
사용자 이벤트 → ViewModel → Action → Reducer → Store 상태 갱신
                   ↑                              ↓
                   └──── Selector 구독 ────────────┘
                   ↓
                View 재렌더링
```

실제 검색은 ViewModel 안의 Thunk가 `searchStarted`를 전달하고, UseCase의 비동기 실행 후
`searchSucceeded` 또는 `searchFailed`를 전달합니다. HTTP 호출은 Thunk가 제어하고 Reducer는 상태를 갱신합니다.
따라서 채팅은 **ViewModel Hook으로 화면 동작을 분리하면서 Redux로 공유 상태를 관리**합니다.
사이드바·IME·DOM 참조 같은 화면 전용 상태는 로컬 Hook에 둡니다.

SampleItem 비교 예제도 두 버전 모두 ViewModel Hook을 사용합니다. Hook 버전은 React Hook Form과
로컬 상태에, Redux 버전은 Store에 입력·결과를 보관하므로 화면 이동 시 상태 수명이 다릅니다.
클린 아키텍처의 적용 범위와 실제 DI·검색 흐름은 [Frontend 설계 상세](docs/technology.md#frontend-화면과-데이터-처리-분리)에 있습니다.

### Core API: 레이어드 아키텍처와 Facade

Core API는 `supportprogram` 같은 **기능 안에서 계층별 책임을 나누는 레이어드 아키텍처**입니다.
Controller는 HTTP 입력·응답, Service는 업무 순서, Domain은 공고 모델과 접수 상태 규칙을 담당합니다.
외부 통신과 DB 접근은 다음처럼 나뉩니다.

```text
Controller → Service
               ├→ Facade → Client → 외부 API
               └→ Repository → MyBatis Mapper → XML → MySQL
```

Facade는 하위 시스템의 호출·응답 검증·모델 변환을 하나의 진입점으로 묶습니다.
예를 들어 검색 Service는 “후보를 찾아 점수화한다”는 순서를 정하고, Facade가 AI 응답의 세부 검증을 맡습니다.

| Facade | 실제 책임 |
|---|---|
| `BizInfoSupportProgramCatalogFacade` | 전체 공고 조회 결과 정규화·검증, 기업마당 예외를 카탈로그 예외로 변환 |
| `BizInfoSupportProgramSourceDocumentFacade` | 명시적 상세 질문에 필요한 기업마당 공식 HTML 원문만 읽고 정규화 |
| `AiSupportProgramRetrievalFacade` | 현재 공고 ID·해시로 검색 범위 구성, 의미 검색 응답 검증 |
| `AiSupportProgramRankingFacade` | 점수화 요청 구성, 후보 ID·점수 합계·자격·정렬 검증, 공고 모델 반환 |
| `AiSupportProgramEvidenceFacade` | 원문 청크 색인·검색과 답변·인용 응답을 검증 |

Spring은 Service·Facade·Repository·Client에 필요한 객체를 생성자로 주입합니다. DB 조회는 Repository를
직접 사용하며, 단순한 AI Health 호출처럼 별도 Facade가 필요 없는 경로도 있습니다.
[Core API 설계 상세](docs/technology.md#core-api-업무-흐름과-외부-경계)에서 각 계층과 DI의 적용 범위를 설명합니다.

### AI Service: 기능별 모듈과 typed Agent

AI Service는 FastAPI의 HTTP 경계, 점수화 업무 흐름, 모델 실행을 분리한 기능별 Python 모듈 구조입니다.

```text
app/
├── main.py                  # FastAPI 생성·라우터 등록·종료 처리
├── config.py                # 환경변수·실행 설정
├── bootstrap.py             # Client·Agent·Service 생성과 연결
├── health/                  # 내부 상태 확인
├── support_program_index/   # 공고 임베딩·Qdrant 색인·후보 검색
├── support_program_ranking/ # 후보 점수화: Router·Models·Prompt·Agent·Service·Errors
└── support_program_evidence/ # 상세 원문 청크 색인·근거 검색·답변
```

점수화와 원문 근거 답변은 각각 `HTTP API → Service → Agent → OpenAI → Response`로 실행됩니다.
`bootstrap.py`가 객체를 생성해 연결하고, Router는 FastAPI `Depends`를 통해 조립된 Service를 받습니다.
일반 공고 검색의 임베딩·Qdrant 검색은 `support_program_index`의 Service가, 상세 원문의 청크 색인·검색은
`support_program_evidence`의 Service가 직접 처리하며 둘 다 별도 컬렉션을 사용합니다. 두 업무 Agent는
tool·handoff·graph 없이 각각 한 turn으로 실행합니다.
[AI Service 설계 상세](docs/technology.md#ai-service-기능별-모듈과-객체-조립)에 파일별 역할과 요청 흐름을 정리했습니다.

## 빠른 시작

Docker와 Docker Compose를 준비합니다. 저장소 루트에서 **새 `.env`를 만들 때** 예시를 복사한 뒤
`DATA_GO_KR_SERVICE_KEY`와 `OPENAI_API_KEY`를 입력합니다.

```bash
cp .env.example .env
# .env에 공공데이터포털 인증키와 OpenAI API 키 입력
docker compose --env-file .env --file infrastructure/compose.yaml up --build
```

[http://127.0.0.1:5173](http://127.0.0.1:5173)에서 접속합니다. 첫 실행에서는 공고 수집과 벡터 색인이
완료된 뒤 검색할 데이터가 공개됩니다. 공고·질의 임베딩과 AI 점수화에 OpenAI 사용 비용이 발생합니다.
이 Compose 구성은 로컬 개발용입니다.

데이터 볼륨을 유지하며 종료하려면 다음 명령을 사용합니다.

```bash
docker compose --env-file .env --file infrastructure/compose.yaml down
```

실제 API 키 없이 로컬 스텁으로 전체 연결과 장애 복구를 확인할 수도 있습니다.
Docker 이미지·의존성을 처음 내려받을 때는 네트워크가 필요하며, 검증 전에 개발 서버의
`5173`·`8080` 포트를 비워야 합니다.

```bash
./infrastructure/scripts/verify-compose.sh
```

## 상세 문서

| 문서 | 내용 |
|---|---|
| [프로젝트 기술](docs/technology.md) | 클린 아키텍처·DI·MVVM·Flux, Core 계층과 Facade, AI 모듈, 검색·동기화 구조 |
| [구현 현황](docs/implementation-status.md) | 구현된 기능, 현재 제약, 검증 범위와 다음 개발 과제 |
| [아키텍처](docs/architecture.md) | 코드 계층, 호출 흐름, 의존성 규칙 |
| [검색·상세 API 계약](docs/support-program-search-contract.md) | 공개 API와 내부 AI 요청·응답 |
| [실행·컨테이너 안내](infrastructure/README.md) | 환경변수, 서비스별 접속, 검증·초기화 방법 |
| 서비스별 안내 | [Frontend](frontend/README.md) · [Core API](backend/core-api/README.md) · [AI Service](backend/ai-service/README.md) |
| [검색 평가 자료](evaluation/support-program-search/README.md) | 가상 공고 회귀 평가와 실제 검색 흐름 캡처·실데이터 평가 준비 |

학습용 SampleItem 예제는 [예제 계약](docs/sample-item-contract.md), 기능 추가 방법은
[확장 안내](docs/customization-guide.md)를 참고하세요.
