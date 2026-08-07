package ai.xelta.craft.auth;

import ai.xelta.craft.config.XeltaCraftConfig;
import java.util.Base64;

public class AuthManager {
    
    /**
     * Checks if the configured Xelta API Key (JWT) is structurally valid.
     * Prevents the mod from making broken HTTP requests if the token is missing or malformed.
     */
    public static boolean isTokenValid() {
        String token = XeltaCraftConfig.API_KEY.get();
        if (token == null || token.isBlank()) {
            return false;
        }
        
        // A standard JWT has 3 parts separated by dots
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        
        // Decode payload to check if it's a valid JSON payload containing an expiry claim
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            return payload.contains("\"exp\"");
        } catch (Exception e) {
            return false; // Malformed base64
        }
    }

    /**
     * Gets the current token for HTTP headers.
     */
    public static String getToken() {
        return XeltaCraftConfig.API_KEY.get();
    }
}
