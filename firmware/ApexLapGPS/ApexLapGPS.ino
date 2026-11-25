// ApexLapGPS — ricevitore GPS esterno per l'app ApexLap
// Scheda: ESP32-C3 SuperMini.  GPS: u-blox M10 (es. HGLRC M100-5883) su GPIO20 (RX) / GPIO21 (TX).
// Il telefono alimenta la scheda dalla presa USB-C (OTG); i dati vanno al telefono via Bluetooth Low Energy.
//
// Cosa fa:
//  1. trova da solo a che velocità parla il GPS e lo porta a 115200 baud;
//  2. lo configura (25 Hz solo GPS, oppure 10 Hz GPS+Galileo) e gli fa mandare solo UBX-NAV-PVT;
//  3. manda le posizioni all'app, fino a 4 per pacchetto Bluetooth (20 byte l'una, ognuna con l'ora GPS esatta);
//  4. se il GPS tace per 3 secondi lo riconfigura da capo.
// LED blu della scheda: lampeggio lento = nessun telefono collegato; acceso fisso = collegato e GPS con fix;
//                       lampeggio veloce = collegato ma GPS ancora senza fix.

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#if __has_include(<BLE2902.h>)
#include <BLE2902.h>
#define FT_HAS_2902 1
#endif
#include <Preferences.h>
#if defined(CONFIG_BLUEDROID_ENABLED)
#include <esp_gap_ble_api.h>
#endif
#include "ftgps_core.h"

using namespace ftgps;

static const int PIN_GPS_RX = 20;   // arriva il TX del GPS
static const int PIN_GPS_TX = 21;   // va all'RX del GPS
static const int PIN_LED    = 8;    // LED della SuperMini (acceso a livello basso)
static const uint32_t GPS_BAUD = 115200;

HardwareSerial GPS(1);
Preferences prefs;
UbxParser ubx;

BLEServer* server = nullptr;
BLECharacteristic* chPos = nullptr;
BLECharacteristic* chInfo = nullptr;
volatile bool connected = false;
volatile int pendingMode = -1;      // modalità chiesta dall'app, applicata nel loop
uint16_t connId = 0;                // connessione Bluetooth attiva (serve per leggere l'MTU)
uint8_t batch[80];                  // posizioni in attesa di partire insieme
uint8_t nBatch = 0;
Pvt prevPvt;                        // posizione in attesa della compagna (pacchetto doppio da 19 byte)
bool havePrev = false;
uint8_t peerAddr[6];
uint32_t connAtMs = 0;
bool paramsAsked = false;
volatile uint16_t connIntUnits = 0; // intervallo di connessione attuale (unità da 1,25 ms)

uint8_t mode = MODE_25HZ_GPS;
uint8_t cfgResult = 0;              // 0 sconosciuto, 1 ACK, 2 NAK
uint32_t lastPvtMs = 0, lastInfoMs = 0, pvtCount = 0;
uint8_t hzMeasured = 0, lastSats = 0;
bool lastFix = false;

// ---------------------------------------------------------------- Bluetooth
class ServerCb : public BLEServerCallbacks {
#if defined(CONFIG_BLUEDROID_ENABLED)
  void onConnect(BLEServer* s, esp_ble_gatts_cb_param_t* param) override {
    connected = true;
    connId = param->connect.conn_id;
    nBatch = 0;
    havePrev = false;
    memcpy(peerAddr, param->connect.remote_bda, 6);
    connAtMs = millis();
    connIntUnits = param->connect.conn_params.interval;
    paramsAsked = false;
    // intervallo di connessione corto (7,5-20 ms) per reggere 25 punti al secondo
    s->updateConnParams(param->connect.remote_bda, 6, 16, 0, 400);
  }
#else
  void onConnect(BLEServer* s) override { connected = true; }
#endif
  void onDisconnect(BLEServer* s) override {
    connected = false;
    delay(100);
    BLEDevice::startAdvertising();   // torna visibile per riconnettersi
  }
};

class CtrlCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();
    if (v.length() >= 1) {
      uint8_t m = (uint8_t)v[0];
      if (m == MODE_10HZ_MULTI || m == MODE_25HZ_GPS) pendingMode = m;
    }
  }
};

#if defined(CONFIG_BLUEDROID_ENABLED)
// Il telefono comunica l'intervallo di connessione che ha accettato: lo mando all'app per la diagnostica.
void gapEvent(esp_gap_ble_cb_event_t ev, esp_ble_gap_cb_param_t* p) {
  if (ev == ESP_GAP_BLE_UPDATE_CONN_PARAMS_EVT) connIntUnits = p->update_conn_params.conn_int;
}

// Dopo il collegamento richiedo un intervallo di 15 ms: molti Android rifiutano la richiesta fatta subito.
void askFastConnection() {
  esp_ble_conn_update_params_t cp;
  memcpy(cp.bda, peerAddr, 6);
  cp.min_int = 12;   // 15 ms
  cp.max_int = 12;
  cp.latency = 0;
  cp.timeout = 400;  // 4 s
  esp_ble_gap_update_conn_params(&cp);
}
#endif

