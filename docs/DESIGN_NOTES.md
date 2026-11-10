# Pleos Connect 디자인 가이드 요약 (2026-10-29 조사)

출처: https://document.pleos.ai/docs/connect/guide/docs-design/ (42페이지) + vehicle-app-planning-guide 의 driver-distraction / driving-restriction 페이지.
코드 적용: `app/src/main/java/com/askthecar/Theme.kt` (`Pleos` 토큰, `PleosTheme`, `PleosButton`).

## 화면
- Connect-S-17(기본): 2560×1440dp, 160dpi. **앱 창 가로 1700×1184dp / 세로(2앱 분할) 840×1184dp**. 모든 앱은 가로·세로 두 레이아웃 지원 필수.
- 왼쪽 Driving view(속도·텔테일), 위 Status bar(오른쪽 40dp safe bounds), 아래 GNB. System UI가 앱 위를 덮을 수 있음.
- 미확인: 그리드·여백·GNB/Status bar 높이 수치.

## 색 (다크)
| 용도 | 값 |
|---|---|
| background_primary / secondary / popup | #1D1E21 / #131417 / #313236 |
| basic_200 / 300 / 600 | #44464E / #686A72 / #E0E1E6 |
| text primary / secondary / tertiary / quaternary | 흰색 100 / 84 / 64 / 32 % |
| active(성공) / positive / negative(위험) | #00E074 / #0082FF / #FF4C28 |
| regulation yellow / orange / red / green | #FFC224 / #FF8A00 / #FF4339 / #55F165 |
| 버튼 filled enabled | basic_600 |
- 대비 4.5:1 이상.

## 글자
- 글꼴 Asta Sans(Google Fonts) — **앱에 아직 번들 안 함**.
- Headline L 60/80 · M 56/72 · S 48/64 (ExtraBold/Bold), Title L 40/52 · M 36/44 · S 32/40, Body L 30/38 · M 28/36 · S 26/34, Label M 24/28 · S 20/24 (단위 표기 없음 → sp 사용).
- 최소 12sp, 중요 텍스트 높이 5mm 이상.

## 컴포넌트·모션
- Basic button Large/Medium/Small, Primary(밝은 회색)/Secondary(어두운 filled). 눌림 = **scale 95%** + 배경 변화, 손을 뗄 때 실행. 높이·반경 수치 미확인(.fig 안에 있을 것).
- System popup 너비 Large 1200 / Medium 760. Toast max 792, 패딩 24.
- 아이콘 48×48dp, Outlined stroke 3.6px round.
- Easing `cubic-bezier(0.16,1,0.3,1)`, duration 100/150/250/300/400/450ms.
- Vehicle Notification: Safety(흰 배경·주황 아이콘) / **Emergency(빨간 배경·흰 글자)**.

## 주행 중 규칙
- 메시지 120자/24단어 이하, 한 줄 80자, 문단 3줄 이하, 1~2초 안에 파악.
- 입력 응답 0.25초 이하.
- **주행 중 동영상·애니메이션 이미지·자동 스크롤 금지** → 우리 앱은 속도 ≥1km/h 이면 그림 정지.
- DO(Distraction Optimization) 미선언 앱은 주차 중에만 사용 가능. 키보드·설정·대용량 텍스트 주행 중 금지.
- 음성 피드백 권장.

## 리소스
- Display guide .fig: https://document.pleos.ai/img/Pleos_Connect_display_guide_215.fig (44MB)
- Design assets .fig: https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig
- 폰트: https://fonts.google.com/specimen/Asta+Sans
