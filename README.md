# 동선 지도

사진첩의 EXIF GPS 좌표로 여행 경로를 그려 스토리용 카드 이미지로 뽑는 안드로이드 앱.
구글 타임라인 데이터가 없어도 됨 — 사진만 있으면 된다.

## APK 받는 법

### 1) GitHub Actions (안드로이드 스튜디오 없이)

```bash
cd dongseon
git init -b main
git add .
git commit -m "동선 지도 초기 커밋"
gh repo create dongseon --private --source=. --push
# gh가 없으면: GitHub에서 빈 저장소를 만든 뒤
#   git remote add origin https://github.com/<아이디>/dongseon.git
#   git push -u origin main
```

push되면 워크플로가 돌면서 debug APK를 만든다.
Actions 탭 → 최근 실행 → Artifacts에서 `dongseon-debug-apk` 다운로드.

태그를 붙여 push하면 Release에도 APK가 붙는다:

```bash
git tag v1.0 && git push origin v1.0
```

수동 실행은 Actions 탭 → Build APK → Run workflow.

> wrapper(`gradlew`)를 저장소에 넣지 않았기 때문에 워크플로가 Gradle을
> 직접 설치해서 `gradle assembleDebug`로 빌드한다. wrapper를 쓰고 싶으면
> 로컬에서 `gradle wrapper --gradle-version 8.11.1` 한 번 돌려 커밋하면 된다.

### 2) 로컬
```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```
Gradle wrapper는 포함하지 않았다. 안드로이드 스튜디오로 열면 자동 생성되고,
CLI만 쓸 거면 `gradle wrapper --gradle-version 8.9` 한 번 실행하면 된다.

## 핵심 구현

| 파일 | 하는 일 |
|---|---|
| `PhotoScanner.kt` | MediaStore 쿼리 + ExifInterface로 좌표·촬영시각 추출 |
| `TimelineJsonExporter.kt` | 고른 사진의 EXIF를 Google Timeline 호환 JSON으로 변환 |
| `Geo.kt` | haversine, 웹 메르카토르, 순차 클러스터링 |
| `MapRenderer.kt` | CARTO 타일 fetch + Canvas 렌더링 + PNG |
| `RouteViewModel.kt` | 스캔/재계산/저장 상태 |
| `MainActivity.kt` | Compose UI |

### 반드시 알아야 할 함정

Android 10(Q)부터 MediaStore는 **EXIF에서 위치를 지우고 준다**. 되살리려면 둘 다 필요하다:

1. `ACCESS_MEDIA_LOCATION` 런타임 권한
2. `MediaStore.setRequireOriginal(uri)`로 감싼 uri로 열기

둘 중 하나라도 빠지면 모든 사진에서 `latLong`이 null로 나온다. 권한은 있는데
좌표가 안 잡히면 여기부터 확인.

Android 14+에서 사용자가 "전체 허용" 대신 "선택한 사진만" 을 고르면 MediaStore가
고른 사진만 돌려준다. 전체 스캔이 필요하면 전체 허용을 받아야 한다.

### 클러스터링

연속 촬영된 사진들이 반경 안에 있으면 한 정거장으로 합친다. 나중에 같은 장소로
돌아오면 같은 노드를 공유하고, 그 구간을 오간 횟수만큼 선이 굵고 진해진다.

### 지도 타일

지도 SDK 의존성 없음. CARTO Positron 래스터 타일을 직접 받아 Canvas에 그린다.
**OpenStreetMap과 CARTO 저작자 표시를 카드에서 지우지 말 것** — 라이선스 조건이다.
배포용으로 트래픽이 커질 것 같으면 자체 타일 서버나 유료 플랜을 쓰는 게 맞다.

## 이번 버전에서 추가된 것

### Timeline.json 내보내기

앱 상단의 `사진 직접 골라 만들기`에서 원본 사진을 여러 장 고르면,
GPS와 촬영시간이 있는 사진만 시간순으로 정렬해 `Timeline.json`으로 저장한다.
좌표가 없는 사진과 정확히 중복된 지점은 자동으로 제외된다. 사진 자체는 JSON에 포함되지 않는다.
사진을 일일이 고르지 않고 시작일과 종료일만 정해 기간 전체를 자동으로 내보낼 수도 있다.
이 방식은 지도용 출처·폴더 필터와 무관하게 `DCIM/Camera` 카메라 폴더만 확인한다.

출력은 `semanticSegments[].timelinePath[]` 형식이며
[`google-timeline-visualizer` v2.4.1](https://github.com/mahlernim/google-timeline-visualizer/releases/tag/v2.4.1)의
실제 `TimelineParser`가 읽는 스키마에 맞춰져 있다.

### 1. 내가 찍은 사진만
`사진 출처` 3단 선택:

- **내 카메라** (기본) — EXIF에 `Make`/`Model`이 있는 사진만. 스크린샷과 카톡으로
  받은 사진은 이 필드가 없어서 자동으로 걸러진다. 여기에 `DCIM/` 경로 조건도 걸린다.
- **이 기기** — EXIF `Model`이 `Build.MODEL`과 일치하는 사진만. 남이 찍어 보내준
  사진에 좌표가 살아 있어도 확실히 빠진다.
- **전체** — 좌표만 있으면 전부.

여기에 더해 갤러리 폴더를 직접 체크해서 좁힐 수도 있다 (`BUCKET_ID` 필터).

### 2. 기간 선택
Material3 `DateRangePicker`로 시작일–종료일을 고른다. 날짜 조건은 MediaStore 쿼리에
바로 들어가므로, 범위 밖 사진은 아예 열지 않는다. EXIF를 여는 게 느린 부분이라
이 순서가 중요하다.

### 3. 재생
경로가 시간순으로 그려지며 움직인다. 재생/정지, 1×·2×·4× 배속, 스크럽 슬라이더,
그리고 현재 시점 날짜와 누적 거리가 카드에 실시간으로 올라간다.

렌더링은 두 겹으로 나눠져 있다:

- **베이스맵** — 타일을 받아 Bitmap 한 장으로 굽는다. fit이 바뀔 때만 다시 만든다.
- **오버레이** — 매 프레임 Compose `Canvas` 위에 네이티브 Canvas로 직접 그린다.
  프레임마다 Bitmap을 새로 할당하지 않으므로 끊기지 않는다.

진행 중인 구간은 `PathMeasure.getSegment()`로 잘라 그린다.

#### 페이싱
`구간마다 같은 시간`이 기본이다. 실제 시간 비율로 재생하면 한곳에 오래 머문 구간이
화면 시간을 다 먹는다 — 실제 데이터로 시뮬레이션해보면 움직이는 프레임이 6%까지
떨어진다. 균등 배분이면 80% 수준이다. 체크박스로 전환 가능.

### 영상으로 저장
넣지 않았다. Bitmap 시퀀스를 MP4로 굽는 표준 API가 없어 MediaCodec + 입력 Surface에
GL로 그려 넣어야 하는데, 여기서 검증할 수 없는 코드를 넣고 싶지 않았다.
당장은 재생하면서 안드로이드 기본 화면 녹화를 쓰는 게 제일 빠르다.
`이 장면 저장`은 현재 시점의 한 장면을 PNG로 갤러리에 넣는다.

## 정확도

거리는 사진 좌표를 시간순으로 이은 직선 합계다. 비행 구간은 대략 맞고
육로는 실제보다 짧게 나온다. 실제 이동 경로가 아니라 "찍은 지점들의 궤적"이다.

## 버전

AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26.
안드로이드 스튜디오가 업그레이드를 권하면 그냥 받아도 된다.
