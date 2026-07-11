package it.darkfrank;

import okhttp3.OkHttpClient;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String CONFIG_FILE = "config.properties";
    private static final String APP_VERSION;

    private static final String ACCOUNT_ID_KEY = "account_id";
    private static final String SPREADSHEET_FULL_PATH_KEY = "spreadsheet_full_path";

    static {
        APP_VERSION = getAppVersion();
    }

    public static void main(String[] args) throws Exception {
        try (InputStream inputStream = Main.class.getClassLoader().getResourceAsStream("logo.txt")) {
            if (inputStream == null) {
                logger.error("File logo.txt non trovato!");
            } else {

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.replace("{{version}}", APP_VERSION);
                        logger.info(line);
                    }
                }
            }
        }

        // Definizione delle opzioni
        Options options = new Options();
        options.addOption("code", true, "Codice di accesso monouso");
        options.addOption("year", true, "Anno di riferimento");
        options.addOption("month", true, "Mese di riferimento");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = HelpFormatter.builder().get();

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
            formatter.printHelp("firefly-iii-serenity.jar", "", options, "", true);
            throw new IOException("Errore nel parsing dei parametri.");
        }

        // Carico le configurazioni
        Properties config = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            config.load(in);
        }

        OkHttpClient client = OAuthClient.getOkHttpClient(config);

        // Questo workflow di autenticazione si basa su questa discussione: https://github.com/orgs/firefly-iii/discussions/4595
        String accessToken = OAuthClient.isAccessTokenPresent();
        if (accessToken == null) {
            logger.info("Access token non presente, ottengo access token da code");
            accessToken = OAuthClient.obtainAccessToken(config, client, code);
        }
        if (OAuthClient.isTokenExpired(accessToken)) {
            String refreshToken = OAuthClient.isRefreshTokenPresent();
            if (refreshToken == null && code == null) {
                logger.error("Non è presente nessun refresh token, tuttavia non è stato passato il code in input");
                throw new IOException("No refresh token or code provided");
            }
            logger.info("Refresh token presente, rinnovo access token");
            accessToken = OAuthClient.renewAccessToken(config, client, refreshToken);
        }

        // Create ApiClient
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClient(client);
        apiClient.setBasePath(config.getProperty(OAuthClient.FIREFLY_III_BASE_URL_KEY) + "/api");
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
                var cell = sheet.getCellByPosition(month, rowIndex);

                // Modifica il valore
                double value = x.getDifferenceFloat() != null ? x.getDifferenceFloat() : 0;
                cell.setDoubleValue((double) Math.round(Math.abs(value)));

            } else {
                logger.warn("no index for : {} \r\n {}", x.getName(), categoryIndex);
            }
         });
     }

     /**
      * Carica la versione dell'applicazione dal file application.properties
      *
      * @return versione dell'app
      */
     private static String getAppVersion() {
         Properties properties = new Properties();
         try (InputStream inputStream = Main.class.getClassLoader().getResourceAsStream("application.properties")) {
             if (inputStream != null) {
                 properties.load(inputStream);
                 return properties.getProperty("app.version", "unknown");
             }
         } catch (IOException e) {
             logger.warn("Impossibile caricare la versione dell'app", e);
         }
         return "unknown";
     }
 }
