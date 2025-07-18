package it.darkfrank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.commons.cli.*;
import org.jetbrains.annotations.Nullable;
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.odftoolkit.odfdom.doc.table.OdfTableRow;
import org.openapitools.client.ApiClient;
import org.openapitools.client.api.InsightApi;
import org.openapitools.client.model.InsightGroupEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String SECRETS_FILE = "secrets.properties";
    private static final String CONFIG_FILE = "config.properties";

    private static final String ACCESS_TOKEN_KEY = "access_token";
    private static final String REFRESH_TOKEN_KEY = "refresh_token";

    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String CLIENT_ID_KEY = "client_id";
    private static final String ACCOUNT_ID_KEY = "account_id";
    private static final String FIREFLY_III_BASE_URL_KEY = "firefly_iii_base_url";
    private static final String SPREADSHEET_FULL_PATH_KEY = "spreadsheet_full_path";
    private static final String REDIRECT_URI_KEY = "redirect_uri";
    private static final String SKIP_SSL_VERIFICATION_KEY = "skip_ssl_validation";

    /**
     * Ottiene un jwt token di autenticazione, per farlo ha bisogna, per prima cosa creare un client OAuth2
     * seguendo le istruzioni: <a href="https://docs.firefly-iii.org/how-to/firefly-iii/features/api/">api</a>
     * Poi bisogna ottenere il codice di accesso che si ottiene collegandosi da un browser autenticato su firefly-iii a:
     * <url>https://<base_url>/oauth/authorize?response_type=code&client_id=<client_id>&redirect_uri=<redirect_uri>&scope=&state=</url>
     * nella barra dell'indirizzo apparirà una stringa con il code=<code> da passare in input al programma.
     * Il codice scade dopo un po' di tempo.
     *
     * @param config parametri di configurazione
     * @param client OkHttpClient
     * @param code   il codice univoco
     * @return jwt access token
     * @throws IOException se il server ritorna un codice diverso da 200
     */
    private static String obtainAccessToken(Properties config, OkHttpClient client, String code) throws IOException {

        RequestBody formBody = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", config.getProperty(CLIENT_ID_KEY))
                .add("client_secret", config.getProperty(CLIENT_SECRET_KEY))
                .add("code", code)
                .add("redirect_uri", config.getProperty(REDIRECT_URI_KEY))
                .build();

        Request request = new Request.Builder()
                .url(config.getProperty(FIREFLY_III_BASE_URL_KEY) + "/oauth/token")
                .post(formBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            assert response.body() != null;
            String responseBody = response.body().string();

            // Estrai il token (usando Jackson o altro parser JSON)
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(responseBody);
            String accessToken = json.get(ACCESS_TOKEN_KEY).asText();
            String refreshToken = json.get(REFRESH_TOKEN_KEY).asText();

            Properties props = new Properties();
            props.setProperty(REFRESH_TOKEN_KEY, refreshToken);
            try (FileOutputStream out = new FileOutputStream(SECRETS_FILE)) {
                props.store(out, "File di configurazione utente");
                logger.info("Chiavi salvate con successo!");
            } catch (IOException e) {
                logger.error("Errore scrittura properties", e);
            }
            return accessToken;
        }
    }

    /**
     * Se è presente il refresh token salvato nel file di properties apposito, allora lo ritorna, altrimenti ritorna <code>null</code>
     *
     * @return refresh token oppure null
     */
    @Nullable
    private static String isRefreshTokenPresent() {

        try (FileInputStream in = new FileInputStream(SECRETS_FILE)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(REFRESH_TOKEN_KEY);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            logger.error("Errore lettura properties", e);
        }

        return null;
    }

    /**
     * Rinnova il jwt token di autenticazione tramite il refresh token passato in input.
     *
     * @param config       parametri di configurazione
     * @param client       OkHttpClient
     * @param refreshToken refresh token
     * @return jwt access token
     * @throws IOException se il server ritorna un codice diverso da 200
     */
    private static String renewAccessToken(Properties config, OkHttpClient client, String refreshToken) throws IOException {

        RequestBody formBody = new FormBody.Builder()
                .add("grant_type", REFRESH_TOKEN_KEY)
                .add("client_id", config.getProperty(CLIENT_ID_KEY))
                .add("client_secret", config.getProperty(CLIENT_SECRET_KEY))
                .add(REFRESH_TOKEN_KEY, refreshToken)
                .build();

        Request request = new Request.Builder()
                .url(config.getProperty(FIREFLY_III_BASE_URL_KEY) + "/oauth/token")
                .post(formBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            assert response.body() != null;
            String responseBody = response.body().string();

            // Estrai il token (usando Jackson o altro parser JSON)
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(responseBody);
            String accessToken = json.get(ACCESS_TOKEN_KEY).asText();
            refreshToken = json.get(REFRESH_TOKEN_KEY).asText();

            Properties props = new Properties();
            props.setProperty(REFRESH_TOKEN_KEY, refreshToken);
            try (FileOutputStream out = new FileOutputStream(SECRETS_FILE)) {
                props.store(out, "File di configurazione utente");
                logger.info("Chiavi salvate con successo!");
            } catch (IOException e) {
                logger.error("Errore scrittura properties", e);
            }
            return accessToken;
        }
    }

    /**
     * Crea un OkHttpClient, se skip_ssl_validation == true ritorna un client insicuro che salta la validazione ssl.
     *
     * @param config parametri di configurazione
     * @return OkHttpClient
     */
    private static OkHttpClient getOkHttpClient(Properties config) {
        try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder();

            if ("true".equals(config.getProperty(SKIP_SSL_VERIFICATION_KEY))) {
                // Create a trust manager that does not validate certificate chains
                final TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[]{};
                            }
                        }
                };

                // Install the all-trusting trust manager
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                // Create an ssl socket factory with our all-trusting manager
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
                builder.hostnameVerifier((hostname, session) -> true);
            }

            // Scommentare questo codice per log di debug delle chiamate REST
