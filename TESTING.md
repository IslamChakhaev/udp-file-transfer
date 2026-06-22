# Testen

## Build

```bash
mvn clean package -q
```

JAR-Pfad: `target/udp-1.0-SNAPSHOT.jar`

---

## Java Sender → Java Receiver

```bash
# Terminal 1 – Receiver starten
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainRX 5000 ./received

# Terminal 2 – Sender starten
echo "Testinhalt" > test.txt
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainTX 127.0.0.1 5000 test.txt
```

Erwartete Ausgabe:
```
TX start: file=test.txt, size=..., txId=..., chunks=...
TX complete: file=test.txt, time=... ms

RX FIRST: file=test.txt, txId=..., chunks=...
RX complete: saved=.../received/test.txt
```

Für Audio- oder Videodateien: einfach einen anderen Dateipfad angeben.
NAK/Retransmit-Runden bei großen Dateien sind normales Verhalten.

---

## MD5 prüfen

Der Receiver prüft MD5 automatisch. Zur manuellen Kontrolle:

```powershell
# Windows
Get-FileHash test.txt -Algorithm MD5
Get-FileHash received\test.txt -Algorithm MD5
```

```bash
# Linux / macOS
md5sum test.txt received/test.txt
```

---

## Kompatibilitätstests

Eine kompatible Implementierung befindet sich im Unterordner `UDP-File-Transfer-C-Java-Introduce-ACK/`.

Zuerst kompilieren:
```bash
cd UDP-File-Transfer-C-Java-Introduce-ACK
javac UdpFileSender.java UdpFileReceiver.java
```

**Java Sender → kompatibler Receiver:**
```bash
# Terminal 1
cd UDP-File-Transfer-C-Java-Introduce-ACK
java UdpFileReceiver 5001 ./ref-received

# Terminal 2
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainTX 127.0.0.1 5001 test.txt
```
Erwartet: kompatibler Receiver meldet `Transfer complete`.

**Kompatibler Sender → Java Receiver:**
```bash
# Terminal 1
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainRX 5002 ./received

# Terminal 2 – txId ist beim kompatiblen Sender das 4. Argument
cd UDP-File-Transfer-C-Java-Introduce-ACK
java UdpFileSender 127.0.0.1 5002 test.txt 42
```
Erwartet: Java Receiver meldet `RX complete: saved=...`.

---

## Pacing (optional)

```bash
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainTX 127.0.0.1 5000 test.txt 1
```
Das 4. Argument setzt eine künstliche Verzögerung in ms zwischen Paketen.

---

## Typische Fehler

| Fehlermeldung | Ursache / Lösung |
|---------------|------------------|
| `File not readable` | Datei existiert nicht oder Pfad falsch. |
| `Transfer failed: COMPLETE not received` | Receiver läuft nicht oder falscher Port. |
| `Retry limit exceeded for seq=...` | Dauerhafter Paketverlust; Netzwerk prüfen. |
| `MD5 mismatch` | Datei korrumpiert; Transfer wiederholen. |
| `Unsafe output path` | Dateiname enthielt `../`; Schutz aktiv. |
| `Transfer timeout` | Sender zu lange inaktiv; `.part` gelöscht. |
| `Address already in use` | Port belegt; anderen Port wählen. |
