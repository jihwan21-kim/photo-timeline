package com.photoroute.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar
import java.util.TimeZone

private val Ink = Color(0xFF0F1317)
private val Panel = Color(0xFF171C22)
private val Graphite = Color(0xFF8C97A3)
private val TextC = Color(0xFFDFE4E9)
private val Accent = Color(0xFFFF2D74)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Ink, surface = Panel, onSurface = TextC,
                )
            ) { Screen() }
        }
    }
}

private fun neededPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        add(Manifest.permission.READ_MEDIA_IMAGES)
    else
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()

@Composable
fun Screen(vm: RouteViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(neededPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var showDates by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showStops by remember { mutableStateOf(false) }

    val saveTimelineDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination ->
        if (destination != null) vm.savePreparedTimeline(destination)
        else vm.cancelTimelineSave()
    }

    val pickTimelinePhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.prepareTimelineJson(uris)
    }

    val requestTimelineLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (!allowed) vm.timelineLocationPermissionDenied()
        pickTimelinePhotos.launch(arrayOf("image/*"))
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        granted = res.values.all { it }
        if (granted) { vm.loadBuckets(); vm.scan() }
    }

    LaunchedEffect(granted) { if (granted) vm.loadBuckets() }

    LaunchedEffect(vm.timelineReadyToSave) {
        if (vm.timelineReadyToSave) {
            vm.consumeTimelineSaveRequest()
            runCatching { saveTimelineDocument.launch("Timeline.json") }
                .onFailure { vm.timelineSaveLaunchFailed() }
        }
    }

    // playback clock
    LaunchedEffect(vm.playing) {
        if (!vm.playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (vm.playing) {
            withFrameNanos { now ->
                vm.advance((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 42.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("동선 지도", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("사진은 기기 밖으로 나가지 않아.", color = Graphite, fontSize = 12.sp)

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label("GOOGLE TIMELINE VISUALIZER")
                Text("Timeline.json 만들기", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "고른 원본 사진의 GPS와 촬영시간만 추출해. 사진 파일 자체는 JSON에 들어가지 않아.",
                    color = Graphite,
                    fontSize = 11.5.sp,
                )
                Button(
                    onClick = {
                        if (
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_MEDIA_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            pickTimelinePhotos.launch(arrayOf("image/*"))
                        } else {
                            requestTimelineLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.preparingTimeline,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text(if (vm.preparingTimeline) "위치정보 읽는 중…" else "사진 골라 Timeline.json 만들기")
                }
                if (vm.preparingTimeline) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                if (vm.timelineStatus.isNotBlank()) {
                    Text(vm.timelineStatus, color = Graphite, fontSize = 11.sp)
                }
                if (vm.timelineCanSave && !vm.timelineReadyToSave && !vm.preparingTimeline) {
                    OutlinedButton(
                        onClick = { vm.requestTimelineSave() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("저장 위치 다시 고르기")
                    }
                }
            }
        }

        MapPreview(vm)

        if (vm.scanning) LinearProgressIndicator({ vm.progress }, Modifier.fillMaxWidth(), color = Accent)
        if (vm.status.isNotBlank()) Text(vm.status, color = Graphite, fontSize = 11.5.sp)

        PlaybackBar(vm)

        vm.route?.let { r ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(22.dp)) {
                Stat("거리", MapRenderer.km(r.km))
                Stat("정거장", "${r.nodes.size}")
                Stat("구간", "${vm.plan?.segments?.size ?: 0}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ showStops = true }, Modifier.weight(1f)) {
                    Text("잘못된 지점 제거", fontSize = 12.sp)
                }
                if (vm.canUndoRemove) {
                    OutlinedButton({ vm.undoRemoveStop() }, Modifier.weight(1f)) {
                        Text("마지막 제거 취소", fontSize = 12.sp)
                    }
                }
            }
        }

        Label("기간")
        OutlinedButton({ showDates = true }, Modifier.fillMaxWidth()) {
            Text("${MapRenderer.day(vm.fromMillis)}  –  ${MapRenderer.day(vm.toMillis)}", fontSize = 13.sp)
        }

        Label("사진 출처")
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            Source.entries.forEach { s ->
                val on = vm.source == s
                OutlinedButton(
                    { vm.updateSource(s) }, Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (on) Accent else Color.Transparent,
                        contentColor = if (on) Color.White else Graphite,
                    ),
                ) { Text(s.label, fontSize = 11.5.sp) }
            }
        }
        Text(
            when (vm.source) {
                Source.CAMERA -> "카메라로 찍은 사진만. 스크린샷·받은 사진은 EXIF에 기종이 없어서 걸러져."
                Source.THIS_DEVICE -> "EXIF 기종이 이 폰과 일치하는 사진만."
                Source.ALL -> "좌표만 있으면 전부. 남이 찍어 보내준 사진도 들어올 수 있어."
            },
            color = Graphite, fontSize = 11.sp,
        )

        TextButton({ showFolders = !showFolders }) {
            Text(
                if (vm.selectedBuckets.isEmpty()) "폴더 고르기 (지금은 전부)"
                else "폴더 ${vm.selectedBuckets.size}개 선택됨",
                color = Accent, fontSize = 12.sp,
            )
        }
        if (showFolders) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(6.dp)).background(Panel).padding(4.dp)
            ) {
                vm.buckets.take(40).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(b.id in vm.selectedBuckets, { vm.toggleBucket(b.id) })
                        Text(b.name, color = TextC, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        Text("${b.count}", color = Graphite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (vm.selectedBuckets.isNotEmpty()) {
                    TextButton({ vm.clearBuckets() }) { Text("선택 해제", color = Graphite, fontSize = 12.sp) }
                }
            }
        }

        Button(
            onClick = { if (granted) vm.scan() else ask.launch(neededPermissions()) },
            Modifier.fillMaxWidth(), enabled = !vm.scanning,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text(if (granted) "이 조건으로 훑기" else "사진 접근 허용하고 시작") }

        Label("묶기 반경  ${vm.radiusKm.toInt()}km")
        Slider(vm.radiusKm, { vm.setRadius(it) }, valueRange = 1f..200f)

        Label("제목")
        OutlinedTextField(
            vm.spec.title, { vm.updateSpec(vm.spec.copy(title = it)) },
            Modifier.fillMaxWidth(), singleLine = true,
        )

        Label("비율")
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            Ratio.entries.forEach { r ->
                val on = vm.spec.ratio == r
                OutlinedButton(
                    { vm.updateSpec(vm.spec.copy(ratio = r)) }, Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (on) Accent else Color.Transparent,
                        contentColor = if (on) Color.White else Graphite,
                    ),
                ) { Text(r.label, fontSize = 12.sp) }
            }
        }

        Label("색")
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            listOf(0xFFFF2D74L, 0xFF1F6FEBL, 0xFF14A06AL, 0xFFF08C00L, 0xFF6E56CFL).forEach { hex ->
                OutlinedButton(
                    { vm.updateSpec(vm.spec.copy(color = hex.toInt())) },
                    Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(hex)),
                ) {}
            }
        }

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.updateSpec(vm.spec.copy(zoomAdjust = vm.spec.zoomAdjust + 1)) }, Modifier.weight(1f)) { Text("＋") }
            OutlinedButton({ vm.updateSpec(vm.spec.copy(zoomAdjust = vm.spec.zoomAdjust - 1)) }, Modifier.weight(1f)) { Text("－") }
            OutlinedButton({ vm.updateSpec(vm.spec.copy(zoomAdjust = 0)) }, Modifier.weight(2f)) { Text("다시 맞추기", fontSize = 12.sp) }
        }

        Button(
            onClick = { vm.save() }, Modifier.fillMaxWidth(), enabled = vm.basemap != null,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text("이 장면 저장") }

        Button(
            onClick = { vm.saveVideo() },
            Modifier.fillMaxWidth(),
            enabled = vm.basemap != null && !vm.exportingVideo,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text(if (vm.exportingVideo) "영상 만드는 중…" else "동선 영상 저장") }
        if (vm.exportingVideo) {
            LinearProgressIndicator({ vm.videoProgress }, Modifier.fillMaxWidth(), color = Accent)
        }

        Text(
            "저장한 MP4 영상은 갤러리의 Movies/동선지도에서 확인할 수 있어.",
            color = Graphite, fontSize = 11.sp,
        )
    }

    if (showDates) DateRangeDialog(vm) { showDates = false }
    if (showStops) StopEditorDialog(vm) { showStops = false }
}

