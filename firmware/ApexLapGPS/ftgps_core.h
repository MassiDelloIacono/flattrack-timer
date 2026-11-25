// ftgps_core.h — parte "pura" del firmware (niente Arduino): protocollo UBX del GPS u-blox M10
// e formato dei pacchetti Bluetooth verso l'app. Separata così si può provare anche su PC.
#pragma once
#include <stdint.h>
#include <string.h>

namespace ftgps {

// ---------------------------------------------------------------- Bluetooth (deve combaciare con l'app)
// Servizio e caratteristiche: UUID scelti per questo progetto.
static constexpr char BLE_NAME[] = "ApexLap GPS";
static constexpr char UUID_SERVICE[] = "f1a70001-5e4b-4c2a-9d6f-7a3c0de0f00d";
static constexpr char UUID_POS[] = "f1a70002-5e4b-4c2a-9d6f-7a3c0de0f00d"; // notify, 20 byte per punto
static constexpr char UUID_CTRL[] = "f1a70003-5e4b-4c2a-9d6f-7a3c0de0f00d"; // write 1 byte: modalità
static constexpr char UUID_INFO[] = "f1a70004-5e4b-4c2a-9d6f-7a3c0de0f00d"; // notify/read 6 byte di stato
static const uint8_t FW_VERSION = 2;

// Modalità del GPS scelte dall'app
enum Mode : uint8_t {
  MODE_10HZ_MULTI = 0,  // 10 Hz con GPS + Galileo: più satelliti, posizione più stabile
  MODE_25HZ_GPS   = 1,  // 25 Hz con soli satelliti GPS: più punti al secondo
};

// ---------------------------------------------------------------- chiavi di configurazione M10 (UBX-CFG-VALSET)
static const uint32_t CFG_UART1_BAUDRATE          = 0x40520001; // U4
static const uint32_t CFG_RATE_MEAS               = 0x30210001; // U2, ms tra due misure
static const uint32_t CFG_RATE_NAV                = 0x30210002; // U2
static const uint32_t CFG_MSGOUT_UBX_NAV_PVT_UART1 = 0x20910007; // U1
static const uint32_t CFG_UART1OUTPROT_UBX        = 0x10740001; // L
static const uint32_t CFG_UART1OUTPROT_NMEA       = 0x10740002; // L
static const uint32_t CFG_NAVSPG_DYNMODEL         = 0x20110021; // E1 (4 = automotive)
static const uint32_t CFG_SIGNAL_GPS_ENA          = 0x1031001f; // L
static const uint32_t CFG_SIGNAL_SBAS_ENA         = 0x10310020;
static const uint32_t CFG_SIGNAL_GAL_ENA          = 0x10310021;
static const uint32_t CFG_SIGNAL_BDS_ENA          = 0x10310022;
static const uint32_t CFG_SIGNAL_QZSS_ENA         = 0x10310024;
static const uint32_t CFG_SIGNAL_GLO_ENA          = 0x10310025;

// Byte del valore, ricavati dai bit 28..30 della chiave
inline int keySize(uint32_t key) {
  switch ((key >> 28) & 0x7) {
    case 1: case 2: return 1;
    case 3: return 2;
    case 4: return 4;
    default: return 8;
  }
}

// Costruisce un frame UBX completo (sync, classe, id, lunghezza, payload, checksum). Ritorna i byte scritti.
inline int ubxFrame(uint8_t cls, uint8_t id, const uint8_t* payload, uint16_t len, uint8_t* out) {
  out[0] = 0xB5; out[1] = 0x62; out[2] = cls; out[3] = id;
  out[4] = len & 0xFF; out[5] = len >> 8;
  if (len) memcpy(out + 6, payload, len);
  uint8_t a = 0, b = 0;
  for (int i = 2; i < 6 + len; i++) { a += out[i]; b += a; }
  out[6 + len] = a; out[7 + len] = b;
  return 8 + len;
}

// Payload di UBX-CFG-VALSET (solo memoria RAM: il GPS torna di fabbrica a ogni accensione e il firmware lo riconfigura)
inline int valsetPayload(const uint32_t* keys, const uint32_t* vals, int n, uint8_t* out) {
  int len = 0;
  out[len++] = 0x00;  // versione
  out[len++] = 0x01;  // layer RAM
  out[len++] = 0; out[len++] = 0;
  for (int i = 0; i < n; i++) {
    for (int b = 0; b < 4; b++) out[len++] = (keys[i] >> (8 * b)) & 0xFF;
    int sz = keySize(keys[i]);
    for (int b = 0; b < sz; b++) out[len++] = (b < 4) ? (vals[i] >> (8 * b)) & 0xFF : 0;
  }
  return len;
}

// Le due configurazioni da mandare per una modalità: prima i satelliti (il GPS si riavvia), poi la frequenza.
struct KV { uint32_t k, v; };
inline int signalConfig(Mode m, KV* out) {
  bool multi = (m == MODE_10HZ_MULTI);
  int n = 0;
  out[n++] = {CFG_SIGNAL_GPS_ENA, 1};
  out[n++] = {CFG_SIGNAL_GAL_ENA, multi ? 1u : 0u};
  out[n++] = {CFG_SIGNAL_BDS_ENA, 0};
  out[n++] = {CFG_SIGNAL_GLO_ENA, 0};
  out[n++] = {CFG_SIGNAL_QZSS_ENA, 0};
  out[n++] = {CFG_SIGNAL_SBAS_ENA, 0};
  return n;
}
inline uint16_t measPeriodMs(Mode m) { return m == MODE_25HZ_GPS ? 40 : 100; }
inline int rateConfig(Mode m, KV* out) {
  int n = 0;
  out[n++] = {CFG_RATE_MEAS, measPeriodMs(m)};
  out[n++] = {CFG_RATE_NAV, 1};
  out[n++] = {CFG_MSGOUT_UBX_NAV_PVT_UART1, 1};
  out[n++] = {CFG_UART1OUTPROT_UBX, 1};
  out[n++] = {CFG_UART1OUTPROT_NMEA, 0};
  out[n++] = {CFG_NAVSPG_DYNMODEL, 4};
  return n;
}

// ---------------------------------------------------------------- lettura dei messaggi UBX
class UbxParser {
 public:
  // Ritorna true quando un messaggio completo e con checksum giusto è pronto in cls/id/len/buf.
  bool feed(uint8_t c) {
    switch (st_) {
      case 0: if (c == 0xB5) st_ = 1; break;
      case 1: st_ = (c == 0x62) ? 2 : (c == 0xB5 ? 1 : 0); break;
      case 2: cls = c; a_ = c; b_ = a_; st_ = 3; break;
      case 3: id = c; a_ += c; b_ += a_; st_ = 4; break;
      case 4: len = c; a_ += c; b_ += a_; st_ = 5; break;
      case 5:
        len |= (uint16_t)c << 8; a_ += c; b_ += a_; idx_ = 0;
        st_ = (len > sizeof(buf)) ? 0 : (len ? 6 : 7);
        break;
      case 6: buf[idx_++] = c; a_ += c; b_ += a_; if (idx_ >= len) st_ = 7; break;
      case 7: st_ = (c == a_) ? 8 : 0; break;
      case 8: st_ = 0; if (c == b_) return true; break;
    }
    return false;
  }
  uint8_t cls = 0, id = 0;
  uint16_t len = 0;
  uint8_t buf[128];
 private:
  uint8_t st_ = 0, a_ = 0, b_ = 0;
  uint16_t idx_ = 0;
};

// Riconosce anche una frase NMEA ("$G...") per capire la velocità della seriale prima della configurazione.
class NmeaSniffer {
 public:
  bool feed(uint8_t c) {
    if (c == '$') { n_ = 1; return false; }
    if (n_ == 0) return false;
    if (n_ == 1) { n_ = (c == 'G' || c == 'B') ? 2 : 0; return false; }
    if (c == '*' && n_ > 6) { n_ = 0; return true; }
    if (c < 32 || c > 126) { n_ = 0; return false; }
    if (++n_ > 90) n_ = 0;
    return false;
  }
 private:
  int n_ = 0;
};

inline uint32_t rdU4(const uint8_t* b) { return b[0] | (b[1] << 8) | (b[2] << 16) | ((uint32_t)b[3] << 24); }
inline int32_t rdI4(const uint8_t* b) { return (int32_t)rdU4(b); }

struct Pvt {
  uint32_t iTOW;     // ms nella settimana GPS
  uint8_t fixType;   // 0 nessuno, 2 = 2D, 3 = 3D
  bool fixOk;
  uint8_t numSV;
  int32_t lon, lat;  // 1e-7 gradi
  uint32_t hAccMm;
  int32_t gSpeedMmS;
  int32_t headMot;   // 1e-5 gradi
};

// UBX-NAV-PVT (classe 0x01, id 0x07, 92 byte)
inline bool decodePvt(const UbxParser& p, Pvt& o) {
  if (p.cls != 0x01 || p.id != 0x07 || p.len < 92) return false;
  const uint8_t* b = p.buf;
  o.iTOW = rdU4(b + 0);
  o.fixType = b[20];
  o.fixOk = b[21] & 0x01;
  o.numSV = b[23];
  o.lon = rdI4(b + 24);
  o.lat = rdI4(b + 28);
  o.hAccMm = rdU4(b + 40);
  o.gSpeedMmS = rdI4(b + 60);
  o.headMot = rdI4(b + 64);
  return true;
}

// ---------------------------------------------------------------- pacchetto Bluetooth di una posizione (20 byte, little-endian)
//  0 u32 iTOW ms   4 i32 lat 1e-7   8 i32 lon 1e-7   12 u16 velocità cm/s   14 u16 direzione 0,01°
// 16 u16 precisione cm   18 u8 satelliti   19 u8 bit0-2 tipo di fix, bit3 fix valido
inline void wr16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
inline void wr32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xFF; }
inline uint16_t clamp16(int64_t v) { return v < 0 ? 0 : (v > 65535 ? 65535 : (uint16_t)v); }

inline void packPos(const Pvt& p, uint8_t* out) {
  wr32(out + 0, p.iTOW);
  wr32(out + 4, (uint32_t)p.lat);
  wr32(out + 8, (uint32_t)p.lon);
  wr16(out + 12, clamp16(p.gSpeedMmS / 10));
  int32_t h = p.headMot / 1000;          // 1e-5° -> 0,01°
  h %= 36000; if (h < 0) h += 36000;
  wr16(out + 14, (uint16_t)h);
  wr16(out + 16, clamp16((p.hAccMm + 5) / 10));
  out[18] = p.numSV;
  out[19] = (p.fixType & 0x07) | (p.fixOk ? 0x08 : 0);
}

// Stato per l'app (6 byte): versione, modalità, punti al secondo misurati, stato GPS, satelliti, esito config
enum GpsState : uint8_t { GPS_SILENT = 0, GPS_TALKING = 1, GPS_FIX = 2 };
inline void packInfo(uint8_t mode, uint8_t hz, uint8_t state, uint8_t sats, uint8_t cfg, uint8_t* out) {
  out[0] = FW_VERSION; out[1] = mode; out[2] = hz; out[3] = state; out[4] = sats; out[5] = cfg;
}

}  // namespace ftgps
