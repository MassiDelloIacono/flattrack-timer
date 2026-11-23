# ApexLap – cronometro GPS da pista per telefono Android

Cronometro GPS da pista (nato per il flat track), con la stessa interfaccia touch del cronometro fatto in casa.
Funziona da Chrome, si installa sulla schermata Home e lavora anche **senza connessione**.

File da pubblicare (tutti e 5, nella stessa cartella):
`index.html`, `manifest.webmanifest`, `sw.js`, `icon-192.png`, `icon-512.png`

---

## 1. Pubblicarla su GitHub Pages (gratis, una volta sola)

Il GPS nel browser funziona solo da un indirizzo **https**: GitHub Pages te ne dà uno gratis.

1. Crea un account su **github.com** (se non ce l'hai).
2. In alto a destra **+ → New repository**. Nome: `flattrack`. Lascialo **Public** (GitHub Pages gratis richiede un repository pubblico). Clicca **Create repository**.
3. Nella pagina del repository: **Add file → Upload files**. Trascina i 5 file. Clicca **Commit changes**.
4. Vai su **Settings → Pages**. In *Branch* scegli `main` e `/ (root)`, poi **Save**.
5. Dopo un minuto l'app è a: `https://TUONOME.github.io/flattrack/`

Per aggiornarla in futuro: carichi di nuovo i file con **Upload files** (sostituiscono i vecchi).

## 2. Installarla sul telefono

1. Sul telefono apri **Chrome** all'indirizzo `https://TUONOME.github.io/flattrack/`
2. Quando chiede la **posizione**, tocca **Consenti**. Imposta anche *Posizione → Precisa* se te lo chiede.
3. Menu **⋮ → Installa app** (oppure *Aggiungi a schermata Home*).
4. Da ora la apri dall'icona gialla **FT**, a schermo intero e in orizzontale.

La prima volta aprila con internet: l'app si salva sul telefono e poi funziona anche in pista senza campo.

## 3. Uso

| Schermata | Cosa fai |
|---|---|
| **Da fermo** | riepilogo (migliore, ultimo, media) + pulsanti GIRI, TRAGUARDO, OPZIONI |
| **Traguardo** | fermati sulla linea → *Sono sulla linea* → parti: la direzione la prende da sola dopo 8 m |
| **In pista** (sopra 15 km/h) | si apre da sola la vista gara, senza pulsanti. Tocca lo schermo per alternare **Delta** e **Tempo** |
| **Fine giro** | tempo a tutto schermo per 4 s (verde = record) + vibrazione |
| **Fermo per 3 s** | torna il riepilogo |

- La pista viene **riconosciuta da sola** le volte successive (entro 1,5 km dal traguardo salvato).
- I giri restano salvati anche se chiudi l'app. *Opzioni → Condividi giri* li manda su WhatsApp, email, ecc.
- **Modalità DEMO** (in Opzioni o nella schermata iniziale): una moto virtuale gira su un ovale, per provare tutto a casa. Comandi PARTI / RITMO a lato dello schermo.
- Lo schermo resta **sempre acceso** mentre l'app è aperta.

## 4. Cosa aspettarsi dal GPS del telefono

- Quasi tutti i telefoni danno **1 posizione al secondo**. L'app interpola il passaggio sul traguardo; nei test con un GPS "da telefono" l'errore medio è stato di circa 3 centesimi. Su un telefono vero dipende molto da quanto è buono il suo GPS e da dove è montato.
- In alto a sinistra vedi la **precisione** (es. `±4 m`): verde sotto 8 m, gialla sopra, rossa se il GPS è perso o sopra 25 m.
- Monta il telefono con un **supporto antivibrante**: le vibrazioni della moto possono danneggiare la fotocamera.

**Fase 2 (in arrivo):** GPS esterno a 10 Hz (Beitian BE-280 + ESP32) collegato via **Bluetooth**. La stessa app lo userà al posto del GPS del telefono.
