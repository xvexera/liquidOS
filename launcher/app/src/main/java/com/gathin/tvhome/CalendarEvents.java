package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.format.DateFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The next events of the calendars the TV already syncs from the owner's Google account (CalendarContract, the
 * same data the phone's calendar app reads), for the calendar widget. No sign-in and no network of our own: Google's
 * calendar sync adapter keeps the data fresh, and an observer on the provider reloads when it does. Needs
 * READ_CALENDAR, granted once over adb ({@code pm grant com.gathin.tvhome android.permission.READ_CALENDAR}).
 */
final class CalendarEvents {
    static final class Event {
        final String title;
        final long begin;
        final long end;
        final boolean allDay;
        final int color;

        Event(String title, long begin, long end, boolean allDay, int color) {
            this.title = title;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.color = color;
        }
    }

    interface Listener {
        /** On the main thread: up to {@code limit} upcoming events, soonest first; empty when there are none. */
        void onEvents(List<Event> events);
    }

    /** How far ahead the list looks: as far as the pill's count can be set. */
    private static final long WINDOW_MS = (long) Extras.CALENDAR_MAX_DAYS * 24 * 60 * 60 * 1000;
    private static final long REFRESH_MS = 5 * 60_000L;

    private final Context context;
    private final Listener listener;
    private final int limit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean started;

    private final ContentObserver changed = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    /** Every few minutes too: "Now" and "Today" labels move on even when no event changes. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    CalendarEvents(Context context, int limit, Listener listener) {
        this.context = context.getApplicationContext();
        this.limit = limit;
        this.listener = listener;
    }

    static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (started || !allowed(context)) return;
        started = true;
        try {
            context.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, changed);
        } catch (RuntimeException ignored) {
            // no calendar provider: the periodic reload still runs and finds nothing
        }
        tick.run();
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(tick);
        context.getContentResolver().unregisterContentObserver(changed);
    }

    private void load() {
        if (!started) return;
        worker.execute(() -> {
            List<Event> events = query();
            main.post(() -> {
                if (started) listener.onEvents(events);
            });
        });
    }

    /** Instances, not Events: a weekly meeting appears as each of its occurrences. Ongoing events count too. */
    private List<Event> query() {
        List<Event> events = new ArrayList<>();
        long now = System.currentTimeMillis();
        Uri.Builder range = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(range, now - 24L * 60 * 60 * 1000);   // all-day events of today began at midnight UTC
        ContentUris.appendId(range, now + WINDOW_MS);
        String[] columns = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR};
        try (Cursor c = context.getContentResolver().query(range.build(), columns,
                CalendarContract.Instances.VISIBLE + "=1 AND " + CalendarContract.Instances.END + ">?",
                new String[]{String.valueOf(now)}, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext() && events.size() < limit + 6) {
                String title = c.getString(0);
                if (title == null || title.trim().isEmpty()) continue;
                events.add(new Event(title.trim(), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getInt(4)));
            }
        } catch (RuntimeException ignored) {
            // permission revoked or provider busy: show nothing this time
        }
        // All-day events sort by their UTC midnight; put them with the day they belong to, before its timed events.
        events.sort((a, b) -> Long.compare(dayKey(a), dayKey(b)) != 0 ? Long.compare(dayKey(a), dayKey(b))
                : a.allDay != b.allDay ? (a.allDay ? -1 : 1) : Long.compare(a.begin, b.begin));
        while (events.size() > limit) events.remove(events.size() - 1);
        return events;
    }

    private static long dayKey(Event e) {
        ZoneId zone = e.allDay ? ZoneOffset.UTC : ZoneId.systemDefault();
        return Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate().toEpochDay();
    }

    /** "Now", "Today · 18:30", "Tomorrow · All day", "Fri · 10:00", "24 Oct · 10:00". */
    static String when(Context ctx, Event e) {
        long now = System.currentTimeMillis();
        if (!e.allDay && e.begin <= now && e.end > now) return ctx.getString(R.string.calendar_now);
        LocalDate today = LocalDate.now();
        LocalDate day = Instant.ofEpochMilli(e.begin).atZone(e.allDay ? ZoneOffset.UTC : ZoneId.systemDefault()).toLocalDate();
        String dayLabel;
        if (day.equals(today)) dayLabel = ctx.getString(R.string.calendar_today);
        else if (day.equals(today.plusDays(1))) dayLabel = ctx.getString(R.string.calendar_tomorrow);
        else if (day.isBefore(today.plusDays(7))) dayLabel = DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(day);
        else dayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day);
        String time = e.allDay ? ctx.getString(R.string.calendar_all_day)
                : DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a", Locale.getDefault())
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.begin), ZoneId.systemDefault()));
        return dayLabel.replace(".", "") + "  ·  " + time;
    }
}