//            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
//            logging.setLevel(HttpLoggingInterceptor.Level.BODY); // Log completo
//            builder.addInterceptor(logging);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        // Definizione delle opzioni
        Options options = new Options();
        options.addOption("code", true, "Codice di accesso monouso");
        options.addOption("year", true, "Anno di riferimento");
        options.addOption("month", true, "Mese di riferimento");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        String code;
        String year;
        String month;
        try {
            CommandLine cmd = parser.parse(options, args);
            code = cmd.getOptionValue("code");
            year = cmd.getOptionValue("year");
            month = cmd.getOptionValue("month");
        } catch (ParseException e) {
            logger.error("Errore nel parsing dei parametri.");
            formatter.printHelp("Main", options);
            throw new IOException("Errore nel parsing dei parametri.");
        }

        // Carico le configurazioni
        Properties config = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            config.load(in);
        }

        OkHttpClient client = getOkHttpClient(config);

        // Questo workflow di autenticazione si basa su questa discussione: https://github.com/orgs/firefly-iii/discussions/4595
        String accessToken;
        String refreshToken = isRefreshTokenPresent();
        if (refreshToken == null) {
            if (code == null) {
                logger.error("Non è presente nessun refresh token, tuttavia non è stato passato il code in input");
                throw new IOException("No refresh token or code provided");
            }
            accessToken = obtainAccessToken(config, client, args[0]);
        } else {
            accessToken = renewAccessToken(config, client, refreshToken);
        }

        // Create ApiClient
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClient(client);
        apiClient.setBasePath(config.getProperty(FIREFLY_III_BASE_URL_KEY) + "/api");
        apiClient.setBearerToken(accessToken);

        InsightApi insightApi = new InsightApi(apiClient);

        // Carica il file .ods
        try (OdfSpreadsheetDocument ods = OdfSpreadsheetDocument.loadDocument(config.getProperty(SPREADSHEET_FULL_PATH_KEY))) {

            List<Long> accounts = null;
            var acc = config.getProperty(ACCOUNT_ID_KEY);
            if (acc != null) {
                accounts = List.of(Long.parseLong(acc));
            }

            var currYear = LocalDate.now().getYear();

            if (year == null) {
                year = String.valueOf(currYear);
            }
            var sheet = getTableByName(ods, year);

            Map<String, Integer> categoryIndex = new HashMap<>();
            buildCategoryIndex(sheet, categoryIndex);

            if (month != null) {
                exportCategoryByMonth(Integer.parseInt(year), Integer.parseInt(month), accounts, insightApi, categoryIndex, sheet);
            } else {
                for (int i = 1; i <= ((Integer.parseInt(year) < currYear) ? 12 : LocalDate.now().getMonthValue()); i++) {
                    exportCategoryByMonth(Integer.parseInt(year), i, accounts, insightApi, categoryIndex, sheet);
                }
            }

            // Salva il file modificato
            ods.save(config.getProperty(SPREADSHEET_FULL_PATH_KEY));
        }

        logger.info("Modifica completata.");
    }

    /**
     * Costruisce l'indice delle categori basato sulla colonna A
     *
     * @param sheet         OdfTable
     * @param categoryIndex Map<String, Integer>
     */
    private static void buildCategoryIndex(OdfTable sheet, Map<String, Integer> categoryIndex) {
        for (int i = 0; i < sheet.getRowCount(); i++) {
            OdfTableRow row = sheet.getRowByIndex(i);
            OdfTableCell cell = row.getCellByIndex(0); // colonna A
            String cellText = cell.getStringValue();

            if (cellText != null && !cellText.isEmpty()) {
                categoryIndex.put(cellText, i);
            }
        }
    }

    /**
     * Seleziona il tab del foglio di lavoro con il cui nome corrisponde con l'anno selezionato.
     *
     * @param ods  OdfSpreadsheetDocument
     * @param year anno
     * @return OdfTable
     * @throws IOException se non trova il foglio
     */
    private static OdfTable getTableByName(OdfSpreadsheetDocument ods, String year) throws IOException {
        // Seleziona il foglio con nome "2025"
        var sheet = ods.getTableByName(year);
        if (sheet == null) {
            logger.error("Foglio '{}' non trovato.", year);
            throw new IOException("Foglio non trovato.");
        }
        return sheet;
    }

    /**
     * Richiama InsightApi insightIncomeCategory, insightExpenseCategory e le riporta nel file excel
     *
     * @param year          anno
     * @param month         mese
     * @param insightApi    InsightApi
     * @param categoryIndex mappa categoria-indice riga
     * @param sheet         OdfTable
     */
    private static void exportCategoryByMonth(int year, int month, @Nullable List<Long> accounts, InsightApi insightApi, Map<String, Integer> categoryIndex, OdfTable sheet) {
        // Crea un oggetto YearMonth
        YearMonth yearMonth = YearMonth.of(year, month);
        // Primo giorno del mese
        LocalDate firstDay = yearMonth.atDay(1);
        // Ultimo giorno del mese
        LocalDate lastDay = yearMonth.atEndOfMonth();

        logger.info("working insightIncomeCategory y: {} m: {}...", year, month);
        var income = insightApi.insightIncomeCategory(firstDay, lastDay, null, null, accounts);
        setValuesOnSheet(month, categoryIndex, sheet, income);

        logger.info("working insightTransferCategory y: {} m: {}...", year, month);
        var transfer = insightApi.insightTransferCategory(firstDay, lastDay, null, null, accounts);
        setValuesOnSheet(month, categoryIndex, sheet, transfer);

        logger.info("working insightExpenseCategory y: {} m: {}...", year, month);
        var expense = insightApi.insightExpenseCategory(firstDay, lastDay, null, null, accounts);
        setValuesOnSheet(month, categoryIndex, sheet, expense);
    }

    /**
     * Riporta i valori nel file excel
     *
     * @param month         individua la colonna
     * @param categoryIndex mappa categoria-indice riga
     * @param sheet         OdfTable
     * @param insight       lista di InsightGroupEntry
     */
    private static void setValuesOnSheet(int month, Map<String, Integer> categoryIndex, OdfTable sheet, List<InsightGroupEntry> insight) {
        insight.forEach(x -> {

            logger.info("category : {}", x.getName());
            Integer rowIndex = categoryIndex.get(x.getName());
            if (rowIndex != null) {
                // Modifica la cella (int colIndex, int rowIndex), A1 corrisponde a (riga 0, colonna 0)
                var cell = sheet.getCellByPosition(month, rowIndex);
                double value = x.getDifferenceFloat() != null ? x.getDifferenceFloat() : 0;
                cell.setDoubleValue((double) Math.round(Math.abs(value)));
            } else {
                logger.warn("no index for : {} \r\n {}", x.getName(), categoryIndex);
            }
        });
    }
}
