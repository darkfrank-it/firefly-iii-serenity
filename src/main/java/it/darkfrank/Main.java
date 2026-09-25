package it.darkfrank;

import okhttp3.OkHttpClient;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
import org.jetbrains.annotations.Nullable;
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.odftoolkit.odfdom.dom.OdfDocumentNamespace;
import org.odftoolkit.odfdom.dom.element.table.TableTableCellElementBase;
import org.openapitools.client.ApiClient;
import org.openapitools.client.api.CategoriesApi;
import org.openapitools.client.api.InsightApi;
import org.openapitools.client.model.CategoryArray;
import org.openapitools.client.model.InsightGroupEntry;
import org.openapitools.client.model.MetaPagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_CONFIG_FILE = "config.properties";
    private static final String SECRETS_FILE = "secrets.properties";
    private static final String APP_VERSION;

    private static final String ACCOUNT_ID_KEY = "account_id";
    private static final String SPREADSHEET_FULL_PATH_KEY = "spreadsheet_full_path";

    /** Numero di righe vuote consecutive in colonna A oltre il quale si smette di cercare categorie. */
    private static final int MAX_CONSECUTIVE_EMPTY_ROWS = 200;
    private static final int CATEGORY_PAGE_SIZE = 100;
    private static final String CALCEXT_NAMESPACE_URI = "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0";

    static {
        APP_VERSION = getAppVersion();
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            // Errori di input o di configurazione dell'utente: basta il messaggio
            logger.error(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Esecuzione terminata con errore: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        printLogo();

        // Definizione delle opzioni
        Options options = new Options();
        options.addOption("c", "code", true, "Codice di accesso monouso");
        options.addOption("y", "year", true, "Anno di riferimento");
        options.addOption("m", "month", true, "Mese di riferimento (1-12)");
        options.addOption(null, "config", true, "Percorso del file di configurazione (default: " + DEFAULT_CONFIG_FILE + ")");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = HelpFormatter.builder().get();

        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            formatter.printHelp("firefly-iii-serenity.jar", "", options, "", true);
            throw new UsageException("Errore nel parsing dei parametri: " + e.getMessage(), e);
        }
        String code = cmd.getOptionValue("code");

        LocalDate today = LocalDate.now();
        int year = parseIntOption(cmd, "year", today.getYear(), 1970, 9999);
        Integer month = cmd.hasOption("month") ? parseIntOption(cmd, "month", 0, 1, 12) : null;

        // Carico le configurazioni; il file dei segreti viene cercato nella stessa directory
        Path configFile = Path.of(cmd.getOptionValue("config", DEFAULT_CONFIG_FILE)).toAbsolutePath();
        Properties config = loadConfig(configFile);
        Path secretsFile = configFile.resolveSibling(SECRETS_FILE);

        String spreadsheetPath = requireConfig(config, SPREADSHEET_FULL_PATH_KEY);
        String baseUrl = requireConfig(config, OAuthClient.FIREFLY_III_BASE_URL_KEY);
        List<Long> accounts = parseAccounts(config.getProperty(ACCOUNT_ID_KEY));

        OkHttpClient client = OAuthClient.getOkHttpClient(config);
        String accessToken = new OAuthClient(config, client, secretsFile).getAccessToken(code);

        // Create ApiClient
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClient(client);
        apiClient.setBasePath(baseUrl + "/api");
        apiClient.setBearerToken(accessToken);

        InsightApi insightApi = new InsightApi(apiClient);
        Set<String> fireflyCategories = loadCategoryNames(new CategoriesApi(apiClient));

        // Carica il file .ods
        try (OdfSpreadsheetDocument ods = OdfSpreadsheetDocument.loadDocument(spreadsheetPath)) {

            var sheet = getTableByName(ods, String.valueOf(year));
            Map<String, Integer> categoryIndex = buildCategoryIndex(sheet);

            if (month != null) {
                exportCategoryByMonth(year, month, accounts, insightApi, categoryIndex, fireflyCategories, sheet);
            } else {
                int lastMonth = (year < today.getYear()) ? 12 : today.getMonthValue();
                for (int i = 1; i <= lastMonth; i++) {
                    exportCategoryByMonth(year, i, accounts, insightApi, categoryIndex, fireflyCategories, sheet);
                }
            }

            // Salva il file modificato
            ods.save(spreadsheetPath);
        }

        logger.info("Modifica completata.");
    }

    private static void printLogo() throws IOException {
        try (InputStream inputStream = Main.class.getClassLoader().getResourceAsStream("logo.txt")) {
            if (inputStream == null) {
                logger.error("File logo.txt non trovato!");
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line.replace("{{version}}", APP_VERSION));
                }
            }
        }
    }

    private static int parseIntOption(CommandLine cmd, String name, int defaultValue, int min, int max) {
        String value = cmd.getOptionValue(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new UsageException("Parametro --" + name + " fuori intervallo (" + min + "-" + max + "): " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new UsageException("Parametro --" + name + " non numerico: " + value, e);
        }
    }

    private static Properties loadConfig(Path configFile) throws IOException {
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            config.load(in);
        } catch (NoSuchFileException e) {
            throw new UsageException("File di configurazione non trovato: " + configFile, e);
        }
        return config;
    }

    private static String requireConfig(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new UsageException("Parametro '" + key + "' mancante nel file di configurazione");
        }
        return value.trim();
    }

    @Nullable
    private static List<Long> parseAccounts(@Nullable String acc) {
        if (acc == null || acc.isBlank()) {
            return null;
        }
        try {
            return List.of(Long.parseLong(acc.trim()));
        } catch (NumberFormatException e) {
            throw new UsageException("Parametro '" + ACCOUNT_ID_KEY + "' non valido: " + acc, e);
        }
    }

    /**
     * Legge da Firefly III i nomi di tutte le categorie, gestendo la paginazione.
     *
     * @param categoriesApi CategoriesApi
     * @return nomi delle categorie
     */
    private static Set<String> loadCategoryNames(CategoriesApi categoriesApi) {
        Set<String> names = new HashSet<>();
        int page = 1;
        int totalPages;
        do {
            CategoryArray result = categoriesApi.listCategory(null, CATEGORY_PAGE_SIZE, page);
            result.getData().forEach(c -> names.add(c.getAttributes().getName()));
            MetaPagination pagination = result.getMeta().getPagination();
            totalPages = (pagination != null && pagination.getTotalPages() != null) ? pagination.getTotalPages() : page;
            page++;
        } while (page <= totalPages);
        return names;
    }

    /**
     * Costruisce l'indice delle categorie basato sulla colonna A.
     * La scansione si interrompe dopo {@value #MAX_CONSECUTIVE_EMPTY_ROWS} righe vuote consecutive, per non
     * espandere le righe "ripetute" che alcuni fogli dichiarano fino al limite massimo.
     *
     * @param sheet OdfTable
     * @return mappa categoria-indice riga
     */
    private static Map<String, Integer> buildCategoryIndex(OdfTable sheet) {
        Map<String, Integer> categoryIndex = new HashMap<>();
        int emptyRows = 0;
        for (int i = 0; i < sheet.getRowCount() && emptyRows < MAX_CONSECUTIVE_EMPTY_ROWS; i++) {
            String cellText = sheet.getRowByIndex(i).getCellByIndex(0).getStringValue(); // colonna A

            if (cellText == null || cellText.isBlank()) {
                emptyRows++;
                continue;
            }
            emptyRows = 0;
            Integer previous = categoryIndex.put(cellText, i);
            if (previous != null) {
                logger.warn("Categoria '{}' duplicata in colonna A (righe {} e {}), uso la riga {}",
                        cellText, previous + 1, i + 1, i + 1);
            }
        }
        return categoryIndex;
    }

    /**
     * Seleziona il tab del foglio di lavoro con il cui nome corrisponde con l'anno selezionato.
     *
     * @param ods  OdfSpreadsheetDocument
     * @param year anno
     * @return OdfTable
     */
    private static OdfTable getTableByName(OdfSpreadsheetDocument ods, String year) {
        var sheet = ods.getTableByName(year);
        if (sheet == null) {
            throw new UsageException("Foglio '" + year + "' non trovato.");
        }
        return sheet;
    }

    /**
     * Richiama InsightApi insightIncomeCategory, insightTransferCategory, insightExpenseCategory e riporta
     * nel foglio, per ogni categoria, la somma dei valori ottenuti.
     *
     * @param year              anno
     * @param month             mese
     * @param accounts          account da filtrare, <code>null</code> per tutti
     * @param insightApi        InsightApi
     * @param categoryIndex     mappa categoria-indice riga
     * @param fireflyCategories categorie esistenti su Firefly III
     * @param sheet             OdfTable
     */
    private static void exportCategoryByMonth(int year, int month, @Nullable List<Long> accounts, InsightApi insightApi,
                                              Map<String, Integer> categoryIndex, Set<String> fireflyCategories, OdfTable sheet) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate lastDay = yearMonth.atEndOfMonth();

        Map<String, Double> totals = new HashMap<>();
        Map<String, Set<String>> currencies = new HashMap<>();

        logger.info("working insightIncomeCategory y: {} m: {}...", year, month);
        accumulate(insightApi.insightIncomeCategory(firstDay, lastDay, null, null, accounts), totals, currencies);

        logger.info("working insightTransferCategory y: {} m: {}...", year, month);
        accumulate(insightApi.insightTransferCategory(firstDay, lastDay, null, null, accounts), totals, currencies);

        logger.info("working insightExpenseCategory y: {} m: {}...", year, month);
        accumulate(insightApi.insightExpenseCategory(firstDay, lastDay, null, null, accounts), totals, currencies);

        currencies.forEach((name, codes) -> {
            if (codes.size() > 1) {
                logger.warn("Categoria '{}' con importi in più valute {} nel mese {}/{}: sono stati sommati senza conversione",
                        name, codes, month, year);
            }
        });
        totals.keySet().stream()
                .filter(name -> !categoryIndex.containsKey(name))
                .forEach(name -> logger.warn("no index for : {}", name));

        // Si scrivono tutte le categorie di Firefly presenti nel foglio: quelle senza movimenti nel mese
        // vengono svuotate, così non restano valori di esecuzioni precedenti.
        Set<String> toWrite = new HashSet<>(fireflyCategories);
        toWrite.addAll(totals.keySet());
        toWrite.retainAll(categoryIndex.keySet());
        toWrite.forEach(name -> setValueOnSheet(sheet, month, categoryIndex.get(name), name, totals.get(name)));
    }

    /**
     * Somma i valori per categoria.
     *
     * @param insight    lista di InsightGroupEntry
     * @param totals     somma per categoria
     * @param currencies valute incontrate per categoria
     */
    private static void accumulate(List<InsightGroupEntry> insight, Map<String, Double> totals, Map<String, Set<String>> currencies) {
        insight.forEach(x -> {
            logger.debug("category : {}", x.getName());
            double value = x.getDifferenceFloat() != null ? x.getDifferenceFloat() : 0;
            totals.merge(x.getName(), value, Double::sum);
            if (x.getCurrencyCode() != null) {
                currencies.computeIfAbsent(x.getName(), k -> new HashSet<>()).add(x.getCurrencyCode());
            }
        });
    }

    /**
     * Riporta il valore nel foglio, la colonna corrisponde al mese (B = gennaio).
     * Le celle che contengono una formula non vengono sovrascritte.
     *
     * @param value valore da scrivere, <code>null</code> per svuotare la cella
     */
    private static void setValueOnSheet(OdfTable sheet, int month, int rowIndex, String category, @Nullable Double value) {
        OdfTableCell cell = sheet.getCellByPosition(month, rowIndex);
        if (cell.getFormula() != null) {
            logger.warn("La cella della categoria '{}' per il mese {} contiene una formula, non la sovrascrivo", category, month);
            return;
        }
        if (value == null) {
            clearCell(cell);
        } else {
            cell.setDoubleValue((double) Math.round(Math.abs(value)));
        }
    }

    /**
     * Svuota la cella mantenendone lo stile. Oltre al testo vanno rimossi gli attributi del valore
     * (<code>office:value-type</code>, <code>office:value</code>, ...), altrimenti la cella resta numerica.
     */
    private static void clearCell(OdfTableCell cell) {
        cell.removeContent(); // separa anche le celle ripetute, così la modifica riguarda solo questa
        TableTableCellElementBase element = cell.getOdfElement();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = attributes.getLength() - 1; i >= 0; i--) {
            Node attribute = attributes.item(i);
            String namespace = attribute.getNamespaceURI();
            if (OdfDocumentNamespace.OFFICE.getUri().equals(namespace)
                    || (CALCEXT_NAMESPACE_URI.equals(namespace) && "value-type".equals(attribute.getLocalName()))) {
                element.removeAttributeNS(namespace, attribute.getLocalName());
            }
        }
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
