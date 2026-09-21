# EulerScope

Magnificazione euleriana basata sulla fase per Android, con estrazione di
parametri fisiologici e analisi vibrazionale.

Target di sviluppo: **Oppo Find X5 Pro, Android 14 (ColorOS 14)**,
Snapdragon 8 Gen 1 / Adreno 730.

---

## Compilazione

Il progetto non include il Gradle wrapper (il binario `gradle-wrapper.jar`
non è distribuibile come sorgente).

1. Apri la cartella in **Android Studio Koala (2024.1.1) o superiore**.
2. Android Studio genera il wrapper al primo import.
3. Installa NDK e CMake da *SDK Manager → SDK Tools*:
   - NDK (Side by side) **26.x**
   - CMake **3.22.1**
4. `Build → Make Project`, poi `Run`.

Da riga di comando, dopo che il wrapper esiste:

```
./gradlew assembleDebug
```

L'APK esce in `app/build/outputs/apk/debug/`.

Solo `arm64-v8a` è compilato: non serve altro su dispositivi moderni e
dimezza i tempi di build.

---

## Architettura

```
app/src/main/cpp/           motore DSP nativo (C++17, nessuna dipendenza)
  fft.h                     FFT radix-2, spettro di ampiezza, picchi
  riesz.h/.cpp              piramide di Riesz, fase quaternionica,
                            filtro temporale, shift di fase
  vitals.h/.cpp             rPPG con algoritmo POS, HR, respiro, tremore
  jni_bridge.cpp            interfaccia JNI

app/src/main/java/it/rs/eulerscope/
  core/NativeEngine.kt      binding JNI e tipi di misura
  core/Modes.kt             preset di banda per le cinque modalità
  core/LieGame.kt           logica del gioco
  camera/CameraCapabilities interrogazione reale delle capacità del device
  camera/LiveCameraController   acquisizione live YUV con controlli bloccati
  camera/HighSpeedCapture   registrazione 120/240 fps e decodifica offline
  ui/EulerScopeViewModel    stato e ciclo di vita
  MainActivity.kt           interfaccia Compose
```

Un solo core, cinque modalità: cambiano banda temporale, guadagno e
visualizzazione, non la pipeline.

---

## Algoritmo

Piramide di Riesz secondo Wadhwa, Rubinstein, Durand, Freeman,
*Riesz Pyramids for Fast Phase-Based Video Magnification*, ICCP 2014.

Per ogni livello laplaciano:

1. Trasformata di Riesz approssimata con kernel FIR `[0.5, 0, -0.5]`.
2. Differenza di fase quaternionica tra frame consecutivi.
3. Accumulo temporale della fase.
4. Passa-banda IIR Butterworth del 1° ordine (differenza di due passa-basso).
5. Denoising spaziale pesato sull'ampiezza: `blur(φ·A²)/blur(A²)`.
6. Shift di fase: `I' = I·cos(Δ) − R_θ·sin(Δ)` con `Δ = α·|φ|`.

La scelta di Riesz rispetto alla piramide complessa orientabile è dettata
dal mobile: circa 4× più veloce, al costo di una gestione peggiore dei
movimenti di grande ampiezza.

rPPG con algoritmo **POS** (Wang, den Brinker, Stuijk, de Haan, IEEE TBME
2017), scelto rispetto a CHROM e ICA per la maggiore robustezza alle
variazioni di illuminazione.

---

## Verifica eseguita

Il core C++ è stato compilato ed eseguito su banco prima della consegna.

| Prova | Atteso | Misurato |
|---|---|---|
| Magnificazione, sinusoide a 1.5 Hz, α=15 | amplificazione | guadagno 3.30× |
| HR, pulsazione nota | 55 bpm | 54.99 |
| HR, pulsazione nota | 72 bpm | 72.00 |
| HR, pulsazione nota | 105 bpm | 105.01 |
| HR, pulsazione nota | 150 bpm | 149.99 |
| Respiro | 15 /min | 15.02 |
| Rumore pseudocasuale puro | nessuna misura | rifiutato |
| HR con rumore 4 LSB | rilevato, qualità bassa | 71.63, q=0.07 |
| HR con rumore 8 LSB | rifiutato | rifiutato |

Una prima versione usava come metrica di affidabilità il rapporto
picco/mediana dello spettro: su rumore bianco produceva **falsi positivi**,
perché il periodogramma ha distribuzione esponenziale e il massimo su N bin
supera facilmente 2-3 volte la mediana. È stata sostituita da un rapporto
di energia di banda in dominio di potenza (fondamentale + prima armonica
contro il resto della banda), che rifiuta correttamente il rumore.

