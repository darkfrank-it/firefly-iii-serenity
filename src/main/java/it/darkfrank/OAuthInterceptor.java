package it.darkfrank;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class OAuthInterceptor implements Interceptor {
    private final String tokenType;
    private final String accessToken;

    public OAuthInterceptor(String tokenType, String accessToken) {
        this.tokenType = tokenType;
        this.accessToken = accessToken;
    }

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Request requestWithAuth = originalRequest.newBuilder()
                .header("Authorization", tokenType + " " + accessToken)
                .build();
        return chain.proceed(requestWithAuth);
    }
}