@Composable
private fun StopEditorDialog(vm: RouteViewModel, onClose: () -> Unit) {
    val stops = vm.route?.stops.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("잘못된 지점 제거") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "날짜와 좌표를 확인한 뒤 제거하세요. 해당 지점의 사진들이 동선에서 빠지고 앞뒤 경로가 다시 연결됩니다.",
                    color = Graphite, fontSize = 11.5.sp,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    itemsIndexed(stops, key = { index, stop -> "${stop.t0}-$index" }) { index, stop ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${index + 1}. ${MapRenderer.day(stop.t0)}  ·  사진 ${stop.count}장",
                                    color = TextC, fontSize = 12.5.sp,
                                )
                                Text(
                                    "%.5f, %.5f".format(stop.lat, stop.lon),
                                    color = Graphite, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                            TextButton({ vm.removeStop(stop) }) {
                                Text("제거", color = Accent, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Graphite.copy(alpha = 0.2f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("완료") } },
    )
}

@Composable
private fun MapPreview(vm: RouteViewModel) {
    val ratio = vm.spec.ratio
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio.w.toFloat() / ratio.h)
            .clip(RoundedCornerShape(6.dp)).background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        val base = vm.basemap
        val plan = vm.plan
        if (base != null && plan != null) {
            Canvas(Modifier.fillMaxSize()) {
                val at = vm.cursor          // read in draw phase -> only redraws, no recomposition
                vm.tileRevision             // redraw as soon as newly requested map tiles arrive
                val s = size.width / ratio.w
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    val save = nc.save()
                    nc.scale(s, s)
                    MapRenderer.drawScene(nc, base, plan, vm.spec, at)
                    nc.restoreToCount(save)
                }
            }
        } else {
            Text(
                if (vm.scanning) "읽는 중…" else "훑고 나면 여기서 경로가 움직여",
                color = Graphite, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun PlaybackBar(vm: RouteViewModel) {
    val plan = vm.plan ?: return
    if (plan.segments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                { if (vm.playing) vm.pause() else vm.play() },
                Modifier.width(84.dp).height(38.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text(if (vm.playing) "❚❚ 정지" else "▶ 재생", fontSize = 12.sp) }

            Spacer(Modifier.width(8.dp))
            listOf(1f, 2f, 4f).forEach { s ->
                val on = vm.speed == s
                TextButton({ vm.updateSpeed(s) }, Modifier.width(46.dp)) {
                    Text("${s.toInt()}×", fontSize = 12.sp, color = if (on) Accent else Graphite)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                MapRenderer.day(vm.cursor),
                color = TextC, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Slider(vm.progressT, { vm.seekTo(it) }, valueRange = 0f..1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.evenPacing, { vm.updateEvenPacing(it) })
            Text(
                if (vm.evenPacing) "구간마다 같은 시간" else "체류시간 반영 (압축)",
                color = Graphite, fontSize = 11.5.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(vm: RouteViewModel, onClose: () -> Unit) {
    // the picker hands back UTC midnight; shift it onto the local day
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = localToUtcDay(vm.fromMillis),
        initialSelectedEndDateMillis = localToUtcDay(vm.toMillis),
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton({
                val s = state.selectedStartDateMillis
                val e = state.selectedEndDateMillis
                if (s != null && e != null) {
                    vm.setRange(utcDayToLocalStart(s), utcDayToLocalEnd(e))
                    vm.scan()
                }
                onClose()
            }) { Text("이 기간으로 훑기") }
        },
        dismissButton = { TextButton(onClose) { Text("취소") } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), title = null)
    }
}

private fun localToUtcDay(local: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = local }
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }
    return utc.timeInMillis
}

private fun utcDay(millis: Long, endOfDay: Boolean): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    return Calendar.getInstance().apply {
        clear()
        set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        if (endOfDay) {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }
    }.timeInMillis
}

private fun utcDayToLocalStart(m: Long) = utcDay(m, false)
private fun utcDayToLocalEnd(m: Long) = utcDay(m, true)

@Composable private fun Label(t: String) =
    Text(t, color = Graphite, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

@Composable private fun Stat(label: String, value: String) = Column {
    Text(label, color = Graphite, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Text(value, color = TextC, fontSize = 18.sp)
}
