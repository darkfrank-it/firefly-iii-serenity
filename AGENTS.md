# AGENTS.md

Istruzioni per gli agenti che lavorano su questo repository.

## Progetto

Applicazione Java a riga di comando che legge gli insight (entrate, uscite, trasferimenti per categoria) da
Firefly III tramite API REST e li scrive in un foglio di calcolo `.ods`. Vedere `README.md` per l'uso.

- `src/main/java/it/darkfrank/Main.java`: parsing CLI, caricamento configurazione, esportazione nel foglio.
- `src/main/java/it/darkfrank/OAuthClient.java`: flusso OAuth2 (code, refresh token), salvataggio token, client HTTP.
- `src/main/java/it/darkfrank/UsageException.java`: errori dovuti all'utente, mostrati senza stack trace.
- `src/main/resources/firefly-iii-*-v1.yaml`: specifica OpenAPI di Firefly III da cui viene generato il client.

## Build

Richiede Java 17 e Maven (non c'è il Maven wrapper).

```shell
mvn clean package
```

Il jar con tutte le dipendenze è `target/firefly-iii-serenity.jar`.

Il client API (`org.openapitools.client.*`) viene generato durante la build in `target/generated-sources/openapi`
dall'`openapi-generator-maven-plugin`: non modificarlo a mano. Per aggiornare le API si sostituisce il file yaml
in `src/main/resources` e si aggiorna `inputSpec` nel `pom.xml`.

`disallowAdditionalPropertiesIfNotPresent=false` nel `pom.xml` è necessario: Firefly restituisce campi non
dichiarati nella specifica (es. `links` in `CategoryArray`) e senza questa opzione il client generato rifiuta
le risposte.

## Test

Non ci sono test automatici. Per verificare una modifica senza un'istanza reale di Firefly III si può usare un
server HTTP finto che risponde a `/oauth/token`, `/api/v1/categories` e `/api/v1/insight/{income,expense,transfer}/category`
e un `.ods` di prova con un foglio chiamato come l'anno. Le risposte finte devono includere anche campi non presenti
nella specifica (come `links`), perché l'istanza reale li restituisce.

## Convenzioni

- Commenti, Javadoc e messaggi di log sono in italiano; i messaggi di commit sono in inglese.
- Gli errori causati da input, configurazione o stato locale dell'utente usano `UsageException`; gli altri
  errori vengono propagati e loggati con stack trace.
- Non committare mai `config.properties`, `secrets.properties` o fogli `.ods` personali (sono in `.gitignore`):
  contengono credenziali e dati finanziari.

## Versioni e release

- La versione nel `pom.xml` usa il formato `AA.MM.GG` (es. `26.09.25`) e viene inserita in
  `application.properties` durante la build, poi mostrata nel logo all'avvio.
- Per pubblicare una release: aggiornare la versione nel `pom.xml`, fare il commit, poi creare e pushare una tag
  con lo stesso nome (senza `v`):

  ```shell
  git tag 26.09.25
  git push origin 26.09.25
  ```

  La GitHub Action `.github/workflows/release.yml` compila il progetto, verifica che la tag corrisponda alla
  versione del `pom.xml` e crea la release su GitHub allegando il jar.

## Note sul comportamento

- Il foglio deve avere un tab chiamato come l'anno; la colonna A contiene le categorie, le colonne B–M i mesi.
- Le celle con formula non vengono mai sovrascritte; le categorie di Firefly senza movimenti nel mese vengono
  svuotate (cella vuota, non 0).
- ODF Toolkit non ricalcola le formule: l'utente deve ricalcolarle nel foglio (F9).
- I file `.properties` non supportano commenti a fine riga (`chiave=valore # commento`).
