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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
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
    int mtu = 23;               // dimensione dei pacchetti concordata col GPS
    String mtuNote = "";
    final ArrayDeque<BluetoothGattDescriptor> cccdQueue = new ArrayDeque<>();
    GeolocationPermissions.Callback geoCallback;
    // cavo USB: la ESP32 è collegata al telefono e manda le posizioni anche sul cavo
    static final int ESP_VID = 0x303A;
    static final String ACTION_USB_PERM = "it.apexlap.app.USB_PERMISSION";
    UsbManager usbManager;
    UsbDeviceConnection usbConn;
    UsbInterface usbIf;
    UsbEndpoint usbIn, usbOut;
    volatile boolean usbRun = false;
    boolean usbActive = false;
    boolean usbAsking = false;
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

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        IntentFilter uf = new IntentFilter();
        uf.addAction(ACTION_USB_PERM);
        uf.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        uf.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, uf, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, uf);

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

    // aperta da Android quando si collega la ESP32 al cavo
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (wanted) startUsb();
    }

    @Override
    protected void onDestroy() {
        wanted = false;
        stopUsb(false);
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
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
            main.post(() -> { wanted = true; if (!startUsb()) startBle(); });
        }

        @JavascriptInterface
        public void disconnect() {
            main.post(() -> {
                wanted = false;
                stopUsb(false);
                stopScan();
                if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } gatt = null; }
                ready = false;
            });
        }

        @JavascriptInterface
        public void setMode(int m) {
            main.post(() -> { pendingMode = m; if (usbActive) usbWriteMode(); else writeMode(); });
        }

        @JavascriptInterface
        public String version() {
            return "android";
        }

        @JavascriptInterface
        public String diag() {
            if (usbActive) return "via cavo USB";
            return "Bluetooth, pacchetto " + mtu + " byte (" + mtuNote + ")";
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
        if (!wanted || usbActive || gatt != null || scanning) return;
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
                    // pacchetti grandi e connessione veloce (è quello che Chrome non permette),
                    // una richiesta alla volta: Android ignora le richieste sovrapposte
                    mtu = 23;
                    mtuNote = "chiesto";
                    main.postDelayed(() -> {
                        if (g != gatt) return;
                        if (!g.requestMtu(185)) {
                            mtuNote = "ritento";
                            main.postDelayed(() -> {
                                if (g != gatt) return;
                                if (!g.requestMtu(185)) { mtuNote = "rifiutato"; afterMtu(g); }
                            }, 800);
                        }
                    }, 400);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasReady = ready;
                    ready = false;
                    try { g.close(); } catch (Exception ignored) { }
                    if (g == gatt) gatt = null;
                    if (usbActive) return;   // va il cavo: il Bluetooth non serve
                    if (!wasReady) failures++;
                    state("lost");
                    if (wanted) main.postDelayed(MainActivity.this::startBle, 1500);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int m, int status) {
            main.post(() -> {
                mtu = m;
                mtuNote = status == BluetoothGatt.GATT_SUCCESS ? "ok" : "errore " + status;
                afterMtu(g);
            });
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
            if (v == null || usbActive) return;
            if (POS.equals(c.getUuid())) js("window.apexBle&&apexBle.pos('" + hex(v) + "')");
            else if (INFO.equals(c.getUuid())) js("window.apexBle&&apexBle.info('" + hex(v) + "')");
        }
    };

    void afterMtu(BluetoothGatt g) {
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        main.postDelayed(() -> { if (g == gatt) g.discoverServices(); }, 400);
    }

    void writeNextCccd(BluetoothGatt g) {
        BluetoothGattDescriptor d = cccdQueue.poll();
        if (d != null) {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(d)) main.postDelayed(() -> { cccdQueue.addFirst(d); writeNextCccd(g); }, 100);
            return;
        }
        ready = true;
        if (usbActive) { g.disconnect(); return; }
        state("on");
        writeMode();
    }

    void writeMode() {
        if (!ready || gatt == null || pendingMode < 0) return;
        BluetoothGattService s = gatt.getService(SVC);
        if (s == null) return;
        BluetoothGattCharacteristic c = s.getCharacteristic(CTRL);
        if (c == null) return;
        // modalità + dimensione dei pacchetti concordata, così il GPS ci mette dentro più posizioni
        c.setValue(new byte[]{(byte) pendingMode, (byte) (mtu & 255), (byte) (mtu >> 8)});
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(c);
    }
    // ------------------------------------------------------------------ cavo USB
    final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERM.equals(a)) {
                usbAsking = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && wanted) startUsb();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (wanted) main.postDelayed(MainActivity.this::startUsb, 500);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && d.getVendorId() == ESP_VID) stopUsb(true);
            }
        }
    };

    UsbDevice findEsp() {
        if (usbManager == null) return null;
        for (UsbDevice d : usbManager.getDeviceList().values()) if (d.getVendorId() == ESP_VID) return d;
        return null;
    }

    /** Apre il cavo se la ESP32 è collegata. Ritorna true se il cavo è (o sta per essere) in uso. */
    boolean startUsb() {
        if (usbActive) return true;
        UsbDevice d = findEsp();
        if (d == null) return false;
        if (!usbManager.hasPermission(d)) {
            if (!usbAsking) {
                usbAsking = true;
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERM).setPackage(getPackageName()), flags);
                usbManager.requestPermission(d, pi);
            }
            return true;
        }
        // interfaccia dati CDC: un endpoint bulk in ingresso e uno in uscita
        UsbInterface found = null;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < d.getInterfaceCount() && found == null; i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() != UsbConstants.USB_CLASS_CDC_DATA) continue;
            UsbEndpoint ei = null, eo = null;
            for (int e = 0; e < it.getEndpointCount(); e++) {
                UsbEndpoint ep = it.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep; else eo = ep;
            }
            if (ei != null) { found = it; in = ei; out = eo; }
        }
        if (found == null) return false;
        UsbDeviceConnection c = usbManager.openDevice(d);
        if (c == null) return false;
        if (!c.claimInterface(found, true)) { c.close(); return false; }
        usbConn = c;
        usbIf = found;
        usbIn = in;
        usbOut = out;
        usbActive = true;
        usbRun = true;
        // il Bluetooth non serve più
        stopScan();
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } gatt = null; }
        ready = false;
        Thread t = new Thread(this::usbLoop, "apexlap-usb");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        state("usb");
        usbWriteMode();
        return true;
    }

    void usbLoop() {
        final UsbDeviceConnection c = usbConn;
        final UsbEndpoint in = usbIn;
        final StringBuilder pos = new StringBuilder();
        UsbFrameParser parser = new UsbFrameParser((type, data) -> {
            if (type == 1) pos.append(hex(data));
            else if (type == 2) js("window.apexBle&&apexBle.info('" + hex(data) + "')");
        });
        byte[] buf = new byte[1024];
        while (usbRun) {
            int n = c.bulkTransfer(in, buf, buf.length, 250);
            if (n > 0) {
                parser.feed(buf, n);
                if (pos.length() > 0) {
                    js("window.apexBle&&apexBle.pos('" + pos + "')");
                    pos.setLength(0);
                }
            } else if (n < 0) {
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }
    }

    void stopUsb(boolean lost) {
        if (!usbActive) return;
        usbRun = false;
        usbActive = false;
        try { usbConn.releaseInterface(usbIf); } catch (Exception ignored) { }
        try { usbConn.close(); } catch (Exception ignored) { }
        usbConn = null;
        if (lost) {
            state("lost");
            if (wanted) main.postDelayed(this::startBle, 1000);
        }
    }

    void usbWriteMode() {
        if (!usbActive || usbOut == null || pendingMode < 0) return;
        final UsbDeviceConnection c = usbConn;
        final UsbEndpoint out = usbOut;
        final byte[] cmd = new byte[]{'M', (byte) ('0' + pendingMode)};
        new Thread(() -> { try { c.bulkTransfer(out, cmd, cmd.length, 500); } catch (Exception ignored) { } }).start();
    }
}
