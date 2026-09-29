package sh.zeron.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import sh.zeron.android.R
import sh.zeron.android.design.Glyph
import sh.zeron.android.design.Glyphs
import sh.zeron.android.design.ZeronColors
import sh.zeron.android.design.ZeronType
import sh.zeron.android.design.glassSurface
import sh.zeron.android.schedule.ScheduleTime
import sh.zeron.android.schedule.ScheduledAlarms
import sh.zeron.android.schedule.ScheduledMessage
import java.util.Calendar

/**
 * Long-press Send → pick a time. The day is implied: today, or tomorrow
 * when that time has already passed (phone clock and zone only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSendDialog(colors: ZeronColors, onDismiss: () -> Unit, onSchedule: (atMs: Long) -> Unit) {
    val context = LocalContext.current
    val start = remember {
        // Default: five minutes out, on a five-minute mark.
        Calendar.getInstance().apply {
            add(Calendar.MINUTE, 5 + (5 - get(Calendar.MINUTE) % 5) % 5)
        }
    }
    val state = rememberTimePickerState(
        initialHour = start.get(Calendar.HOUR_OF_DAY),
        initialMinute = start.get(Calendar.MINUTE) / 5 * 5,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    val now = System.currentTimeMillis()
    val at = ScheduleTime.next(state.hour, state.minute, now)
    val clock = ScheduleTime.clock(at)
    val whenLabel = if (ScheduleTime.isSameDay(at, now)) stringResource(R.string.schedule_today, clock) else stringResource(R.string.schedule_tomorrow, clock)
    val exact = remember { ScheduledAlarms.canExact(context) }
    val well = if (colors.dark) Color(0xFF2C2C2E) else Color(0xFFEFEFF1)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (colors.dark) Color(0xFF1C1C1E) else Color.White)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.schedule_send_title),
                color = colors.text,
                fontFamily = ZeronType.Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = well,
                    selectorColor = colors.accent,
                    timeSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.22f),
                    timeSelectorUnselectedContainerColor = well,
                    timeSelectorSelectedContentColor = colors.text,
                    timeSelectorUnselectedContentColor = colors.secondary,
                    periodSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.22f),
                    periodSelectorUnselectedContainerColor = Color.Transparent,
                    periodSelectorSelectedContentColor = colors.text,
                    periodSelectorUnselectedContentColor = colors.secondary,
                    clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = colors.secondary,
                ),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Glyphs.Clock, 15.dp, colors.secondary)
                Spacer(Modifier.width(6.dp))
                Text(whenLabel, color = colors.text, fontFamily = ZeronType.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            if (!exact) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.schedule_exact_hint),
                        color = colors.tertiary,
                        fontFamily = ZeronType.Sans,
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Text(
                            stringResource(R.string.schedule_exact_allow),
                            color = colors.accent,
                            fontFamily = ZeronType.Sans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.schedule_dismiss),
                    color = colors.secondary,
                    fontFamily = ZeronType.Sans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 9.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.schedule_confirm),
                    color = Color.White,
                    fontFamily = ZeronType.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.accent)
                        .clickable { onSchedule(ScheduleTime.next(state.hour, state.minute, System.currentTimeMillis())) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Above the composer: "Scheduled for 01:20 · Cancel" (iOS status-pill geometry). */
@Composable
internal fun ScheduledChip(colors: ZeronColors, message: ScheduledMessage, onCancel: () -> Unit) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.height(30.dp).glassSurface(colors, 15.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Clock, 14.dp, colors.accent)
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.schedule_chip, ScheduleTime.clock(message.atMs)),
                color = colors.secondary,
                fontFamily = ZeronType.Sans,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(" · ", color = colors.tertiary, fontFamily = ZeronType.Sans, fontSize = 13.sp)
            Box(Modifier.clip(RoundedCornerShape(11.dp)).clickable(onClick = onCancel).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.schedule_chip_cancel), color = colors.accent, fontFamily = ZeronType.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}
