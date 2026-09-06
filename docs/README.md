# GovBiz 문서 안내

[메인 README](../README.md)로 돌아가기

메인 README는 프로젝트 소개와 빠른 시작만 안내합니다. 상세 설명은 아래 문서에서 관리합니다.

## 구조와 구현 범위

| 문서 | 확인할 내용 |
|---|---|
| [아키텍처 README](architecture/README.md) | 서비스 경계, Frontend·Core API·AI Service의 계층·DI·디자인 패턴 |
| [서비스 호출·데이터 흐름](architecture.md) | 검색·상세·RAG·동기화·벡터 복구와 오류 처리 순서 |
| [기술 스택과 데이터 구성](technology.md) | 사용 기술·버전, MySQL·Qdrant 저장 역할과 점수 정책 |
| [구현 현황](implementation-status.md) | 현재 완료 단계, 검증 범위, 제한 사항과 다음 작업 |

## 실행과 API

| 문서 | 확인할 내용 |
|---|---|
| [Compose 실행·검증](../infrastructure/README.md) | 환경변수, 시작·중지·초기화, 실제 API 키 없는 통합 검증 |
| [지원사업 API 계약](support-program-search-contract.md) | 검색·상세·원문 질문·준비 상태의 공개/내부 요청·응답 |
| [요청량·동시 실행 제한](support-program-request-limits.md) | 제한 설정·429/503 계약·운영 한계·4단계 최종 통합 검증 |
| [Frontend 개발](../frontend/README.md) | 화면 구조, 실행, 테스트·lint·build |
| [Core API 개발](../backend/core-api/README.md) | 패키지·DB 규칙, 평가 프로필, JDK 21·MySQL 테스트 |
| [AI Service 개발](../backend/ai-service/README.md) | 실행 설정, 내부 API, 테스트와 패키지 빌드 |

## 평가와 개발 확장

| 문서 | 확인할 내용 |
|---|---|
| [실데이터 평가 결과·이어받기](../evaluation/support-program-search/runs/support-program-catalog-20260906-v1/README.md) | 고정 스냅샷·판정 원표·3단계 기준선·4단계 전후 비교와 API 없는 재현 |
| [검색 평가 도구](../evaluation/support-program-search/README.md) | 가상 공고 회귀 평가, 실제 후보·최종 추천 캡처와 지표 계산 |
| [RAG 검수·답변 평가](../evaluation/support-program-evidence/README.md) | 5단계 코드 검수, 가상 고정 근거 12개 질문, API 없는 검증과 선택적 실제 답변 캡처 |
| [판정·검토 도구](../evaluation/support-program-search/review/README.md) | AI-only·혼합·사람 검토 모드 선택과 출처 관리 |
| [Agent 모듈 구조](../backend/ai-service/docs/agent-structure.md) | Agent의 책임과 기능 추가 기준 |
| [기능 확장 안내](customization-guide.md) | 새 기능을 추가할 때의 계층·계약·검증 순서 |
| [SampleItem 예제 계약](sample-item-contract.md) | 계층 학습용 예제 API |

## 문서별 관리 범위

- 현재 개발 단계와 다음 과제는 구현 현황에 기록합니다.
- 실험 수치·판정 원표·전후 비교는 해당 평가 실행 폴더에 보존하고 메인 README에는 반복하지 않습니다.
- 설계 패턴은 아키텍처 README, 실제 실행 순서는 호출·데이터 흐름, 실행 명령·환경변수는 해당 서비스 안내에서 관리합니다.
