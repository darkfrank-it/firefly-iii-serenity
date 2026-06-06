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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

public class OAuthClient {

    private static final String SECRETS_FILE = "secrets.properties";

    private static final String ACCESS_TOKEN_KEY = "access_token";
    private static final String REFRESH_TOKEN_KEY = "refresh_token";

    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String REDIRECT_URI_KEY = "redirect_uri";
    public static final String FIREFLY_III_BASE_URL_KEY = "firefly_iii_base_url";

    private static final String SKIP_SSL_VERIFICATION_KEY = "skip_ssl_validation";

    private static final Logger logger = LoggerFactory.getLogger(OAuthClient.class);

    private static String extractAndSaveAccessToken(String responseBody) {
        // Estrai il token (usando Jackson o altro parser JSON)
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(responseBody);
        String accessToken = json.get(ACCESS_TOKEN_KEY).asString();
        String refreshToken = json.get(REFRESH_TOKEN_KEY).asString();

        Properties props = new Properties();
        props.setProperty(ACCESS_TOKEN_KEY, accessToken);
        props.setProperty(REFRESH_TOKEN_KEY, refreshToken);
        try (FileOutputStream out = new FileOutputStream(SECRETS_FILE)) {
            props.store(out, "File di configurazione utente");
            logger.info("Chiavi salvate con successo!");
        } catch (IOException e) {
            logger.error("Errore scrittura properties", e);
        }

        return accessToken;
    }

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
     * @return jwt access token or null in caso di 401
     * @throws IOException se il server ritorna un codice diverso da 200
     */
    public static String obtainAccessToken(Properties config, OkHttpClient client, String code) throws IOException {

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
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();

            return extractAndSaveAccessToken(responseBody);
        }
    }

    @Nullable
    private static String isTokenPresent(String tokenKey) {
        try (FileInputStream in = new FileInputStream(SECRETS_FILE)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(tokenKey);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            logger.error("Errore lettura properties", e);
        }

        return null;
    }

    /**
     * Se è presente il refresh token salvato nel file di properties apposito, allora lo ritorna, altrimenti ritorna <code>null</code>
     *
     * @return refresh token oppure null
     */
    @Nullable
    public static String isRefreshTokenPresent() {
        return isTokenPresent(REFRESH_TOKEN_KEY);
    }

    /**
     * Se è presente l'access token salvato nel file di properties apposito, allora lo ritorna, altrimenti ritorna <code>null</code>
     *
     * @return refresh token oppure null
     */
    @Nullable
    public static String isAccessTokenPresent() {
        return isTokenPresent(ACCESS_TOKEN_KEY);
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
    public static String renewAccessToken(Properties config, OkHttpClient client, String refreshToken) throws IOException {

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

            String responseBody = response.body().string();

            return extractAndSaveAccessToken(responseBody);
        }
    }

    private static final String expField = "\"exp\":";

    public static boolean isTokenExpired(String jwt) {
        try {
            // Dividiamo il token in 3 parti: header.payload.signature
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Token JWT non valido");
            }

            // Decodifica del payload (seconda parte)
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            // Cerchiamo il claim "exp" nel JSON (senza librerie, parsing manuale)
            int startIndex = payloadJson.indexOf(expField);
            if (startIndex == -1) {
                throw new IllegalArgumentException("Claim 'exp' non trovato");
            }

            startIndex += expField.length();
            int endIndex = payloadJson.indexOf(",", startIndex);
            if (endIndex == -1) {
                endIndex = payloadJson.indexOf("}", startIndex);
            }

            String expValueStr = payloadJson.substring(startIndex, endIndex).trim();
            long expValue = Double.valueOf(expValueStr).longValue();

            // Confronto con il tempo attuale
            long now = Instant.now().getEpochSecond();
            return expValue < now;

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
}
