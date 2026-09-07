package com.gostx.amneziadiag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final String GATEWAY = "http://gw.amnezia.org:80/v1/services";

    // Public RSA key extracted from the official AmneziaVPN 5.0.1.5 Android APK
    // (official APK SHA-256: 78dcdcbba6c578037e597b94e781f7873b0f4efe87d8e09574649894af289986).
    // This is a public encryption key, not a credential or private key.
    private static final String PROD_PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAj5mxl/4DL3Sk89ntxs5G\n" +
            "X3JawGQWIoq6rvNkOzNGuNgedNS2+pi6hZl3Izl1Io9om4KiUlMT6mgLO1hTr9q+\n" +
            "s7CYhlvroFA7ErucF+9L+7FCt0Igi0kIK/R2/vxd/2HaUrorn/aSvvutkYwbfxqW\n" +
            "SwtzE+RuBeDWGvEt937OW0oqYONPYv9E4T56Dz/EZ6v2t8ejAnKLbGD/GocMmipK\n" +
            "7etFSiSMAB2RmaztqTq4NleBepfO80XpYlW9pCSXuHcE8wxHczkzxsbyMAMsG/K3\n" +
            "vUQY6qPtohqqzSSBwa/8u2ptNHBeor7l7DdYXeR/Nqcc4z92VUkZ5lOVR4evkS5V\n" +
            "/wQqp5tnOJEj3NjUhEhXFoNEapbZd1bh6iQoUk7jC1TdvKJ/nPKGZAsHRpr0rNKz\n" +
            "fx/N/Oo6lr2yh/+ps6VxTkbPmB6E85WOO3UvjImZUY0XQdBjWle/4iJLdEC77Nr0\n" +
            "jXhdgeypucy6jkB6iBHMeVMlrNMEV7UxoBR/cCNx55zu/8sml5ByiDvCDT7sRomN\n" +
            "NgVt5S/FaVjYuzFUifJ12ToChXFgESKFmuso7WluEaWvMIGREdrMrKQKHfYLOzWF\n" +
            "2B5ZJDqw4o03fU4J/6rw61M1b+rjVpXMjPnzc2A+RgcjTvXv955gfZkwe4lt5wk/\n" +
            "3j8zMVo3+zLrMTAaEeIUM0UCAwEAAQ==\n" +
            "-----END PUBLIC KEY-----";

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
        title.setText("Amnezia Gateway Diagnostic v2");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(20, 20, 24));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bu sürüm artık tahmin yapmıyor. Resmi Amnezia istemcisinin kullandığı şifreli /v1/services API çağrısını doğrudan yapar. Test sırasında diğer VPN/proxy uygulamalarını kapat.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        runButton = new Button(this);
        runButton.setText("GERÇEK AMNEZIA API TESTİ");
        runButton.setAllCaps(false);
        runButton.setTextSize(16);
        runButton.setOnClickListener(v -> runProbe());
        root.addView(runButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        copyButton = new Button(this);
        copyButton.setText("RAPORU KOPYALA");
        copyButton.setAllCaps(false);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        root.addView(copyButton, cp);

        resultView = new TextView(this);
        resultView.setText("Hazır.\n\nBeklenen alanlar:\n• Amnezia user_country_code\n• Amnezia Free mevcut mu\n• is_available\n• service_protocol\n• servis sayısı");
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

    private void runProbe() {
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        resultView.setText("Amnezia production gateway sorgulanıyor...\n\nRSA + AES isteği hazırlanıyor ve /v1/services cevabı decrypt ediliyor.");

        executor.execute(() -> {
            String report;
            try {
                report = queryServices();
            } catch (Exception e) {
                report = "=== Amnezia Gateway Diagnostic v2 ===\n\nBAŞARISIZ\n" + shortError(e) +
                        "\n\nNot: Diğer VPN/proxy'leri kapatıp tekrar dene. Bu çağrı doğrudan gw.amnezia.org:80 adresine gider.";
            }
            lastReport = report;
            runOnUiThread(() -> {
                resultView.setText(lastReport);
                runButton.setEnabled(true);
                copyButton.setEnabled(true);
            });
        });
    }

    private String queryServices() throws Exception {
        byte[] aesKey = randomBytes(32);
        byte[] aesIv = randomBytes(32);
        byte[] aesSalt = randomBytes(8);

        JSONObject keyPayload = new JSONObject();
        keyPayload.put("aes_key", b64(aesKey));
        keyPayload.put("aes_iv", b64(aesIv));
        keyPayload.put("aes_salt", b64(aesSalt));

        JSONObject apiPayload = new JSONObject();
        apiPayload.put("os_version", "android");
        apiPayload.put("app_version", "5.0.1.5");
        apiPayload.put("cli_name", "AmneziaVPN");
        apiPayload.put("app_language", Locale.getDefault().getLanguage());
        apiPayload.put("installation_uuid", installationUuid());

        byte[] encryptedKeyPayload = rsaEncrypt(
                keyPayload.toString().getBytes(StandardCharsets.UTF_8),
                loadPublicKey(PROD_PUBLIC_KEY));
        byte[] encryptedApiPayload = aesCrypt(
                Cipher.ENCRYPT_MODE,
                apiPayload.toString().getBytes(StandardCharsets.UTF_8),
                aesKey,
                aesIv);

        JSONObject requestBody = new JSONObject();
        requestBody.put("key_payload", b64(encryptedKeyPayload));
        requestBody.put("api_payload", b64(encryptedApiPayload));
        byte[] requestBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);

        long started = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(GATEWAY).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(16000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Client-Request-ID", UUID.randomUUID().toString());
        c.setRequestProperty("User-Agent", "AmneziaVPN/5.0.1.5 Android");
        c.setFixedLengthStreamingMode(requestBytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(requestBytes);
        }

        int httpCode = c.getResponseCode();
        InputStream stream = httpCode >= 200 && httpCode < 400 ? c.getInputStream() : c.getErrorStream();
        byte[] encryptedResponse = readAllBytes(stream);
        long elapsed = System.currentTimeMillis() - started;
        c.disconnect();

        if (encryptedResponse.length == 0) {
            throw new Exception("Gateway HTTP " + httpCode + " ama cevap gövdesi boş (" + elapsed + " ms)");
        }

        byte[] plain = aesCrypt(Cipher.DECRYPT_MODE, encryptedResponse, aesKey, aesIv);
        String text = new String(plain, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);

        String country = root.optString("user_country_code", "BELİRSİZ");
        JSONArray services = root.optJSONArray("services");
        int serviceCount = services == null ? 0 : services.length();

        JSONObject free = null;
        StringBuilder serviceTypes = new StringBuilder();
        if (services != null) {
            for (int i = 0; i < services.length(); i++) {
                JSONObject s = services.optJSONObject(i);
                if (s == null) continue;
                String type = s.optString("service_type", "?");
                if (serviceTypes.length() > 0) serviceTypes.append(", ");
                serviceTypes.append(type);
                if ("amnezia-free".equals(type)) free = s;
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("=== Amnezia Gateway Diagnostic v2 ===\n\n");
        report.append("Gateway: ").append(GATEWAY).append('\n');
        report.append("HTTP: ").append(httpCode).append('\n');
        report.append("Yanıt süresi: ").append(elapsed).append(" ms\n");
        report.append("Decrypt: OK\n\n");
        report.append("Amnezia user_country_code: ").append(country).append('\n');
        report.append("Servis sayısı: ").append(serviceCount).append('\n');
        report.append("Servisler: ").append(serviceTypes.length() == 0 ? "YOK" : serviceTypes).append("\n\n");

        report.append("--- Amnezia Free ---\n");
        if (free == null) {
            report.append("Free servis cevabında YOK\n");
        } else {
            report.append("Free servis: VAR\n");
            report.append("is_available: ").append(free.optBoolean("is_available", true)).append('\n');
            report.append("service_protocol: ").append(free.optString("service_protocol", "BELİRSİZ")).append('\n');
            JSONArray countries = free.optJSONArray("available_countries");
            if (countries != null) report.append("available_countries: ").append(countries.toString()).append('\n');
            JSONObject info = free.optJSONObject("service_info");
            if (info != null && info.has("api_endpoint")) {
                report.append("api_endpoint mevcut: EVET\n");
            }
        }

        report.append("\n--- Sonuç ---\n");
        if (free != null && free.optBoolean("is_available", true)) {
            report.append("AMNEZIA FREE BU AĞ İÇİN SUNULUYOR. Sonraki adım Free config'i aynı gateway'den alıp AWG bağlantısını test etmek.");
        } else {
            report.append("AMNEZIA FREE BU AĞ İÇİN SUNULMUYOR veya servis listesinde yok.");
        }

        report.append("\n\nHam cevap özeti: ").append(trim(text, 1200));
        return report.toString();
    }

    private String installationUuid() {
        String value = getSharedPreferences("diag", MODE_PRIVATE).getString("installation_uuid", "");
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString();
            getSharedPreferences("diag", MODE_PRIVATE).edit().putString("installation_uuid", value).apply();
        }
        return value;
    }

    private static PublicKey loadPublicKey(String pem) throws Exception {
        String clean = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(clean, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] rsaEncrypt(byte[] plain, PublicKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plain);
    }

    private static byte[] aesCrypt(int mode, byte[] input, byte[] key, byte[] iv32) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(Arrays.copyOf(iv32, 16));
        cipher.init(mode, secretKey, iv);
        return cipher.doFinal(input);
    }

    private static byte[] randomBytes(int size) {
        byte[] out = new byte[size];
        new SecureRandom().nextBytes(out);
        return out;
    }

    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Amnezia Gateway Diagnostic v2", lastReport));
        Toast.makeText(this, "Rapor kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private static String shortError(Exception e) {
        String s = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) s += ": " + e.getMessage().trim();
        return trim(s, 700);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
