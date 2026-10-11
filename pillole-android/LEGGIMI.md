# Le mie pillole – app Android

App Android con sveglie di sistema: suonano all'orario anche a telefono bloccato,
con l'app chiusa e dopo un riavvio.

## Creare l'APK (gratis, con GitHub)

1. Crea un account su https://github.com e poi un nuovo repository privato (pulsante **New**).
2. Nel repository: **Add file → Upload files**. Trascina **tutto il contenuto** di questa cartella,
   compresa la cartella `.github`, poi **Commit changes**.
   - Se la cartella `.github` non viene caricata: **Add file → Create new file**, nome
     `.github/workflows/crea-apk.yml`, incolla il contenuto del file omonimo e salva.
3. Apri la scheda **Actions**: parte "Crea APK" (circa 5 minuti). Quando è verde, aprila e scarica
   **LeMiePillole-APK** in fondo alla pagina. Dentro c'è `LeMiePillole.apk`.

## Installare sul telefono

1. Copia `LeMiePillole.apk` sul telefono e aprilo. Consenti l'installazione da questa fonte.
2. Al primo avvio consenti le **notifiche**.
3. Nell'app: ingranaggio → **Sveglie del telefono**. Se compare "Concedi il permesso", toccalo.
4. Tocca **Togli le restrizioni della batteria** e scegli "Senza restrizioni".
5. Tocca **Prova la sveglia**, blocca il telefono e aspetta 10 secondi.

## Come funziona la sveglia

- Suona con la suoneria della sveglia e si ripete finché non scegli **Ferma** o **Posticipa 10 min**.
- Se assumi la pillola prima dell'orario, la sveglia di quella pillola non suona.
- Ogni giorno le sveglie ripartono dagli orari programmati, anche se non apri l'app.