// Due posizioni consecutive (40 ms) in 19 byte, per i telefoni che accettano solo pacchetti da 20 byte:
//  0 u32 ora GPS della prima   4 i32 lat   8 i32 lon   12 u16 velocità cm/s
// 14 i16 spostamento lat della seconda (1e-7°)   16 i16 spostamento lon
// 18 u8 bit7: seconda dopo 100 ms (altrimenti 40 ms); bit0-6 precisione in dm (127 = senza fix)
bool packPair(const Pvt& a, const Pvt& b, uint8_t* out) {
  int32_t dlat = b.lat - a.lat, dlon = b.lon - a.lon;
  bool okA = a.fixOk && a.fixType >= 3, okB = b.fixOk && b.fixType >= 3;
  uint32_t gap = b.iTOW - a.iTOW;
  if ((gap != 40 && gap != 100) || okA != okB || dlat < -32768 || dlat > 32767 || dlon < -32768 || dlon > 32767) return false;
  wr32(out + 0, a.iTOW);
  wr32(out + 4, (uint32_t)a.lat);
  wr32(out + 8, (uint32_t)a.lon);
  wr16(out + 12, clamp16(a.gSpeedMmS / 10));
  wr16(out + 14, (uint16_t)(int16_t)dlat);
  wr16(out + 16, (uint16_t)(int16_t)dlon);
  uint32_t dm = (a.hAccMm + 50) / 100;
  out[18] = (gap == 100 ? 0x80 : 0) | (okA ? (dm > 126 ? 126 : dm) : 127);
  return true;
}

void sendSingle(const Pvt& p) {
  uint8_t pkt[20];
  packPos(p, pkt);
  chPos->setValue(pkt, 20);
  chPos->notify();
}

