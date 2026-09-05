# 실데이터 검색 평가 산출물 보관

한 번의 실데이터 평가를 `runs/<runId>/` 하나에 보관합니다. 이 디렉터리 아래의 실제 산출물은 Git에서
제외됩니다. 실제 공고의 검색용 텍스트와 사람이 작성한 라벨이 포함될 수 있으므로, 제공처 이용 조건과 팀의
보관 정책을 확인한 승인된 위치에 별도로 백업해야 합니다.

권장 파일 구성은 다음과 같습니다.

- `fixture-unlabeled.json`: `evaluation-fixture-export`가 만든 기준일 고정 초안
- `fixture-labeled.json`: 사람이 `cases`의 질문·정답을 검토한 파일
- `query-set.json`: capture에 전달한 질문 묶음
- `capture.json`: 같은 기준일의 실제 후보·최종 추천 결과(v2)
- `run-manifest.md`: 실행 시각·기준일·커밋·모델·해시 기록
- `report.md`: 후보와 최종 추천 지표 및 오류 사례

fixture와 capture의 `referenceDate`는 반드시 같아야 합니다. 날짜가 달라지면 `OPEN` 공고 집합도 달라질 수
있으므로 `evaluate.py`가 평가를 거부합니다. 생성 파일을 임의로 덮어쓰지 말고 새 `runId` 디렉터리를 만들어
보관합니다.
