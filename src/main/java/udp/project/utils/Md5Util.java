package udp.project.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Util {

    private Md5Util() {}

    public static byte[] calculateFile(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return digest.digest();
    }
}
