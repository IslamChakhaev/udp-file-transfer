"""
UDP Rich Live Monitor

Run from the Java project root:
    python udp-rich-monitor.py

Dependency:
    python -m pip install rich

This version is adapted for the current Java implementation:
- Sender uses Sliding Window and drains ACK/NAK replies in batches.
- Receiver processes UDP packets in small batches and coalesces ACKs.
- The monitor does NOT create rx.log / tx.log files.
- Java RX/TX output is kept only in memory and shown in the dashboard.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

try:
    from rich.console import Console
    from rich.live import Live
    from rich.panel import Panel
    from rich.progress import BarColumn, Progress, TextColumn
    from rich.table import Table
    from rich.text import Text
    RICH_AVAILABLE = True
except Exception:
    RICH_AVAILABLE = False


# ============================================================
# Projektpfade und Java-Einstiegspunkte
#
# Der Monitor wird aus dem Java-Projektordner gestartet.
# Daraus ergeben sich automatisch src/main/java, out und die Main-Klassen.
# ============================================================

PROJECT_ROOT = Path.cwd()
SRC_DIR = PROJECT_ROOT / "src" / "main" / "java"
OUT_DIR = PROJECT_ROOT / "out"

RECEIVER_MAIN = "udp.project.MainRX"
SENDER_MAIN = "udp.project.MainTX"
JAVA_RELEASE = "21"

# ============================================================
# Muster für die Auswertung der Java-Ausgaben
#
# Der Monitor verändert das Protokoll nicht.
# Er liest nur Textausgaben von Sender/Receiver und erkennt daraus:
# - txId
# - ACK / NAK / ERROR
# - gespeicherte RX-Datei
# ============================================================

TX_ID_RE = re.compile(r"txId=(\d+)")
CONTROL_RE = re.compile(r"\b(ACK|NAK|ERROR)\b", re.IGNORECASE)
RX_SAVED_RE = re.compile(r"RX complete: saved=(.+)", re.IGNORECASE)


# ============================================================
# Java-Prozessausgabe im Speicher
#
# RX und TX schreiben nicht in Logdateien.
# Deshalb sammelt der Monitor die letzten Zeilen thread-sicher im RAM.
# Diese Daten werden später im Dashboard angezeigt und ausgewertet.
# ============================================================

@dataclass
class ProcessOutput:
    lines: deque[str] = field(default_factory=lambda: deque(maxlen=80))
    text_parts: deque[str] = field(default_factory=lambda: deque(maxlen=300))
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add_line(self, line: str) -> None:
        # Neue Zeile aus RX/TX übernehmen und leere Zeilen ignorieren.
        clean = line.rstrip("\r\n")
        if not clean:
            return

        with self.lock:
            self.lines.append(clean)
            self.text_parts.append(clean)

    def tail(self, count: int = 6) -> list[str]:
        # Nur die letzten Zeilen für die Live-Ansicht zurückgeben.
        with self.lock:
            items = list(self.lines)[-count:]
        return items if items else ["-"]

    def text(self) -> str:
        # Gesamte gemerkte Ausgabe als Text für Regex-Auswertungen liefern.
        with self.lock:
            return "\n".join(self.text_parts)


# ============================================================
# Gemeinsamer Zustand des Monitors
#
# Hier liegt alles, was die Anzeige braucht:
# - Modus und Dateipfade
# - erwartete Dateigröße
# - laufende Java-Prozesse
# - gesammelte RX/TX-Ausgaben
# ============================================================

@dataclass
class MonitorState:
    mode: str
    source_file: Optional[Path]
    output_dir: Path
    expected_size: Optional[int]
    preferred_final_file: Optional[Path]
    start_time: float
    rx_process: Optional[subprocess.Popen] = None
    tx_process: Optional[subprocess.Popen] = None
    rx_output: ProcessOutput = field(default_factory=ProcessOutput)
    tx_output: ProcessOutput = field(default_factory=ProcessOutput)


# ============================================================
# Eingaben und kleine Hilfsfunktionen
# ============================================================

def ask(prompt: str, default: Optional[str] = None) -> str:
    suffix = f" [{default}]" if default is not None else ""
    value = input(f"{prompt}{suffix}: ").strip()
    return value if value else (default or "")


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        print(f"ERROR: '{name}' not found in PATH.", file=sys.stderr)
        sys.exit(1)


def file_size(path: Optional[Path]) -> int:
    if path is None:
        return 0

    try:
        return path.stat().st_size if path.is_file() else 0
    except OSError:
        return 0


def format_bytes(value: int) -> str:
    size = float(value)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{size:.2f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024

    return f"{value} B"


def process_status(process: Optional[subprocess.Popen]) -> str:
    if process is None:
        return "-"

    code = process.poll()

    if code is None:
        return "running"

    if code == 0:
        return "finished"

    return f"failed ({code})"


# ============================================================
# Transfer-Datei und txId erkennen
#
# Ablauf:
# 1. RX/TX-Ausgaben zusammenführen
# 2. txId aus den Logs lesen
# 3. passende .part-Datei oder endgültige Datei finden
# 4. aktuellen Dateistand für den Fortschritt verwenden
# ============================================================

def combined_output(state: MonitorState) -> str:
    return state.rx_output.text() + "\n" + state.tx_output.text()


def rx_saved_file(state: MonitorState) -> Optional[Path]:
    matches = RX_SAVED_RE.findall(state.rx_output.text())

    if not matches:
        return None

    candidate = Path(matches[-1].strip())

    try:
        return candidate if candidate.is_file() else None
    except OSError:
        return None


def last_tx_id(state: MonitorState) -> Optional[str]:
    matches = TX_ID_RE.findall(combined_output(state))
    return matches[-1] if matches else None


def newest_file(output_dir: Path) -> Optional[Path]:
    if not output_dir.is_dir():
        return None

    candidates: list[Path] = []

    try:
        for item in output_dir.iterdir():
            if item.is_file():
                if item.name.startswith(".udp-") and item.name.endswith(".part"):
                    candidates.append(item)
                elif not item.name.startswith("."):
                    candidates.append(item)
    except OSError:
        return None

    if not candidates:
        return None

    return max(candidates, key=lambda p: p.stat().st_mtime)


def resolve_monitor_file(state: MonitorState) -> Optional[Path]:
    tx_id = last_tx_id(state)

    if tx_id:
        part = state.output_dir / f".udp-{tx_id}.part"
        if part.is_file():
            return part

    saved = rx_saved_file(state)

    if saved:
        return saved

    if state.preferred_final_file and state.preferred_final_file.is_file():
        return state.preferred_final_file

    return newest_file(state.output_dir)


def is_rx_complete(state: MonitorState) -> bool:
    return bool(re.search(r"\bRX complete\b", state.rx_output.text(), flags=re.IGNORECASE))


def is_tx_complete(state: MonitorState) -> bool:
    return bool(re.search(r"\bTX complete\b", state.tx_output.text(), flags=re.IGNORECASE))


def tx_failed(state: MonitorState) -> bool:
    return state.tx_process is not None and state.tx_process.poll() not in (None, 0)


def rx_status(state: MonitorState) -> str:
    if state.rx_process is None:
        return "-"

    if is_rx_complete(state):
        return "finished"

    return process_status(state.rx_process)


def tx_status(state: MonitorState) -> str:
    if state.tx_process is None:
        return "-"

    if is_tx_complete(state):
        return "finished"

    return process_status(state.tx_process)


# ============================================================
# Fortschritt und Protokollereignisse auswerten
#
# Der Monitor zählt nur sichtbare Ausgaben.
# Er sendet selbst keine ACK/NAK/COMPLETE-Pakete.
# ============================================================

def count_events(state: MonitorState) -> dict[str, int]:
    text = combined_output(state)
    result = {"ACK": 0, "NAK": 0, "COMPLETE": 0, "ERROR": 0}

    for match in CONTROL_RE.findall(text):
        key = match.upper()
        if key in result:
            result[key] += 1

    # Die Java-Ausgabe enthält meistens "RX complete" / "TX complete",
    # nicht immer das rohe COMPLETE-Control-Paket.
    result["COMPLETE"] += len(re.findall(r"\b(?:RX|TX) complete\b", text, flags=re.IGNORECASE))

    return result


def progress_percent(current_size: int, expected_size: Optional[int]) -> float:
    if not expected_size:
        return 0.0

    return min(100.0, current_size * 100.0 / expected_size)


# ============================================================
# Java-Projekt kompilieren
#
# Ablauf:
# 1. javac prüfen
# 2. alte out/-Klassen löschen
# 3. alle Java-Dateien sammeln
# 4. mit --release 21 nach out/ kompilieren
# ============================================================

def compile_project() -> None:
    require_tool("javac")

    if not SRC_DIR.is_dir():
        print(f"ERROR: source directory not found: {SRC_DIR}", file=sys.stderr)
        sys.exit(1)

    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    sources = sorted(str(path) for path in SRC_DIR.rglob("*.java"))

    if not sources:
        print("ERROR: no Java source files found.", file=sys.stderr)
        sys.exit(1)

    # Projektordner sauber halten:
    # javac nutzt weiterhin eine Argumentdatei, aber sie liegt im Temp-Verzeichnis
    # und wird nach der Kompilierung sofort gelöscht.
    temp_sources_file: Optional[Path] = None

    try:
        with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                suffix=".txt",
                prefix="udp-java-sources-",
                delete=False
        ) as file:
            file.write("\n".join(sources))
            temp_sources_file = Path(file.name)

        command = [
            "javac",
            "--release",
            JAVA_RELEASE,
            "-encoding",
            "UTF-8",
            "-d",
            str(OUT_DIR),
            f"@{temp_sources_file}",
        ]

        subprocess.run(command, cwd=PROJECT_ROOT, check=True)
    finally:
        if temp_sources_file is not None:
            try:
                temp_sources_file.unlink(missing_ok=True)
            except OSError:
                pass


# ============================================================
# Java-Prozesse starten, Ausgaben lesen und Prozesse stoppen
#
# RX/TX laufen als normale Java-Prozesse.
# Ein Hintergrund-Thread liest stdout und speichert die Zeilen in ProcessOutput.
# ============================================================

def start_process(args: list[str], output: ProcessOutput) -> subprocess.Popen:
    process = subprocess.Popen(
        args,
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    thread = threading.Thread(
        target=read_process_output,
        args=(process, output),
        daemon=True,
    )
    thread.start()

    return process


def read_process_output(process: subprocess.Popen, output: ProcessOutput) -> None:
    # stdout des Java-Prozesses fortlaufend lesen.
    stream = process.stdout

    if stream is None:
        return

    try:
        for line in stream:
            output.add_line(line)
    except Exception as e:
        output.add_line(f"[monitor] output reader stopped: {e}")


def stop_process(process: Optional[subprocess.Popen]) -> None:
    # Beim Beenden des Monitors RX/TX sauber stoppen.
    if process is None or process.poll() is not None:
        return

    try:
        process.terminate()
        process.wait(timeout=2)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass


# ============================================================
# Rich-Dashboard bauen
#
# Ablauf der Anzeige:
# 1. aktuelle Transfer-Datei bestimmen
# 2. Größe, Fortschritt und Geschwindigkeit berechnen
# 3. RX/TX-Status anzeigen
# 4. letzte Ausgaben von Sender und Receiver nebeneinander darstellen
# ============================================================

def make_rich_view(state: MonitorState):
    monitor_file = resolve_monitor_file(state)
    current_size = file_size(monitor_file)
    elapsed = max(1, int(time.time() - state.start_time))
    speed = current_size // elapsed
    percent = progress_percent(current_size, state.expected_size)
    tx_id = last_tx_id(state) or "not detected yet"

    # Obere Tabelle: Kerndaten der aktuellen Übertragung.
    table = Table.grid(expand=True)
    table.add_column(justify="left", ratio=1)
    table.add_column(justify="left", ratio=2)

    table.add_row("Mode", state.mode)
    table.add_row("TxId", tx_id)
    table.add_row("Source file", str(state.source_file) if state.source_file else "-")
    table.add_row("Output dir", str(state.output_dir))
    table.add_row("Monitor file", str(monitor_file) if monitor_file else "not detected yet")
    table.add_row("Expected size", format_bytes(state.expected_size or 0) if state.expected_size else "unknown")
    table.add_row("Current size", format_bytes(current_size))
    table.add_row("Elapsed", f"{elapsed}s")
    table.add_row("Speed", f"{format_bytes(speed)}/s")
    table.add_row("RX status", rx_status(state))
    table.add_row("TX status", tx_status(state))
    table.add_row("RX complete", "yes" if is_rx_complete(state) else "no")
    table.add_row("TX complete", "yes" if is_tx_complete(state) else "no")

    # Fortschrittsbalken: basiert auf aktueller Dateigröße und erwarteter Größe.
    progress = Progress(
        TextColumn("Progress"),
        BarColumn(bar_width=44),
        TextColumn("{task.percentage:>6.2f}%"),
        expand=False,
    )
    progress.add_task("transfer", total=100, completed=percent)

    # Untere Ansicht: letzte Sender- und Receiver-Ausgaben.
    logs = Table.grid(expand=True)
    logs.add_column(ratio=1)
    logs.add_column(ratio=1)

    tx_tail = "\n".join(state.tx_output.tail(7))
    rx_tail = "\n".join(state.rx_output.tail(7))

    logs.add_row(
        Panel(tx_tail, title="Last Sender output", border_style="blue"),
        Panel(rx_tail, title="Last Receiver output", border_style="green"),
    )

    content = Table.grid(expand=True)
    content.add_row(table)
    content.add_row(progress)
    content.add_row(logs)
    content.add_row(Text("Press Ctrl+C to stop.", style="dim"))

    return Panel(content, title="UDP TRANSFER LIVE DASHBOARD", border_style="cyan")


# ============================================================
# Fallback-Anzeige ohne Rich
#
# Wenn rich nicht installiert ist, wird eine einfache Textansicht verwendet.
# Die Logik bleibt gleich, nur die Darstellung ist einfacher.
# ============================================================

def fallback_clear() -> None:
    if os.name == "nt":
        os.system("cls")
    else:
        sys.stdout.write("\033[H\033[2J")
        sys.stdout.flush()


def make_plain_view(state: MonitorState) -> str:
    # Gleiche Informationen wie im Rich-Dashboard, aber als reiner Text.
    monitor_file = resolve_monitor_file(state)
    current_size = file_size(monitor_file)
    elapsed = max(1, int(time.time() - state.start_time))
    speed = current_size // elapsed
    percent = progress_percent(current_size, state.expected_size)
    filled = int(percent / 100 * 40)
    bar = "#" * filled + "-" * (40 - filled)
    return "\n".join([
        "================ UDP TRANSFER LIVE DASHBOARD ================",
        f"Mode:           {state.mode}",
        f"TxId:           {last_tx_id(state) or 'not detected yet'}",
        f"Source file:    {state.source_file or '-'}",
        f"Output dir:     {state.output_dir}",
        f"Monitor file:   {monitor_file or 'not detected yet'}",
        f"Expected size:  {format_bytes(state.expected_size or 0) if state.expected_size else 'unknown'}",
        f"Current size:   {format_bytes(current_size)}",
        f"Progress:       [{bar}] {percent:.2f}%",
        f"Elapsed:        {elapsed}s",
        f"Speed:          {format_bytes(speed)}/s",
        f"RX status:      {rx_status(state)}",
        f"TX status:      {tx_status(state)}",
        f"RX complete:    {'yes' if is_rx_complete(state) else 'no'}",
        f"TX complete:    {'yes' if is_tx_complete(state) else 'no'}",
        "",
        "Last Sender output:",
        *state.tx_output.tail(6),
        "",
        "Last Receiver output:",
        *state.rx_output.tail(6),
        "==============================================================",
        "Press Ctrl+C to stop.",
    ])


# ============================================================
# Live-Schleife, Auto-Stop und Zusammenfassung
# ============================================================

def should_stop(state: MonitorState) -> bool:
    monitor_file = resolve_monitor_file(state)
    current_size = file_size(monitor_file)

    if tx_failed(state):
        return True

    if state.expected_size and current_size >= state.expected_size and is_rx_complete(state):
        return True

    if state.tx_process is not None and state.tx_process.poll() is not None and is_rx_complete(state):
        return True

    return False


def run_live(state: MonitorState, auto_stop: bool) -> None:
    # Anzeige regelmäßig aktualisieren, bis Transfer fertig ist oder Ctrl+C kommt.
    try:
        if RICH_AVAILABLE:
            console = Console()

            with Live(make_rich_view(state), console=console, refresh_per_second=8, screen=False, transient=False) as live:
                while True:
                    live.update(make_rich_view(state))

                    if auto_stop and should_stop(state):
                        time.sleep(0.4)
                        live.update(make_rich_view(state))
                        break

                    time.sleep(0.125)
        else:
            while True:
                fallback_clear()
                print(make_plain_view(state), flush=True)

                if auto_stop and should_stop(state):
                    time.sleep(0.4)
                    fallback_clear()
                    print(make_plain_view(state), flush=True)
                    break

                time.sleep(0.125)
    except KeyboardInterrupt:
        pass


def print_summary(state: MonitorState) -> None:
    # Am Ende eine feste Zusammenfassung ausgeben.
    monitor_file = resolve_monitor_file(state)
    current_size = file_size(monitor_file)
    elapsed = max(1, int(time.time() - state.start_time))
    percent = progress_percent(current_size, state.expected_size)
    print("\n================ TRANSFER SUMMARY ================")
    print(f"Mode:          {state.mode}")
    print(f"Source file:   {state.source_file or '-'}")
    print(f"Monitor file:  {monitor_file or '-'}")
    print(f"Expected size: {format_bytes(state.expected_size or 0) if state.expected_size else 'unknown'}")
    print(f"Current size:  {format_bytes(current_size)}")
    print(f"Progress:      {percent:.2f}%")
    print(f"Duration:      {elapsed}s")
    print(f"Average speed: {format_bytes(current_size // elapsed)}/s")
    print(f"RX status:     {rx_status(state)}")
    print(f"TX status:     {tx_status(state)}")
    print("")
    print("Last Sender output:")
    for line in state.tx_output.tail(8):
        print(line)
    print("")
    print("Last Receiver output:")
    for line in state.rx_output.tail(8):
        print(line)
    print("==================================================")


# ============================================================
# Betriebsarten
#
# 1. Local demo    -> Receiver und Sender automatisch starten
# 2. Receive only  -> nur Receiver starten
# 3. Monitor only  -> nur vorhandenen Zielordner beobachten
# ============================================================

def local_demo() -> MonitorState:
    # Eingaben für eine komplette lokale Demo sammeln.
    source = Path(ask("Source file path", "test.txt")).resolve()
    output_dir = Path(ask("Receiver output directory", "received")).resolve()
    host = ask("Receiver host", "127.0.0.1")
    port = ask("UDP port", "9000")
    delay = ask("Sender delay ms", "0")

    if not source.is_file():
        print(f"ERROR: source file not found: {source}", file=sys.stderr)
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)
    final_file = output_dir / source.name

    # Java-Code kompilieren, bevor RX/TX gestartet werden.
    compile_project()

    rx_output = ProcessOutput()
    tx_output = ProcessOutput()

    # Phase 1: Receiver starten.
    rx = start_process(
        ["java", "-cp", str(OUT_DIR), RECEIVER_MAIN, port, str(output_dir)],
        rx_output,
    )

    time.sleep(0.4)

    # Phase 2: Sender starten, nachdem der Receiver kurz Zeit zum Starten hatte.
    tx = start_process(
        ["java", "-cp", str(OUT_DIR), SENDER_MAIN, host, port, str(source), delay],
        tx_output,
    )

    return MonitorState(
        mode="Local demo",
        source_file=source,
        output_dir=output_dir,
        expected_size=file_size(source),
        preferred_final_file=final_file,
        start_time=time.time(),
        rx_process=rx,
        tx_process=tx,
        rx_output=rx_output,
        tx_output=tx_output,
    )


def receive_only() -> MonitorState:
    # Nur Receiver starten, wenn ein externer Sender verwendet wird.
    output_dir = Path(ask("Receiver output directory", "received")).resolve()
    port = ask("Local UDP port", "9000")
    expected_raw = ask("Expected file size in bytes, empty = unknown", "")
    expected_size = int(expected_raw) if expected_raw else None

    output_dir.mkdir(parents=True, exist_ok=True)

    compile_project()

    rx_output = ProcessOutput()

    rx = start_process(
        ["java", "-cp", str(OUT_DIR), RECEIVER_MAIN, port, str(output_dir)],
        rx_output,
    )

    return MonitorState(
        mode="Receive only",
        source_file=None,
        output_dir=output_dir,
        expected_size=expected_size,
        preferred_final_file=None,
        start_time=time.time(),
        rx_process=rx,
        rx_output=rx_output,
    )


def monitor_only() -> MonitorState:
    # Keine Java-Prozesse starten, sondern nur einen Ordner beobachten.
    output_dir = Path(ask("Directory to monitor", "received")).resolve()
    expected_raw = ask("Expected file size in bytes, empty = unknown", "")
    expected_size = int(expected_raw) if expected_raw else None
    final_raw = ask("Final file path, empty = auto-detect", "")
    final_file = Path(final_raw).resolve() if final_raw else None

    return MonitorState(
        mode="Monitor only",
        source_file=None,
        output_dir=output_dir,
        expected_size=expected_size,
        preferred_final_file=final_file,
        start_time=time.time(),
    )


# ============================================================
# Programmstart
# ============================================================

def main() -> None:
    print("================ UDP RICH LIVE MONITOR ================")
    print("1 - Local demo: start Receiver + Sender")
    print("2 - Receive from external Sender: start Receiver only")
    print("3 - Monitor only: start nothing")
    print("=======================================================")

    if not RICH_AVAILABLE:
        print("NOTE: Rich is not installed. For the best live dashboard run:")
        print("      python -m pip install rich")
        print("      Then start this script again.\n")

    mode = ask("Choose mode", "1")
    state: Optional[MonitorState] = None

    try:
        # Modus auswählen und danach dieselbe Live-Anzeige verwenden.
        if mode == "1":
            state = local_demo()
            run_live(state, auto_stop=True)
        elif mode == "2":
            state = receive_only()
            run_live(state, auto_stop=True)
        elif mode == "3":
            state = monitor_only()
            run_live(state, auto_stop=False)
        else:
            print(f"Unknown mode: {mode}")
            sys.exit(1)
    finally:
        # Egal wie der Monitor endet: Prozesse stoppen und Summary ausgeben.
        if state:
            stop_process(state.tx_process)
            stop_process(state.rx_process)
            print_summary(state)


if __name__ == "__main__":
    main()
