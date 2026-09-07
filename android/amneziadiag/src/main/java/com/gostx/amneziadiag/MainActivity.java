package com.gostx.amneziadiag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView resultView;
    private Button runButton;
    private Button copyButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        TextView title = new TextView(this);
        title.setText("Amnezia Diagnostic");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(20, 20, 24));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Türkiye mi, Çin mi? Telefon ayarını ve gerçek internet çıkışını ayrı ayrı ölçer. Test sırasında diğer VPN/proxy uygulamalarını kapat.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        runButton = new Button(this);
        runButton.setText("TESTİ BAŞLAT");
        runButton.setAllCaps(false);
        runButton.setTextSize(16);
        runButton.setOnClickListener(v -> runDiagnostics());
        root.addView(runButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        copyButton = new Button(this);
        copyButton.setText("RAPORU KOPYALA");
        copyButton.setAllCaps(false);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        copyParams.topMargin = dp(8);
        root.addView(copyButton, copyParams);

        resultView = new TextView(this);
        resultView.setText("Hazır.\n\nNot: Bu uygulama Amnezia'nın şifreli production API protokolünü taklit etmez. Bunun yerine backend'in göreceği kaynak IP ülkesini üç bağımsız servisten ölçer. App Store/Play Store bölgesi ayrı gösterilir.");
        resultView.setTextSize(15);
        resultView.setTextColor(Color.rgb(25, 25, 28));
        resultView.setTextIsSelectable(true);
        resultView.setGravity(Gravity.START);
        resultView.setPadding(0, dp(18), 0, dp(30));
        root.addView(resultView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void runDiagnostics() {
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        resultView.setText("Test ediliyor...\n\nIP ülke servisleri sırayla kontrol ediliyor. Bir servis Çin'de engelliyse diğerleri devam edecek.");

        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            report.append("=== Amnezia Diagnostic ===\n\n");

            boolean vpnActive = isVpnActive();
            report.append("VPN transport aktif: ").append(vpnActive ? "EVET" : "HAYIR").append('\n');
            report.append("Aktif bağlantı: ").append(activeTransport()).append('\n');
            report.append("Telefon locale ülkesi: ").append(localeCountry()).append('\n');
            report.append("SIM ülkesi: ").append(simCountry()).append('\n');
            report.append("Mobil ağ ülkesi: ").append(networkCountry()).append('\n');
            report.append("Saat dilimi: ").append(TimeZone.getDefault().getID()).append("\n\n");

            if (vpnActive) {
                report.append("UYARI: Android şu anda aktif bir VPN transport görüyor. Aşağıdaki IP ülkesi VPN çıkış ülkesi olabilir.\n\n");
            }

            List<GeoResult> geoResults = new ArrayList<>();
            geoResults.add(fetchIpWho());
            geoResults.add(fetchCountryIs());
            geoResults.add(fetchIpApi());

            report.append("--- Gerçek internet çıkışı ---\n");
            for (GeoResult r : geoResults) {
                report.append(r.source).append(": ");
                if (r.ok) {
                    report.append(r.countryCode);
                    if (!r.ip.isEmpty()) report.append(" | IP ").append(r.ip);
                    if (!r.extra.isEmpty()) report.append(" | ").append(r.extra);
                } else {
                    report.append("BAŞARISIZ | ").append(r.error);
                }
                report.append('\n');
            }

            String consensus = consensusCountry(geoResults);
            report.append("\nIP ülke sonucu: ").append(consensus.isEmpty() ? "BELİRLENEMEDİ" : consensus).append('\n');

            Reachability amnezia = testReachability("https://amnezia.org/");
            report.append("Amnezia.org erişimi: ")
                    .append(amnezia.ok ? "OK" : "BAŞARISIZ")
                    .append(" | ")
                    .append(amnezia.detail)
                    .append('\n');

            report.append("\n--- Yorum ---\n");
            if ("CN".equals(consensus)) {
                report.append("ÇIKIŞIN ÇİN. Android/mağaza/locale Türkiye görünse bile internet isteğin Çin IP'sinden çıkıyor. Amnezia'nın ilk ülke sınıflandırmasında CN görmesi beklenir.");
            } else if ("TR".equals(consensus)) {
                report.append("ÇIKIŞIN TÜRKİYE. Amnezia seni Türkiye sanıyorsa sebep büyük olasılıkla mağaza bölgesi değil, mevcut ağ/VPN çıkışının TR olması.");
            } else if (!consensus.isEmpty()) {
                report.append("ÇIKIŞ ÜLKESİ ").append(consensus).append(". Amnezia'nın bölge seçimini mağaza yerine bu IP çıkışına göre yapması beklenir.");
            } else {
                report.append("Ülke servislerinden yeterli cevap alınamadı. Sonucu kopyalayıp gönder; hangi isteklerin Çin'de engellendiğini oradan ayırabiliriz.");
            }

            report.append("\n\nBu APK bağlantı açmaz ve Amnezia Free sunucusu kullanmaz; sadece bölge/erişim teşhisi yapar.");

            lastReport = report.toString();
            runOnUiThread(() -> {
                resultView.setText(lastReport);
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
            });
        });
    }

    private GeoResult fetchIpWho() {
        try {
            JSONObject j = getJson("https://ipwho.is/");
            if (j.has("success") && !j.optBoolean("success", true)) {
                return GeoResult.fail("ipwho.is", j.optString("message", "service error"));
            }
            String isp = "";
            JSONObject connection = j.optJSONObject("connection");
            if (connection != null) isp = connection.optString("isp", "");
            return GeoResult.ok("ipwho.is", j.optString("country_code", ""), j.optString("ip", ""), isp);
        } catch (Exception e) {
            return GeoResult.fail("ipwho.is", shortError(e));
        }
    }

    private GeoResult fetchCountryIs() {
        try {
            JSONObject j = getJson("https://api.country.is/");
            return GeoResult.ok("api.country.is", j.optString("country", ""), j.optString("ip", ""), "");
        } catch (Exception e) {
            return GeoResult.fail("api.country.is", shortError(e));
        }
    }

    private GeoResult fetchIpApi() {
        try {
            JSONObject j = getJson("https://ipapi.co/json/");
            String error = j.optString("reason", "");
            if (j.optBoolean("error", false)) return GeoResult.fail("ipapi.co", error.isEmpty() ? "service error" : error);
            return GeoResult.ok("ipapi.co", j.optString("country_code", ""), j.optString("ip", ""), j.optString("org", ""));
        } catch (Exception e) {
            return GeoResult.fail("ipapi.co", shortError(e));
        }
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6500);
        c.setReadTimeout(6500);
        c.setRequestProperty("User-Agent", "GostX-Amnezia-Diagnostic/1.0 Android");
        c.setRequestProperty("Accept", "application/json");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();
        if (code < 200 || code >= 400) throw new Exception("HTTP " + code + " " + trim(body, 80));
        return new JSONObject(body);
    }

    private Reachability testReachability(String url) {
        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6500);
            c.setReadTimeout(6500);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "GostX-Amnezia-Diagnostic/1.0 Android");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - start;
            return new Reachability(code >= 200 && code < 500, "HTTP " + code + " | " + ms + " ms");
        } catch (Exception e) {
            return new Reachability(false, shortError(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String consensusCountry(List<GeoResult> results) {
        Map<String, Integer> counts = new HashMap<>();
        for (GeoResult r : results) {
            if (!r.ok) continue;
            String cc = r.countryCode == null ? "" : r.countryCode.trim().toUpperCase(Locale.ROOT);
            if (cc.length() != 2) continue;
            counts.put(cc, counts.getOrDefault(cc, 0) + 1);
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return bestCount >= 2 ? best : "";
    }

    private boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String activeTransport() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps == null) return "YOK/BELİRSİZ";
            List<String> names = new ArrayList<>();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names.add("VPN");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.add("Wi-Fi");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.add("Mobil");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.add("Ethernet");
            return names.isEmpty() ? "Diğer" : join(names, " + ");
        } catch (Exception e) {
            return "BELİRSİZ";
        }
    }

    private String localeCountry() {
        try {
            String c = getResources().getConfiguration().getLocales().get(0).getCountry();
            return emptyAsUnknown(c);
        } catch (Exception e) {
            return "BELİRSİZ";
        }
    }

    private String simCountry() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            return emptyAsUnknown(tm.getSimCountryIso()).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "BELİRSİZ";
        }
    }

    private String networkCountry() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            return emptyAsUnknown(tm.getNetworkCountryIso()).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "BELİRSİZ";
        }
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Amnezia Diagnostic", lastReport));
        Toast.makeText(this, "Rapor kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String shortError(Exception e) {
        String s = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) s += ": " + e.getMessage().trim();
        return trim(s, 140);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String emptyAsUnknown(String s) {
        return s == null || s.trim().isEmpty() ? "BELİRSİZ" : s.trim();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class GeoResult {
        final String source;
        final boolean ok;
        final String countryCode;
        final String ip;
        final String extra;
        final String error;

        private GeoResult(String source, boolean ok, String countryCode, String ip, String extra, String error) {
            this.source = source;
            this.ok = ok;
            this.countryCode = countryCode == null ? "" : countryCode.toUpperCase(Locale.ROOT);
            this.ip = ip == null ? "" : ip;
            this.extra = extra == null ? "" : extra;
            this.error = error == null ? "" : error;
        }

        static GeoResult ok(String source, String countryCode, String ip, String extra) {
            return new GeoResult(source, true, countryCode, ip, extra, "");
        }

        static GeoResult fail(String source, String error) {
            return new GeoResult(source, false, "", "", "", error);
        }
    }

    private static final class Reachability {
        final boolean ok;
        final String detail;

        Reachability(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }
}