`jni_bridge.cpp` è stato verificato solo sintatticamente: la compilazione
completa richiede l'NDK.

---

## Limiti reali, non formali

**Frame rate.** Il limite di Nyquist è `fps/2`. A 30 fps non si vede nulla
sopra 15 Hz. I 240 fps della scheda tecnica del Find X5 Pro appartengono
all'app camera di sistema; **Camera2 potrebbe non esporli**. La schermata
*Info* interroga il dispositivo e dice cosa c'è davvero.

**Sessioni ad alta velocità.** `CameraConstrainedHighSpeedCaptureSession`
accetta al massimo due superfici, e solo SurfaceView o MediaRecorder/
MediaCodec. Un `ImageReader` non è ammesso. Quindi a 120/240 fps si
registra su file e si elabora dopo: l'analisi live resta a 30-60 fps.
Questa non è una scelta di design, è un vincolo dell'API.

**Artefatti.** A guadagni alti la magnificazione produce movimento
apparente anche su rumore puro. Il confronto con magnificazione
disattivata è l'unico controllo affidabile, ed è per questo che
l'interruttore è sempre in vista.

**Illuminazione.** Serve luce continua. Le sorgenti LED con ripple di rete
introducono una modulazione a 100 Hz (50 Hz × 2) che genera aliasing a
qualunque frame rate sotto i 200 fps.

**Supporto.** Il telefono va su treppiede. A mano libera il tremore
corporeo a 8-12 Hz copre ogni segnale.

---

## Cosa l'app non fa, e perché

**Pressione sanguigna.** Non implementata. Il metodo basato sul pulse
transit time richiede due punti di misura sincronizzati e una calibrazione
individuale con bracciale che decade nell'arco di giorni. Con una sola
camera puntata sul volto l'informazione fisica non è presente nel segnale:
qualsiasi valore in mmHg sarebbe inventato. Nel 2018 la FTC ha sanzionato
*Instant Blood Pressure* proprio per questo.

**SpO₂.** Non implementata. Richiede due lunghezze d'onda e calibrazione;
senza di esse l'errore cade esattamente nell'intervallo in cui il dato
conterebbe.

**Glicemia, temperatura corporea.** Nessuna base fisica nel segnale video.

**Stato emotivo.** L'inferenza delle emozioni ricade nel divieto del
Regolamento UE 2024/1689 (AI Act) in ambito lavorativo e scolastico. I
parametri misurati qui — battito, respiro, tremore — sono misure
fisiologiche, non inferenze emotive, e restano fuori dal divieto.

**Rilevamento delle menzogne.** Non esiste. La modalità *Gioco* misura
scostamenti fisiologici reali rispetto a una baseline della stessa
persona; l'attribuzione alla menzogna è la parte finta ed è dichiarata
tale a schermo. Il caso iBorderCtrl è il precedente più documentato del
fallimento dell'approccio.

**Destinazione d'uso.** Benessere, non diagnosi. La qualifica di
dispositivo medico ai sensi del Reg. UE 2017/745 dipende dalla
destinazione d'uso dichiarata dal fabbricante, ma il disclaimer regge solo
se l'interfaccia non lo contraddice: per questo nessun valore viene
mostrato quando il segnale non è affidabile, e l'assenza di un numero
significa assenza di dato, mai una stima plausibile messa al suo posto.

---

## Uso

1. **Info** — leggi cosa il tuo dispositivo espone davvero.
2. **Vitali** — volto inquadrato, telefono fermo, luce stabile.
3. **Magnifica** — regola banda e guadagno, confronta con magnificazione off.
4. **Strutture** — macchine, motori, edifici. Lo spettro non ha vincoli
   normativi ed è il terreno dove questa tecnica rende di più.
5. **Flussi d'aria** — banda 0.1-1.5 Hz, guadagno alto, sfondo texturizzato.
6. **Gioco** — baseline, poi domande.

---

## Sviluppi possibili

- Porting del core su compute shader OpenGL ES 3.1 per la magnificazione
  a piena risoluzione in tempo reale.
- Piramide complessa orientabile come motore alternativo per l'elaborazione
  offline, dove la velocità conta meno della fedeltà.
- Recupero audio dalle vibrazioni (*visual microphone*, Davis et al.,
  SIGGRAPH 2014) sul percorso ad alta velocità, con i limiti di banda del
  caso.
- Import di video dalla galleria: il decoder in `VideoFileProcessor` è già
  pronto, manca il selettore.
