// ApexLapGPS — ricevitore GPS esterno per l'app ApexLap
// Scheda: ESP32-C3 SuperMini.  GPS: u-blox M10 (es. HGLRC M100-5883) su GPIO20 (RX) / GPIO21 (TX).
// Il telefono alimenta la scheda dalla presa USB-C (OTG); i dati vanno al telefono via Bluetooth Low Energy.
//
// Cosa fa:
//  1. trova da solo a che velocità parla il GPS e lo porta a 115200 baud;
//  2. lo configura (25 Hz solo GPS, oppure 10 Hz GPS+Galileo) e gli fa mandare solo UBX-NAV-PVT;
//  3. manda ogni posizione all'app in un pacchetto da 20 byte con l'ora GPS esatta;
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

void setupBle() {
  BLEDevice::init(BLE_NAME);
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
    uint8_t pkt[20];
    packPos(pvt, pkt);
    chPos->setValue(pkt, sizeof(pkt));
    chPos->notify();
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
    uint8_t info[6];
    packInfo(mode, hzMeasured, state, lastSats, cfgResult, info);
    chInfo->setValue(info, sizeof(info));
    if (connected) chInfo->notify();
    Serial.printf("[stato] %u Hz, satelliti %u, fix %d, telefono %s\n", hzMeasured, lastSats, lastFix,
                  connected ? "collegato" : "no");
  }

  // LED (acceso = livello basso)
  bool on;
  if (!connected) on = (now % 2000) < 100;          // lampeggio lento
  else if (lastFix) on = true;                      // tutto ok
  else on = (now % 300) < 150;                      // collegato, aspetto il fix
  digitalWrite(PIN_LED, on ? LOW : HIGH);
  delay(1);
}
