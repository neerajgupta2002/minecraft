import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TestEnv {
    public static void main(String[] args) {
        System.out.println("=== STARTING ENV TEST ===");
        String key = "GEMINI_API_KEY";
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            System.out.println("Found in System.getenv: " + value);
            return;
        }
        
        try {
            Path currentPath = Paths.get("").toAbsolutePath();
            System.out.println("Starting search from: " + currentPath);
            Path envPath = null;
            
            for (int i = 0; i < 3; i++) {
                Path checkPath = currentPath.resolve(".env");
                System.out.println("Checking: " + checkPath);
                if (Files.exists(checkPath)) {
                    envPath = checkPath;
                    System.out.println("✅ Found .env at: " + envPath);
                    break;
                }
                currentPath = currentPath.getParent();
                if (currentPath == null) break;
            }

            if (envPath != null) {
                List<String> lines = Files.readAllLines(envPath);
                for (String line : lines) {
                    if (line.trim().startsWith(key + "=")) {
                        String parsedValue = line.substring(line.indexOf('=') + 1).trim();
                        System.out.println("✅ Successfully parsed " + key + " = " + (parsedValue.length() > 5 ? parsedValue.substring(0, 5) + "..." : parsedValue));
                        return;
                    }
                }
                System.out.println("❌ Could not find " + key + " in .env file");
            } else {
                System.out.println("❌ Could not find .env file within 3 levels");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("=== ENV TEST FINISHED ===");
    }
}
