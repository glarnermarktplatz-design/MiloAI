# Milo AI V1 – GitHub Actions APK

**Private Android-App** mit animierten Augen, abwechselnder freihändiger Sprachunterhaltung (Android-Spracherkennung und TTS), OpenAI `gpt-4o-mini`, lokalem Profil und verschlüsselt gespeichertem API-Schlüssel.

## Ausschliesslich mit dem Samsung: APK über GitHub bauen

1. Auf github.com anmelden und ein **privates, leeres Repository** namens `MiloAI` erstellen. **Keine** README oder `.gitignore` bei der Erstellung hinzufügen.
2. Diese ZIP-Datei auf dem Samsung entpacken. Der ZIP-Inhalt liegt im Ordner `MiloAI`.
3. GitHub-Repository im Browser öffnen, **Add file → Upload files** wählen. Die Dateien **und Ordnerinhalte** aus `MiloAI` hochladen, einschliesslich des versteckten Ordners `.github/workflows/build-apk.yml`. Der GitHub-Mobile-Browser kann Ordner-Uploads erschweren. Am einfachsten auf dem Samsung im Browser **Desktopwebsite** aktivieren und die Dateien per GitHub-Webeditor erstellen/hochladen; alternativ ist ein einmaliger Upload über den privaten Laptop deutlich einfacher.
4. Darauf achten, dass `.github/workflows/build-apk.yml` im Repository wirklich exakt an dieser Stelle liegt und `app/src/main/java/ch/milo/ai/MainActivity.kt` ebenfalls vorhanden ist. Der **Inhalt** des Ordners `MiloAI` muss im Repository-Stamm liegen, nicht ein zusätzlicher Unterordner `MiloAI`.
5. In GitHub **Actions → Milo AI - Android APK → Run workflow** wählen (oder den automatisch gestarteten Push-Build abwarten).
6. Nach erfolgreichem Lauf den Workflow öffnen, unter **Artifacts** `MiloAI-V1-debug-APK` herunterladen, ZIP entpacken, `app-debug.apk` installieren. Gegebenenfalls für Browser/Dateimanager „Unbekannte Apps installieren“ erlauben.
7. Milo starten, OpenAI-API-Key **nur in der App** eingeben, speichern, Profil anpassen, Mikrofonberechtigung erlauben und Gespräch starten.

## Sicherheit und Hinweise

- **Niemals** den OpenAI-API-Key in GitHub, die Workflow-Datei, Issues, Commits oder diesen Chat eintragen. Der Key wird erst in der installierten App eingegeben und per Android Keystore (AES-GCM) lokal verschlüsselt gespeichert.
- Die App sendet erkanntes Gespräch, Profil und Verlauf an OpenAI. Android-Spracherkennung kann zusätzlich die Spracherkennungsdienste des Telefons verwenden.
- Es handelt sich um eine **Debug-APK** für private Tests. Diese ist signiert, aber nicht für den Play Store vorgesehen. Für spätere Updates sollte eine beständige Release-Signatur eingerichtet werden.
- Der Workflow ist eingerichtet, aber in dieser Umgebung **nicht ausgeführt** und die App **nicht auf dem Gerät getestet**. Falls der Build fehlschlägt, die Fehlermeldung aus dem Actions-Log prüfen.
- Die App läuft nur im Vordergrund. Keine Kamera, Gesichtserkennung, Meeting-Aufnahme, Wake-Word-Funktion oder ChatGPT-Kontosynchronisierung in V1.
- „Freihändig“ bedeutet einmal starten, danach automatisch abwechselnd hören und sprechen. Android kann die Spracherkennung bei Stille beenden; die App versucht dann neu zu starten. Für zuverlässiges Dauerhören ist eine spätere Audio-Architektur nötig.
- OpenAI-API-Abrechnung ist unabhängig von ChatGPT Plus.
