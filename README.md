# GovBiz

자연어로 정부지원사업을 찾고 지원 대상·신청 기간·공식 원문을 확인하는 채팅형 웹앱입니다.
기업마당 공고를 MySQL에 동기화하고, Qdrant 의미 검색과 AI 점수화로 관련 공고를 최대 5개 추천합니다.

## 주요 기능

- 자연어 공고 검색과 추천 이유·점수 표시
- 공고 상세 조회, 원문 링크, 서울 날짜 기준 접수 상태 계산
- 기업마당 전체 공고 자동 동기화와 누락 벡터 복구
- 입력·응답 검증, 한글 입력 처리, 검색 취소 및 장애 안내

현재 연동한 공고 제공처는 기업마당입니다. 상세 원문·첨부문서에 근거한 RAG 질의응답과
대화 맥락을 이어가는 검색은 아직 구현하지 않았습니다.

## 기술 구성

| 영역 | 주요 기술 |
|---|---|
| Frontend | React, TypeScript, Vite, Tailwind CSS, Redux Toolkit, Awilix, Zod |
| Core API | Kotlin, Spring Boot, MyBatis, Flyway |
| AI Service | Python, FastAPI, OpenAI Agents SDK, OpenAI 임베딩 |
| 데이터·실행 | MySQL 8.4, Qdrant, Docker Compose, GitHub Actions |

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
| [프로젝트 기술](docs/technology.md) | 기술별 역할, MySQL·Qdrant 구분, 검색·동기화 구조와 설계 선택 |
| [구현 현황](docs/implementation-status.md) | 구현된 기능, 현재 제약, 검증 범위와 다음 개발 과제 |
| [아키텍처](docs/architecture.md) | 코드 계층, 호출 흐름, 의존성 규칙 |
| [검색·상세 API 계약](docs/support-program-search-contract.md) | 공개 API와 내부 AI 요청·응답 |
| [실행·컨테이너 안내](infrastructure/README.md) | 환경변수, 서비스별 접속, 검증·초기화 방법 |
| 서비스별 안내 | [Frontend](frontend/README.md) · [Core API](backend/core-api/README.md) · [AI Service](backend/ai-service/README.md) |
| [검색 평가 자료](evaluation/support-program-search/README.md) | 가상 공고 회귀 평가와 실데이터 평가 준비 |

학습용 SampleItem 예제는 [예제 계약](docs/sample-item-contract.md), 기능 추가 방법은
[확장 안내](docs/customization-guide.md)를 참고하세요.
