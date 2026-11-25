# ApexLap GPS: firmware del GPS esterno

Questo firmware trasforma la ESP32-C3 SuperMini con il GPS u-blox M10 (HGLRC M100-5883) in un GPS da pista a 25 Hz. Il GPS manda le posizioni all'app ApexLap via Bluetooth.

## Collegamenti

| GPS (scritte sulla scheda del GPS) | ESP32-C3 SuperMini |
|---|---|
| 5V / VCC | 5V |
| GND | GND |
| TX | GPIO20 (sulla scheda: 20) |
| RX | GPIO21 (sulla scheda: 21) |
| SDA, SCL | non collegati |

TX e RX vanno incrociati. Guarda le scritte sul GPS, non i colori dei fili. Il telefono alimenta tutto dalla presa USB-C della ESP32-C3.

## Caricare il firmware (una volta sola, dal computer)

1. Installa **Arduino IDE 2** da arduino.cc.
2. Aggiungi l'indirizzo delle schede Espressif:
   1. Vai in File → Preferenze.
   2. In "URL aggiuntive per il Gestore schede" incolla
      `https://espressif.github.io/arduino-esp32/package_esp32_index.json`.
3. Installa il pacchetto delle schede: in Strumenti → Scheda → Gestore schede cerca **esp32** e installa "esp32 di Espressif Systems".
4. Apri il file `ApexLapGPS/ApexLapGPS.ino`. Il file `ftgps_core.h` deve restare nella stessa cartella.
5. Nel menu **Strumenti** imposta:
   - Scheda: **ESP32C3 Dev Module**, oppure "Nologo ESP32C3 Super Mini" se c'è;
   - USB CDC On Boot: **Enabled**;
   - Partition Scheme: **Huge APP (3MB No OTA/1MB SPIFFS)**, perché il Bluetooth occupa spazio;
   - Porta: quella che compare quando colleghi la scheda.
6. Collega la scheda al computer col cavo USB-C e premi **Carica** (la freccia →).
   - Se la porta non compare o il caricamento non parte: tieni premuto il tasto **BOOT** della scheda, premi e rilascia **RST**, poi rilascia BOOT e riprova.
7. Apri Strumenti → Monitor seriale a 115200. Con il GPS collegato e all'aperto devi vedere:
   ```
   [gps] trovato a 115200 baud
   [gps] modalita 1: misura ogni 40 ms
   [stato] 25 Hz, satelliti 12, fix 1, telefono no
   ```
   Se vedi "18 Hz" o "10 Hz" invece di 25, quel GPS non regge i 25 Hz. Funziona comunque, solo con meno punti al secondo.

## Usarlo con l'app

1. Collega il GPS esterno al telefono con il cavo USB-C. Il LED blu della scheda lampeggia lento.
2. Nell'app vai in **Opzioni → GPS → Esterno**, scegli **ApexLap GPS** dall'elenco e conferma. Nella barra in alto compare la frequenza, per esempio "25 Hz".
3. In **Opzioni → Frequenza GPS esterno** scegli:
   - **25 Hz**: solo satelliti GPS, più punti al secondo;
   - **10 Hz**: GPS + Galileo, più satelliti e posizione più stabile.

   Provale entrambe in pista e tieni quella che dà tempi più costanti.
4. Se il Bluetooth cade, l'app passa da sola al GPS del telefono e si ricollega appena può.

Significato del LED:
- lampeggio lento: nessun telefono collegato;
- lampeggio veloce: collegato, ma il GPS cerca ancora i satelliti;
- acceso fisso: tutto pronto.

## Note tecniche

- Il GPS viene riconfigurato a ogni accensione, solo in RAM: non resta modificato in modo permanente.
- Ogni pacchetto Bluetooth porta fino a 4 posizioni, ognuna con la sua ora GPS: così bastano 6 pacchetti al secondo per avere 25 punti, e il ritardo del Bluetooth non entra nei tempi sul giro.
- Serve il pacchetto schede esp32 3.3.x: la 4.0 non ha più la libreria BLE usata qui.
- Formato dei pacchetti e UUID del servizio sono in `ftgps_core.h`.
