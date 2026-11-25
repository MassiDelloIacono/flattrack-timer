package it.apexlap.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * ApexLap per Android: mostra l'app web (la stessa di GitHub Pages, così si aggiorna da sola)
 * e gestisce in modo nativo il Bluetooth del GPS esterno, senza i limiti di Chrome:
 * pacchetti grandi (MTU 185) e connessione veloce, così arrivano tutti i 25 punti al secondo.
 */
@SuppressLint({"MissingPermission", "SetJavaScriptEnabled"})
public class MainActivity extends Activity {

    static final String APP_URL = "https://massidelloiacono.github.io/flattrack-timer/";
    static final UUID SVC = UUID.fromString("f1a70001-5e4b-4c2a-9d6f-7a3c0de0f00d");
    static final UUID POS = UUID.fromString("f1a70002-5e4b-4c2a-9d6f-7a3c0de0f00d");
    static final UUID CTRL = UUID.fromString("f1a70003-5e4b-4c2a-9d6f-7a3c0de0f00d");
    static final UUID INFO = UUID.fromString("f1a70004-5e4b-4c2a-9d6f-7a3c0de0f00d");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int REQ_PERMS = 1;

    WebView web;
    final Handler main = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothLeScanner scanner;
    boolean wanted = false;     // l'app vuole il GPS esterno collegato
    boolean scanning = false;
    boolean ready = false;      // notifiche attive
    int failures = 0;
    int pendingMode = -1;
    final ArrayDeque<BluetoothGattDescriptor> cccdQueue = new ArrayDeque<>();
    GeolocationPermissions.Callback geoCallback;
    String geoOrigin;

    // ------------------------------------------------------------------ avvio
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("apexlap", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setBackgroundColor(0xFF0F0F10);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("massidelloiacono.github.io".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    cb.invoke(origin, true, true);
                } else {
                    geoCallback = cb;
                    geoOrigin = origin;
                    askPermissions();
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "ApexNative");
        setContentView(web);
        hideBars();
        web.loadUrl(APP_URL);
        askPermissions();
    }

    void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    // il tasto indietro non chiude l'app (si perderebbe il turno in corso): la manda in secondo piano
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        wanted = false;
        stopScan();
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) { } gatt = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permessi
    boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    String[] neededPermissions() {
        List<String> l = new ArrayList<>();
        l.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return l.toArray(new String[0]);
    }

    boolean blePermsOk() {
        for (String p : neededPermissions()) if (!has(p)) return false;
        return true;
    }

    void askPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) if (!has(p)) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (geoCallback != null) {
            geoCallback.invoke(geoOrigin, has(Manifest.permission.ACCESS_FINE_LOCATION), true);
            geoCallback = null;
        }
        if (wanted) startBle();
    }

    // ------------------------------------------------------------------ ponte con l'app web
    class Bridge {
        @JavascriptInterface
        public void connect() {
            main.post(() -> { wanted = true; startBle(); });
        }

        @JavascriptInterface
        public void disconnect() {
            main.post(() -> {
                wanted = false;
                stopScan();
                if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } gatt = null; }
                ready = false;
            });
        }

        @JavascriptInterface
        public void setMode(int m) {
            main.post(() -> { pendingMode = m; writeMode(); });
        }

        @JavascriptInterface
        public String version() {
            return "android";
        }
    }

    void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    void state(String s) {
        js("window.apexBle&&apexBle.state('" + s + "')");
    }

    static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 15];
            c[i * 2 + 1] = HEX[b[i] & 15];
        }
        return new String(c);
    }

    // ------------------------------------------------------------------ Bluetooth
    void startBle() {
        if (!wanted || gatt != null || scanning) return;
        if (adapter == null) { state("btoff"); return; }
        if (!blePermsOk()) { state("noperm"); askPermissions(); return; }
        if (!adapter.isEnabled()) {
            state("btoff");
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
            main.postDelayed(this::startBle, 4000);
            return;
        }
        String addr = prefs.getString("device", null);
        if (addr != null && failures < 2) {
            connectTo(adapter.getRemoteDevice(addr));
        } else {
            scan();
        }
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            if (!scanning) return;
            stopScan();
            failures = 0;
            connectTo(r.getDevice());
        }

        @Override
        public void onScanFailed(int err) {
            scanning = false;
            state("notfound");
            if (wanted) main.postDelayed(MainActivity.this::startBle, 5000);
        }
    };

    void scan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { state("btoff"); return; }
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build());
        ScanSettings set = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        state("scan");
        scanner.startScan(filters, set, scanCb);
        main.postDelayed(() -> {
            if (scanning) {
                stopScan();
                state("notfound");
                if (wanted) main.postDelayed(this::startBle, 5000);
            }
        }, 15000);
    }

    void stopScan() {
        if (scanning && scanner != null) { try { scanner.stopScan(scanCb); } catch (Exception ignored) { } }
        scanning = false;
    }

    void connectTo(BluetoothDevice d) {
        ready = false;
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // connessione veloce (11-15 ms) e pacchetti grandi: è quello che Chrome non permette
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    if (!g.requestMtu(185)) g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasReady = ready;
                    ready = false;
                    try { g.close(); } catch (Exception ignored) { }
                    if (g == gatt) gatt = null;
                    if (!wasReady) failures++;
                    state("lost");
                    if (wanted) main.postDelayed(MainActivity.this::startBle, 1500);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(g::discoverServices);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                BluetoothGattService s = g.getService(SVC);
                if (s == null) { g.disconnect(); return; }
                prefs.edit().putString("device", g.getDevice().getAddress()).apply();
                failures = 0;
                cccdQueue.clear();
                for (UUID u : new UUID[]{INFO, POS}) {
                    BluetoothGattCharacteristic c = s.getCharacteristic(u);
                    if (c == null) continue;
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) cccdQueue.add(d);
                }
                writeNextCccd(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> writeNextCccd(g));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            if (POS.equals(c.getUuid())) js("window.apexBle&&apexBle.pos('" + hex(v) + "')");
            else if (INFO.equals(c.getUuid())) js("window.apexBle&&apexBle.info('" + hex(v) + "')");
        }
    };

    void writeNextCccd(BluetoothGatt g) {
        BluetoothGattDescriptor d = cccdQueue.poll();
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(d)) main.postDelayed(() -> { cccdQueue.addFirst(d); writeNextCccd(g); }, 100);
            return;
        }
        ready = true;
        state("on");
        writeMode();
    }

    void writeMode() {
        if (!ready || gatt == null || pendingMode < 0) return;
        BluetoothGattService s = gatt.getService(SVC);
        if (s == null) return;
        BluetoothGattCharacteristic c = s.getCharacteristic(CTRL);
        if (c == null) return;
        c.setValue(new byte[]{(byte) pendingMode});
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(c);
    }
}
