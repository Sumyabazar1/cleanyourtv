package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Current weather, the next three days and the US air quality index from Open-Meteo, which needs no key. The only
 * thing sent is the TV's latitude and longitude (config.xml). MainActivity reads it on a background thread: every {@link #REFRESH_MS}, or every
 * {@link #RETRY_MS} after a failure.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60_000L;
    static final long RETRY_MS = 5 * 60_000L;
    private static final String TAG = "Weather";
    private static final int TIMEOUT_MS = 8_000;

    private Weather() { }

    /** Condition group of a WMO weather code, for the backdrop colours. */
    enum Kind { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

    /** One reading: whole degrees Celsius (air and feels-like), the WMO weather code and whether it is daytime there. */
    static final class Report {
        final int tempC;
        final int feelsC;
        final int code;
        final boolean day;
        /** The next days, tomorrow first; empty if the reply had none. */
        final List<Day> days;
        /** US AQI, or -1 when the air quality read failed. */
        final int aqi;

        Report(int tempC, int feelsC, int code, boolean day, List<Day> days, int aqi) {
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.code = code;
            this.day = day;
            this.days = days;
            this.aqi = aqi;
        }
    }

    /** One forecast day: its date, the WMO code and the high and low in whole degrees Celsius. */
    static final class Day {
        final LocalDate date;
        final int code;
        final int maxC;
        final int minC;

        Day(LocalDate date, int code, int maxC, int minC) {
            this.date = date;
            this.code = code;
            this.maxC = maxC;
            this.minC = minC;
        }
    }

    /** US AQI bands: upper bound, label, pill colour, text colour. */
    private static final int[] AQI_MAX = {50, 100, 150, 200, 300, Integer.MAX_VALUE};
    private static final int[] AQI_LABEL = {R.string.aqi_good, R.string.aqi_moderate, R.string.aqi_sensitive,
            R.string.aqi_unhealthy, R.string.aqi_very_unhealthy, R.string.aqi_hazardous};
    private static final int[] AQI_COLOUR = {0xE643A047, 0xE6FBC02D, 0xE6FB8C00, 0xE6E53935, 0xE68E24AA, 0xE67E0023};

    static Kind kind(int code) {
        if (code <= 1) return Kind.CLEAR;
        if (code <= 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Kind.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.STORM;
        return Kind.CLOUDY;
    }

    /** The words for a WMO weather code, as fine as the code goes ("Light snow", not "Snow"). */
    static int sky(int code, boolean day) {
        switch (code) {
            case 0: return day ? R.string.sky_sunny : R.string.sky_clear;
            case 1: return day ? R.string.sky_mostly_sunny : R.string.sky_mostly_clear;
            case 2: return R.string.sky_partly_cloudy;
            case 3: return R.string.sky_cloudy;
            case 45: case 48: return R.string.sky_fog;
            case 51: case 53: case 55: return R.string.sky_drizzle;
            case 56: case 57: return R.string.sky_freezing_drizzle;
            case 61: return R.string.sky_light_rain;
            case 63: return R.string.sky_rain;
            case 65: return R.string.sky_heavy_rain;
            case 66: case 67: return R.string.sky_freezing_rain;
            case 71: return R.string.sky_light_snow;
            case 73: return R.string.sky_snow;
            case 75: return R.string.sky_heavy_snow;
            case 77: return R.string.sky_snow_grains;
            case 80: case 81: case 82: return R.string.sky_showers;
            case 85: case 86: return R.string.sky_snow_showers;
            default: return code >= 95 ? R.string.sky_thunderstorm : R.string.sky_cloudy;
        }
    }

    /** Reads the current weather, or returns null (logged) when the TV is offline or the reply is unexpected. */
    static Report fetchOrNull(String latitude, String longitude) {
        String where = "latitude=" + latitude + "&longitude=" + longitude;
        try {
            // DWD's ICON model: Open-Meteo's default blend here (ECMWF) called light snow over Ulaanbaatar
            // "overcast", when ICON and Google both had snow falling.
            JSONObject reply = get("https://api.open-meteo.com/v1/forecast?" + where + "&models=icon_seamless"
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4");
            JSONObject current = reply.getJSONObject("current");
            return new Report((int) Math.round(current.getDouble("temperature_2m")),
                    (int) Math.round(current.getDouble("apparent_temperature")),
                    current.getInt("weather_code"), current.getInt("is_day") == 1,
                    days(reply.optJSONObject("daily")), aqiOrUnknown(where));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "weather not updated: " + e);
            return null;
        }
    }

    /** Tomorrow and the days after it; today (index 0) is already the current reading. */
    private static List<Day> days(JSONObject daily) throws JSONException {
        List<Day> out = new ArrayList<>();
        if (daily == null) return out;
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        for (int i = 1; i < dates.length(); i++) {
            out.add(new Day(LocalDate.parse(dates.getString(i)), codes.getInt(i),
                    (int) Math.round(max.getDouble(i)), (int) Math.round(min.getDouble(i))));
        }
        return out;
    }

    /** The air quality is a separate service: if it fails, the weather still shows, without the pill. */
    private static int aqiOrUnknown(String where) {
        try {
            JSONObject current = get("https://air-quality-api.open-meteo.com/v1/air-quality?" + where
                    + "&current=us_aqi").getJSONObject("current");
            return current.isNull("us_aqi") ? -1 : current.getInt("us_aqi");
        } catch (IOException | JSONException e) {
            Log.w(TAG, "air quality not updated: " + e);
            return -1;
        }
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            return new JSONObject(readAll(in));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** "3° · feels like 1° · Light snow" */
    static String label(Context context, Report report) {
        return context.getString(R.string.weather_line, report.tempC, report.feelsC,
                context.getString(sky(report.code, report.day)));
    }

    /** "Fri Snow 2°/−10°   Sat Clear 5°/−8°   …" */
    static String forecast(Context context, Report report) {
        StringBuilder out = new StringBuilder();
        for (Day day : report.days) {
            if (out.length() > 0) out.append("    ");
            out.append(context.getString(R.string.forecast_day,
                    day.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    context.getString(sky(day.code, true)), day.maxC, day.minC));
        }
        return out.toString();
    }

    private static int aqiBand(int aqi) {
        int band = 0;
        while (aqi > AQI_MAX[band]) band++;
        return band;
    }

    /** "AQI 152 · Unhealthy" */
    static String aqiLabel(Context context, int aqi) {
        return context.getString(R.string.aqi_line, aqi, context.getString(AQI_LABEL[aqiBand(aqi)]));
    }

    /** The pill behind the AQI label, in its band's colour. */
    static Drawable aqiPill(Context context, int aqi) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(AQI_COLOUR[aqiBand(aqi)]);
        pill.setCornerRadius(100 * context.getResources().getDisplayMetrics().density);
        return pill;
    }

    /** Dark text on the yellow band, white on the others. */
    static int aqiTextColour(int aqi) {
        return aqiBand(aqi) == 1 ? 0xFF1A1A1A : 0xFFFFFFFF;
    }

    /** A gradient in the weather's colours, dark at the bottom like the rest of Home. */
    static Drawable backdrop(Report report) {
        int[] colours;
        switch (kind(report.code)) {
            case CLEAR:
                colours = report.day ? new int[]{0xFF1E4A7A, 0xFF050A14} : new int[]{0xFF0D1B3E, 0xFF020308};
                break;
            case CLOUDY:
                colours = new int[]{0xFF3B4654, 0xFF0C0F14};
                break;
            case FOG:
                colours = new int[]{0xFF5A6068, 0xFF101113};
                break;
            case RAIN:
                colours = new int[]{0xFF1E2C3D, 0xFF06090E};
                break;
            case SNOW:
                colours = new int[]{0xFF6F8497, 0xFF101820};
                break;
            default:
                colours = new int[]{0xFF2D2140, 0xFF07050C};
                break;
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours);
    }
}
