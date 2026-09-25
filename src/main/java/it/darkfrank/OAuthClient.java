package it.darkfrank;

import okhttp3.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;

public class OAuthClient {

    private static final String ACCESS_TOKEN_KEY = "access_token";
    private static final String REFRESH_TOKEN_KEY = "refresh_token";

    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String REDIRECT_URI_KEY = "redirect_uri";
    public static final String FIREFLY_III_BASE_URL_KEY = "firefly_iii_base_url";

    private static final String SKIP_SSL_VERIFICATION_KEY = "skip_ssl_validation";

    /**
     * Margine in secondi: un token che scade entro questo intervallo è considerato già scaduto,
     * così non scade a metà dell'esportazione.
     */
    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private static final Logger logger = LoggerFactory.getLogger(OAuthClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Properties config;
    private final OkHttpClient client;
    private final Path secretsFile;

    /**
     * @param config      parametri di configurazione
     * @param client      OkHttpClient
     * @param secretsFile file in cui vengono salvati access e refresh token
     */
    public OAuthClient(Properties config, OkHttpClient client, Path secretsFile) {
        this.config = config;
        this.client = client;
        this.secretsFile = secretsFile;
    }

    /**
     * Restituisce un access token valido: usa quello salvato se non è scaduto, altrimenti prova a rinnovarlo
     * con il refresh token e, se non è possibile, lo ottiene tramite il code passato in input.
     * Il workflow si basa su questa discussione: <a href="https://github.com/orgs/firefly-iii/discussions/4595">#4595</a>
     *
     * @param code codice di accesso monouso, può essere <code>null</code> se è presente un token salvato
     * @return jwt access token
     * @throws IOException           se la chiamata al server fallisce
     * @throws UsageException        se non c'è un token valido e non è stato passato il code
     */
    public String getAccessToken(@Nullable String code) throws IOException {
        String accessToken = loadToken(ACCESS_TOKEN_KEY);
        if (accessToken != null && !isTokenExpired(accessToken)) {
            return accessToken;
        }

        String refreshToken = loadToken(REFRESH_TOKEN_KEY);
        if (refreshToken != null) {
            logger.info("Access token assente o scaduto, rinnovo tramite refresh token");
            try {
                return renewAccessToken(refreshToken);
            } catch (IOException e) {
                if (code == null) {
                    throw new UsageException("Rinnovo del token fallito (" + e.getMessage()
                            + ") e nessun code passato in input, ottenere un nuovo code e rilanciare con --code", e);
                }
                logger.warn("Rinnovo del token fallito ({}), provo con il code passato in input", e.getMessage());
            }
        }

        if (code == null) {
            throw new UsageException("Nessun token valido salvato in " + secretsFile
                    + ", ottenere un code e rilanciare con --code");
        }
        logger.info("Ottengo access token da code");
        return obtainAccessToken(code);
    }

    /**
     * Ottiene un jwt token di autenticazione, per farlo ha bisogno, per prima cosa, di creare un client OAuth2
     * seguendo le istruzioni: <a href="https://docs.firefly-iii.org/how-to/firefly-iii/features/api/">api</a>
     * Poi bisogna ottenere il codice di accesso che si ottiene collegandosi da un browser autenticato su firefly-iii a:
     * <url>https://<base_url>/oauth/authorize?response_type=code&client_id=<client_id>&redirect_uri=<redirect_uri>&scope=&state=</url>
     * nella barra dell'indirizzo apparirà una stringa con il code=<code> da passare in input al programma.
     * Il codice scade in breve tempo, va usato subito.
     *
     * @param code il codice univoco
     * @return jwt access token
     * @throws IOException se il server ritorna un codice diverso da 2xx
     */
    private String obtainAccessToken(String code) throws IOException {
        return requestToken(new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", requireConfig(REDIRECT_URI_KEY)), null);
    }

    /**
     * Rinnova il jwt token di autenticazione tramite il refresh token passato in input.
     *
     * @param refreshToken refresh token
     * @return jwt access token
     * @throws IOException se il server ritorna un codice diverso da 2xx
     */
    private String renewAccessToken(String refreshToken) throws IOException {
        return requestToken(new FormBody.Builder()
                .add("grant_type", REFRESH_TOKEN_KEY)
                .add(REFRESH_TOKEN_KEY, refreshToken), refreshToken);
    }

    /**
     * Esegue la chiamata a /oauth/token aggiungendo le credenziali del client e salva i token ottenuti.
     *
     * @param form                 parametri specifici del grant
     * @param previousRefreshToken refresh token da mantenere se il server non ne restituisce uno nuovo
     * @return jwt access token
     */
    private String requestToken(FormBody.Builder form, @Nullable String previousRefreshToken) throws IOException {
        RequestBody formBody = form
                .add("client_id", requireConfig(CLIENT_ID_KEY))
                .add("client_secret", requireConfig(CLIENT_SECRET_KEY))
                .build();

        Request request = new Request.Builder()
                .url(requireConfig(FIREFLY_III_BASE_URL_KEY) + "/oauth/token")
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return extractAndSaveTokens(response.body().string(), previousRefreshToken);
        }
    }

    private String extractAndSaveTokens(String responseBody, @Nullable String previousRefreshToken) throws IOException {
        JsonNode json = MAPPER.readTree(responseBody);
        if (!json.hasNonNull(ACCESS_TOKEN_KEY)) {
            throw new IOException("Risposta del server priva di access_token");
        }
        String accessToken = json.get(ACCESS_TOKEN_KEY).asString();
        String refreshToken = json.hasNonNull(REFRESH_TOKEN_KEY) ? json.get(REFRESH_TOKEN_KEY).asString() : previousRefreshToken;

        Properties props = new Properties();
        props.setProperty(ACCESS_TOKEN_KEY, accessToken);
        if (refreshToken != null) {
            props.setProperty(REFRESH_TOKEN_KEY, refreshToken);
        }
        saveSecrets(props);
        logger.info("Chiavi salvate con successo!");

        return accessToken;
    }

    /**
     * Salva i token su file con permessi di lettura/scrittura riservati al proprietario (dove supportato).
     * Un errore viene propagato: il refresh token viene ruotato dal server, perderlo obbligherebbe a
     * ripetere l'autorizzazione.
     */
    private void saveSecrets(Properties props) throws IOException {
        Path tmp = secretsFile.resolveSibling(secretsFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            restrictPermissions(tmp);
            props.store(out, "File di configurazione utente");
        }
        Files.move(tmp, secretsFile, StandardCopyOption.REPLACE_EXISTING);
        restrictPermissions(secretsFile);
    }

    private static void restrictPermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // File system non POSIX (es. Windows): si mantengono i permessi di default
        }
    }

    @Nullable
    private String loadToken(String tokenKey) {
        try (InputStream in = Files.newInputStream(secretsFile)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(tokenKey);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            logger.error("Errore lettura properties", e);
        }

        return null;
    }

    private String requireConfig(String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new UsageException("Parametro '" + key + "' mancante nel file di configurazione");
        }
        return value.trim();
    }

    /**
     * Verifica se il jwt è scaduto o scadrà entro {@value #EXPIRY_MARGIN_SECONDS} secondi.
     *
     * @param jwt token da verificare
     * @return true se scaduto o non leggibile
     */
    static boolean isTokenExpired(String jwt) {
        try {
            // Dividiamo il token in 3 parti: header.payload.signature
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Token JWT non valido");
            }

            // Decodifica del payload (seconda parte)
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode exp = MAPPER.readTree(payloadJson).get("exp");
            if (exp == null || !exp.isNumber()) {
                throw new IllegalArgumentException("Claim 'exp' non trovato");
            }

            // exp può essere decimale (Laravel Passport)
            long expValue = (long) exp.asDouble();
            return expValue < Instant.now().getEpochSecond() + EXPIRY_MARGIN_SECONDS;

        } catch (Exception e) {
            // In caso di errore consideriamo il token non valido/scaduto
            logger.error("Errore nel parse del token JWT!", e);
            return true;
        }
    }

    /**
     * Crea un OkHttpClient, se skip_ssl_validation == true ritorna un client insicuro che salta la validazione ssl.
     *
     * @param config parametri di configurazione
     * @return OkHttpClient
     */
    public static OkHttpClient getOkHttpClient(Properties config) {
        try {
            OkHttpClient.Builder builder = new OkHttpClient.Builder();

            if ("true".equalsIgnoreCase(config.getProperty(SKIP_SSL_VERIFICATION_KEY, "").trim())) {
                logger.warn("Validazione SSL disabilitata!");
                // Create a trust manager that does not validate certificate chains
                final X509TrustManager trustAll = new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                };

                // Install the all-trusting trust manager
                final SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());
                // Create an ssl socket factory with our all-trusting manager
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                builder.sslSocketFactory(sslSocketFactory, trustAll);
                builder.hostnameVerifier((hostname, session) -> true);
            }

            // Scommentare questo codice per log di debug delle chiamate REST
            // ATTENZIONE: con Level.BODY token e client_secret vengono stampati in console
//            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
//            logging.setLevel(HttpLoggingInterceptor.Level.BODY); // Log completo
//            builder.addInterceptor(logging);

            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile creare il client HTTP", e);
        }
    }
}
