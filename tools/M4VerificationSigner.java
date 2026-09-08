import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/** Signs a validated M4 payload with an Android app signing key. Passwords are read only from env. */
final class M4VerificationSigner {
    private M4VerificationSigner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 8 || !"sign".equals(args[0])) {
            throw new IllegalArgumentException(
                "Usage: java tools/M4VerificationSigner.java sign <payload> <keystore> <type> <alias> <output> --no-overwrite <true>"
            );
        }
        Path payloadPath = Path.of(args[1]);
        Path keyStorePath = Path.of(args[2]);
        String keyStoreType = args[3];
        String alias = args[4];
        Path outputPath = Path.of(args[5]);
        if (!"--no-overwrite".equals(args[6]) || !"true".equals(args[7])) {
            throw new IllegalArgumentException("--no-overwrite true is required");
        }
        if (Files.exists(outputPath)) {
            throw new IllegalArgumentException("Refusing to overwrite existing output: " + outputPath);
        }
        char[] storePassword = requiredEnv("VESQEN_KEYSTORE_PASSWORD").toCharArray();
        String keyPasswordValue = System.getenv("VESQEN_KEY_PASSWORD");
        char[] keyPassword = keyPasswordValue == null ? storePassword : keyPasswordValue.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            try (var input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, storePassword);
            }
            Key key = keyStore.getKey(alias, keyPassword);
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("Alias does not contain a private key");
            }
            String algorithm = switch (privateKey.getAlgorithm().toUpperCase()) {
                case "RSA" -> "SHA256withRSA";
                case "EC", "ECDSA" -> "SHA256withECDSA";
                default -> throw new IllegalArgumentException("Unsupported private-key algorithm");
            };
            byte[] payload = Files.readAllBytes(payloadPath);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(payload);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(keyStore.getCertificate(alias).getPublicKey());
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("Signature self-check failed");
            }
            String envelope = "{\"schemaVersion\":1,\"signatureAlgorithm\":\"" + algorithm
                + "\",\"payload\":\"" + Base64.getEncoder().encodeToString(payload)
                + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\"}";
            Path parent = outputPath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, outputPath.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, envelope, StandardCharsets.UTF_8);
                Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            System.out.println("Signed registry written: " + outputPath + " (" + algorithm + ")");
        } finally {
            java.util.Arrays.fill(storePassword, '\0');
            java.util.Arrays.fill(keyPassword, '\0');
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }
}
