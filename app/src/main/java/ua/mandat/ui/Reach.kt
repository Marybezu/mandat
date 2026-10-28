package ua.mandat.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.mandat.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calling and writing to someone through the phone's own apps. Mandat only hands over the number;
 * it never reads calls or messages (there is no safe official way to do that, and it is not needed).
 */
object Reach {
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    private const val SIGNAL = "org.thoughtcrime.securesms"

    fun dial(context: Context, phone: String) = start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))

    fun sms(context: Context, phone: String) = start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)))

    fun email(context: Context, address: String) = start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))

    /** Opens the chat in the WhatsApp app itself (never in a browser), so the number goes nowhere else. */
    fun whatsapp(context: Context, phone: String) {
        val uri = Uri.parse("https://wa.me/" + phone.filter { it.isDigit() })
        if (!tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP)) &&
            !tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP_BUSINESS))
        ) notFound(context)
    }

    /** Opens the chat in the Signal app itself. */
    fun signal(context: Context, phone: String) {
        val uri = Uri.parse("https://signal.me/#p/+" + phone.filter { it.isDigit() })
        if (!tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(SIGNAL))) notFound(context)
    }

    private fun start(context: Context, intent: Intent) {
        if (!tryStart(context, intent)) notFound(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun notFound(context: Context) =
        Toast.makeText(context, R.string.reach_no_app, Toast.LENGTH_SHORT).show()
}

/** Buttons to call or write, for each phone and e-mail found in a contact. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReachButtons(phones: List<String>, emails: List<String>) {
    if (phones.isEmpty() && emails.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        phones.forEach { phone ->
            Text(phone, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { Reach.dial(context, phone) }, label = { Text(stringResource(R.string.reach_call)) })
                AssistChip(onClick = { Reach.sms(context, phone) }, label = { Text(stringResource(R.string.reach_sms)) })
                // Messengers need the international form (+…)
                if (phone.startsWith("+")) {
                    AssistChip(onClick = { Reach.signal(context, phone) }, label = { Text("Signal") })
                    AssistChip(onClick = { Reach.whatsapp(context, phone) }, label = { Text("WhatsApp") })
                }
            }
        }
        emails.forEach { address ->
            AssistChip(onClick = { Reach.email(context, address) }, label = { Text(address) })
        }
        Text(stringResource(R.string.reach_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/** An event from the phone's calendar: shown only, never saved in Mandat. */
data class PhoneEvent(val title: String, val day: Long, val startMin: Int?, val endMin: Int?, val place: String)

/**
 * Showing the phone's calendar next to Mandat's own, only if the owner turns it on and allows
 * it. Read when the calendar is on screen; nothing from it is stored. Turning it off also gives
 * the permission back (from Android 13).
 */
object PhoneCalendar {
    private const val PREFS = "phone_calendar"
    private const val KEY_ON = "on"
    private const val MAX_ROWS = 2_000
    private const val MAX_SPAN_DAYS = 62

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false) && allowed(context)

    /** From Android 13 Mandat can give the permission back itself; before that, only the phone settings can. */
    val canGiveBack get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (!on && allowed(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Takes effect when Mandat is next closed by the system
            context.revokeSelfPermissionOnKill(Manifest.permission.READ_CALENDAR)
        }
    }

    /** Events touching the days from..to (at most two months); empty if off or not allowed. */
    fun events(context: Context, from: Long, to: Long): List<PhoneEvent> {
        if (!isOn(context) || to < from) return emptyList()
        val last = minOf(to, from + MAX_SPAN_DAYS)
        val zone = ZoneId.systemDefault()
        val begin = LocalDate.ofEpochDay(from).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.ofEpochDay(last + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString()).appendPath(end.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = ArrayList<PhoneEvent>()
        try {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < MAX_ROWS) {
                    val title = c.getString(0)?.trim().orEmpty().take(300)
                    val startMs = c.getLong(1)
                    val endMs = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    val place = c.getString(4)?.trim().orEmpty().take(300)
                    if (allDay) {
                        // All-day events are stored as UTC midnights
                        val d0 = Math.floorDiv(startMs, 86_400_000L)
                        val d1 = maxOf(d0, Math.floorDiv(endMs - 1, 86_400_000L))
                        for (d in maxOf(d0, from)..minOf(d1, last)) out += PhoneEvent(title, d, null, null, place)
                    } else {
                        val s = Instant.ofEpochMilli(startMs).atZone(zone)
                        val e = Instant.ofEpochMilli(maxOf(endMs, startMs)).atZone(zone)
                        val d0 = s.toLocalDate().toEpochDay()
                        // Ending exactly at midnight is the end of that day, not a moment of the next one
                        val d1 = maxOf(d0, Instant.ofEpochMilli(maxOf(endMs - 1, startMs)).atZone(zone).toLocalDate().toEpochDay())
                        val endsAtMidnight = e.toLocalDate().toEpochDay() > d1
                        for (d in maxOf(d0, from)..minOf(d1, last)) {
                            val sm = if (d == d0) s.hour * 60 + s.minute else null
                            val em = if (d == d1 && d == d0) (if (endsAtMidnight) 24 * 60 else e.hour * 60 + e.minute) else null
                            out += PhoneEvent(title, d, sm, em, place)
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            // Not allowed any more, or the calendar app failed: show nothing rather than crash
            return emptyList()
        }
        return out
    }

    /**
     * Hands an event to the phone's calendar app to add (no permission needed). Only the title,
     * time and place leave Mandat; notes, people and the outcome stay inside.
     */
    fun addToPhone(context: Context, title: String, day: Long, endDay: Long?, startMin: Int?, endMin: Int?, place: String) {
        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (place.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, place)
        if (startMin == null) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, LocalDate.ofEpochDay((endDay ?: day) + 1).atStartOfDay(zone).toInstant().toEpochMilli())
        } else {
            val start = LocalDate.ofEpochDay(day).atStartOfDay(zone).plusMinutes(startMin.toLong())
            val end = LocalDate.ofEpochDay(endDay ?: day).atStartOfDay(zone).plusMinutes((endMin ?: (startMin + 60)).toLong())
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, maxOf(end, start.plusMinutes(15)).toInstant().toEpochMilli())
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.reach_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