void setupBle() {
  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(185);            // pacchetti più grandi: più posizioni per volta
#if defined(CONFIG_BLUEDROID_ENABLED)
  BLEDevice::setCustomGapHandler(gapEvent);
#endif
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService* svc = server->createService(UUID_SERVICE);

  chPos = svc->createCharacteristic(UUID_POS, BLECharacteristic::PROPERTY_NOTIFY);
  chInfo = svc->createCharacteristic(UUID_INFO, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  BLECharacteristic* chCtrl = svc->createCharacteristic(
      UUID_CTRL, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ);
#ifdef FT_HAS_2902
  chPos->addDescriptor(new BLE2902());
  chInfo->addDescriptor(new BLE2902());
#endif
  chCtrl->setCallbacks(new CtrlCb());
  uint8_t m = mode;
  chCtrl->setValue(&m, 1);
  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(UUID_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------- GPS
void sendUbx(uint8_t cls, uint8_t id, const uint8_t* p, uint16_t len) {
  uint8_t frame[8 + 160];
  int n = ubxFrame(cls, id, p, len, frame);
  GPS.write(frame, n);
  GPS.flush();
}

void sendValset(const KV* kv, int n) {
  uint32_t k[16], v[16];
  for (int i = 0; i < n; i++) { k[i] = kv[i].k; v[i] = kv[i].v; }
  uint8_t p[4 + 16 * 8];
  int len = valsetPayload(k, v, n, p);
  sendUbx(0x06, 0x8A, p, len);
}

// Ascolta per un po' e dice se a questa velocità arrivano messaggi sensati (UBX valido o frase NMEA).
bool gpsTalksAt(uint32_t baud, uint32_t ms) {
  GPS.updateBaudRate(baud);
  delay(20);
  while (GPS.available()) GPS.read();
  UbxParser p;
  NmeaSniffer nmea;
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    while (GPS.available()) {
      uint8_t c = GPS.read();
      if (p.feed(c) || nmea.feed(c)) return true;
    }
    delay(2);
  }
  return false;
}

void applyMode(uint8_t m) {
  KV kv[8];
  int n = signalConfig((Mode)m, kv);
  sendValset(kv, n);
  delay(1200);                      // il ricevitore si riavvia dopo il cambio dei satelliti
  n = rateConfig((Mode)m, kv);
  sendValset(kv, n);
  delay(100);
  mode = m;
  Serial.printf("[gps] modalita %u: misura ogni %u ms\n", m, measPeriodMs((Mode)m));
}

void configureGps() {
  digitalWrite(PIN_LED, LOW);
  const uint32_t bauds[] = {GPS_BAUD, 38400, 9600, 57600, 230400, 460800};
  uint32_t found = 0;
  for (uint32_t b : bauds) {
    if (gpsTalksAt(b, 1300)) { found = b; break; }
  }
  Serial.printf("[gps] trovato a %lu baud\n", (unsigned long)found);
  KV kv = {CFG_UART1_BAUDRATE, GPS_BAUD};
  if (found && found != GPS_BAUD) {
    GPS.updateBaudRate(found);
    sendValset(&kv, 1);
    delay(80);
  } else if (!found) {
    // nessuna risposta: provo a portarlo a 115200 da tutte le velocità di fabbrica
    for (uint32_t b : bauds) { GPS.updateBaudRate(b); delay(20); sendValset(&kv, 1); delay(60); }
  }
  GPS.updateBaudRate(GPS_BAUD);
  delay(100);
  applyMode(mode);
  lastPvtMs = millis();
  digitalWrite(PIN_LED, HIGH);
}

// Quante posizioni stanno in un pacchetto: dipende dall'MTU concordato col telefono.
// A 25 Hz ne metto fino a 4 (un pacchetto ogni 160 ms), a 10 Hz fino a 2 (ogni 200 ms).
int pointsPerPacket() {
  uint16_t mtu = 23;
#if defined(CONFIG_BLUEDROID_ENABLED)
  mtu = server->getPeerMTU(connId);
#endif
  int fit = (mtu > 23 ? mtu - 3 : 20) / 20;
  int want = (mode == MODE_25HZ_GPS) ? 4 : 2;
  if (fit < 1) fit = 1;
  return fit < want ? fit : want;
}

void handleUbx() {
  if (ubx.cls == 0x05 && ubx.len >= 2 && ubx.buf[0] == 0x06 && ubx.buf[1] == 0x8A) {
    cfgResult = (ubx.id == 0x01) ? 1 : 2;   // ACK-ACK / ACK-NAK a un VALSET
    if (cfgResult == 2) Serial.println("[gps] configurazione rifiutata (NAK)");
    return;
  }
  Pvt pvt;
  if (!decodePvt(ubx, pvt)) return;
  lastPvtMs = millis();
  pvtCount++;
  lastSats = pvt.numSV;
  lastFix = pvt.fixOk && pvt.fixType >= 3;
  if (connected) {
    // molti telefoni non reggono 25 pacchetti al secondo: ne mando meno, con più posizioni dentro
    int ppp = pointsPerPacket();
    if (ppp >= 2) {
      packPos(pvt, batch + nBatch * 20);
      nBatch++;
      if (nBatch >= ppp) {
        chPos->setValue(batch, nBatch * 20);
        chPos->notify();
        nBatch = 0;
      }
    } else {
      // pacchetti piccoli: due posizioni compresse insieme
      if (!havePrev) { prevPvt = pvt; havePrev = true; }
      else {
        uint8_t pkt[19];
        if (packPair(prevPvt, pvt, pkt)) {
          chPos->setValue(pkt, 19);
          chPos->notify();
          havePrev = false;
        } else {
          sendSingle(prevPvt);
          prevPvt = pvt;
        }
      }
    }
  }
}

// ---------------------------------------------------------------- avvio e ciclo
void setup() {
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
  Serial.begin(115200);
  prefs.begin("ftgps", false);
  mode = prefs.getUChar("mode", MODE_25HZ_GPS);
  GPS.setRxBufferSize(2048);
  GPS.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  setupBle();
  configureGps();
}

void loop() {
  while (GPS.available()) {
    if (ubx.feed(GPS.read())) handleUbx();
  }

  if (pendingMode >= 0) {
    uint8_t m = (uint8_t)pendingMode;
    pendingMode = -1;
    if (m != mode) { prefs.putUChar("mode", m); applyMode(m); }
  }

  uint32_t now = millis();
#if defined(CONFIG_BLUEDROID_ENABLED)
  if (connected && !paramsAsked && now - connAtMs > 2000) { askFastConnection(); paramsAsked = true; }
#endif
  if (now - lastPvtMs > 3000) {     // GPS muto: forse si è riavviato, lo riconfiguro
    Serial.println("[gps] nessun dato da 3 s, riconfiguro");
    configureGps();
    now = millis();
  }

  if (now - lastInfoMs >= 1000) {
    hzMeasured = pvtCount > 255 ? 255 : pvtCount;
    pvtCount = 0;
    lastInfoMs = now;
    uint8_t state = (now - lastPvtMs < 1500) ? (lastFix ? GPS_FIX : GPS_TALKING) : GPS_SILENT;
    uint8_t info[7];
    packInfo(mode, hzMeasured, state, lastSats, cfgResult, info);
    uint32_t ci = connIntUnits * 5 / 4;   // ms
    info[6] = ci > 255 ? 255 : ci;
    chInfo->setValue(info, sizeof(info));
    if (connected) chInfo->notify();
    Serial.printf("[stato] %u Hz, satelliti %u, fix %d, telefono %s, intervallo %u ms\n", hzMeasured, lastSats, lastFix,
                  connected ? "collegato" : "no", (unsigned)(connIntUnits * 5 / 4));
  }

  // LED (acceso = livello basso)
  bool on;
  if (!connected) on = (now % 2000) < 100;          // lampeggio lento
  else if (lastFix) on = true;                      // tutto ok
  else on = (now % 300) < 150;                      // collegato, aspetto il fix
  digitalWrite(PIN_LED, on ? LOW : HIGH);
  delay(1);
}
