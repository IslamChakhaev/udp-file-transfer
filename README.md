# UDP File Transfer

![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/Build-Maven-blue)
![Protocol](https://img.shields.io/badge/Protocol-UDP-lightgrey)
![Reliability](https://img.shields.io/badge/Reliability-ACK%2FNAK-green)
![Window](https://img.shields.io/badge/Sliding%20Window-supported-brightgreen)
![Integrity](https://img.shields.io/badge/Integrity-MD5-yellow)

Java/Maven-Projekt für zuverlässige Dateiübertragung über UDP.
Die Implementierung bleibt mit einer kompatiblen Sender/Receiver-Umsetzung testbar.

## Warum UDP allein nicht reicht

UDP liefert keine Garantien: Pakete können verloren gehen, doppelt ankommen oder
in der falschen Reihenfolge eintreffen. Dieses Projekt setzt daher eigene Mechanismen
obendrauf — Sequenznummern, ACK/NAK-Feedback, Sliding Window, Retransmission und
MD5-Integritätsprüfung.

## Protokollablauf

Der Sender schickt drei Pakettypen (alle in Big-Endian):

- **FIRST** (seq=0): startet die Übertragung, enthält Dateiname und Anzahl der Chunks
- **DATA** (seq 1..maxSeq): je bis zu 1400 Byte Nutzdaten
- **LAST** (seq=maxSeq+1): schließt ab, enthält den 16-Byte-MD5-Hash der Datei

Der Receiver antwortet mit Control-Paketen:

- **ACK** (Code 2): alles bis ackBase−1 angekommen (kumulativ)
- **NAK** (Code 0): Liste fehlender Sequenznummern — Sender schickt sie erneut
- **COMPLETE** (Code 1): Datei vollständig und MD5 verifiziert
- **ERROR** (Code 3): Fehler (Timeout, MD5-Fehler o. ä.)

## Sliding Window und Retransmission

Der Sender hält bis zu 64 DATA-Pakete gleichzeitig „in flight", ohne auf ein ACK
zu warten. Das Congestion Window (`cwnd`) startet bei 8, wächst pro ACK um 1 und
wird bei NAK oder Timeout halbiert.

Zwei Retransmit-Mechanismen:
- **RTO**: Ältestes Paket ohne ACK nach `rtoMs` erneut senden; RTO verdoppelt sich
  bei Timeout (Exponential Backoff, max. 3 s).
- **Fast Retransmit**: Drei gleiche ACKs mit derselben ackBase → sofortiger Retransmit.

## MD5-Prüfung

Der Sender berechnet den Hash vor dem Senden und schickt ihn im LAST-Paket mit.
Der Receiver prüft ihn nach dem Zusammensetzen. Stimmen die Hashes nicht überein,
wird die `.part`-Datei gelöscht.

## Ablauf im Code

```
MainTX → Sender.sendFile()
           → FIRST senden
           → sendWithWindow(): DATA per Sliding Window, auf ACK/NAK/COMPLETE warten

MainRX → Receiver.start()
           → handle() → ReceiveSession.accept()
                          ├─ FIRST:  .part-Datei anlegen
                          ├─ DATA:   Chunk per seek+write in .part schreiben
                          ├─ LAST:   MD5-Hash merken
                          └─ Alle Chunks da: MD5 prüfen, Datei atomar umbenennen, COMPLETE
```

DATA oder LAST vor FIRST (Netzwerk-Reorder): gepuffert im `pending`-Map, sofort
nachverarbeitet, sobald FIRST eintrifft.

## Projektstruktur

```
src/main/java/udp/project/
├── MainTX.java / MainRX.java       CLI-Einstiegspunkte
├── protocol/                       Packet, ControlPacket, PacketSerializer
├── sender/                         Sender (Sliding Window, RTO, Fast Retransmit)
│                                   SlidingWindow
├── receiver/                       Receiver (Empfangsschleife, Session-Dispatching)
│                                   ReceiveSession (.part-Datei, MD5-Prüfung)
└── utils/Md5Util.java
```

## Starten

```bash
mvn clean package -q
```

**Receiver:**
```bash
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainRX <port> [outputDir] [idleTimeoutMs]
```

**Sender:**
```bash
java -cp target/udp-1.0-SNAPSHOT.jar udp.project.MainTX <host> <port> <datei> [delayMs] [txId]
```

Ohne Argumente startet jeweils ein interaktiver Modus.

## Bekannte Einschränkungen

- Keine Verschlüsselung; Daten im Klartext.
- txId ist 16 Bit; bei zufälliger Wahl sehr geringe Kollisionswahrscheinlichkeit.
- Dateinamen mit `../` werden per Directory-Traversal-Schutz abgelehnt.
- Unvollständige `.part`-Dateien werden bei Timeout oder MD5-Fehler gelöscht.

→ [TESTING.md](TESTING.md)
