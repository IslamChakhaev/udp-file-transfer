# UDP File Transfer

![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/Build-Maven-blue)
![Protocol](https://img.shields.io/badge/Protocol-UDP-lightgrey)
![Reliability](https://img.shields.io/badge/Reliability-ACK%2FNAK-green)
![Window](https://img.shields.io/badge/Sliding%20Window-supported-brightgreen)
![Integrity](https://img.shields.io/badge/Integrity-MD5-yellow)

Dieses Projekt überträgt Dateien über **UDP**. Da UDP selbst keine zuverlässige Übertragung garantiert, ergänzt das Projekt einen eigenen einfachen Mechanismus für Reihenfolge, Bestätigung, erneutes Senden und Integritätsprüfung.

Der Sender zerlegt eine Datei in mehrere Pakete. Der Receiver setzt diese Pakete wieder zusammen und speichert die Datei erst dann endgültig, wenn sie vollständig angekommen ist und der MD5-Hash stimmt.

## Idee des Projekts

UDP ist schnell, garantiert aber nicht, dass Pakete ankommen, nur einmal ankommen oder in der richtigen Reihenfolge eintreffen. Für eine Dateiübertragung reicht reines UDP deshalb nicht aus.

Deshalb verwendet dieses Projekt zusätzlich zu UDP:

- Sequenznummern für die richtige Reihenfolge der Datenpakete
- ACK-Pakete für bestätigte Daten
- NAK-Pakete für fehlende Daten
- Sliding Window für mehrere gleichzeitig gesendete Pakete
- Retransmission für erneutes Senden verlorener Pakete
- MD5 zur Prüfung, ob die empfangene Datei dem Original entspricht


## Projektstruktur

```text
src/main/java/udp/project/
├── MainRX.java              # startet den Receiver
├── MainTX.java              # startet den Sender
├── protocol/                # Pakettypen und Serialisierung
├── sender/                  # Sendelogik und Sliding Window
├── receiver/                # Empfangslogik und Dateiaufbau
└── utils/                   # MD5-Hilfsfunktionen
```

Wichtige Klassen:

| Klasse | Aufgabe |
|---|---|
| `MainRX` | Startet den Receiver. |
| `MainTX` | Startet den Sender. |
| `Sender` | Sendet FIRST, DATA, LAST und reagiert auf ACK, NAK, COMPLETE und ERROR. |
| `Receiver` | Empfängt UDP-Pakete und verwaltet laufende Übertragungen. |
| `ReceiveSession` | Baut aus empfangenen DATA-Paketen wieder eine Datei zusammen. |
| `SlidingWindow` | Merkt, welche DATA-Pakete bereits gesendet und bestätigt wurden. |
| `PacketSerializer` | Wandelt Pakete in Bytes und Bytes wieder in Objekte um. |
| `Md5Util` | Berechnet MD5-Hashes. |

## Protokoll

Die Übertragung arbeitet mit Datenpaketen vom Sender zum Receiver und Kontrollpaketen vom Receiver zurück zum Sender.

**Sender -> Receiver**

| Paket | Bedeutung |
|---|---|
| `FIRST` | Startet eine neue Übertragung. Enthält Dateiname und Anzahl der DATA-Pakete. |
| `DATA` | Enthält einen Teil der Datei. Jedes DATA-Paket hat eine Sequenznummer. |
| `LAST` | Beendet die Datenphase und enthält den MD5-Hash der Originaldatei. |

**Receiver -> Sender**

| Paket | Bedeutung |
|---|---|
| `ACK` | Bestätigt korrekt empfangene Pakete. |
| `NAK` | Fordert fehlende Pakete erneut an. |
| `COMPLETE` | Meldet, dass die Datei vollständig und korrekt angekommen ist. |
| `ERROR` | Meldet einen Fehler beim Receiver. |

Alle Zahlen werden in **Big-Endian** übertragen. DATA-Pakete verwenden Sequenznummern ab `1`. Das FIRST-Paket verwendet `seq = 0`, das LAST-Paket kommt nach den DATA-Paketen.

## Ablauf

1. Der Sender liest die Datei, berechnet den MD5-Hash und teilt die Datei in DATA-Pakete auf.
2. Danach sendet er FIRST, damit der Receiver eine neue Übertragung vorbereiten kann.
3. Anschließend sendet der Sender mehrere DATA-Pakete über ein Sliding Window.
4. Der Receiver bestätigt empfangene Pakete mit ACK und fordert fehlende Pakete mit NAK erneut an.
5. Fehlende Pakete werden vom Sender erneut gesendet.
6. Am Ende sendet der Sender LAST mit dem MD5-Hash.
7. Der Receiver prüft die Datei und sendet COMPLETE, wenn alles korrekt ist.


## Start

--- Compile ---

```bash
javac -d out -sourcepath src/main/java src/main/java/udp/project/MainRX.java src/main/java/udp/project/MainTX.java
```

--- Receiver ---
```bash
java -cp out udp.project.MainRX 9000 received 40000
```
--- Sender ---
```bash
java -cp out udp.project.MainTX 127.0.0.1 9000 test.txt 1
```
## Kompatibilität

Normalerweise werden Sender und Receiver aus diesem Projekt zusammen verwendet. Eine andere Implementierung kann aber ebenfalls funktionieren, wenn sie dasselbe Binärprotokoll nutzt: FIRST, DATA, LAST, ACK, NAK, COMPLETE, Sequenznummern, Big-Endian und eine passende DATA-Größe.
